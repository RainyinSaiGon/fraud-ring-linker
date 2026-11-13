package io.github.rainyinsaigon.fraudlinker.domain;

/** The one-line streaming response for a new account. */
public record Assignment(String accountId, String clusterId, double confidence) {
}
