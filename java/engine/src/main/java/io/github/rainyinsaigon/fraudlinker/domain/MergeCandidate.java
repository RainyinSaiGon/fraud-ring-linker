package io.github.rainyinsaigon.fraudlinker.domain;

/**
 * Another existing cluster that a streaming account also linked to strongly.
 * Streaming joins only the best cluster, so this is reported for review instead
 * of being merged silently.
 *
 * @param clusterId the other cluster
 * @param score     rounded score of the account's best link into that cluster
 * @param rule      the merge rule behind that link
 */
public record MergeCandidate(String clusterId, double score, String rule) {
}
