package io.github.rainyinsaigon.fraudlinker.mules;

import io.github.rainyinsaigon.fraudlinker.LinkerException;
import io.github.rainyinsaigon.fraudlinker.clustering.BatchLinker;
import io.github.rainyinsaigon.fraudlinker.clustering.ClusteringConfig;
import io.github.rainyinsaigon.fraudlinker.clustering.Constraints;
import io.github.rainyinsaigon.fraudlinker.clustering.LinkState;
import io.github.rainyinsaigon.fraudlinker.domain.Account;
import io.github.rainyinsaigon.fraudlinker.domain.ClustersOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

import static io.github.rainyinsaigon.fraudlinker.Fixtures.account;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MuleScorerTest {
    static final Instant T0 = Instant.parse("2026-11-01T09:00:00Z");
    static final MuleScorer SCORER = new MuleScorer(MuleConfig.defaults());
    static final BiPredicate<String, String> NOBODY_RELATED = (a, b) -> false;

    private int seq;

    Transfer pay(String from, String to, long cents, Duration after) {
        return new Transfer("t" + seq++, from, to, cents, T0.plus(after));
    }

    /**
     * Six external victims pay m1; m1 forwards 90% to m2 within minutes; m2 sends
     * nearly all of it out to an external cash-out account.
     */
    List<Transfer> muleChain() {
        List<Transfer> transfers = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            transfers.add(pay("victim" + i, "m1", 50_000, Duration.ofMinutes(5L * i)));
        }
        transfers.add(pay("m1", "m2", 270_000, Duration.ofMinutes(30)));
        transfers.add(pay("m2", "cashout", 265_000, Duration.ofMinutes(45)));
        return transfers;
    }

    @Test
    void flagsBothMulesInAChainAndNobodyElse() {
        Map<String, MuleScore> scores = SCORER.scoreAll(muleChain(), NOBODY_RELATED);

        MuleScore m1 = scores.get("m1");
        assertThat(m1.flagged()).isTrue();
        assertThat(m1.score()).isEqualTo(0.94); // 1 - 0.4 * 0.3 * 0.5
        assertThat(m1.reasons()).extracting(MuleReason::code).containsExactly("fan_in", "pass_through", "chain");
        assertThat(m1.reasons()).extracting(MuleReason::describe)
                .containsExactly("6 unrelated payers in 25 min", "90% sent on within 30 min",
                        "on a money chain of 2 accounts");

        MuleScore m2 = scores.get("m2");
        assertThat(m2.flagged()).isTrue();
        assertThat(m2.score()).isEqualTo(0.85); // 1 - 0.3 * 0.5
        assertThat(m2.reasons()).extracting(MuleReason::code).containsExactly("pass_through", "chain");

        // Victims and the cash-out account touch the chain but move no money on.
        for (String other : List.of("victim0", "victim5", "cashout")) {
            assertThat(scores.get(other).flagged()).as(other).isFalse();
            assertThat(scores.get(other).score()).as(other).isEqualTo(0.5);
            assertThat(scores.get(other).reasons()).as(other).extracting(MuleReason::code).containsExactly("chain");
        }
        assertThat(scores.keySet()).containsExactly("cashout", "m1", "m2", "victim0", "victim1", "victim2",
                "victim3", "victim4", "victim5");
    }

    @Test
    void aBusySmallBusinessIsNotFlagged() {
        List<Transfer> transfers = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            transfers.add(pay("customer" + i, "bakery", 1_500, Duration.ofMinutes(20L * i)));
        }
        transfers.add(pay("bakery", "flour-supplier", 9_000, Duration.ofHours(30)));

        MuleScore bakery = SCORER.scoreAll(transfers, NOBODY_RELATED).get("bakery");

        assertThat(bakery.reasons()).extracting(MuleReason::code).containsExactly("fan_in");
        assertThat(bakery.score()).isEqualTo(0.6);
        assertThat(bakery.flagged()).isFalse();
    }

    @Test
    void movingYourOwnMoneyBetweenYourOwnAccountsIsNotFlagged() {
        LinkState state = BatchLinker.build(List.of(
                account("me", "sam.lee@example.com", "", "10.1.0.1", "card-sam", T0),
                account("my-savings", "sam.lee+save@example.com", "", "10.9.0.1", "card-sam", T0)),
                Constraints.of(List.of()), ClusteringConfig.defaults());
        assertThat(state.clusterIdOf("me")).isEqualTo(state.clusterIdOf("my-savings"));

        List<Transfer> transfers = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            transfers.add(pay("client" + i, "me", 100_000, Duration.ofMinutes(i)));
        }
        transfers.add(pay("me", "my-savings", 590_000, Duration.ofMinutes(10)));

        MuleScore me = SCORER.scoreAll(transfers, MuleScorer.sameCluster(state)).get("me");

        assertThat(me.reasons()).extracting(MuleReason::code).containsExactly("fan_in");
        assertThat(me.flagged()).isFalse();
    }

    @Test
    void scoringNeverChangesTheClusters() {
        List<Account> accounts = List.of(
                account("m1", "a@example.com", "", "10.1.0.1", "card-1", T0),
                account("m2", "b@example.com", "", "10.2.0.1", "card-1", T0),
                account("victim0", "c@example.com", "", "10.3.0.1", "", T0));
        LinkState state = BatchLinker.build(accounts, Constraints.of(List.of()), ClusteringConfig.defaults());
        ClustersOutput before = state.clusters();

        Map<String, MuleScore> scores = SCORER.scoreAll(muleChain(), MuleScorer.sameCluster(state));

        assertThat(state.clusters()).isEqualTo(before);
        state.validateInvariants();
        // m1 and m2 share a card, so the linker put them in one cluster. m1 paying m2 is moving its own
        // money, so m1 is not pass-through, and m2's pass-through has no unrelated mule to chain to.
        assertThat(scores.get("m1").reasons()).extracting(MuleReason::code).containsExactly("fan_in");
        assertThat(scores.get("m2").reasons()).extracting(MuleReason::code).containsExactly("pass_through");
        assertThat(scores.get("m2").score()).isEqualTo(0.7);
        assertThat(scores.get("m2").flagged()).isFalse();
    }

    @Test
    void accountsTheBankDoesNotHoldAreRelatedToNothing() {
        LinkState state = BatchLinker.build(List.of(account("known", "k@example.com", T0)),
                Constraints.of(List.of()), ClusteringConfig.defaults());
        BiPredicate<String, String> related = MuleScorer.sameCluster(state);

        assertThat(related.test("known", "known")).isTrue();
        assertThat(related.test("known", "external")).isFalse();
        assertThat(related.test("external", "known")).isFalse();
        assertThat(related.test("external", "external")).isFalse();
    }

    @Test
    void rejectsDuplicateTransferIds() {
        Transfer first = new Transfer("t1", "a", "b", 1, T0);
        Transfer again = new Transfer("t1", "c", "d", 2, T0);
        assertThatThrownBy(() -> SCORER.scoreAll(List.of(first, again), NOBODY_RELATED))
                .isInstanceOf(LinkerException.class).hasMessage("duplicate transfer_id t1");
    }

    @Test
    void theResultDoesNotDependOnInputOrder() {
        List<Transfer> transfers = muleChain();
        List<Transfer> reversed = new ArrayList<>(transfers);
        java.util.Collections.reverse(reversed);
        assertThat(SCORER.scoreAll(reversed, NOBODY_RELATED)).isEqualTo(SCORER.scoreAll(transfers, NOBODY_RELATED));
    }

    @Test
    void customWeightsAndThresholdApply() {
        MuleScorer strict = new MuleScorer(new MuleConfig(5, Duration.ofHours(24), 0.8, Duration.ofMinutes(60),
                1.0, 0.0, 0.25, 0.95));
        Map<String, MuleScore> scores = strict.scoreAll(muleChain(), NOBODY_RELATED);
        assertThat(scores.get("m1").score()).isEqualTo(1.0);
        assertThat(scores.get("m1").flagged()).isTrue();
        assertThat(scores.get("m2").score()).isEqualTo(0.25);
        assertThat(scores.get("m2").flagged()).isFalse();

        // A score exactly at the threshold is flagged: m2 scores 0.85 with the default weights.
        MuleScorer atThreshold = new MuleScorer(new MuleConfig(5, Duration.ofHours(24), 0.8,
                Duration.ofMinutes(60), 0.6, 0.7, 0.5, 0.85));
        assertThat(atThreshold.scoreAll(muleChain(), NOBODY_RELATED).get("m2").flagged()).isTrue();
    }

    @Test
    void sortsEachAccountsPaymentsOutByTime() {
        // 9,000 leaves 30 min after arriving; the last 1,000 leaves after the window, so it is the
        // account's own balance. Given latest first, the late payment must not expire the money early.
        List<Transfer> latestFirst = List.of(
                pay("mule", "next", 1_000, Duration.ofMinutes(65)),
                pay("mule", "next", 9_000, Duration.ofMinutes(30)),
                pay("victim", "mule", 10_000, Duration.ZERO));

        MuleScore mule = SCORER.scoreAll(latestFirst, NOBODY_RELATED).get("mule");

        assertThat(mule.reasons()).containsExactly(new MuleReason.PassThrough(0.9, Duration.ofMinutes(30)));
    }

    @Test
    void scoresAreRoundedToFourDecimals() {
        MuleScorer odd = new MuleScorer(new MuleConfig(5, Duration.ofHours(24), 0.8, Duration.ofMinutes(60),
                0.123456, 0.0, 0.0, 0.5));
        assertThat(odd.scoreAll(muleChain(), NOBODY_RELATED).get("m1").score()).isEqualTo(0.1235);
    }

    @ParameterizedTest
    @CsvSource({
            "'', a, b, 1, a transfer needs a transfer_id",
            "' ', a, b, 1, a transfer needs a transfer_id",
            "t, '', b, 1, transfer t needs both a from and a to account",
            "t, a, ' ', 1, transfer t needs both a from and a to account",
            "t, a, a, 1, transfer t sends money from an account to itself",
            "t, a, b, 0, transfer t must move a positive amount",
            "t, a, b, -5, transfer t must move a positive amount",
    })
    void rejectsInvalidTransfers(String id, String from, String to, long cents, String message) {
        assertThatThrownBy(() -> new Transfer(id, from, to, cents, T0))
                .isInstanceOf(LinkerException.class).hasMessage(message);
    }

    @Test
    void rejectsTransfersWithMissingFields() {
        assertThatThrownBy(() -> new Transfer(null, "a", "b", 1, T0)).hasMessage("a transfer needs a transfer_id");
        assertThatThrownBy(() -> new Transfer("t", null, "b", 1, T0))
                .hasMessage("transfer t needs both a from and a to account");
        assertThatThrownBy(() -> new Transfer("t", "a", null, 1, T0))
                .hasMessage("transfer t needs both a from and a to account");
        assertThatThrownBy(() -> new Transfer("t", "a", "b", 1, null)).hasMessage("transfer t needs a time");
    }

    @Test
    void validatesConfig() {
        Duration day = Duration.ofHours(24);
        Duration hour = Duration.ofHours(1);
        assertThatThrownBy(() -> new MuleConfig(1, day, 0.8, hour, 0.6, 0.7, 0.5, 0.8))
                .hasMessage("fan-in needs at least 2 payers");
        assertThatThrownBy(() -> new MuleConfig(2, Duration.ZERO, 0.8, hour, 0.6, 0.7, 0.5, 0.8))
                .hasMessage("fan-in window must be longer than zero");
        assertThatThrownBy(() -> new MuleConfig(2, day, 0.8, Duration.ofMinutes(-1), 0.6, 0.7, 0.5, 0.8))
                .hasMessage("pass-through window must be longer than zero");
        assertThatThrownBy(() -> new MuleConfig(2, null, 0.8, hour, 0.6, 0.7, 0.5, 0.8))
                .hasMessage("fan-in window must be longer than zero");
        assertThatThrownBy(() -> new MuleConfig(2, day, 0.0, hour, 0.6, 0.7, 0.5, 0.8))
                .hasMessage("pass-through share must be between 0 and 1, got 0.0");
        assertThatThrownBy(() -> new MuleConfig(2, day, 1.01, hour, 0.6, 0.7, 0.5, 0.8))
                .hasMessage("pass-through share must be between 0 and 1, got 1.01");
        assertThatThrownBy(() -> new MuleConfig(2, day, 0.8, hour, -0.1, 0.7, 0.5, 0.8))
                .hasMessage("fan-in weight must be between 0 and 1, got -0.1");
        assertThatThrownBy(() -> new MuleConfig(2, day, 0.8, hour, 0.6, 1.5, 0.5, 0.8))
                .hasMessage("pass-through weight must be between 0 and 1, got 1.5");
        assertThatThrownBy(() -> new MuleConfig(2, day, 0.8, hour, 0.6, 0.7, 2, 0.8))
                .hasMessage("chain weight must be between 0 and 1, got 2.0");
        assertThatThrownBy(() -> new MuleConfig(2, day, 0.8, hour, 0.6, 0.7, 0.5, 0))
                .hasMessage("flag threshold must be between 0 and 1, got 0.0");
        // Edges that are allowed.
        new MuleConfig(2, Duration.ofNanos(1), 1.0, Duration.ofNanos(1), 0, 1, 0, 1);
    }

    @Test
    void defaultsMatchTheIssue() {
        MuleConfig d = MuleConfig.defaults();
        assertThat(d.fanInMinPayers()).isEqualTo(5);
        assertThat(d.fanInWindow()).isEqualTo(Duration.ofHours(24));
        assertThat(d.passThroughMinShare()).isEqualTo(0.8);
        assertThat(d.passThroughWindow()).isEqualTo(Duration.ofMinutes(60));
        // No single detector reaches the flag threshold alone.
        assertThat(List.of(d.fanInWeight(), d.passThroughWeight(), d.chainWeight())).allMatch(w -> w < d.flagThreshold());
    }

    @ParameterizedTest
    @CsvSource({"0, 0 min", "59, 59 min", "60, 1 h", "2879, 47 h", "2880, 2 d", "10000, 6 d"})
    void describesDurationsCompactly(long minutes, String text) {
        assertThat(MuleReason.duration(Duration.ofMinutes(minutes))).isEqualTo(text);
    }

    @Test
    void reasonsHaveStableCodesAndPlainDescriptions() {
        assertThat(new MuleReason.FanIn(7, Duration.ofHours(3)).describe()).isEqualTo("7 unrelated payers in 3 h");
        assertThat(new MuleReason.PassThrough(0.925, Duration.ofMinutes(40)).describe())
                .isEqualTo("93% sent on within 40 min");
        assertThat(new MuleReason.Chain(3).describe()).isEqualTo("on a money chain of 3 accounts");
        assertThat(List.of(new MuleReason.FanIn(1, Duration.ZERO), new MuleReason.PassThrough(1, Duration.ZERO),
                new MuleReason.Chain(2))).extracting(MuleReason::code).containsExactly("fan_in", "pass_through", "chain");
    }
}
