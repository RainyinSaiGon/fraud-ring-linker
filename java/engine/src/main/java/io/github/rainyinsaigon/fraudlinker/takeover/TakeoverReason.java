package io.github.rainyinsaigon.fraudlinker.takeover;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * One takeover signal on an account, at the moment it happened, with the numbers
 * an analyst needs to judge it.
 */
public sealed interface TakeoverReason {

    /** Stable code for APIs and audit records, for example {@code new_device}. */
    String code();

    /** Plain-language summary, for example "first login from a new device after 14 logins". */
    String describe();

    /** When the signal happened: the login that showed it. */
    Instant at();

    /** A successful login from a device the account had never used, after a history of logins. */
    record NewDevice(Instant at, String deviceId, int priorLogins) implements TakeoverReason {
        @Override
        public String code() {
            return "new_device";
        }

        @Override
        public String describe() {
            return "first login from a new device after " + priorLogins + " logins";
        }
    }

    /** Two successful logins further apart than anyone could travel in the time between them. */
    record ImpossibleTravel(Instant at, double km, Duration between) implements TakeoverReason {
        @Override
        public String code() {
            return "impossible_travel";
        }

        public double kmh() {
            return km / Math.max(between.toSeconds(), 60) * 3600;
        }

        @Override
        public String describe() {
            return String.format(Locale.ROOT, "%,.0f km in %s (%,.0f km/h)", km, duration(between), kmh());
        }
    }

    /** Many failed logins in a short time, then a success: a guessed or stuffed password. */
    record BurstThenSuccess(Instant at, int failures, Duration span) implements TakeoverReason {
        @Override
        public String code() {
            return "failed_burst_then_success";
        }

        @Override
        public String describe() {
            return failures + " failed logins in " + duration(span) + ", then a success";
        }
    }

    /** Many failed logins in a short time with no success: someone tried and did not get in. */
    record BurstOnly(Instant at, int failures, Duration span) implements TakeoverReason {
        @Override
        public String code() {
            return "failed_burst";
        }

        @Override
        public String describe() {
            return failures + " failed logins in " + duration(span);
        }
    }

    /** The device of this login also logged in to many unrelated accounts: credential stuffing. */
    record SharedDevice(Instant at, String deviceId, int accounts, Duration span) implements TakeoverReason {
        @Override
        public String code() {
            return "shared_device";
        }

        @Override
        public String describe() {
            return "its device logged in to " + accounts + " unrelated accounts in " + duration(span);
        }
    }

    /** Compact duration: "45 min", "3 h", "2 d", rounded down. */
    static String duration(Duration d) {
        long minutes = d.toMinutes();
        if (minutes < 60) {
            return minutes + " min";
        }
        long hours = d.toHours();
        return hours < 48 ? hours + " h" : d.toDays() + " d";
    }
}
