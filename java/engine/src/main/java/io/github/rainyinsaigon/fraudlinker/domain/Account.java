package io.github.rainyinsaigon.fraudlinker.domain;

import java.time.Instant;

/**
 * One account record from the accounts JSONL input.
 *
 * @param deviceId           may be null (no device signal)
 * @param paymentFingerprint may be null (no payment method)
 */
public record Account(
        String accountId,
        String email,
        String deviceId,
        String ip,
        String paymentFingerprint,
        Instant createdAt) {

    public Account withAccountId(String newAccountId) {
        return new Account(newAccountId, email, deviceId, ip, paymentFingerprint, createdAt);
    }
}
