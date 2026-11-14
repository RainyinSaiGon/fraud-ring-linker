package io.github.rainyinsaigon.fraudlinker.domain;

/**
 * The result of scoring a candidate account pair.
 *
 * @param eligible     whether this pair may be merged at all
 * @param identitySeed whether the pair is strong identity evidence (not just network context)
 * @param rule         the merge rule that fired, or "" when not eligible
 */
public record Assessment(Evidence evidence, double score, boolean eligible, boolean identitySeed, String rule) {
}
