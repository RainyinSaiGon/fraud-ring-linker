package io.github.rainyinsaigon.fraudlinker.index;

/**
 * Bounds every posting list that can generate candidate pairs. A value shared by
 * more accounts than its limit (a "hot key", like an office IP) can still add
 * evidence when another signal finds the pair, but it can never cause quadratic
 * candidate expansion on its own.
 */
public record IndexConfig(
        int emailBlockLimit,
        int deviceBlockLimit,
        int paymentBlockLimit,
        int ipBlockLimit,
        int prefixBlockLimit,
        int signatureBlockLimit,
        int maxEmailRunes,
        int maxCandidates) {

    public static IndexConfig defaults() {
        return new IndexConfig(64, 24, 32, 10, 32, 32, 64, 4096);
    }
}
