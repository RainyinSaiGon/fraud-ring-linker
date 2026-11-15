package io.github.rainyinsaigon.fraudlinker.mules;

import io.github.rainyinsaigon.fraudlinker.LinkerException;

import java.time.Duration;

/**
 * Thresholds and weights for mule scoring. The defaults are starting points,
 * to be tuned against the simulator.
 *
 * <p>The weights are chosen so that no single detector can flag an account on
 * its own: a busy small business (fan-in only) or someone paying bills on payday
 * (pass-through only) stays below the flag threshold. Two detectors together cross it.
 *
 * @param fanInMinPayers     distinct unrelated payers needed within {@code fanInWindow}
 * @param passThroughMinShare share of the money received that must leave again within {@code passThroughWindow}
 * @param flagThreshold      a score at or above this flags the account for review
 */
public record MuleConfig(
        int fanInMinPayers,
        Duration fanInWindow,
        double passThroughMinShare,
        Duration passThroughWindow,
        double fanInWeight,
        double passThroughWeight,
        double chainWeight,
        double flagThreshold) {

    public MuleConfig {
        if (fanInMinPayers < 2) {
            throw new LinkerException("fan-in needs at least 2 payers");
        }
        requirePositive(fanInWindow, "fan-in window");
        requirePositive(passThroughWindow, "pass-through window");
        requireShare(passThroughMinShare, "pass-through share", false);
        requireShare(fanInWeight, "fan-in weight", true);
        requireShare(passThroughWeight, "pass-through weight", true);
        requireShare(chainWeight, "chain weight", true);
        requireShare(flagThreshold, "flag threshold", false);
    }

    public static MuleConfig defaults() {
        return new MuleConfig(5, Duration.ofHours(24), 0.80, Duration.ofMinutes(60), 0.6, 0.7, 0.5, 0.8);
    }

    private static void requirePositive(Duration duration, String name) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            throw new LinkerException(name + " must be longer than zero");
        }
    }

    private static void requireShare(double value, String name, boolean zeroAllowed) {
        boolean inRange = zeroAllowed ? value >= 0 && value <= 1 : value > 0 && value <= 1;
        if (!inRange) {
            throw new LinkerException(name + " must be between 0 and 1, got " + value);
        }
    }
}
