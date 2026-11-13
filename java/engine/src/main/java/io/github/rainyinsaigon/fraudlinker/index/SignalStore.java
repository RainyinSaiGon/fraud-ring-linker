package io.github.rainyinsaigon.fraudlinker.index;

import io.github.rainyinsaigon.fraudlinker.LinkerException;
import io.github.rainyinsaigon.fraudlinker.domain.Account;
import io.github.rainyinsaigon.fraudlinker.domain.Features;
import io.github.rainyinsaigon.fraudlinker.domain.Frequencies;
import io.github.rainyinsaigon.fraudlinker.normalize.EditDistance;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Owns every account seen so far, its normalized features, global signal
 * frequencies and the inverted indexes (signal value to account indexes) used
 * to find candidate pairs without comparing every account with every other.
 *
 * <p>Not thread-safe: batch building and stream assignment run on one thread.
 */
public final class SignalStore {
    private final List<Account> accounts = new ArrayList<>();
    private final List<Features> features = new ArrayList<>();
    private final Map<String, Integer> byId = new HashMap<>();
    private final Frequencies frequencies = new Frequencies();

    private final Map<String, List<Integer>> byCanonicalEmail = new HashMap<>();
    private final Map<String, List<Integer>> byDevice = new HashMap<>();
    private final Map<String, List<Integer>> byPayment = new HashMap<>();
    private final Map<String, List<Integer>> byIp = new HashMap<>();
    private final Map<String, List<Integer>> byIpPrefix = new HashMap<>();
    private final Map<String, List<Integer>> byEmailSignature = new HashMap<>();

    private final IndexConfig config;

    public SignalStore(IndexConfig config) {
        this.config = config;
    }

    public IndexConfig config() {
        return config;
    }

    public int size() {
        return accounts.size();
    }

    public Account account(int index) {
        return accounts.get(index);
    }

    public Features features(int index) {
        return features.get(index);
    }

    /** Returns the index of an account ID, or -1 when it has not been seen. */
    public int indexOf(String accountId) {
        Integer index = byId.get(accountId);
        return index == null ? -1 : index;
    }

    public Frequencies frequencies() {
        return frequencies;
    }

    /** Read-only view of the accounts in an IP prefix, in arrival order. */
    public List<Integer> prefixPosting(String prefix) {
        List<Integer> posting = byIpPrefix.get(prefix);
        return posting == null ? List.of() : Collections.unmodifiableList(posting);
    }

    /** Every known IP prefix. */
    public Set<String> prefixes() {
        return Collections.unmodifiableSet(byIpPrefix.keySet());
    }

    /** Stores an already validated account and indexes each available signal. */
    public int add(Account account, Features accountFeatures) {
        if (byId.containsKey(account.accountId())) {
            throw new LinkerException("duplicate account_id \"" + account.accountId() + "\"");
        }
        int index = accounts.size();
        accounts.add(account);
        features.add(accountFeatures);
        byId.put(account.accountId(), index);

        if (!accountFeatures.emailCanonical().isEmpty()) {
            frequencies.incrementEmail(accountFeatures.emailCanonical());
            post(byCanonicalEmail, accountFeatures.emailCanonical(), index);
        }
        if (!accountFeatures.deviceId().isEmpty()) {
            frequencies.incrementDevice(accountFeatures.deviceId());
            post(byDevice, accountFeatures.deviceId(), index);
        }
        if (!accountFeatures.payment().isEmpty()) {
            frequencies.incrementPayment(accountFeatures.payment());
            post(byPayment, accountFeatures.payment(), index);
        }
        if (accountFeatures.ip() != null) {
            frequencies.incrementIp(accountFeatures.ip());
            post(byIp, accountFeatures.ip(), index);
        }
        if (accountFeatures.ipPrefix() != null) {
            frequencies.incrementIpPrefix(accountFeatures.ipPrefix());
            post(byIpPrefix, accountFeatures.ipPrefix(), index);
        }
        if (!accountFeatures.emailDomain().isEmpty()) {
            for (String signature : EditDistance.deletionSignatures(accountFeatures.emailBaseLocal(), config.maxEmailRunes())) {
                post(byEmailSignature, signatureKey(accountFeatures.emailDomain(), signature), index);
            }
        }
        return index;
    }

    private static void post(Map<String, List<Integer>> postings, String key, int index) {
        postings.computeIfAbsent(key, ignored -> new ArrayList<>()).add(index);
    }

    /**
     * Returns local candidates for a feature vector, sorted by account ID.
     *
     * @param projected true for a streaming record that is not stored yet; posting
     *                  limits then count that incoming occurrence too
     */
    public List<Integer> candidatesFor(Features query, boolean projected) {
        Set<Integer> candidates = new LinkedHashSet<>();
        if (!query.emailCanonical().isEmpty()) {
            addPosting(candidates, byCanonicalEmail.get(query.emailCanonical()), config.emailBlockLimit(), projected);
        }
        if (!query.deviceId().isEmpty()) {
            addPosting(candidates, byDevice.get(query.deviceId()), config.deviceBlockLimit(), projected);
        }
        if (!query.payment().isEmpty()) {
            addPosting(candidates, byPayment.get(query.payment()), config.paymentBlockLimit(), projected);
        }
        if (query.ip() != null) {
            addPosting(candidates, byIp.get(query.ip()), config.ipBlockLimit(), projected);
        }
        if (query.ipPrefix() != null) {
            addPosting(candidates, byIpPrefix.get(query.ipPrefix()), config.prefixBlockLimit(), projected);
        }
        if (!query.emailDomain().isEmpty()) {
            for (String signature : EditDistance.deletionSignatures(query.emailBaseLocal(), config.maxEmailRunes())) {
                addPosting(candidates, byEmailSignature.get(signatureKey(query.emailDomain(), signature)),
                        config.signatureBlockLimit(), projected);
            }
        }
        List<Integer> result = new ArrayList<>(candidates);
        result.sort(Comparator.comparing(index -> accounts.get(index).accountId()));
        return result;
    }

    private void addPosting(Set<Integer> candidates, List<Integer> posting, int limit, boolean projected) {
        int length = posting == null ? 0 : posting.size();
        int projectedLength = projected ? length + 1 : length;
        if (posting == null || limit <= 0 || projectedLength > limit) {
            return;
        }
        for (int candidate : posting) {
            if (candidates.size() >= config.maxCandidates()) {
                return;
            }
            candidates.add(candidate);
        }
    }

    /** Builds the sparse batch pair set, sorted by (smaller ID, larger ID). */
    public List<Pair> candidatePairs() {
        Set<Pair> pairs = new HashSet<>();
        for (int i = 0; i < features.size(); i++) {
            for (int j : candidatesFor(features.get(i), false)) {
                if (i != j) {
                    pairs.add(Pair.of(i, j));
                }
            }
        }
        List<Pair> result = new ArrayList<>(pairs);
        result.sort(this::comparePairsById);
        return result;
    }

    /** Orders pairs by their account IDs, smaller ID first, for deterministic output. */
    public int comparePairsById(Pair left, Pair right) {
        String[] leftIds = orderedIds(left.a(), left.b());
        String[] rightIds = orderedIds(right.a(), right.b());
        int first = leftIds[0].compareTo(rightIds[0]);
        return first != 0 ? first : leftIds[1].compareTo(rightIds[1]);
    }

    private String[] orderedIds(int a, int b) {
        String left = accounts.get(a).accountId();
        String right = accounts.get(b).accountId();
        return left.compareTo(right) <= 0 ? new String[] {left, right} : new String[] {right, left};
    }

    private static String signatureKey(String domain, String signature) {
        return domain + "\u0000" + signature;
    }

    /**
     * Returns the largest number of accounts in the shared prefix that were
     * created within one window that also contains both endpoints.
     *
     * @param incoming a not-yet-stored streaming record to count too, or null
     */
    public int burstSize(Features left, Features right, Duration window, Features incoming) {
        if (left.ipPrefix() == null || !left.ipPrefix().equals(right.ipPrefix())
                || left.createdAt() == null || right.createdAt() == null) {
            return 0;
        }
        if (Duration.between(left.createdAt(), right.createdAt()).abs().compareTo(window) > 0) {
            return 0;
        }
        List<Instant> times = new ArrayList<>();
        for (int index : prefixPosting(left.ipPrefix())) {
            Instant createdAt = features.get(index).createdAt();
            if (createdAt != null) {
                times.add(createdAt);
            }
        }
        if (incoming != null) {
            times.add(incoming.createdAt());
        }
        Collections.sort(times);

        Instant low = left.createdAt().isBefore(right.createdAt()) ? left.createdAt() : right.createdAt();
        Instant high = left.createdAt().isBefore(right.createdAt()) ? right.createdAt() : left.createdAt();
        int best = 0;
        for (int start = 0; start < times.size(); start++) {
            Instant windowStart = times.get(start);
            Instant windowEnd = windowStart.plus(window);
            if (windowStart.isAfter(low) || windowEnd.isBefore(high)) {
                continue;
            }
            int end = start;
            while (end < times.size() && !times.get(end).isAfter(windowEnd)) {
                end++;
            }
            best = Math.max(best, end - start);
        }
        return best;
    }

    /** Share of distinct IP addresses in a cohort: 1.0 means every account used its own IP. */
    public double distinctIpRatio(List<Integer> indices) {
        if (indices.isEmpty()) {
            return 0;
        }
        Set<String> unique = new HashSet<>();
        for (int index : indices) {
            String ip = features.get(index).ip();
            if (ip != null) {
                unique.add(ip);
            }
        }
        return (double) unique.size() / indices.size();
    }
}
