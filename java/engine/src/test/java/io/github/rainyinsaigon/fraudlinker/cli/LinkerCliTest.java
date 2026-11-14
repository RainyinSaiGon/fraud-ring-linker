package io.github.rainyinsaigon.fraudlinker.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class LinkerCliTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path directory;

    private record Result(int code, String stdout, String stderr) {
    }

    private static Result run(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = LinkerCli.run(args, new ByteArrayInputStream(new byte[0]), stdout,
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Result(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    @Test
    void linkWritesClustersFileAndNothingToStdout() throws Exception {
        Path accounts = directory.resolve("accounts.jsonl");
        Path constraints = directory.resolve("constraints.jsonl");
        Path output = directory.resolve("clusters.json");
        Files.writeString(accounts,
                "{\"account_id\":\"a\",\"email\":\"one@gmail.com\",\"device_id\":null,\"ip\":\"192.0.2.1\",\"payment_fingerprint\":\"p\",\"created_at\":\"2026-01-01T00:00:00Z\"}\n"
                        + "{\"account_id\":\"b\",\"email\":\"two@gmail.com\",\"device_id\":null,\"ip\":\"192.0.2.2\",\"payment_fingerprint\":\"p\",\"created_at\":\"2026-01-02T00:00:00Z\"}\n");
        Files.writeString(constraints, "");

        Result result = run("link", "--output", output.toString(), "--constraints", constraints.toString(),
                "--accounts=" + accounts);

        assertThat(result.code()).as(result.stderr()).isZero();
        assertThat(result.stdout()).isEmpty();
        JsonNode clusters = MAPPER.readTree(output.toFile()).get("clusters");
        assertThat(clusters).hasSize(1);
        assertThat(clusters.get(0).get("account_ids")).hasSize(2);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void streamFlushesEachResponseBeforeStdinCloses() throws Exception {
        Path accounts = directory.resolve("accounts.jsonl");
        Path constraints = directory.resolve("constraints.jsonl");
        Files.writeString(accounts,
                "{\"account_id\":\"a\",\"email\":\"person@gmail.com\",\"device_id\":null,\"ip\":\"192.0.2.1\",\"payment_fingerprint\":\"pay\",\"created_at\":\"2026-01-01T00:00:00Z\"}\n");
        Files.writeString(constraints, "");

        PipedOutputStream stdinWriter = new PipedOutputStream();
        PipedInputStream stdin = new PipedInputStream(stdinWriter);
        PipedInputStream stdoutReader = new PipedInputStream();
        OutputStream stdout = new PipedOutputStream(stdoutReader);
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        CompletableFuture<Integer> exit = CompletableFuture.supplyAsync(() -> {
            int code = LinkerCli.run(new String[] {"stream", "--accounts", accounts.toString(),
                    "--constraints", constraints.toString()}, stdin, stdout, new PrintStream(stderr, true, StandardCharsets.UTF_8));
            try {
                stdout.close();
            } catch (Exception ignored) {
                // Test teardown only.
            }
            return code;
        });

        stdinWriter.write(("{\"account_id\":\"b\",\"email\":\"other@example.com\",\"device_id\":null,\"ip\":\"198.51.100.2\","
                + "\"payment_fingerprint\":\"pay\",\"created_at\":\"2026-01-02T00:00:00Z\"}\n").getBytes(StandardCharsets.UTF_8));
        stdinWriter.flush();

        BufferedReader responses = new BufferedReader(new InputStreamReader(stdoutReader, StandardCharsets.UTF_8));
        String line = CompletableFuture.supplyAsync(() -> {
            try {
                return responses.readLine();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).get(2, TimeUnit.SECONDS);

        JsonNode assignment = MAPPER.readTree(line);
        assertThat(assignment.get("account_id").asText()).isEqualTo("b");
        assertThat(assignment.get("cluster_id").asText()).isEqualTo("c1");

        stdinWriter.close();
        assertThat(exit.get(2, TimeUnit.SECONDS)).as(stderr.toString(StandardCharsets.UTF_8)).isZero();
    }

    @Test
    void missingOrUnknownCommandPrintsUsageToStderr() {
        for (String[] args : new String[][] {{}, {"unknown"}}) {
            Result result = run(args);
            assertThat(result.code()).isEqualTo(1);
            assertThat(result.stderr()).contains("Usage:");
            assertThat(result.stdout()).isEmpty();
        }
    }

    private Path[] labelledDataset() throws Exception {
        Path accounts = directory.resolve("cost-accounts.jsonl");
        Path constraints = directory.resolve("cost-constraints.jsonl");
        Path truth = directory.resolve("cost-truth.json");
        Files.writeString(accounts,
                "{\"account_id\":\"r1\",\"email\":\"maria.lopez@x.com\",\"device_id\":\"phone-1\",\"ip\":\"10.1.0.1\",\"payment_fingerprint\":null,\"created_at\":\"2026-11-01T09:00:00Z\"}\n"
                        + "{\"account_id\":\"r2\",\"email\":\"zz.other@y.com\",\"device_id\":null,\"ip\":\"10.2.0.1\",\"payment_fingerprint\":\"card-1\",\"created_at\":\"2026-11-01T09:01:00Z\"}\n"
                        + "{\"account_id\":\"r3\",\"email\":\"maria.lopes@x.com\",\"device_id\":\"phone-1\",\"ip\":\"10.3.0.1\",\"payment_fingerprint\":\"card-1\",\"created_at\":\"2026-11-01T09:02:00Z\"}\n");
        Files.writeString(constraints, "");
        Files.writeString(truth, """
                {"schema_version": 1,
                 "actors": [{"actor_id": "ring_0", "kind": "fraud_ring", "account_ids": ["r1", "r2", "r3"]}],
                 "account_to_actor": {"r1": "ring_0", "r2": "ring_0", "r3": "ring_0"},
                 "fraud_actor_ids": ["ring_0"]}
                """);
        return new Path[] {accounts, constraints, truth};
    }

    @Test
    void costPrintsBatchAndStreamSideBySide() throws Exception {
        Path[] files = labelledDataset();

        Result result = run("cost", "--accounts", files[0].toString(), "--constraints", files[1].toString(),
                "--truth", files[2].toString());

        assertThat(result.code()).as(result.stderr()).isZero();
        assertThat(result.stdout()).contains("$50 per wrongly linked account, $2,000 per missed ring")
                .containsPattern("stream \\+ proposals")
                .containsPattern("Fraud rings fully linked +1 / 1 +0 / 1 +1 / 1")
                .containsPattern("Cost +\\$0 +\\$2,000 +\\$0")
                .contains("Merge proposals opened: 1 (1 merged when accepted)");
    }

    @Test
    void costAsJsonWithCustomPricesAndWrittenClusters() throws Exception {
        Path[] files = labelledDataset();
        Path out = directory.resolve("clusters-out");

        Result result = run("cost", "--accounts", files[0].toString(), "--constraints", files[1].toString(),
                "--truth", files[2].toString(), "--missed-ring-cost", "500", "--wrong-link-cost=10",
                "--format", "json", "--write-clusters", out.toString());

        assertThat(result.code()).as(result.stderr()).isZero();
        JsonNode report = MAPPER.readTree(result.stdout());
        assertThat(report.at("/costs/missed_ring").asLong()).isEqualTo(500);
        assertThat(report.at("/costs/wrong_link").asLong()).isEqualTo(10);
        assertThat(report.at("/batch/cost").asLong()).isZero();
        assertThat(report.at("/stream/cost").asLong()).isEqualTo(500);
        assertThat(report.at("/stream/split_fraud_rings").asInt()).isEqualTo(1);
        assertThat(MAPPER.readTree(out.resolve("batch-clusters.json").toFile()).get("clusters")).hasSize(1);
        assertThat(MAPPER.readTree(out.resolve("stream-clusters.json").toFile()).get("clusters")).hasSize(2);
        assertThat(report.at("/stream_with_proposals/cost").asLong()).isZero();
        assertThat(report.at("/merge_proposals/opened").asLong()).isEqualTo(1);
        assertThat(report.at("/merge_proposals/merged").asLong()).isEqualTo(1);
        assertThat(MAPPER.readTree(out.resolve("stream-with-proposals-clusters.json").toFile()).get("clusters"))
                .hasSize(1);
    }

    @Test
    void costRejectsMissingOrBadArguments() throws Exception {
        Path[] files = labelledDataset();
        String a = files[0].toString();
        String c = files[1].toString();
        String t = files[2].toString();

        assertThat(run("cost", "--accounts", a).stderr()).contains("cost requires --accounts, --constraints and --truth");
        assertThat(run("cost", "--accounts", a, "--constraints", c, "--truth", t, "--missed-ring-cost", "lots").stderr())
                .contains("--missed-ring-cost must be a whole number of dollars");
        assertThat(run("cost", "--accounts", a, "--constraints", c, "--truth", t, "--format", "xml").stderr())
                .contains("--format must be text or json");
        assertThat(run("cost", "--accounts", a, "--constraints", c, "--truth", t, "--wrong-link-cost", "-5").stderr())
                .contains("costs must not be negative");
    }

    private Path sweepDataset(String name) throws Exception {
        Path[] files = labelledDataset();
        Path dir = Files.createDirectories(directory.resolve(name));
        Files.copy(files[0], dir.resolve("accounts.jsonl"));
        Files.copy(files[1], dir.resolve("constraints.jsonl"));
        Files.copy(files[2], dir.resolve("truth.json"));
        return dir;
    }

    @Test
    void sweepPrintsTheMistakeGridAndTheCheapestSettingPerPrice() throws Exception {
        Path seed = sweepDataset("seed-1");

        Result result = run("sweep", "--datasets", seed + "," + seed, "--merge-scores", "1.0",
                "--stream-thresholds", "0.9", "--costs", "50:2000,0:100,2:5");

        assertThat(result.code()).as(result.stderr()).isZero();
        assertThat(result.stdout())
                .contains("Datasets: 2 (seed-1, seed-1)")
                .contains("Current setting (*): merge score 0.70, stream threshold 0.82")
                .containsPattern("0\\.82\\* +0\\.90")
                .containsPattern("0\\.70\\* +0 / 0 +0 / 2 +0 / 2")
                .containsPattern("1\\.00 +0 / 2 +0 / 2 +0 / 2")
                .contains("(40:1)").contains("(wrong links free)").contains("(2.50:1)")
                .contains("Stream: cheapest is merge score 0.70, stream threshold 0.82: $4,000 (current $4,000)")
                .contains("Batch:  cheapest is merge score 0.70: $0 (current $0)")
                .contains("Stream with every merge proposal accepted: wrongly linked / split / proposals opened")
                .containsPattern("0\\.70\\* +0 / 0 / 2 +0 / 2 / 0")
                .contains("Stream with every proposal accepted, current setting: $0 (2 proposals)");
    }

    @Test
    void sweepAsJson() throws Exception {
        Path seed = sweepDataset("seed-2");

        Result result = run("sweep", "--datasets", seed.toString(), "--merge-scores=0.7", "--stream-thresholds=0.82",
                "--costs", "50:2000", "--format", "json");

        assertThat(result.code()).as(result.stderr()).isZero();
        JsonNode report = MAPPER.readTree(result.stdout());
        assertThat(report.at("/datasets/0").asText()).isEqualTo("seed-2");
        assertThat(report.at("/current/stream_threshold").asDouble()).isEqualTo(0.82);
        assertThat(report.at("/points")).hasSize(1);
        assertThat(report.at("/points/0/stream_split_actors").asLong()).isEqualTo(1);
        assertThat(report.at("/points/0/batch_wrongly_linked").asLong()).isZero();
        assertThat(report.at("/points/0/proposals_split_actors").asLong()).isZero();
        assertThat(report.at("/points/0/proposals_wrongly_linked").asLong()).isZero();
        assertThat(report.at("/points/0/proposals_opened").asLong()).isEqualTo(1);
        assertThat(report.at("/prices/0/missed_ring").asLong()).isEqualTo(2000);
        assertThat(report.at("/prices/0/cheapest_stream/cost").asLong()).isEqualTo(2000);
        assertThat(report.at("/prices/0/cheapest_batch/min_merge_score").asDouble()).isEqualTo(0.7);
    }

    @Test
    void sweepRejectsBadArguments() throws Exception {
        String seed = sweepDataset("seed-3").toString();

        assertThat(run("sweep").stderr()).contains("sweep requires --datasets");
        assertThat(run("sweep", "--datasets", seed, "--merge-scores", "high").stderr())
                .contains("--merge-scores must be numbers between 0 and 1, got \"high\"");
        assertThat(run("sweep", "--datasets", seed, "--stream-thresholds", "1.5").stderr())
                .contains("--stream-thresholds must be numbers between 0 and 1");
        assertThat(run("sweep", "--datasets", seed, "--stream-thresholds", "NaN").stderr())
                .contains("--stream-thresholds must be numbers between 0 and 1");
        assertThat(run("sweep", "--datasets", seed, "--costs", "50").stderr()).contains("--costs must look like");
        assertThat(run("sweep", "--datasets", seed, "--costs", "a:b").stderr()).contains("--costs must look like");
        assertThat(run("sweep", "--datasets", seed, "--format", "csv").stderr()).contains("--format must be text or json");
        assertThat(run("sweep", "--datasets", directory.resolve("nowhere").toString()).stderr()).contains("open accounts");
    }

    @Test
    void missingFlagsAndUnknownFlagsFail() {
        assertThat(run("link", "--accounts", "a.jsonl").stderr()).contains("link requires");
        assertThat(run("stream", "--bogus", "x").stderr()).contains("flag provided but not defined");
        assertThat(run("link", "positional").stderr()).contains("unexpected positional arguments");
    }

    @Test
    void unreadableInputIsReportedNotThrown() {
        Result result = run("link", "--accounts", directory.resolve("missing.jsonl").toString(),
                "--constraints", "x", "--output", directory.resolve("o.json").toString());
        assertThat(result.code()).isEqualTo(1);
        assertThat(result.stderr()).startsWith("linker: open accounts");
    }
}
