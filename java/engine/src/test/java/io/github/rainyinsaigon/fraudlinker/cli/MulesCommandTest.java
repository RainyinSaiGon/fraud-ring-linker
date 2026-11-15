package io.github.rainyinsaigon.fraudlinker.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MulesCommandTest {
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

    private static String transfer(int i, String from, String to, long cents, int minutes) {
        return """
                {"transfer_id":"t%d","from_account":"%s","to_account":"%s","amount_cents":%d,"occurred_at":"2026-11-03T%02d:%02d:00Z"}
                """.formatted(i, from, to, cents, 10 + minutes / 60, minutes % 60);
    }

    /** One two-hop mule chain and one bakery that keeps its takings, as the simulator exports them. */
    private Path dataset(String name) throws Exception {
        Path dir = Files.createDirectories(directory.resolve(name));
        Files.writeString(dir.resolve("accounts.jsonl"), """
                {"account_id":"m1","email":"m1@example.com","device_id":null,"ip":"10.1.0.1","payment_fingerprint":null,"created_at":"2026-11-01T09:00:00Z"}
                {"account_id":"m2","email":"m2@example.com","device_id":null,"ip":"10.2.0.1","payment_fingerprint":null,"created_at":"2026-11-01T09:00:00Z"}
                {"account_id":"bakery","email":"b@example.com","device_id":null,"ip":"10.3.0.1","payment_fingerprint":null,"created_at":"2026-11-01T09:00:00Z"}
                """);
        Files.writeString(dir.resolve("constraints.jsonl"), "");
        List<String> lines = new ArrayList<>();
        int i = 0;
        for (int v = 0; v < 6; v++) {
            lines.add(transfer(i++, "victim" + v, "m1", 50_000, 5 * v));
        }
        lines.add(transfer(i++, "m1", "m2", 270_000, 30));
        lines.add(transfer(i++, "m2", "cashout", 265_000, 45));
        for (int b = 0; b < 20; b++) {
            lines.add(transfer(i++, "buyer" + b, "bakery", 1_500, 20 * b));
        }
        Files.writeString(dir.resolve("transfers.jsonl"), String.join("", lines));
        Files.writeString(dir.resolve("truth.json"), """
                {"schema_version": 2, "account_to_actor": {"m1": "p1", "m2": "p2", "bakery": "p3"},
                 "fraud_actor_ids": [], "mule_account_ids": ["m1", "m2"],
                 "transfer_cases": [{"case": "small_business", "account_id": "bakery"}]}
                """);
        return dir;
    }

    @Test
    void reportsTheCurrentSettingTheGridAndTheCheapestSettingPerPrice() throws Exception {
        Path seed = dataset("seed-1");

        Result result = run("mules", "--datasets", seed + "," + seed, "--fan-in-payers", "8",
                "--pass-through-shares", "0.9", "--flag-thresholds", "0.5", "--costs", "50:2000,1000:10");

        assertThat(result.code()).as(result.stderr()).isZero();
        assertThat(result.stdout())
                .contains("Datasets: 2 (seed-1, seed-1)")
                .contains("Current setting (*): fan-in 5 payers, pass-through 0.80, flag at 0.80")
                .containsPattern("Mules planted +4\\n")
                .containsPattern("Mules flagged +4 \\(recall 100\\.0%\\)")
                .containsPattern("Legitimate accounts flagged +0 \\(precision 100\\.0%\\)")
                .containsPattern("small_business +0 of 2")
                .containsPattern("other accounts +0")
                .containsPattern("0\\.50 +0\\.80\\*")
                .containsPattern("5\\* +0\\.80\\* +4 / 2 +4 / 0")
                // m1 passes exactly 90% on, so even a 0.90 share with no fan-in keeps both mules (0.85 each).
                .containsPattern("8 +0\\.90 +4 / 2 +4 / 0")
                .contains("At $50 per false flag and $2,000 per missed mule:")
                .contains("At $1,000 per false flag and $10 per missed mule:")
                .contains("cheapest is fan-in 5 payers, pass-through 0.80, flag at 0.80: $0 (current $0)");
    }

    @Test
    void listsFalseFlagsAndMissedMulesAtTheCurrentSetting() throws Exception {
        Path seed = dataset("seed-2");
        // A truth that calls the bakery a mule and m2 legitimate turns the run into misses and false flags.
        Files.writeString(seed.resolve("truth.json"), """
                {"mule_account_ids": ["bakery"], "transfer_cases": []}
                """);

        Result result = run("mules", "--datasets", seed.toString());

        assertThat(result.code()).as(result.stderr()).isZero();
        assertThat(result.stdout())
                .containsPattern("Mules flagged +0 \\(recall 0\\.0%\\)")
                .containsPattern("Legitimate accounts flagged +2 \\(precision 0\\.0%\\)")
                .containsPattern("other accounts +2")
                .containsPattern("False flags by fan_in\\+pass_through\\+chain +1")
                .containsPattern("False flags by pass_through\\+chain +1")
                .contains("Missed mule: bakery");
    }

    @Test
    void writesJson() throws Exception {
        Path seed = dataset("seed-3");

        Result result = run("mules", "--datasets", seed.toString(), "--fan-in-payers=5", "--pass-through-shares=0.8",
                "--flag-thresholds=0.5,0.8", "--costs", "50:2000", "--format", "json");

        assertThat(result.code()).as(result.stderr()).isZero();
        JsonNode report = MAPPER.readTree(result.stdout());
        assertThat(report.at("/datasets/0").asText()).isEqualTo("seed-3");
        assertThat(report.at("/current/flag_threshold").asDouble()).isEqualTo(0.8);
        assertThat(report.at("/at_current/mules_flagged").asInt()).isEqualTo(2);
        assertThat(report.at("/at_current/recall").asDouble()).isEqualTo(1.0);
        assertThat(report.at("/at_current/hard_cases_by_case/small_business").asInt()).isEqualTo(1);
        assertThat(report.at("/points")).hasSize(2);
        assertThat(report.at("/points/0/false_flags").asInt()).isEqualTo(1);
        assertThat(report.at("/prices/0/cheapest/flag_threshold").asDouble()).isEqualTo(0.8);
        assertThat(report.at("/prices/0/cheapest/cost").asLong()).isZero();
        assertThat(report.at("/prices/0/current_cost").asLong()).isZero();
    }

    @Test
    void rejectsBadArguments() throws Exception {
        String seed = dataset("seed-4").toString();

        assertThat(run("mules").stderr()).contains("mules requires --datasets");
        assertThat(run("mules", "--datasets", seed, "--fan-in-payers", "1").stderr())
                .contains("--fan-in-payers must be whole numbers of 2 or more, got \"1\"");
        assertThat(run("mules", "--datasets", seed, "--fan-in-payers", "many").stderr())
                .contains("--fan-in-payers must be whole numbers");
        assertThat(run("mules", "--datasets", seed, "--pass-through-shares", "0").stderr())
                .contains("must be above 0");
        assertThat(run("mules", "--datasets", seed, "--flag-thresholds", "1.2").stderr())
                .contains("--flag-thresholds must be numbers between 0 and 1");
        assertThat(run("mules", "--datasets", seed, "--costs", "50").stderr()).contains("--costs must look like");
        assertThat(run("mules", "--datasets", seed, "--format", "csv").stderr()).contains("--format must be text or json");
        assertThat(run("mules", "--datasets", seed, "--unknown", "x").code()).isEqualTo(1);

        Files.writeString(Path.of(seed).resolve("truth.json"), "{\"account_to_actor\": {\"m1\": \"p\"}}");
        assertThat(run("mules", "--datasets", seed).stderr()).contains("has no mule_account_ids");
    }

    @Test
    void isListedInTheUsage() {
        assertThat(run("help").stdout()).contains("linker mules --datasets");
    }
}
