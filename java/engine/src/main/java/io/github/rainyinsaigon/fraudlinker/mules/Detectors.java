package io.github.rainyinsaigon.fraudlinker.mules;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;

/** The three mule detectors. Each looks at one account's transfers only. */
final class Detectors {
    /** Longest chain followed in each direction; real mule chains are a few hops. */
    static final int MAX_CHAIN_DEPTH = 20;

    /** Earlier first, then by ID, so the order never depends on input order. */
    static final Comparator<Transfer> BY_TIME = Comparator.comparing(Transfer::occurredAt)
            .thenComparing(Transfer::transferId);

    private Detectors() {
    }

    /**
     * The most distinct unrelated payers within any window of {@code config.fanInWindow()}.
     *
     * @param incoming transfers into {@code account}, sorted by {@link #BY_TIME}
     */
    static Optional<MuleReason.FanIn> fanIn(String account, List<Transfer> incoming,
                                            BiPredicate<String, String> related, MuleConfig config) {
        List<Transfer> unrelated = incoming.stream().filter(t -> !related.test(account, t.fromAccount())).toList();
        Map<String, Integer> inWindow = new HashMap<>();
        int start = 0;
        int best = 0;
        Duration bestSpan = Duration.ZERO;
        for (Transfer last : unrelated) {
            inWindow.merge(last.fromAccount(), 1, Integer::sum);
            while (Duration.between(unrelated.get(start).occurredAt(), last.occurredAt())
                    .compareTo(config.fanInWindow()) > 0) {
                inWindow.computeIfPresent(unrelated.get(start).fromAccount(), (payer, n) -> n == 1 ? null : n - 1);
                start++;
            }
            if (inWindow.size() > best) {
                best = inWindow.size();
                bestSpan = Duration.between(unrelated.get(start).occurredAt(), last.occurredAt());
            }
        }
        return best >= config.fanInMinPayers() ? Optional.of(new MuleReason.FanIn(best, bestSpan)) : Optional.empty();
    }

    /**
     * Matches money out (to unrelated accounts) against money in, oldest first. Money
     * counts as passed on only if it leaves within {@code config.passThroughWindow()}
     * of arriving; anything else is the account's own balance.
     *
     * @param incoming transfers into {@code account}, sorted by {@link #BY_TIME}
     * @param outgoing transfers out of {@code account}, sorted by {@link #BY_TIME}
     */
    static Optional<MuleReason.PassThrough> passThrough(String account, List<Transfer> incoming,
                                                        List<Transfer> outgoing,
                                                        BiPredicate<String, String> related, MuleConfig config) {
        long received = 0;
        for (Transfer in : incoming) {
            received += in.amountCents();
        }
        if (received == 0) {
            return Optional.empty();
        }
        Deque<long[]> lots = new ArrayDeque<>(); // {arrival epoch millis, cents left}
        long matched = 0;
        Duration longest = Duration.ZERO;
        int next = 0;
        for (Transfer out : outgoing) {
            while (next < incoming.size() && !incoming.get(next).occurredAt().isAfter(out.occurredAt())) {
                Transfer in = incoming.get(next++);
                lots.addLast(new long[] {in.occurredAt().toEpochMilli(), in.amountCents()});
            }
            if (related.test(account, out.toAccount())) {
                continue;
            }
            Instant oldestAllowed = out.occurredAt().minus(config.passThroughWindow());
            while (!lots.isEmpty() && Instant.ofEpochMilli(lots.peekFirst()[0]).isBefore(oldestAllowed)) {
                lots.removeFirst();
            }
            long toMatch = out.amountCents();
            while (toMatch > 0 && !lots.isEmpty()) {
                long[] lot = lots.peekFirst();
                long take = Math.min(toMatch, lot[1]);
                matched += take;
                toMatch -= take;
                lot[1] -= take;
                Duration delay = Duration.between(Instant.ofEpochMilli(lot[0]), out.occurredAt());
                if (delay.compareTo(longest) > 0) {
                    longest = delay;
                }
                if (lot[1] == 0) {
                    lots.removeFirst();
                }
            }
        }
        double share = (double) matched / received;
        return share >= config.passThroughMinShare()
                ? Optional.of(new MuleReason.PassThrough(share, longest))
                : Optional.empty();
    }

    /**
     * The longest chain of pass-through accounts that {@code account} is on, following
     * money between unrelated accounts both upstream and downstream.
     *
     * @return empty unless at least one unrelated counterparty is pass-through
     */
    static Optional<MuleReason.Chain> chain(String account, Set<String> passThrough,
                                            Map<String, Set<String>> payersOf, Map<String, Set<String>> payeesOf) {
        int upstream = longest(account, payersOf, passThrough, new HashSet<>(Set.of(account)), 0);
        int downstream = longest(account, payeesOf, passThrough, new HashSet<>(Set.of(account)), 0);
        return upstream + downstream == 0 ? Optional.empty() : Optional.of(new MuleReason.Chain(1 + upstream + downstream));
    }

    private static int longest(String from, Map<String, Set<String>> edges, Set<String> passThrough,
                               Set<String> onPath, int depth) {
        if (depth == MAX_CHAIN_DEPTH) {
            return 0;
        }
        int best = 0;
        for (String next : edges.getOrDefault(from, Set.of())) {
            if (passThrough.contains(next) && onPath.add(next)) {
                best = Math.max(best, 1 + longest(next, edges, passThrough, onPath, depth + 1));
                onPath.remove(next);
            }
        }
        return best;
    }
}
