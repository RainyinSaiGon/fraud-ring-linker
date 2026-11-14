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

    @Test
    void newMembersAfterANotFraudReviewNeedAnotherReview() {
        RiskAssessment risk = RiskPolicy.assess(List.of("a", "b", "c", "d"),
                Optional.of(decision(Outcome.NOT_FRAUD, "a", "b")));
        assertThat(risk.action()).isEqualTo(RiskAction.REVIEW);
        assertThat(risk.reason()).startsWith("2 account(s) joined since");
    }
}
