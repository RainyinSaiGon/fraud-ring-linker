package io.github.rainyinsaigon.fraudlinker.takeover;

import io.github.rainyinsaigon.fraudlinker.LinkerException;

import java.time.Duration;

/**
 * Thresholds and weights for takeover scoring. The defaults are starting points,
 * to be tuned against the simulator.
 *
 * <p>The weights are chosen so that no single signal flags an account: a new
 * phone, a long flight, a forgotten password or a shared family tablet each stay
 * below the flag threshold. Two strong signals in one incident cross it.
 *
 * @param minHistory           earlier successful logins an account needs before a device can be "new"
 * @param maxSpeedKmh          travel between two logins faster than this is impossible
 * @param minTravelKm          shorter jumps are ignored: IP locations are only city-accurate
 * @param burstFailures        failed logins that make a burst
 * @param burstWindow          the burst must fit in this window, and a success must follow within it
 * @param sharedDeviceAccounts unrelated accounts one device must log in to, to count as shared
 * @param sharedDeviceWindow   those logins must fit in this window
 * @param incidentWindow       signals combine only when they happen within this window of each other
 * @param flagThreshold        a score at or above this flags the account for review
 */
public record TakeoverConfig(
        int minHistory,
        double maxSpeedKmh,
        double minTravelKm,
        int burstFailures,
        Duration burstWindow,
        int sharedDeviceAccounts,
        Duration sharedDeviceWindow,
        Duration incidentWindow,
        double newDeviceWeight,
        double impossibleTravelWeight,
        double burstThenSuccessWeight,
        double burstOnlyWeight,
        double sharedDeviceWeight,
        double flagThreshold) {

    public TakeoverConfig {
        if (minHistory < 1) {
            throw new LinkerException("a device can only be new after at least 1 earlier login");
        }
        if (!(maxSpeedKmh > 0) || !(minTravelKm >= 0)) {
            throw new LinkerException("travel speed must be above 0 and the minimum distance not negative");
        }
        if (burstFailures < 2) {
            throw new LinkerException("a failed-login burst needs at least 2 failures");
        }
        if (sharedDeviceAccounts < 2) {
            throw new LinkerException("a shared device needs at least 2 accounts");
        }
        requirePositive(burstWindow, "burst window");
        requirePositive(sharedDeviceWindow, "shared-device window");
        requirePositive(incidentWindow, "incident window");
        requireShare(newDeviceWeight, "new-device weight", true);
        requireShare(impossibleTravelWeight, "impossible-travel weight", true);
        requireShare(burstThenSuccessWeight, "burst-then-success weight", true);
        requireShare(burstOnlyWeight, "burst-only weight", true);
        requireShare(sharedDeviceWeight, "shared-device weight", true);
        requireShare(flagThreshold, "flag threshold", false);
    }

    public static TakeoverConfig defaults() {
        return new TakeoverConfig(5, 900, 300, 5, Duration.ofMinutes(15), 4, Duration.ofHours(24),
                Duration.ofHours(24), 0.4, 0.7, 0.7, 0.3, 0.7, 0.8);
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
