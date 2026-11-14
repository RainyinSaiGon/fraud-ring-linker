package io.github.rainyinsaigon.fraudlinker.service.risk;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a cluster plus its review history into an action.
 *
 * <p>The engine only links accounts; it never restricts anyone by itself. A
 * wrong restriction harms a real customer, so restriction needs an analyst's
 * confirmation. Once a cluster is confirmed, every account that later joins
 * it is restricted straight away, which is where most of the value is: a known
 * ring cannot simply open fresh accounts.
 *
 * <p>Rules, first match wins:
 * <ol>
 *   <li>Latest decision covering any member is CONFIRMED_FRAUD: RESTRICT.</li>
 *   <li>Latest decision is NOT_FRAUD and covered every current member: ALLOW.</li>
 *   <li>Latest decision is NOT_FRAUD but accounts joined since: REVIEW again.</li>
 *   <li>No decision, more than one member: REVIEW.</li>
 *   <li>No decision, single account: ALLOW.</li>
 * </ol>
 */
public final class RiskPolicy {
    private RiskPolicy() {
    }

    public static RiskAssessment assess(List<String> clusterMembers, Optional<AnalystDecision> latestDecision) {
        if (latestDecision.isPresent()) {
            AnalystDecision decision = latestDecision.get();
            if (decision.outcome() == Outcome.CONFIRMED_FRAUD) {
                return new RiskAssessment(RiskAction.RESTRICT,
                        "Linked to accounts confirmed as fraud by " + decision.analyst() + ".");
            }
            Set<String> reviewed = new HashSet<>(decision.accountIds());
            long unreviewed = clusterMembers.stream().filter(id -> !reviewed.contains(id)).count();
            if (unreviewed == 0) {
                return new RiskAssessment(RiskAction.ALLOW, "Reviewed as not fraud by " + decision.analyst() + ".");
            }
            return new RiskAssessment(RiskAction.REVIEW,
                    unreviewed + " account(s) joined since this cluster was reviewed as not fraud.");
        }
        if (clusterMembers.size() > 1) {
            return new RiskAssessment(RiskAction.REVIEW,
                    "Linked to " + (clusterMembers.size() - 1) + " other account(s); not reviewed yet.");
        }
        return new RiskAssessment(RiskAction.ALLOW, "Not linked to any other account.");
    }
}
