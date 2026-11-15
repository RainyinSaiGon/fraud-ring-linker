package io.github.rainyinsaigon.fraudlinker.service.risk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RiskPolicyTest {

    private static AnalystDecision decision(Outcome outcome, String... accounts) {
        return new AnalystDecision(UUID.randomUUID(), "c1", outcome, "analyst-1", null, Instant.EPOCH,
                List.of(accounts));
    }

    private static List<String> members(String csv) {
        return Arrays.asList(csv.split(" "));
    }

    @Test
    void singleAccountWithoutDecisionIsAllowed() {
        RiskAssessment risk = RiskPolicy.assess(List.of("a"), Optional.empty());
        assertThat(risk.action()).isEqualTo(RiskAction.ALLOW);
    }

    @ParameterizedTest(name = "{0} members -> REVIEW")
    @CsvSource({"a b, 1", "a b c d, 3"})
    void linkedAccountsWithoutDecisionNeedReview(String cluster, int others) {
        RiskAssessment risk = RiskPolicy.assess(members(cluster), Optional.empty());
        assertThat(risk.action()).isEqualTo(RiskAction.REVIEW);
        assertThat(risk.reason()).contains("Linked to " + others + " other account(s)");
    }

    @ParameterizedTest(name = "fraud confirmed on [{0}], cluster now [{1}] -> RESTRICT")
    @CsvSource({
            "a b, a b",
            "a b, a b c",   // new member joins a confirmed ring: restricted on arrival
            "a, a",         // a single account can be confirmed too
    })
    void confirmedFraudRestrictsEveryCurrentMember(String decided, String cluster) {
        RiskAssessment risk = RiskPolicy.assess(members(cluster),
                Optional.of(decision(Outcome.CONFIRMED_FRAUD, members(decided).toArray(String[]::new))));
        assertThat(risk.action()).isEqualTo(RiskAction.RESTRICT);
        assertThat(risk.reason()).contains("analyst-1");
    }

    @Test
    void notFraudCoveringEveryMemberIsAllowed() {
        RiskAssessment risk = RiskPolicy.assess(List.of("a", "b"),
                Optional.of(decision(Outcome.NOT_FRAUD, "a", "b")));
        assertThat(risk.action()).isEqualTo(RiskAction.ALLOW);
    }

    private static MuleFlag flag(String account, double score, Instant flaggedAt) {
        return new MuleFlag(account, score, "6 unrelated payers in 25 min", flaggedAt);
    }

    private static AnalystDecision decisionAt(Outcome outcome, Instant at, String... accounts) {
        return new AnalystDecision(UUID.randomUUID(), "c1", outcome, "analyst-1", null, at, List.of(accounts));
    }

    @Test
    void aMuleFlagAsksForReviewEvenOnASingleAccount() {
        RiskAssessment risk = RiskPolicy.assess(List.of("a"), Optional.empty(),
                List.of(flag("a", 0.94, Instant.EPOCH)));
        assertThat(risk.action()).isEqualTo(RiskAction.REVIEW);
        assertThat(risk.reason()).isEqualTo("Account a looks like a money mule: 6 unrelated payers in 25 min.");
    }

    @Test
    void theHighestScoringFlagLeadsTheReasonAndTheRestAreCounted() {
        RiskAssessment risk = RiskPolicy.assess(List.of("a", "b", "c"), Optional.empty(), List.of(
                flag("a", 0.85, Instant.EPOCH), flag("c", 0.94, Instant.EPOCH), flag("b", 0.94, Instant.EPOCH)));
        assertThat(risk.reason()).startsWith("Account b looks like a money mule")
                .endsWith(" 2 more account(s) in this cluster too.");
    }

    @Test
    void confirmedFraudStillRestrictsAFlaggedCluster() {
        RiskAssessment risk = RiskPolicy.assess(List.of("a"),
                Optional.of(decisionAt(Outcome.CONFIRMED_FRAUD, Instant.EPOCH, "a")),
                List.of(flag("a", 0.94, Instant.EPOCH.plusSeconds(60))));
        assertThat(risk.action()).isEqualTo(RiskAction.RESTRICT);
    }

    @Test
    void aNotFraudDecisionAfterTheFlagClearsIt() {
        Instant flagged = Instant.parse("2026-11-03T10:00:00Z");
        MuleFlag muleFlag = flag("a", 0.94, flagged);
        for (Instant decided : List.of(flagged, flagged.plusSeconds(1))) {
            Optional<AnalystDecision> decision = Optional.of(decisionAt(Outcome.NOT_FRAUD, decided, "a", "b"));
            assertThat(RiskPolicy.clears(decision, muleFlag)).isTrue();
            assertThat(RiskPolicy.assess(List.of("a", "b"), decision, List.of(muleFlag)).action())
                    .isEqualTo(RiskAction.ALLOW);
        }
    }

    @Test
    void aFlagIsNotClearedByAnEarlierDecisionOrOneThatDidNotCoverIt() {
        Instant flagged = Instant.parse("2026-11-03T10:00:00Z");
        MuleFlag muleFlag = flag("a", 0.94, flagged);
        Optional<AnalystDecision> earlier = Optional.of(decisionAt(Outcome.NOT_FRAUD, flagged.minusSeconds(1), "a"));
        Optional<AnalystDecision> otherAccount = Optional.of(decisionAt(Outcome.NOT_FRAUD, flagged, "b"));
        Optional<AnalystDecision> fraud = Optional.of(decisionAt(Outcome.CONFIRMED_FRAUD, flagged, "a"));

        assertThat(RiskPolicy.clears(earlier, muleFlag)).isFalse();
        assertThat(RiskPolicy.clears(otherAccount, muleFlag)).isFalse();
        assertThat(RiskPolicy.clears(fraud, muleFlag)).isFalse();
        assertThat(RiskPolicy.clears(Optional.empty(), muleFlag)).isFalse();
        assertThat(RiskPolicy.clears(Optional.of(decisionAt(Outcome.NOT_FRAUD, flagged, "a")),
                flag("a", 0.94, null))).isFalse();
        assertThat(RiskPolicy.assess(List.of("a"), earlier, List.of(muleFlag)).action()).isEqualTo(RiskAction.REVIEW);
    }

    @Test
    void newMembersAfterANotFraudReviewNeedAnotherReview() {
        RiskAssessment risk = RiskPolicy.assess(List.of("a", "b", "c", "d"),
                Optional.of(decision(Outcome.NOT_FRAUD, "a", "b")));
        assertThat(risk.action()).isEqualTo(RiskAction.REVIEW);
        assertThat(risk.reason()).startsWith("2 account(s) joined since");
    }
}
