package io.github.rainyinsaigon.fraudlinker.clustering;

import io.github.rainyinsaigon.fraudlinker.LinkerException;
import io.github.rainyinsaigon.fraudlinker.domain.Constraint;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Symmetric, deduplicated cannot-link adjacency. Endpoints not in the initial
 * accounts are kept, so an account that arrives later in the stream is still
 * protected.
 */
public final class Constraints {
    public static final String VERIFIED_DISTINCT = "verified_distinct";

    private final Map<String, Set<String>> neighbors = new HashMap<>();

    private Constraints() {
    }

    public static Constraints empty() {
        return new Constraints();
    }

    public static Constraints of(List<Constraint> records) {
        Constraints result = new Constraints();
        for (Constraint record : records) {
            if (!VERIFIED_DISTINCT.equals(record.relation())) {
                throw new LinkerException("unsupported relation \"" + record.relation() + "\" for \""
                        + record.accountA() + "\" and \"" + record.accountB() + "\"");
            }
            if (record.accountA().equals(record.accountB())) {
                throw new LinkerException("self constraint for account \"" + record.accountA()
                        + "\" is unsatisfiable");
            }
            result.add(record.accountA(), record.accountB());
            result.add(record.accountB(), record.accountA());
        }
        return result;
    }

    private void add(String left, String right) {
        neighbors.computeIfAbsent(left, ignored -> new HashSet<>()).add(right);
    }

    /** Accounts that must never share a cluster with this one. */
    public Set<String> neighbors(String accountId) {
        Set<String> result = neighbors.get(accountId);
        return result == null ? Set.of() : Collections.unmodifiableSet(result);
    }

    public boolean isDistinct(String left, String right) {
        return neighbors(left).contains(right);
    }

    /** Every account that appears in at least one constraint. */
    public Set<String> accountIds() {
        return Collections.unmodifiableSet(neighbors.keySet());
    }
}
