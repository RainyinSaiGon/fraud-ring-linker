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

class TakeoversCommandTest {
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

    private int seq;

    private String login(String account, String device, boolean london, boolean success, int day, int hour) {
        return """
                {"login_id":"l%d","account_id":"%s","device_id":"%s","ip":"10.0.0.1","success":%s,"occurred_at":"2026-11-%02dT%02d:00:00Z","latitude":%s,"longitude":%s}
                """.formatted(seq++, account, device, success, day, hour, london ? "51.51" : "-33.87",
                london ? "-0.13" : "151.21");
    }

    /** The victim is phished from London on day 7; the phone customer gets a new phone at home. */
    private Path dataset(String name) throws Exception {
        Path dir = Files.createDirectories(directory.resolve(name));
        Files.writeString(dir.resolve("accounts.jsonl"), """
                {"account_id":"victim","email":"v@example.com","device_id":null,"ip":"10.1.0.1","payment_fingerprint":null,"created_at":"2026-11-01T09:00:00Z"}
                {"account_id":"phone","email":"p@example.com","device_id":null,"ip":"10.2.0.1","payment_fingerprint":null,"created_at":"2026-11-01T09:00:00Z"}
                """);
        Files.writeString(dir.resolve("constraints.jsonl"), "");
        List<String> lines = new ArrayList<>();
        for (int day = 1; day <= 6; day++) {
            lines.add(login("victim", "victim-phone", false, true, day, 8));
            lines.add(login("phone", "old-phone", false, true, day, 8));
        }
        lines.add(login("victim", "victim-phone", false, true, 7, 8));
        lines.add(login("victim", "evil", true, true, 7, 10));
        lines.add(login("phone", "new-phone", false, true, 7, 9));
        Files.writeString(dir.resolve("logins.jsonl"), String.join("", lines));
        Files.writeString(dir.resolve("truth.json"), """
                {"schema_version": 3, "mule_account_ids": [], "transfer_cases": [],
                 "takeovers": [{"account_id": "victim", "kind": "phishing", "attacker_device": "evil",
                                "first_bad_login_id": "l13", "first_bad_at": "2026-11-07T10:00:00Z",
                                "attacker_login_ids": ["l13"]}],
                 "takeover_account_ids": ["victim"],
                 "login_cases": [{"case": "new_phone", "account_id": "phone"}]}
                """);
        return dir;
    }

    @Test
    void reportsTheCurrentSettingTheGridAndTheCheapestSettingPerPrice() throws Exception {
        Path seed = dataset("seed-1");

        Result result = run("takeovers", "--datasets", seed + "," + seed, "--min-history", "8",
                "--max-speeds", "900", "--burst-failures", "5", "--shared-device-accounts", "4",
                "--flag-thresholds", "0.3", "--costs", "50:5000,1000:10");

        assertThat(result.code()).as(result.stderr()).isZero();
        assertThat(result.stdout())
                .contains("Datasets: 2 (seed-1, seed-1)")
                .contains("Current setting (*): history 5, speed 900 km/h, burst 5, shared device 4, flag at 0.80")
                .containsPattern("Takeovers planted +2\\n")
                .containsPattern("Takeovers flagged +2 \\(recall 100\\.0%\\)")
                .containsPattern("phishing +2 of 2")
                .containsPattern("Legitimate accounts flagged +0 \\(precision 100\\.0%\\)")
                .containsPattern("new_phone +0 of 2")
                .containsPattern("other accounts +0")
                .containsPattern("Reviews \\(all flags\\) +2")
                .containsPattern("Attacker sessions up to the flag +mean 1\\.00; 1: 2")
                .containsPattern("threshold +0\\.30 +0\\.80\\*")
                // At 0.30 a new phone alone (0.4) is a flag.
                .containsPattern("history 5\\* +2 / 2 +2 / 0")
                // With 8 earlier logins needed, neither device is new, and travel alone (0.7) flags only at 0.30.
                .containsPattern("history 8 +2 / 0 +0 / 0")
                .contains("At $50 per false flag and $5,000 per missed takeover:")
                .contains("cheapest is history 5, speed 900 km/h, burst 5, shared device 4, flag at 0.80: $0"
                        + " (current $0)")
                .contains("recall 100.0%, precision 100.0%, 2 reviews, 1.00 attacker sessions up to the flag");
    }

    @Test
    void listsFalseFlagsAndMissedTakeoversAtTheCurrentSetting() throws Exception {
        Path seed = dataset("seed-2");
        // A truth that calls the new phone a takeover and the victim legitimate turns the run into misses.
        Files.writeString(seed.resolve("truth.json"), """
                {"takeovers": [{"account_id": "phone", "kind": "phishing", "first_bad_at": "2026-11-07T09:00:00Z",
                                "attacker_login_ids": ["l14"]}],
                 "login_cases": []}
                """);

        Result result = run("takeovers", "--datasets", seed.toString());

        assertThat(result.code()).as(result.stderr()).isZero();
        assertThat(result.stdout())
                .containsPattern("Takeovers flagged +0 \\(recall 0\\.0%\\)")
                .containsPattern("Legitimate accounts flagged +1 \\(precision 0\\.0%\\)")
                .containsPattern("other accounts +1")
                .containsPattern("False flags by new_device\\+impossible_travel +1")
                .contains("Missed takeover: phone (phishing)");
    }

    @Test
    void writesJson() throws Exception {
        Path seed = dataset("seed-3");

        Result result = run("takeovers", "--datasets", seed.toString(), "--min-history=5", "--max-speeds=900",
                "--burst-failures=5", "--shared-device-accounts=4", "--flag-thresholds=0.3,0.8", "--costs",
                "50:5000", "--format", "json");

        assertThat(result.code()).as(result.stderr()).isZero();
        JsonNode report = MAPPER.readTree(result.stdout());
        assertThat(report.at("/datasets/0").asText()).isEqualTo("seed-3");
        assertThat(report.at("/current/flag_threshold").asDouble()).isEqualTo(0.8);
        assertThat(report.at("/current/min_history").asInt()).isEqualTo(5);
        assertThat(report.at("/at_current/takeovers_flagged").asInt()).isEqualTo(1);
        assertThat(report.at("/at_current/flagged_by_kind/phishing").asInt()).isEqualTo(1);
        assertThat(report.at("/at_current/sessions_before_flag/1").asInt()).isEqualTo(1);
        assertThat(report.at("/at_current/hard_cases_by_case/new_phone").asInt()).isEqualTo(1);
        assertThat(report.at("/at_current/recall").asDouble()).isEqualTo(1.0);
        // Four knobs with one value each, at two flag thresholds.
        assertThat(report.at("/points")).hasSize(8);
        assertThat(report.at("/points/0/varied").asText()).isEqualTo("min_history");
        assertThat(report.at("/points/0/false_flags").asInt()).isEqualTo(1);
        assertThat(report.at("/prices/0/missed_takeover").asLong()).isEqualTo(5000);
        assertThat(report.at("/prices/0/cheapest/flag_threshold").asDouble()).isEqualTo(0.8);
        assertThat(report.at("/prices/0/cheapest/cost").asLong()).isZero();
        assertThat(report.at("/prices/0/current_cost").asLong()).isZero();
    }

    @Test
    void rejectsBadArguments() throws Exception {
        String seed = dataset("seed-4").toString();

        assertThat(run("takeovers").stderr()).contains("takeovers requires --datasets");
        assertThat(run("takeovers", "--datasets", seed, "--min-history", "0").stderr())
                .contains("--min-history must be whole numbers of 1 or more, got \"0\"");
        assertThat(run("takeovers", "--datasets", seed, "--min-history", "2.5").stderr())
                .contains("--min-history must be whole numbers");
        assertThat(run("takeovers", "--datasets", seed, "--burst-failures", "1").stderr())
                .contains("--burst-failures must be whole numbers of 2 or more");
        assertThat(run("takeovers", "--datasets", seed, "--shared-device-accounts", "x").stderr())
                .contains("--shared-device-accounts must be whole numbers of 2 or more");
        assertThat(run("takeovers", "--datasets", seed, "--max-speeds", "0.5").stderr())
                .contains("--max-speeds must be numbers of 1 or more");
        assertThat(run("takeovers", "--datasets", seed, "--max-speeds", "Infinity").stderr())
                .contains("--max-speeds must be numbers of 1 or more");
        assertThat(run("takeovers", "--datasets", seed, "--flag-thresholds", "0").stderr())
                .contains("--flag-thresholds must be above 0");
        assertThat(run("takeovers", "--datasets", seed, "--format", "xml").stderr())
                .contains("--format must be text or json");
        assertThat(run("takeovers", "--datasets", seed, "--costs", "5").stderr()).contains("--costs must look like");
        assertThat(run("takeovers", "--datasets", seed, "--bogus", "1").code()).isNotZero();
        assertThat(run("takeovers", "--datasets", directory.resolve("none").toString()).stderr())
                .contains("open accounts");
    }
}
