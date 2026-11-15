package io.github.rainyinsaigon.fraudlinker.evaluation;

import io.github.rainyinsaigon.fraudlinker.LinkerException;
import io.github.rainyinsaigon.fraudlinker.domain.Account;
import io.github.rainyinsaigon.fraudlinker.io.JsonlReader;
import io.github.rainyinsaigon.fraudlinker.mules.MuleConfig;
import io.github.rainyinsaigon.fraudlinker.mules.Transfer;
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
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.rainyinsaigon.fraudlinker.Fixtures.account;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MuleEvaluationTest {
    static final Instant T0 = Instant.parse("2026-11-03T10:00:00Z");

    @TempDir
    Path dir;

    private int seq;

    Transfer pay(String from, String to, long cents, int minutes) {
        return new Transfer("t" + seq++, from, to, cents, T0.plus(Duration.ofMinutes(minutes)));
    }

    /**
     * m1 and m2 form a mule chain fed by six outside victims; the bakery is paid by
     * twenty outside buyers and keeps the money. Victims and buyers are not held.
     */
    MuleEvaluation.Dataset dataset() {
        List<Account> accounts = List.of(
                account("m1", "m1@example.com", "", "10.1.0.1", "", T0),
                account("m2", "m2@example.com", "", "10.2.0.1", "", T0),
                account("bakery", "bakery@example.com", "", "10.3.0.1", "", T0));
        List<Transfer> transfers = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            transfers.add(pay("victim" + i, "m1", 50_000, 5 * i));
        }
        transfers.add(pay("m1", "m2", 270_000, 30));
        transfers.add(pay("m2", "cashout", 265_000, 45));
        for (int i = 0; i < 20; i++) {
            transfers.add(pay("buyer" + i, "bakery", 1_500, 20 * i));
        }
        return new MuleEvaluation.Dataset("seed", accounts, List.of(), transfers,
                new MuleTruth(Set.of("m1", "m2"), Map.of("bakery", "small_business")));
    }

    static MuleConfig withThreshold(double threshold) {
        MuleConfig d = MuleConfig.defaults();
        return new MuleConfig(d.fanInMinPayers(), d.fanInWindow(), d.passThroughMinShare(), d.passThroughWindow(),
                d.fanInWeight(), d.passThroughWeight(), d.chainWeight(), threshold);
    }

    @Test
    void findsEveryMuleAndFlagsNoLegitimateAccountAtTheDefaults() {
        MuleEvaluation.Result r = MuleEvaluation.evaluate(dataset(), MuleConfig.defaults());

        assertThat(r.mulesPlanted()).isEqualTo(2);
        assertThat(r.mulesFlagged()).isEqualTo(2);
        assertThat(r.missedMules()).isEmpty();
        assertThat(r.falseFlags()).isZero();
        assertThat(r.recall()).isEqualTo(1.0);
        assertThat(r.precision()).isEqualTo(1.0);
        assertThat(r.hardCasesByCase()).containsExactly(Map.entry("small_business", 1));
        assertThat(r.cost(new MuleCostModel(50, 2000))).isZero();
    }

    @Test
    void countsOnlyHeldAccountsAndBreaksFalseFlagsDownByCaseAndReasons() {
        // At 0.5 the bakery (fan-in, 0.6) is flagged, and so would be the outside victims (chain, 0.5),
        // but nobody can review an account the bank does not hold.
        MuleEvaluation.Result r = MuleEvaluation.evaluate(dataset(), withThreshold(0.5));

        assertThat(r.falseFlags()).isEqualTo(1);
        assertThat(r.falseFlagsByCase()).containsExactly(Map.entry("small_business", 1));
        assertThat(r.falseFlagsByReasons()).containsExactly(Map.entry("fan_in", 1));
        assertThat(r.precision()).isEqualTo(2.0 / 3);
        assertThat(r.cost(new MuleCostModel(50, 2000))).isEqualTo(50);
    }

    @Test
    void reportsMissedMulesAndTheirCost() {
        MuleEvaluation.Result r = MuleEvaluation.evaluate(dataset(), withThreshold(0.9));

        assertThat(r.mulesFlagged()).isEqualTo(1);
        assertThat(r.missedMules()).containsExactly("m2");
        assertThat(r.recall()).isEqualTo(0.5);
        assertThat(r.cost(new MuleCostModel(50, 2000))).isEqualTo(2000);
    }

    @Test
    void anAccountWithNoTransfersIsMissedAndAnUnheldAccountIsNoFalseFlag() {
        MuleEvaluation.Dataset base = dataset();
        MuleEvaluation.Dataset withSilentMule = new MuleEvaluation.Dataset("seed", base.accounts(), List.of(),
                base.transfers(), new MuleTruth(Set.of("m1", "m2", "quiet"), Map.of("victim0", "own_money")));

        MuleEvaluation.Result r = MuleEvaluation.evaluate(withSilentMule, withThreshold(0.5));

        assertThat(r.missedMules()).containsExactly("quiet");
        assertThat(r.falseFlagsByCase()).containsExactly(Map.entry(MuleEvaluation.OTHER, 1));
    }

    @Test
    void addsResultsAcrossDatasets() {
        MuleEvaluation.Result r = MuleEvaluation.evaluateAll(List.of(dataset(), dataset()), withThreshold(0.5));

        assertThat(r.mulesPlanted()).isEqualTo(4);
        assertThat(r.mulesFlagged()).isEqualTo(4);
        assertThat(r.falseFlags()).isEqualTo(2);
        assertThat(r.falseFlagsByCase()).containsExactly(Map.entry("small_business", 2));
        assertThat(r.hardCasesByCase()).containsExactly(Map.entry("small_business", 2));
        assertThat(MuleEvaluation.evaluateAll(List.of(), MuleConfig.defaults()).recall()).isEqualTo(1.0);
        assertThat(MuleEvaluation.evaluateAll(List.of(), MuleConfig.defaults()).precision()).isEqualTo(1.0);
    }

    @Test
    void sweepsEverySettingAndPicksTheCheapestKeepingTheCurrentOnATie() {
        List<MuleSweep.Point> points = MuleSweep.run(List.of(dataset()), List.of(5, 8), List.of(0.8),
                List.of(0.5, 0.8, 0.9), MuleConfig.defaults());

        assertThat(points).hasSize(6);
        assertThat(points).extracting(MuleSweep.Point::fanInMinPayers).containsExactly(5, 5, 5, 8, 8, 8);
        // The bakery's 20 buyers are fan-in at 5 and at 8 payers, so a threshold of 0.5 flags it both times.
        assertThat(points).extracting(p -> p.result().falseFlags()).containsExactly(1, 0, 0, 1, 0, 0);
        // At 8 payers m1 still has 6 victims, so fan-in no longer fires and m1 drops to 0.85.
        assertThat(points.get(4).result().mulesFlagged()).isEqualTo(2);

        MuleCostModel prices = new MuleCostModel(50, 2000);
        MuleSweep.Point best = MuleSweep.cheapest(points, prices, MuleConfig.defaults());
        assertThat(best.fanInMinPayers()).isEqualTo(5);
        assertThat(best.flagThreshold()).isEqualTo(0.8);
        assertThat(best.cost(prices)).isZero();

        // Cheaper beats nearer: (8, 0.80) costs nothing, (5, 0.90) misses m2.
        assertThat(MuleSweep.cheapest(List.of(points.get(2), points.get(4)), prices, MuleConfig.defaults()))
                .isEqualTo(points.get(4));
        // Without the current setting, the nearest equally cheap one wins.
        MuleSweep.Point far = new MuleSweep.Point(8, 0.6, 0.8, MuleEvaluation.empty());
        MuleSweep.Point near = new MuleSweep.Point(6, 0.8, 0.8, MuleEvaluation.empty());
        MuleSweep.Point nearer = new MuleSweep.Point(5, 0.75, 0.8, MuleEvaluation.empty());
        assertThat(MuleSweep.cheapest(List.of(far, near), prices, MuleConfig.defaults())).isEqualTo(near);
        // One payer weighs like 0.1 of a share, so 0.05 off the share is nearer than one payer off.
        assertThat(MuleSweep.cheapest(List.of(near, nearer, far), prices, MuleConfig.defaults())).isEqualTo(nearer);
    }

    @Test
    void aSweepNeedsSomethingToSweep() {
        assertThatThrownBy(() -> MuleSweep.run(List.of(), List.of(5), List.of(0.8), List.of(0.8),
                MuleConfig.defaults())).isInstanceOf(LinkerException.class).hasMessageContaining("at least one");
        assertThatThrownBy(() -> MuleSweep.run(List.of(dataset()), List.of(), List.of(0.8), List.of(0.8),
                MuleConfig.defaults())).isInstanceOf(LinkerException.class);
    }

    @Test
    void readsMuleTruthFromASimulatorFile() throws IOException {
        Path file = dir.resolve("truth.json");
        Files.writeString(file, """
                {"schema_version": 2, "account_to_actor": {"m1": "p1"}, "fraud_actor_ids": [],
                 "mule_account_ids": ["m1", "m2"],
                 "transfer_cases": [{"case": "own_money", "account_id": "s1"}]}
                """);

        MuleTruth truth = MuleTruth.read(file);

        assertThat(truth.muleAccountIds()).containsExactlyInAnyOrder("m1", "m2");
        assertThat(truth.caseOf()).containsExactly(Map.entry("s1", "own_money"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"account_to_actor\": {\"a\": \"x\"}}",
            "[]",
            "{\"mule_account_ids\": [\"m1\", \"m1\"]}",
            "{\"mule_account_ids\": [\"\"]}",
            "{\"mule_account_ids\": [], \"transfer_cases\": [{\"case\": \"own_money\"}]}",
            "{\"mule_account_ids\": [], \"transfer_cases\": [{\"case\": \"a\", \"account_id\": \"s\"},"
                    + " {\"case\": \"b\", \"account_id\": \"s\"}]}",
            "{\"mule_account_ids\": [\"s\"], \"transfer_cases\": [{\"case\": \"a\", \"account_id\": \"s\"}]}",
            "not json",
    })
    void rejectsTruthWithoutUsableMuleFields(String json) throws IOException {
        Path file = dir.resolve("bad.json");
        Files.writeString(file, json);
        assertThatThrownBy(() -> MuleTruth.read(file)).isInstanceOf(LinkerException.class);
    }

    @Test
    void readsTransfersAndNamesTheLineOfABadOne() throws IOException {
        Path good = dir.resolve("transfers.jsonl");
        Files.writeString(good, """
                {"transfer_id":"t1","from_account":"a","to_account":"b","amount_cents":1500,"occurred_at":"2026-11-03T10:00:00Z"}

                {"transfer_id":" t2 ","from_account":"b","to_account":"c","amount_cents":900,"occurred_at":"2026-11-03T11:00:00+10:00"}
                """);
        assertThat(JsonlReader.readTransfers(good)).containsExactly(
                new Transfer("t1", "a", "b", 1500, T0),
                new Transfer("t2", "b", "c", 900, Instant.parse("2026-11-03T01:00:00Z")));

        for (String bad : List.of(
                "{\"transfer_id\":\"t\",\"from_account\":\"a\",\"to_account\":\"b\",\"amount_cents\":1.5,\"occurred_at\":\"2026-11-03T10:00:00Z\"}",
                "{\"transfer_id\":\"t\",\"from_account\":\"a\",\"to_account\":\"b\",\"amount_cents\":\"9\",\"occurred_at\":\"2026-11-03T10:00:00Z\"}",
                "{\"transfer_id\":\"t\",\"from_account\":\"a\",\"to_account\":\"b\",\"occurred_at\":\"2026-11-03T10:00:00Z\"}",
                "{\"transfer_id\":\"t\",\"from_account\":\"a\",\"to_account\":\"b\",\"amount_cents\":5}",
                "{\"transfer_id\":\"t\",\"from_account\":\"a\",\"to_account\":\"a\",\"amount_cents\":5,\"occurred_at\":\"2026-11-03T10:00:00Z\"}")) {
            Path file = dir.resolve("bad.jsonl");
            Files.writeString(file, "\n" + bad + "\n");
            assertThatThrownBy(() -> JsonlReader.readTransfers(file)).isInstanceOf(LinkerException.class)
                    .hasMessageContaining("bad.jsonl:2:");
        }
        assertThatThrownBy(() -> JsonlReader.readTransfers(dir.resolve("missing.jsonl")))
                .hasMessageContaining("open transfers");
    }

    @Test
    void rejectsNegativePrices() {
        assertThatThrownBy(() -> new MuleCostModel(-1, 0)).hasMessage("costs must not be negative");
        assertThatThrownBy(() -> new MuleCostModel(0, -1)).hasMessage("costs must not be negative");
    }
}
