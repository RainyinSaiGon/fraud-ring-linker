package io.github.rainyinsaigon.fraudlinker.evaluation;

import io.github.rainyinsaigon.fraudlinker.LinkerException;
import io.github.rainyinsaigon.fraudlinker.domain.Account;
import io.github.rainyinsaigon.fraudlinker.io.JsonlReader;
import io.github.rainyinsaigon.fraudlinker.takeover.Login;
import io.github.rainyinsaigon.fraudlinker.takeover.TakeoverConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.rainyinsaigon.fraudlinker.Fixtures.account;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TakeoverEvaluationTest {
    static final Instant DAY1 = Instant.parse("2026-11-03T08:00:00Z");
    static final double[] SYDNEY = {-33.87, 151.21};
    static final double[] LONDON = {51.51, -0.13};

    @TempDir
    Path dir;

    private int seq;
    private final List<Login> logins = new ArrayList<>();

    private String login(String account, String device, double[] where, boolean success, Instant at) {
        String id = "l" + seq++;
        logins.add(new Login(id, account, device, "10.0.0.1", success, at, where[0], where[1]));
        return id;
    }

    /** Six daily logins from home on the account's own device. */
    private void history(String account) {
        for (int day = 0; day < 6; day++) {
            login(account, account + "-phone", SYDNEY, true, DAY1.plus(Duration.ofDays(day)));
        }
    }

    private static Instant day7(int hour, int minute) {
        return DAY1.plus(Duration.ofDays(6)).plus(Duration.ofHours(hour - 8)).plus(Duration.ofMinutes(minute));
    }

    /**
     * {@code victim} is phished from London (new device plus impossible travel), and
     * {@code quiet} is taken over from a new device in Sydney only, which no single
     * signal flags. {@code phone} gets a new phone at home. {@code f1} to {@code f4}
     * share a family tablet, so it is new on each and shared by four unrelated accounts.
     */
    TakeoverEvaluation.Dataset dataset(String... notHeld) {
        List<String> ids = List.of("victim", "quiet", "phone", "f1", "f2", "f3", "f4");
        ids.forEach(this::history);
        login("victim", "victim-phone", SYDNEY, true, day7(8, 0));
        String failed = login("victim", "evil", LONDON, false, day7(9, 55));
        String first = login("victim", "evil", LONDON, true, day7(10, 0));
        String second = login("victim", "evil", LONDON, true, day7(11, 0));
        String quiet = login("quiet", "evil2", SYDNEY, true, day7(10, 0));
        login("phone", "phone-new", SYDNEY, true, day7(9, 0));
        for (int i = 1; i <= 4; i++) {
            login("f" + i, "tablet", SYDNEY, true, day7(19, 10 * i));
        }
        List<Account> accounts = new ArrayList<>();
        for (String id : ids) {
            if (!List.of(notHeld).contains(id)) {
                accounts.add(account(id, id + "@example.com", "", "10.9." + accounts.size() + ".1", "", DAY1));
            }
        }
        TakeoverTruth truth = new TakeoverTruth(Map.of(
                "victim", new TakeoverTruth.Planted("phishing", day7(10, 0), Set.of(failed, first, second)),
                "quiet", new TakeoverTruth.Planted("password_guessing", day7(10, 0), Set.of(quiet))),
                Map.of("phone", "new_phone", "f1", "family_tablet", "f2", "family_tablet",
                        "f3", "family_tablet", "f4", "family_tablet"));
        return new TakeoverEvaluation.Dataset("seed", accounts, List.of(), List.copyOf(logins), truth);
    }

    static TakeoverConfig withSharedDevice(int accounts) {
        TakeoverConfig d = TakeoverConfig.defaults();
        return new TakeoverConfig(d.minHistory(), d.maxSpeedKmh(), d.minTravelKm(), d.burstFailures(),
                d.burstWindow(), accounts, d.sharedDeviceWindow(), d.incidentWindow(), d.newDeviceWeight(),
                d.impossibleTravelWeight(), d.burstThenSuccessWeight(), d.burstOnlyWeight(), d.sharedDeviceWeight(),
                d.flagThreshold());
    }

    @Test
    void comparesFlagsWithThePlantedTakeoversAndTheHardCases() {
        TakeoverEvaluation.Result r = TakeoverEvaluation.evaluate(dataset(), TakeoverConfig.defaults());

        assertThat(r.planted()).isEqualTo(2);
        assertThat(r.flagged()).isEqualTo(1);
        assertThat(r.plantedByKind()).containsExactly(Map.entry("password_guessing", 1), Map.entry("phishing", 1));
        assertThat(r.flaggedByKind()).containsExactly(Map.entry("phishing", 1));
        assertThat(r.missed()).containsExactly("quiet (password_guessing)");
        assertThat(r.falseFlags()).isEqualTo(4);
        assertThat(r.falseFlagsByCase()).containsExactly(Map.entry("family_tablet", 4));
        assertThat(r.falseFlagsByReasons()).containsExactly(Map.entry("new_device+shared_device", 4));
        assertThat(r.hardCasesByCase()).containsExactly(Map.entry("family_tablet", 4), Map.entry("new_phone", 1));
        assertThat(r.recall()).isEqualTo(0.5);
        assertThat(r.precision()).isEqualTo(0.2);
        assertThat(r.reviews()).isEqualTo(5);
        assertThat(r.cost(new TakeoverCostModel(50, 5000))).isEqualTo(4 * 50 + 5000);
    }

    @Test
    void countsOnlyTheAttackerSessionsUpToTheFlag() {
        TakeoverEvaluation.Result r = TakeoverEvaluation.evaluate(dataset(), TakeoverConfig.defaults());

        // The failed attempt is no session, and the 11:00 session came after the 10:00 flag.
        assertThat(r.sessionsBeforeFlag()).containsExactly(Map.entry(1, 1));
        assertThat(r.meanSessionsBeforeFlag()).isEqualTo(1.0);
        assertThat(TakeoverEvaluation.empty().meanSessionsBeforeFlag()).isZero();
    }

    @Test
    void anAccountTheBankDoesNotHoldIsNoFalseFlag() {
        TakeoverEvaluation.Result r = TakeoverEvaluation.evaluate(dataset("f4"), TakeoverConfig.defaults());

        assertThat(r.falseFlags()).isEqualTo(3);
    }

    @Test
    void addsResultsAcrossDatasets() {
        TakeoverEvaluation.Dataset one = dataset();
        TakeoverEvaluation.Result r = TakeoverEvaluation.evaluateAll(List.of(one, one), TakeoverConfig.defaults());

        assertThat(r.planted()).isEqualTo(4);
        assertThat(r.flagged()).isEqualTo(2);
        assertThat(r.missed()).containsExactly("quiet (password_guessing)", "quiet (password_guessing)");
        assertThat(r.falseFlags()).isEqualTo(8);
        assertThat(r.sessionsBeforeFlag()).containsExactly(Map.entry(1, 2));
        assertThat(r.hardCasesByCase()).containsEntry("family_tablet", 8);
    }

    @Test
    void sweepsOneThresholdAtATimeAndPicksTheCheapestKeepingTheCurrentOnATie() {
        TakeoverConfig current = TakeoverConfig.defaults();
        Map<TakeoverSweep.Knob, List<Double>> values = new EnumMap<>(TakeoverSweep.Knob.class);
        values.put(TakeoverSweep.Knob.MIN_HISTORY, List.of(5.0, 8.0));
        values.put(TakeoverSweep.Knob.SHARED_DEVICE_ACCOUNTS, List.of(4.0, 5.0, 6.0));

        List<TakeoverSweep.Point> points = TakeoverSweep.run(List.of(dataset()), values, List.of(0.8, 0.9), current);

        assertThat(points).hasSize(10);
        TakeoverSweep.Point history8 = points.get(2);
        assertThat(history8.config().minHistory()).isEqualTo(8);
        // Six earlier logins are too few for a device to be new at 8, so nothing is flagged.
        assertThat(history8.result().flagged()).isZero();
        assertThat(history8.result().falseFlags()).isZero();
        TakeoverSweep.Point shared5 = points.get(6);
        assertThat(shared5.config().sharedDeviceAccounts()).isEqualTo(5);
        assertThat(shared5.result().flagged()).isEqualTo(1);
        assertThat(shared5.result().falseFlags()).isZero();

        // Five accounts no longer share the tablet; 5 and 6 tie, and 5 is nearer today's 4.
        TakeoverSweep.Point best = TakeoverSweep.cheapest(points, new TakeoverCostModel(50, 5000), current);
        assertThat(best.config()).isEqualTo(withSharedDevice(5));
        // Free false flags make today's setting as cheap as any, and a tie keeps it.
        assertThat(TakeoverSweep.cheapest(points, new TakeoverCostModel(0, 5000), current).config())
                .isEqualTo(current);
    }

    @Test
    void aSweepNeedsSomethingToSweep() {
        Map<TakeoverSweep.Knob, List<Double>> values = Map.of(TakeoverSweep.Knob.MIN_HISTORY, List.of(5.0));

        assertThatThrownBy(() -> TakeoverSweep.run(List.of(), values, List.of(0.8), TakeoverConfig.defaults()))
                .isInstanceOf(LinkerException.class).hasMessageContaining("at least one dataset");
        assertThatThrownBy(() -> TakeoverSweep.run(List.of(dataset()), values, List.of(), TakeoverConfig.defaults()))
                .isInstanceOf(LinkerException.class);
        assertThatThrownBy(() -> TakeoverSweep.run(List.of(dataset()), Map.of(TakeoverSweep.Knob.MIN_HISTORY,
                List.of()), List.of(0.8), TakeoverConfig.defaults())).isInstanceOf(LinkerException.class);
    }

    @Test
    void readsTakeoverTruthFromASimulatorFile() throws IOException {
        Path file = dir.resolve("truth.json");
        Files.writeString(file, """
                {"schema_version": 3, "mule_account_ids": [],
                 "takeovers": [{"account_id": "a1", "kind": "phishing", "attacker_device": "d",
                                "first_bad_login_id": "l2", "first_bad_at": "2026-11-10T10:00:00Z",
                                "attacker_login_ids": ["l1", "l2"]}],
                 "takeover_account_ids": ["a1"],
                 "login_cases": [{"case": "holiday", "account_id": "a2"}]}
                """);

        TakeoverTruth truth = TakeoverTruth.read(file);

        assertThat(truth.takeovers()).containsOnlyKeys("a1");
        assertThat(truth.takeovers().get("a1").kind()).isEqualTo("phishing");
        assertThat(truth.takeovers().get("a1").firstBadAt()).isEqualTo(Instant.parse("2026-11-10T10:00:00Z"));
        assertThat(truth.takeovers().get("a1").attackerLoginIds()).containsExactlyInAnyOrder("l1", "l2");
        assertThat(truth.caseOf()).containsExactly(Map.entry("a2", "holiday"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"schema_version\": 2, \"mule_account_ids\": []}",
            "[]",
            "{\"takeovers\": [{\"account_id\": \"a1\", \"kind\": \"phishing\", \"attacker_login_ids\": [\"l1\"]}]}",
            "{\"takeovers\": [{\"account_id\": \"\", \"kind\": \"phishing\", \"first_bad_at\": \"2026-11-10T10:00:00Z\","
                    + " \"attacker_login_ids\": [\"l1\"]}]}",
            "{\"takeovers\": [{\"account_id\": \"a1\", \"kind\": \"phishing\", \"first_bad_at\": \"2026-11-10T10:00:00Z\","
                    + " \"attacker_login_ids\": []}]}",
            "{\"takeovers\": [], \"login_cases\": [{\"case\": \"holiday\", \"account_id\": \"a2\"},"
                    + " {\"case\": \"new_phone\", \"account_id\": \"a2\"}]}",
    })
    void rejectsTruthWithoutUsableTakeoverFields(String json) throws IOException {
        Path file = dir.resolve("bad.json");
        Files.writeString(file, json);

        assertThatThrownBy(() -> TakeoverTruth.read(file)).isInstanceOf(LinkerException.class);
    }

    @Test
    void anAccountCannotBeBothATakeoverAndALegitimateCase() {
        TakeoverTruth.Planted planted = new TakeoverTruth.Planted("phishing", DAY1, Set.of("l1"));

        assertThatThrownBy(() -> new TakeoverTruth(Map.of("a1", planted), Map.of("a1", "holiday")))
                .isInstanceOf(LinkerException.class).hasMessageContaining("both");
    }

    @Test
    void readsLoginsAndNamesTheLineOfABadOne() throws IOException {
        Path file = dir.resolve("logins.jsonl");
        Files.writeString(file, """
                {"login_id":"l1","account_id":"a1","device_id":"d1","ip":"10.0.0.1","success":true,"occurred_at":"2026-11-03T07:04:16Z","latitude":-35.3467,"longitude":149.0893}

                {"login_id":"l2","account_id":"a1","device_id":null,"ip":null,"success":false,"occurred_at":"2026-11-03T07:05:00Z","latitude":null,"longitude":null}
                """);

        List<Login> read = JsonlReader.readLogins(file);

        assertThat(read).containsExactly(
                new Login("l1", "a1", "d1", "10.0.0.1", true, Instant.parse("2026-11-03T07:04:16Z"), -35.3467,
                        149.0893),
                new Login("l2", "a1", null, null, false, Instant.parse("2026-11-03T07:05:00Z"), null, null));

        for (String bad : List.of(
                "{\"login_id\":\"l3\",\"account_id\":\"a1\",\"success\":\"yes\",\"occurred_at\":\"2026-11-03T07:05:00Z\"}",
                "{\"login_id\":\"l3\",\"account_id\":\"a1\",\"success\":true}",
                "{\"login_id\":\"l3\",\"account_id\":\"a1\",\"success\":true,\"occurred_at\":\"2026-11-03T07:05:00Z\","
                        + "\"latitude\":\"-35\",\"longitude\":149}",
                "{\"login_id\":\"l3\",\"account_id\":\"a1\",\"success\":true,\"occurred_at\":\"2026-11-03T07:05:00Z\","
                        + "\"latitude\":-35}",
                "{\"login_id\":\"\",\"account_id\":\"a1\",\"success\":true,\"occurred_at\":\"2026-11-03T07:05:00Z\"}")) {
            Files.writeString(file, "\n" + bad + "\n");
            assertThatThrownBy(() -> JsonlReader.readLogins(file))
                    .isInstanceOf(LinkerException.class).hasMessageContaining("logins.jsonl:2");
        }
        assertThatThrownBy(() -> JsonlReader.readLogins(dir.resolve("missing.jsonl")))
                .isInstanceOf(LinkerException.class).hasMessageContaining("open logins");
    }

    @Test
    void rejectsNegativePrices() {
        assertThatThrownBy(() -> new TakeoverCostModel(-1, 0)).isInstanceOf(LinkerException.class);
        assertThatThrownBy(() -> new TakeoverCostModel(0, -1)).isInstanceOf(LinkerException.class);
    }
}
