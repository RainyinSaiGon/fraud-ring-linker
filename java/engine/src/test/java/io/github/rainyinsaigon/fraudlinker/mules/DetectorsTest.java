package io.github.rainyinsaigon.fraudlinker.mules;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

import static org.assertj.core.api.Assertions.assertThat;

class DetectorsTest {
    static final Instant T0 = Instant.parse("2026-11-01T09:00:00Z");
    static final MuleConfig CONFIG = MuleConfig.defaults();
    static final BiPredicate<String, String> NOBODY_RELATED = (a, b) -> false;

    private static int seq;

    static Transfer pay(String from, String to, long cents, Duration after) {
        return new Transfer("t" + seq++, from, to, cents, T0.plus(after));
    }

    static List<Transfer> sorted(List<Transfer> transfers) {
        List<Transfer> copy = new ArrayList<>(transfers);
        copy.sort(Detectors.BY_TIME);
        return copy;
    }

    /** {@code count} different external payers into "mule", one minute apart. */
    static List<Transfer> payers(int count) {
        List<Transfer> in = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            in.add(pay("victim" + i, "mule", 10_000, Duration.ofMinutes(i)));
        }
        return in;
    }

    @Nested
    class FanIn {
        @Test
        void firesAtExactlyTheMinimumNumberOfPayers() {
            assertThat(Detectors.fanIn("mule", payers(5), NOBODY_RELATED, CONFIG))
                    .contains(new MuleReason.FanIn(5, Duration.ofMinutes(4)));
            assertThat(Detectors.fanIn("mule", payers(4), NOBODY_RELATED, CONFIG)).isEmpty();
        }

        @Test
        void countsEachPayerOnce() {
            List<Transfer> in = new ArrayList<>(payers(4));
            in.add(pay("victim0", "mule", 500, Duration.ofMinutes(10)));
            in.add(pay("victim1", "mule", 500, Duration.ofMinutes(11)));
            assertThat(Detectors.fanIn("mule", sorted(in), NOBODY_RELATED, CONFIG)).isEmpty();
        }

        @Test
        void ignoresPayersInTheReceiversOwnCluster() {
            BiPredicate<String, String> family = (a, b) -> Set.of(a, b).equals(Set.of("mule", "victim0"));
            assertThat(Detectors.fanIn("mule", payers(5), family, CONFIG)).isEmpty();
            assertThat(Detectors.fanIn("mule", payers(6), family, CONFIG))
                    .contains(new MuleReason.FanIn(5, Duration.ofMinutes(4)));
        }

        @Test
        void payersMustFallInsideOneWindow() {
            List<Transfer> exactly = List.of(
                    pay("a", "mule", 1, Duration.ZERO),
                    pay("b", "mule", 1, Duration.ofHours(1)),
                    pay("c", "mule", 1, Duration.ofHours(2)),
                    pay("d", "mule", 1, Duration.ofHours(3)),
                    pay("e", "mule", 1, Duration.ofHours(24)));
            assertThat(Detectors.fanIn("mule", exactly, NOBODY_RELATED, CONFIG))
                    .contains(new MuleReason.FanIn(5, Duration.ofHours(24)));

            List<Transfer> tooSlow = new ArrayList<>(exactly.subList(0, 4));
            tooSlow.add(pay("e", "mule", 1, Duration.ofHours(24).plusSeconds(1)));
            assertThat(Detectors.fanIn("mule", tooSlow, NOBODY_RELATED, CONFIG)).isEmpty();
        }

        @Test
        void slidesTheWindowAndReportsTheBusiestOne() {
            List<Transfer> in = new ArrayList<>();
            in.add(pay("early", "mule", 1, Duration.ZERO));
            for (int i = 0; i < 6; i++) {
                in.add(pay("p" + i, "mule", 1, Duration.ofDays(3).plusMinutes(10L * i)));
            }
            // "early" is long out of the window; a repeat payer leaving the window keeps its other payment.
            in.add(pay("p0", "mule", 1, Duration.ofDays(3).plusMinutes(70)));
            assertThat(Detectors.fanIn("mule", sorted(in), NOBODY_RELATED, CONFIG))
                    .contains(new MuleReason.FanIn(6, Duration.ofMinutes(50)));
        }

        @Test
        void aRepeatPayerIsDroppedOnceAllItsPaymentsLeaveTheWindow() {
            List<Transfer> in = new ArrayList<>();
            in.add(pay("p0", "mule", 1, Duration.ZERO));
            in.add(pay("p0", "mule", 1, Duration.ofMinutes(1)));
            for (int i = 1; i <= 4; i++) {
                in.add(pay("p" + i, "mule", 1, Duration.ofDays(2).plusMinutes(i)));
            }
            // p0 paid twice, both long before the others, so only 4 payers ever share a window.
            assertThat(Detectors.fanIn("mule", sorted(in), NOBODY_RELATED, CONFIG)).isEmpty();
        }

        @Test
        void aRepeatPayerStaysCountedWhileAnyOfItsPaymentsIsInTheWindow() {
            List<Transfer> in = new ArrayList<>();
            in.add(pay("p0", "mule", 1, Duration.ZERO));
            in.add(pay("p0", "mule", 1, Duration.ofHours(20)));
            for (int i = 1; i <= 4; i++) {
                in.add(pay("p" + i, "mule", 1, Duration.ofHours(25).plusMinutes(i)));
            }
            // The first p0 payment drops out of the window, but the second one keeps p0 counted.
            assertThat(Detectors.fanIn("mule", sorted(in), NOBODY_RELATED, CONFIG))
                    .contains(new MuleReason.FanIn(5, Duration.ofHours(5).plusMinutes(4)));
        }
    }

    @Nested
    class PassThrough {
        @Test
        void firesWhenEnoughMoneyLeavesWithinTheWindow() {
            List<Transfer> in = List.of(pay("victim", "mule", 10_000, Duration.ZERO));
            List<Transfer> out = List.of(pay("mule", "next", 8_000, Duration.ofMinutes(60)));
            assertThat(Detectors.passThrough("mule", in, out, NOBODY_RELATED, CONFIG))
                    .contains(new MuleReason.PassThrough(0.8, Duration.ofMinutes(60)));

            List<Transfer> less = List.of(pay("mule", "next", 7_999, Duration.ofMinutes(5)));
            assertThat(Detectors.passThrough("mule", in, less, NOBODY_RELATED, CONFIG)).isEmpty();
        }

        @Test
        void moneyLeavingJustAfterTheWindowIsTheAccountsOwnBalance() {
            List<Transfer> in = List.of(pay("victim", "mule", 10_000, Duration.ZERO));
            List<Transfer> late = List.of(pay("mule", "next", 10_000, Duration.ofMinutes(60).plusSeconds(1)));
            assertThat(Detectors.passThrough("mule", in, late, NOBODY_RELATED, CONFIG)).isEmpty();
        }

        @Test
        void moneyMustArriveBeforeItCanLeave() {
            List<Transfer> in = List.of(pay("victim", "mule", 10_000, Duration.ofMinutes(5)));
            List<Transfer> before = List.of(pay("mule", "next", 10_000, Duration.ofMinutes(4)));
            assertThat(Detectors.passThrough("mule", in, before, NOBODY_RELATED, CONFIG)).isEmpty();

            List<Transfer> sameInstant = List.of(pay("mule", "next", 10_000, Duration.ofMinutes(5)));
            assertThat(Detectors.passThrough("mule", in, sameInstant, NOBODY_RELATED, CONFIG))
                    .contains(new MuleReason.PassThrough(1.0, Duration.ZERO));
        }

        @Test
        void movingMoneyToYourOwnAccountsIsNotPassThrough() {
            BiPredicate<String, String> own = (a, b) -> Set.of(a, b).equals(Set.of("me", "my-savings"));
            List<Transfer> in = List.of(pay("employer", "me", 500_000, Duration.ZERO));
            List<Transfer> out = List.of(pay("me", "my-savings", 450_000, Duration.ofMinutes(2)));
            assertThat(Detectors.passThrough("me", in, out, own, CONFIG)).isEmpty();
        }

        @Test
        void ownTransfersDoNotUseUpMoneyThatIsLaterPassedOn() {
            BiPredicate<String, String> own = (a, b) -> Set.of(a, b).equals(Set.of("mule", "my-savings"));
            List<Transfer> in = List.of(pay("victim", "mule", 10_000, Duration.ZERO));
            List<Transfer> out = List.of(
                    pay("mule", "my-savings", 10_000, Duration.ofMinutes(1)),
                    pay("mule", "next", 9_000, Duration.ofMinutes(2)));
            assertThat(Detectors.passThrough("mule", in, out, own, CONFIG))
                    .contains(new MuleReason.PassThrough(0.9, Duration.ofMinutes(2)));
        }

        @Test
        void matchesOldestMoneyFirstAcrossSeveralPayments() {
            List<Transfer> in = List.of(
                    pay("v1", "mule", 6_000, Duration.ZERO),
                    pay("v2", "mule", 4_000, Duration.ofMinutes(30)));
            List<Transfer> out = List.of(
                    pay("mule", "next", 5_000, Duration.ofMinutes(40)),
                    pay("mule", "next", 5_000, Duration.ofMinutes(50)));
            // The second payment out uses the last 1,000 of v1 (50 min old) and 4,000 of v2 (20 min old).
            assertThat(Detectors.passThrough("mule", in, out, NOBODY_RELATED, CONFIG))
                    .contains(new MuleReason.PassThrough(1.0, Duration.ofMinutes(50)));
        }

        @Test
        void expiredMoneyIsSkippedButNewerMoneyStillMatches() {
            List<Transfer> in = List.of(
                    pay("v1", "mule", 2_000, Duration.ZERO),
                    pay("v2", "mule", 8_000, Duration.ofMinutes(90)));
            List<Transfer> out = List.of(pay("mule", "next", 10_000, Duration.ofMinutes(100)));
            assertThat(Detectors.passThrough("mule", in, out, NOBODY_RELATED, CONFIG))
                    .contains(new MuleReason.PassThrough(0.8, Duration.ofMinutes(10)));
        }

        @Test
        void anAccountThatOnlySendsIsNotPassThrough() {
            List<Transfer> out = List.of(pay("payer", "shop", 10_000, Duration.ZERO));
            assertThat(Detectors.passThrough("payer", List.of(), out, NOBODY_RELATED, CONFIG)).isEmpty();
        }

        @Test
        void evenTheSmallestShareThresholdNeedsSomeMoneyPassedOn() {
            MuleConfig anyShare = new MuleConfig(5, Duration.ofHours(24), Double.MIN_VALUE, Duration.ofMinutes(60),
                    0.6, 0.7, 0.5, 0.8);
            List<Transfer> in = List.of(pay("victim", "mule", 10_000, Duration.ZERO));
            assertThat(Detectors.passThrough("mule", in, List.of(), NOBODY_RELATED, anyShare)).isEmpty();
            List<Transfer> out = List.of(pay("mule", "next", 1, Duration.ofMinutes(1)));
            assertThat(Detectors.passThrough("mule", in, out, NOBODY_RELATED, anyShare)).isPresent();
        }
    }

    @Nested
    class Chain {
        final Set<String> passThrough = Set.of("m1", "m2", "m3");

        @Test
        void countsTheWholeChainThroughAnAccount() {
            Map<String, Set<String>> payersOf = Map.of("m1", Set.of("victim"), "m2", Set.of("m1"),
                    "m3", Set.of("m2"), "cash", Set.of("m3"));
            Map<String, Set<String>> payeesOf = Map.of("victim", Set.of("m1"), "m1", Set.of("m2"),
                    "m2", Set.of("m3"), "m3", Set.of("cash"));

            assertThat(Detectors.chain("m2", passThrough, payersOf, payeesOf)).contains(new MuleReason.Chain(3));
            assertThat(Detectors.chain("victim", passThrough, payersOf, payeesOf)).contains(new MuleReason.Chain(4));
            assertThat(Detectors.chain("cash", passThrough, payersOf, payeesOf)).contains(new MuleReason.Chain(4));
        }

        @Test
        void needsAPassThroughCounterparty() {
            Map<String, Set<String>> payersOf = Map.of("shop", Set.of("alice", "bob"));
            Map<String, Set<String>> payeesOf = Map.of("alice", Set.of("shop"), "bob", Set.of("shop"));
            assertThat(Detectors.chain("shop", passThrough, payersOf, payeesOf)).isEmpty();
            assertThat(Detectors.chain("nobody", passThrough, Map.of(), Map.of())).isEmpty();
        }

        @Test
        void stopsOnLoops() {
            Map<String, Set<String>> loop = Map.of("m1", Set.of("m2"), "m2", Set.of("m3"), "m3", Set.of("m1"));
            assertThat(Detectors.chain("m1", passThrough, loop, loop)).contains(new MuleReason.Chain(5));
        }

        @Test
        void stopsAtTheMaximumDepth() {
            Map<String, Set<String>> payeesOf = new java.util.HashMap<>();
            Set<String> all = new java.util.HashSet<>();
            for (int i = 0; i < 30; i++) {
                payeesOf.put("m" + i, Set.of("m" + (i + 1)));
                all.add("m" + (i + 1));
            }
            assertThat(Detectors.chain("m0", all, Map.of(), payeesOf))
                    .contains(new MuleReason.Chain(1 + Detectors.MAX_CHAIN_DEPTH));
        }
    }
}
