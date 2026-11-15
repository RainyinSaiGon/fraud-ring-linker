package io.github.rainyinsaigon.fraudlinker.service.risk;

import java.time.Instant;

/**
 * A cluster member whose money movement currently looks like a mule's.
 *
 * @param description plain-language reasons, for example "6 unrelated payers in 25 min, 90% sent on within 30 min"
 * @param flaggedAt   when the account was first flagged; null if not recorded yet, in which case no earlier
 *                    decision can clear it
 */
public record MuleFlag(String accountId, double score, String description, Instant flaggedAt) {
}
