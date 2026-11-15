package io.github.rainyinsaigon.fraudlinker.mules;

import java.time.Duration;
import java.util.Locale;

/** One detector that fired for an account, with the numbers an analyst needs to judge it. */
public sealed interface MuleReason {

    /** Stable code for APIs and audit records, for example {@code fan_in}. */
    String code();

    /** Plain-language summary, for example "7 unrelated payers in 3 h". */
    String describe();

    /**
     * Money arrived from many payers that are not linked to the receiver.
     *
     * @param payers distinct unrelated payers in the busiest window
     * @param span   time from the first to the last of those payments
     */
    record FanIn(int payers, Duration span) implements MuleReason {
        @Override
        public String code() {
            return "fan_in";
        }

        @Override
        public String describe() {
            return payers + " unrelated payers in " + duration(span);
        }
    }

    /**
     * Most of the money received left again quickly, to accounts not linked to this one.
     *
     * @param share        sent-on share of all money received, in [0, 1]
     * @param longestDelay the slowest matched hand-on, never more than the configured window
     */
    record PassThrough(double share, Duration longestDelay) implements MuleReason {
        @Override
        public String code() {
            return "pass_through";
        }

        @Override
        public String describe() {
            return Math.round(share * 100) + "% sent on within " + duration(longestDelay);
        }
    }

    /**
     * Money links this account to other pass-through accounts.
     *
     * @param accounts accounts on the longest pass-through chain through this one, including it
     */
    record Chain(int accounts) implements MuleReason {
        @Override
        public String code() {
            return "chain";
        }

        @Override
        public String describe() {
            return "on a money chain of " + accounts + " accounts";
        }
    }

    /** Compact duration: "45 min", "3 h", "2 d", rounded down. */
    static String duration(Duration d) {
        long minutes = d.toMinutes();
        if (minutes < 60) {
            return minutes + " min";
        }
        long hours = d.toHours();
        if (hours < 48) {
            return hours + " h";
        }
        return String.format(Locale.ROOT, "%d d", d.toDays());
    }
}
