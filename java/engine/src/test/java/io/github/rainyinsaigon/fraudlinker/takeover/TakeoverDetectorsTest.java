package io.github.rainyinsaigon.fraudlinker.takeover;

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
import static org.assertj.core.api.Assertions.within;

class TakeoverDetectorsTest {
    static final Instant T0 = Instant.parse("2026-11-03T08:00:00Z");
    static final TakeoverConfig CONFIG = TakeoverConfig.defaults();
    static final BiPredicate<String, String> NOBODY_RELATED = (a, b) -> false;
    /** One degree of longitude on the equator. */
    static final double KM_PER_DEGREE = Math.PI * TakeoverDetectors.EARTH_RADIUS_KM / 180;

    private int seq;

    Login ok(String account, String device, Duration after) {
        return new Login("l" + seq++, account, device, "10.0.0.1", true, T0.plus(after), null, null);
    }

    Login fail(String account, Duration after) {
        return new Login("l" + seq++, account, "dev-x", "10.0.0.1", false, T0.plus(after), null, null);
    }

    Login at(String account, double longitude, Duration after) {
        return new Login("l" + seq++, account, "phone", "10.0.0.1", true, T0.plus(after), 0.0, longitude);
    }

    static Duration minutes(long m) {
        return Duration.ofMinutes(m);
    }

    /** {@code count} successful logins from the account's own phone, one a day. */
    List<Login> history(String account, int count) {
        List<Login> logins = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            logins.add(ok(account, "own-phone", Duration.ofDays(i)));
        }
        return logins;
    }

    @Nested
    class NewDevice {
        @Test
        void needsAHistoryOfExactlyMinHistoryEarlierLogins() {
            List<Login> enough = history("a", 5);
            enough.add(ok("a", "new-phone", Duration.ofDays(6)));
            assertThat(TakeoverDetectors.newDevices(enough, CONFIG))
                    .containsExactly(new TakeoverReason.NewDevice(T0.plus(Duration.ofDays(6)), "new-phone", 5));

            List<Login> tooShort = history("a", 4);
            tooShort.add(ok("a", "new-phone", Duration.ofDays(6)));
            assertThat(TakeoverDetectors.newDevices(tooShort, CONFIG)).isEmpty();
        }

        @Test
        void countsOnlySuccessesAsHistoryAndFlagsADeviceOnce() {
            List<Login> logins = history("a", 4);
            logins.add(fail("a", Duration.ofDays(4)));
            logins.add(ok("a", "new-phone", Duration.ofDays(5)));
            logins.add(ok("a", null, Duration.ofDays(6)));
            logins.add(ok("a", "tablet", Duration.ofDays(7)));
            logins.add(ok("a", "tablet", Duration.ofDays(8)));
            // The failure is no history, so the new phone comes after only 4 logins; the unknown device
            // is history but never new; the tablet is new once, after 6 logins.
            assertThat(TakeoverDetectors.newDevices(logins, CONFIG))
                    .containsExactly(new TakeoverReason.NewDevice(T0.plus(Duration.ofDays(7)), "tablet", 6));
        }
    }

    @Nested
    class ImpossibleTravel {
        @Test
        void comparesTheSpeedWithTheLimit() {
            // 10 degrees of longitude on the equator is about 1,112 km.
            Login start = at("a", 0, Duration.ZERO);
            List<Login> justUnder = List.of(start, at("a", 10, minutes(75)));
            List<Login> justOver = List.of(start, at("a", 10, minutes(74)));

            assertThat(TakeoverDetectors.impossibleTravel(justUnder, CONFIG)).isEmpty();
            List<TakeoverReason> found = TakeoverDetectors.impossibleTravel(justOver, CONFIG);
            assertThat(found).hasSize(1);
            TakeoverReason.ImpossibleTravel jump = (TakeoverReason.ImpossibleTravel) found.get(0);
            assertThat(jump.km()).isCloseTo(10 * KM_PER_DEGREE, within(0.01));
            assertThat(jump.kmh()).isCloseTo(10 * KM_PER_DEGREE / 74 * 60, within(0.01));
            assertThat(jump.at()).isEqualTo(T0.plus(minutes(74)));
        }

        @Test
        void ignoresShortJumpsLoginsWithoutALocationAndFailures() {
            Login start = at("a", 0, Duration.ZERO);
            // About 222 km in one minute: fast, but IP locations are only city-accurate.
            assertThat(TakeoverDetectors.impossibleTravel(List.of(start, at("a", 2, minutes(1))), CONFIG)).isEmpty();

            Login far = at("a", 90, minutes(10));
            Login farFailure = new Login("f", "a", "phone", "ip", false, T0.plus(minutes(5)), 0.0, 90.0);
            Login noLocation = ok("a", "phone", minutes(6));
            assertThat(TakeoverDetectors.impossibleTravel(List.of(start, farFailure, noLocation), CONFIG)).isEmpty();
            assertThat(TakeoverDetectors.impossibleTravel(List.of(start, farFailure, noLocation, far), CONFIG))
                    .hasSize(1);
        }

        @Test
        void comparesEachLoginWithThePreviousLocatedOne() {
            List<Login> trip = List.of(at("a", 0, Duration.ZERO), at("a", 90, Duration.ofHours(1)),
                    at("a", 90.5, Duration.ofHours(2)), at("a", 0, Duration.ofHours(3)));
            assertThat(TakeoverDetectors.impossibleTravel(trip, CONFIG)).extracting(TakeoverReason::at)
                    .containsExactly(T0.plus(Duration.ofHours(1)), T0.plus(Duration.ofHours(3)));
        }

        @Test
        void treatsLoginsAtTheSameMomentAsOneMinuteApart() {
            List<Login> same = List.of(at("a", 0, Duration.ZERO), at("a", 10, Duration.ZERO));
            TakeoverReason.ImpossibleTravel jump =
                    (TakeoverReason.ImpossibleTravel) TakeoverDetectors.impossibleTravel(same, CONFIG).get(0);
            assertThat(jump.kmh()).isCloseTo(10 * KM_PER_DEGREE * 60, within(0.01));
        }

        @Test
        void theMinimumDistanceCountsAndTheSpeedLimitItselfIsPossible() {
            Login start = at("a", 0, Duration.ZERO);
            Login end = at("a", 10, minutes(60));
            double km = TakeoverDetectors.distanceKm(start, end);
            double kmh = km;
            assertThat(TakeoverDetectors.impossibleTravel(List.of(start, end), config(km, kmh - 0.001))).hasSize(1);
            assertThat(TakeoverDetectors.impossibleTravel(List.of(start, end), config(km + 0.001, kmh - 0.001)))
                    .isEmpty();
            assertThat(TakeoverDetectors.impossibleTravel(List.of(start, end), config(km, kmh))).isEmpty();
        }

        TakeoverConfig config(double minTravelKm, double maxSpeedKmh) {
            TakeoverConfig d = TakeoverConfig.defaults();
            return new TakeoverConfig(d.minHistory(), maxSpeedKmh, minTravelKm, d.burstFailures(), d.burstWindow(),
                    d.sharedDeviceAccounts(), d.sharedDeviceWindow(), d.incidentWindow(), d.newDeviceWeight(),
                    d.impossibleTravelWeight(), d.burstThenSuccessWeight(), d.burstOnlyWeight(),
                    d.sharedDeviceWeight(), d.flagThreshold());
        }

        @Test
        void measuresGreatCircleDistance() {
            Login sydney = new Login("s", "a", null, "ip", true, T0, -33.8688, 151.2093);
            Login london = new Login("l", "a", null, "ip", true, T0, 51.5074, -0.1278);
            assertThat(TakeoverDetectors.distanceKm(sydney, london)).isCloseTo(16_990, within(30.0));
            assertThat(TakeoverDetectors.distanceKm(sydney, sydney)).isZero();
        }
    }

    @Nested
    class Bursts {
        @Test
        void aBurstThenASuccessUsesUpItsFailures() {
            List<Login> logins = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                logins.add(fail("a", minutes(2L * i)));
            }
            logins.add(ok("a", "dev-x", minutes(15)));
            logins.add(ok("a", "dev-x", minutes(16)));

            assertThat(TakeoverDetectors.bursts(logins, CONFIG))
                    .containsExactly(new TakeoverReason.BurstThenSuccess(T0.plus(minutes(15)), 5, minutes(8)));
        }

        @Test
        void failuresMustFallInsideTheWindowBeforeTheSuccess() {
            List<Login> logins = new ArrayList<>();
            logins.add(fail("a", Duration.ZERO));
            for (int i = 1; i < 5; i++) {
                logins.add(fail("a", minutes(i)));
            }
            logins.add(ok("a", "dev-x", minutes(15).plusSeconds(1)));
            // The first failure is 15 min 1 s before the success: only 4 count, so no burst then success,
            // and the 5 failures together are a burst that did not get in.
            assertThat(TakeoverDetectors.bursts(logins, CONFIG))
                    .containsExactly(new TakeoverReason.BurstOnly(T0.plus(minutes(4)), 5, minutes(4)));
        }

        @Test
        void reportsEachSeparateBurstWithNoSuccessOnce() {
            List<Login> logins = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                logins.add(fail("a", minutes(i)));
            }
            for (int i = 0; i < 5; i++) {
                logins.add(fail("a", Duration.ofHours(2).plusMinutes(3L * i)));
            }
            for (int i = 0; i < 4; i++) {
                logins.add(fail("a", Duration.ofHours(5).plusMinutes(i)));
            }
            assertThat(TakeoverDetectors.bursts(logins, CONFIG)).containsExactly(
                    new TakeoverReason.BurstOnly(T0.plus(minutes(5)), 6, minutes(5)),
                    new TakeoverReason.BurstOnly(T0.plus(Duration.ofHours(2).plusMinutes(12)), 5, minutes(12)));
        }

        List<Login> failuresAt(int... minutes) {
            List<Login> logins = new ArrayList<>();
            for (int m : minutes) {
                logins.add(fail("a", minutes(m)));
            }
            return logins;
        }

        @Test
        void failuresExactlyAWindowApartAreOneBurst() {
            // 0 to 15 min is exactly the window: all six failures are one burst, not five then one.
            assertThat(TakeoverDetectors.bursts(failuresAt(0, 1, 2, 3, 4, 15), CONFIG))
                    .containsExactly(new TakeoverReason.BurstOnly(T0.plus(minutes(15)), 6, minutes(15)));
        }

        @Test
        void theWindowSlidesPastOldFailures() {
            assertThat(TakeoverDetectors.bursts(failuresAt(0, 20, 21, 22, 23, 24), CONFIG))
                    .containsExactly(new TakeoverReason.BurstOnly(T0.plus(minutes(24)), 5, minutes(4)));
        }

        @Test
        void aBurstsFailuresAreNotCountedAgain() {
            // 0 to 10 min is a burst of 5. The next three are within 15 min of the burst's last failures,
            // but those are used up, so there is no second burst.
            assertThat(TakeoverDetectors.bursts(failuresAt(0, 1, 2, 3, 10, 16, 17, 18), CONFIG))
                    .containsExactly(new TakeoverReason.BurstOnly(T0.plus(minutes(10)), 5, minutes(10)));
        }

        @Test
        void aWindowCanStartAgainRightAfterTheLastBurst() {
            List<Login> logins = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                logins.add(fail("a", minutes(2L * i)));
            }
            // 10 failures over 18 min: the first 8 (0 to 14 min) fit one window; the last 2 are too few.
            assertThat(TakeoverDetectors.bursts(logins, CONFIG))
                    .containsExactly(new TakeoverReason.BurstOnly(T0.plus(minutes(14)), 8, minutes(14)));
        }
    }

    @Nested
    class SharedDevice {
        List<Login> stuffing(int accounts, Duration step) {
            List<Login> logins = new ArrayList<>();
            for (int i = 0; i < accounts; i++) {
                logins.add(ok("victim" + i, "bot", step.multipliedBy(i)));
            }
            return logins;
        }

        @Test
        void flagsEveryAccountADeviceGotIntoWhenThereAreEnough() {
            Map<String, List<TakeoverReason>> found = TakeoverDetectors.sharedDevices(stuffing(4, minutes(10)),
                    NOBODY_RELATED, CONFIG);

            assertThat(found).containsOnlyKeys("victim0", "victim1", "victim2", "victim3");
            assertThat(found.get("victim2")).containsExactly(
                    new TakeoverReason.SharedDevice(T0.plus(minutes(20)), "bot", 4, minutes(30)));
            assertThat(TakeoverDetectors.sharedDevices(stuffing(3, minutes(10)), NOBODY_RELATED, CONFIG)).isEmpty();
        }

        @Test
        void accountsMustFallInsideOneWindow() {
            assertThat(TakeoverDetectors.sharedDevices(stuffing(4, Duration.ofHours(8)), NOBODY_RELATED, CONFIG))
                    .hasSize(4);
            assertThat(TakeoverDetectors.sharedDevices(stuffing(4, Duration.ofHours(8).plusSeconds(1)),
                    NOBODY_RELATED, CONFIG)).isEmpty();
        }

        @Test
        void keepsTheLargestCountForALoginInSeveralWindows() {
            List<Login> logins = stuffing(5, Duration.ofHours(6));
            // victim0..4 at 0, 6, 12, 18, 24 h: the window ending at 18 h holds 4 accounts and the one ending
            // at 24 h holds all 5 (the edge counts). victim1 is in both and keeps one signal, the larger.
            Map<String, List<TakeoverReason>> found = TakeoverDetectors.sharedDevices(logins, NOBODY_RELATED, CONFIG);
            assertThat(found).hasSize(5);
            assertThat(found.get("victim1")).extracting(r -> ((TakeoverReason.SharedDevice) r).accounts())
                    .containsExactly(5);
        }

        @Test
        void keepsTheFirstWindowWhenALaterOneCountsNoMoreAccounts() {
            List<Login> logins = stuffing(4, Duration.ofHours(1));
            logins.add(ok("victim0", "bot", Duration.ofHours(4)));
            // victim1 is in the window ending at 3 h (4 accounts over 3 h) and the one ending at 4 h
            // (still 4 accounts, over 4 h): it keeps the first.
            assertThat(TakeoverDetectors.sharedDevices(logins, NOBODY_RELATED, CONFIG).get("victim1"))
                    .containsExactly(new TakeoverReason.SharedDevice(T0.plus(Duration.ofHours(1)), "bot", 4,
                            Duration.ofHours(3)));
        }

        @Test
        void countsAccountsInOneClusterOnceAndIgnoresUnknownDevicesAndFailures() {
            BiPredicate<String, String> family = (a, b) -> Set.of("victim0", "victim1").containsAll(Set.of(a, b));
            assertThat(TakeoverDetectors.sharedDevices(stuffing(4, minutes(10)), family, CONFIG)).isEmpty();

            List<Login> logins = stuffing(3, minutes(10));
            logins.add(new Login("x", "victim9", null, "ip", true, T0.plus(minutes(40)), null, null));
            logins.add(new Login("y", "victim8", "bot", "ip", false, T0.plus(minutes(41)), null, null));
            assertThat(TakeoverDetectors.sharedDevices(logins, NOBODY_RELATED, CONFIG)).isEmpty();
        }
    }
}
