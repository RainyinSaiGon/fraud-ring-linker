package io.github.rainyinsaigon.fraudlinker.service.risk;

import java.time.Instant;

/**
 * An account whose logins currently look like a takeover.
 *
 * @param description plain-language reasons, for example "first login from a new device after 11 logins"
 * @param flaggedAt   when the account was first flagged; null if not recorded yet, in which case no earlier
 *                    decision can answer it
 */
public record TakeoverFlag(String accountId, double score, String description, Instant flaggedAt) {
}
