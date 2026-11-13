package io.github.rainyinsaigon.fraudlinker.domain;

/** A hard cannot-link relationship, e.g. two accounts that both passed identity verification. */
public record Constraint(String accountA, String accountB, String relation) {
}
