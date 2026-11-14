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
