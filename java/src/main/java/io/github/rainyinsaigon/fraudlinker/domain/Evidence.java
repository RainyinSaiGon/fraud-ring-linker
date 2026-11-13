package io.github.rainyinsaigon.fraudlinker.domain;

import java.time.Duration;

/**
 * The independent facts behind a pair score. Keeping the evidence composition
 * explicit prevents a numeric score from hiding unsafe single-signal rules.
 */
public record Evidence(
        boolean exactEmail,
        boolean canonicalEmail,
        boolean fuzzyEmail,
        boolean sameEmailLocal,
        boolean sameEmailSkeleton,
        boolean sameDevice,
        boolean samePayment,
        boolean sameIp,
        boolean sameIpPrefix,
        boolean velocityBurst,
        boolean seededPrefix,
        int emailFrequency,
        int deviceFrequency,
        int paymentFrequency,
        int ipFrequency,
        int prefixFrequency,
        int burstSize,
        int prefixSeedSize,
        Duration timeDelta) {

    public Evidence withVelocityBurst(boolean value) {
        return new Evidence(exactEmail, canonicalEmail, fuzzyEmail, sameEmailLocal, sameEmailSkeleton,
                sameDevice, samePayment, sameIp, sameIpPrefix, value, seededPrefix,
                emailFrequency, deviceFrequency, paymentFrequency, ipFrequency, prefixFrequency,
                burstSize, prefixSeedSize, timeDelta);
    }
}
