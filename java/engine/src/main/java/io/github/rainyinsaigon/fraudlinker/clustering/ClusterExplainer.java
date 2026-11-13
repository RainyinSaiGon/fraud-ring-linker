package io.github.rainyinsaigon.fraudlinker.clustering;

import io.github.rainyinsaigon.fraudlinker.domain.Assessment;
import io.github.rainyinsaigon.fraudlinker.domain.Evidence;
import io.github.rainyinsaigon.fraudlinker.scoring.Scorer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Explains a cluster to an analyst: which member pairs are linked, by which
 * rule, and which signals they share.
 *
 * <p>Pairs are re-scored with the current signal frequencies, so a link made
 * earlier can look weaker now if its signal has since become common. Members
 * joined only through a group rule (a velocity burst or a seeded prefix
 * cohort) may show network evidence rather than a direct pair rule.
 */
public final class ClusterExplainer {
    private ClusterExplainer() {
    }

    /** One explained pair inside a cluster. */
    public record Link(String accountA, String accountB, String rule, double score, List<String> sharedSignals) {
    }

    /**
     * @param truncated true when the cluster was larger than the limit and only
     *                  pairs among its first {@code maxMembers} members (by ID) were scored
     */
    public record Explanation(String clusterId, List<String> accountIds, double confidence, List<Link> links,
                              boolean truncated) {
    }

    /**
     * Explains a cluster, or returns empty if the cluster ID is unknown.
     * Scoring is pairwise, so cost grows with the square of {@code maxMembers}.
     */
    public static Optional<Explanation> explain(LinkState state, String clusterId, int maxMembers) {
        List<Integer> indexes = new ArrayList<>(state.memberIndexes(clusterId));
        if (indexes.isEmpty()) {
            return Optional.empty();
        }
        indexes.sort(Comparator.comparing(index -> state.store().account(index).accountId()));
        boolean truncated = indexes.size() > maxMembers;
        List<Integer> scored = truncated ? indexes.subList(0, maxMembers) : indexes;

        List<Link> links = new ArrayList<>();
        for (int i = 0; i < scored.size(); i++) {
            for (int j = i + 1; j < scored.size(); j++) {
                int a = scored.get(i);
                int b = scored.get(j);
                Assessment assessment = BatchLinker.assessPair(state.store(), a, b, state.config().scoring());
                List<String> signals = sharedSignals(assessment.evidence());
                if (!assessment.eligible() && signals.isEmpty()) {
                    continue;
                }
                links.add(new Link(
                        state.store().account(a).accountId(),
                        state.store().account(b).accountId(),
                        assessment.eligible() ? assessment.rule() : "supporting_evidence_only",
                        Scorer.roundConfidence(assessment.score()),
                        signals));
            }
        }
        links.sort(Comparator.comparingDouble(Link::score).reversed()
                .thenComparing(Link::accountA).thenComparing(Link::accountB));
        return Optional.of(new Explanation(clusterId, state.memberIds(clusterId), state.confidence(clusterId),
                links, truncated));
    }

    /** Human-readable names of the identity and network signals a pair shares. */
    static List<String> sharedSignals(Evidence evidence) {
        List<String> signals = new ArrayList<>();
        if (evidence.exactEmail()) {
            signals.add("same_email");
        } else if (evidence.canonicalEmail()) {
            signals.add("same_email_after_normalization");
        } else if (evidence.fuzzyEmail()) {
            signals.add("email_one_edit_apart");
        } else if (evidence.sameEmailLocal()) {
            signals.add("same_email_name_different_domain");
        } else if (evidence.sameEmailSkeleton()) {
            signals.add("similar_email_ignoring_separators");
        }
        if (evidence.sameDevice()) {
            signals.add("same_device");
        }
        if (evidence.samePayment()) {
            signals.add("same_payment_method");
        }
        if (evidence.sameIp()) {
            signals.add("same_ip");
        } else if (evidence.sameIpPrefix()) {
            signals.add("same_ip_subnet");
        }
        if (evidence.velocityBurst()) {
            signals.add("signup_burst_on_subnet");
        }
        return signals;
    }
}
