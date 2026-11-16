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

    private static final Instant FLAGGED = Instant.parse("2026-11-03T10:00:00Z");

    private static Optional<TakeoverFlag> takeoverFlag(String account, Instant flaggedAt) {
        return Optional.of(new TakeoverFlag(account, 0.82, "first login from a new device after 7 logins",
                flaggedAt));
    }

    private static Optional<TakeoverDecision> takeoverDecision(TakeoverOutcome outcome, Instant at) {
        return Optional.of(new TakeoverDecision(UUID.randomUUID(), "a", outcome, "analyst-2", null, at));
    }

    private static RiskAssessment account(List<String> members, Optional<AnalystDecision> decision,
                                          List<MuleFlag> muleFlags, Optional<TakeoverFlag> flag,
                                          Optional<TakeoverDecision> takeover) {
        return RiskPolicy.assessAccount("a", members, decision, muleFlags, flag, takeover);
    }

    @Test
    void aTakeoverFlagAsksForReviewEvenOnASingleAccount() {
        RiskAssessment risk = account(List.of("a"), Optional.empty(), List.of(), takeoverFlag("a", FLAGGED),
                Optional.empty());
        assertThat(risk.action()).isEqualTo(RiskAction.REVIEW);
        assertThat(risk.reason())
                .isEqualTo("Account a may have been taken over: first login from a new device after 7 logins.");
    }

    @Test
    void aConfirmedTakeoverStepsUpTheAccountWithOrWithoutAFlag() {
        for (Optional<TakeoverFlag> flag : List.of(takeoverFlag("a", FLAGGED), Optional.<TakeoverFlag>empty())) {
            RiskAssessment risk = account(List.of("a", "b"), Optional.empty(), List.of(), flag,
                    takeoverDecision(TakeoverOutcome.CONFIRMED_TAKEOVER, FLAGGED.minusSeconds(60)));
            assertThat(risk.action()).isEqualTo(RiskAction.STEP_UP);
            assertThat(risk.reason()).isEqualTo(
                    "Account a was confirmed as taken over by analyst-2; re-verify the customer before letting them in.");
        }
    }

    @Test
    void confirmedFraudStillRestrictsATakenOverAccount() {
        RiskAssessment risk = account(List.of("a"), Optional.of(decisionAt(Outcome.CONFIRMED_FRAUD, FLAGGED, "a")),
                List.of(), takeoverFlag("a", FLAGGED), takeoverDecision(TakeoverOutcome.CONFIRMED_TAKEOVER, FLAGGED));
        assertThat(risk.action()).isEqualTo(RiskAction.RESTRICT);
    }

    @Test
    void aTakeoverFlagComesBeforeAMuleFlagAndTheClusterRules() {
        RiskAssessment risk = account(List.of("a", "b"), Optional.empty(), List.of(flag("b", 0.94, FLAGGED)),
                takeoverFlag("a", FLAGGED), Optional.empty());
        assertThat(risk.reason()).startsWith("Account a may have been taken over");
    }

    @Test
    void aNotTakeoverDecisionAtOrAfterTheFlagAnswersIt() {
        for (Instant decided : List.of(FLAGGED, FLAGGED.plusSeconds(1))) {
            Optional<TakeoverDecision> decision = takeoverDecision(TakeoverOutcome.NOT_TAKEOVER, decided);
            assertThat(RiskPolicy.answers(decision, takeoverFlag("a", FLAGGED).orElseThrow())).isTrue();
            // With the flag answered, the cluster rules decide: a single account is allowed.
            assertThat(account(List.of("a"), Optional.empty(), List.of(), takeoverFlag("a", FLAGGED), decision)
                    .action()).isEqualTo(RiskAction.ALLOW);
        }
    }

    @Test
    void aFlagIsNotAnsweredByAnEarlierDecisionOrBeforeItIsRecorded() {
        Optional<TakeoverDecision> earlier = takeoverDecision(TakeoverOutcome.NOT_TAKEOVER, FLAGGED.minusSeconds(1));
        assertThat(RiskPolicy.answers(earlier, takeoverFlag("a", FLAGGED).orElseThrow())).isFalse();
        assertThat(RiskPolicy.answers(Optional.empty(), takeoverFlag("a", FLAGGED).orElseThrow())).isFalse();
        assertThat(RiskPolicy.answers(takeoverDecision(TakeoverOutcome.NOT_TAKEOVER, FLAGGED),
                takeoverFlag("a", null).orElseThrow())).isFalse();
        assertThat(account(List.of("a"), Optional.empty(), List.of(), takeoverFlag("a", FLAGGED), earlier).action())
                .isEqualTo(RiskAction.REVIEW);
    }

    @Test
    void withNoTakeoverTheAccountGetsItsClustersAction() {
        assertThat(account(List.of("a", "b"), Optional.empty(), List.of(), Optional.empty(), Optional.empty()))
                .isEqualTo(RiskPolicy.assess(List.of("a", "b"), Optional.empty()));
        // A later NOT_TAKEOVER is how a re-verified customer is recorded: no more STEP_UP.
        assertThat(account(List.of("a"), Optional.empty(), List.of(), Optional.empty(),
                takeoverDecision(TakeoverOutcome.NOT_TAKEOVER, FLAGGED)).action()).isEqualTo(RiskAction.ALLOW);
    }

    @Test
    void newMembersAfterANotFraudReviewNeedAnotherReview() {
        RiskAssessment risk = RiskPolicy.assess(List.of("a", "b", "c", "d"),
                Optional.of(decision(Outcome.NOT_FRAUD, "a", "b")));
        assertThat(risk.action()).isEqualTo(RiskAction.REVIEW);
        assertThat(risk.reason()).startsWith("2 account(s) joined since");
    }
}
