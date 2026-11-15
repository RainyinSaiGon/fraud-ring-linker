package io.github.rainyinsaigon.fraudlinker.service.risk;

import java.util.Comparator;
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
 * ring cannot simply open fresh accounts. A mule flag is evidence too, so it
 * asks for review, but it never restricts on its own either.
 *
 * <p>Rules, first match wins:
 * <ol>
 *   <li>Latest decision covering any member is CONFIRMED_FRAUD: RESTRICT.</li>
 *   <li>A member is flagged as a likely mule, and no NOT_FRAUD decision made after
 *       the flag covers it: REVIEW.</li>
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
        return assess(clusterMembers, latestDecision, List.of());
    }

    /** @param muleFlags members currently flagged as likely mules */
    public static RiskAssessment assess(List<String> clusterMembers, Optional<AnalystDecision> latestDecision,
                                        List<MuleFlag> muleFlags) {
        if (latestDecision.isPresent() && latestDecision.get().outcome() == Outcome.CONFIRMED_FRAUD) {
            return new RiskAssessment(RiskAction.RESTRICT,
                    "Linked to accounts confirmed as fraud by " + latestDecision.get().analyst() + ".");
        }
        List<MuleFlag> open = muleFlags.stream()
                .filter(flag -> !clears(latestDecision, flag))
                .sorted(Comparator.comparingDouble(MuleFlag::score).reversed().thenComparing(MuleFlag::accountId))
                .toList();
        if (!open.isEmpty()) {
            MuleFlag top = open.get(0);
            String more = open.size() == 1 ? "" : " " + (open.size() - 1) + " more account(s) in this cluster too.";
            return new RiskAssessment(RiskAction.REVIEW,
                    "Account " + top.accountId() + " looks like a money mule: " + top.description() + "." + more);
        }
        if (latestDecision.isPresent()) {
            AnalystDecision decision = latestDecision.get();
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

    /**
     * True when an analyst reviewed this flagged account as not fraud after it was
     * flagged. An earlier decision can't clear it: the flag is newer evidence.
     */
    public static boolean clears(Optional<AnalystDecision> latestDecision, MuleFlag flag) {
        return latestDecision.isPresent()
                && latestDecision.get().outcome() == Outcome.NOT_FRAUD
                && flag.flaggedAt() != null
                && !latestDecision.get().decidedAt().isBefore(flag.flaggedAt())
                && latestDecision.get().accountIds().contains(flag.accountId());
    }
}
