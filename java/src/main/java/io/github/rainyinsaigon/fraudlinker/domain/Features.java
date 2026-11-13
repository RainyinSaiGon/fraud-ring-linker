package io.github.rainyinsaigon.fraudlinker.domain;

import java.time.Instant;

/**
 * The normalized representation of an account used by every linking path.
 * Unavailable signals are empty strings (text) or null (IP and prefix).
 *
 * @param ip       canonical IP text, or null when the input IP is missing or malformed
 * @param ipPrefix canonical IPv4 /24 or IPv6 /64 prefix text, or null
 */
public record Features(
        String emailExact,
        String emailCanonical,
        String emailLocal,
        String emailBaseLocal,
        String emailSkeleton,
        String emailDomain,
        String deviceId,
        String payment,
        String ip,
        String ipPrefix,
        Instant createdAt) {
}
