package io.github.rainyinsaigon.fraudlinker.scoring;

import io.github.rainyinsaigon.fraudlinker.domain.Assessment;
import io.github.rainyinsaigon.fraudlinker.domain.Evidence;
import io.github.rainyinsaigon.fraudlinker.domain.Features;
import io.github.rainyinsaigon.fraudlinker.domain.Frequencies;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;

import static io.github.rainyinsaigon.fraudlinker.Fixtures.features;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Pins every limit, rule and weight in {@link Scorer} at its exact boundary.
 * Written after mutation testing showed that changing a {@code <=} to {@code <}
 * in a rarity limit, or swapping a weight, went unnoticed by the other tests.
 * Expected scores are worked out by hand in the comments, not copied from the code.
 */
class ScorerBoundaryTest {
    private static final Instant T0 = Instant.parse("2026-05-01T00:00:00Z");
    private static final ScoringConfig CONFIG = ScoringConfig.defaults();

    private static Frequencies frequencies() {
        return new Frequencies();
    }

    private static void repeat(int times, Runnable action) {
        for (int i = 0; i < times; i++) {
            action.run();
        }
    }

    private static Assessment assess(Features left, Features right, Frequencies frequencies) {
        return Scorer.assess(left, right, new ScoringContext(frequencies, false, 0, 0), CONFIG);
    }

    private static Features email(String exact, String canonical, String base, String domain) {
        return features().email(exact, canonical, base, base, domain).build();
    }

    // ---- rarity limits -------------------------------------------------------

    @ParameterizedTest(name = "card shared by {0} accounts -> rare_payment? {1}")
    @CsvSource({"2, true", "8, true", "9, false"})
    void paymentIsStrongUpToEightAccounts(int sharedBy, boolean strong) {
        Frequencies f = frequencies();
        repeat(sharedBy, () -> f.incrementPayment("card"));
        Features side = features().payment("card").createdAt(T0).build();

        Assessment assessment = assess(side, side, f);

        assertThat(assessment.rule().equals(Scorer.RULE_STRONG_PAYMENT)).isEqualTo(strong);
        assertThat(assessment.evidence().paymentFrequency()).isEqualTo(sharedBy);
    }

    @ParameterizedTest(name = "email shared by {0} accounts -> strong email? {1}")
    @CsvSource({"2, true", "24, true", "25, false"})
    void exactEmailIsStrongUpToTwentyFourAccounts(int sharedBy, boolean strong) {
        Frequencies f = frequencies();
        repeat(sharedBy, () -> f.incrementEmail("a@x.com"));
        Features side = email("a@x.com", "a@x.com", "a", "x.com");

        Assessment assessment = assess(side, side, f);

        assertThat(assessment.rule().equals(Scorer.RULE_STRONG_EMAIL)).isEqualTo(strong);
        assertThat(assessment.identitySeed()).isEqualTo(strong);
    }

    @ParameterizedTest(name = "device shared by {0} accounts, similar email -> device_and_email? {1}")
    @CsvSource({"2, true", "16, true", "17, false"})
    void deviceCountsAsRareUpToSixteenAccounts(int sharedBy, boolean rare) {
        Frequencies f = frequencies();
        repeat(sharedBy, () -> f.incrementDevice("dev"));
        f.incrementEmail("john.smith@x.com");
        f.incrementEmail("john.smyth@x.com");
        Features left = features().email("john.smith@x.com", "john.smith@x.com", "john.smith", "johnsmith", "x.com")
                .device("dev").build();
        Features right = features().email("john.smyth@x.com", "john.smyth@x.com", "john.smyth", "johnsmyth", "x.com")
                .device("dev").build();

        Assessment assessment = assess(left, right, f);

        assertThat(assessment.evidence().fuzzyEmail()).isTrue();
        assertThat(assessment.rule().equals(Scorer.RULE_DEVICE_EMAIL)).isEqualTo(rare);
    }

    // ---- every rule fires in the situation it was written for ---------------

    @Test
    void commonCardWithRareDeviceIsDeviceAndPayment() {
        Frequencies f = frequencies();
        repeat(12, () -> f.incrementPayment("card")); // too common to be strong on its own
        repeat(2, () -> f.incrementDevice("dev"));
        Features side = features().payment("card").device("dev").build();

        Assessment assessment = assess(side, side, f);

        assertThat(assessment.rule()).isEqualTo(Scorer.RULE_DEVICE_PAYMENT);
        assertThat(assessment.identitySeed()).isTrue();
    }

    @ParameterizedTest(name = "IP shared by {0}, {1}h apart -> device_network_and_time? {2}")
    @CsvSource({"2, 1, true", "8, 24, true", "9, 1, false", "2, 25, false"})
    void rareDeviceNeedsARareIpWithinADay(int ipSharedBy, int hoursApart, boolean expected) {
        Frequencies f = frequencies();
        repeat(2, () -> f.incrementDevice("dev"));
        repeat(ipSharedBy, () -> f.incrementIp("203.0.113.9"));
        repeat(ipSharedBy, () -> f.incrementIpPrefix("203.0.113.0/24"));
        Features left = features().device("dev").ip("203.0.113.9", "203.0.113.0/24").createdAt(T0).build();
        Features right = features().device("dev").ip("203.0.113.9", "203.0.113.0/24")
                .createdAt(T0.plus(Duration.ofHours(hoursApart))).build();

        Assessment assessment = assess(left, right, f);

        assertThat(assessment.rule().equals(Scorer.RULE_DEVICE_NETWORK_TIME)).isEqualTo(expected);
        assertThat(assessment.identitySeed()).as("network evidence never seeds an identity").isFalse();
    }

    @Test
    void emailTypoWithCommonCardIsEmailVariationAndPayment() {
        Frequencies f = frequencies();
        repeat(12, () -> f.incrementPayment("card"));
        f.incrementEmail("maria.lopez@x.com");
        f.incrementEmail("maria.lopes@x.com");
        Features left = features().email("maria.lopez@x.com", "maria.lopez@x.com", "maria.lopez", "marialopez", "x.com")
                .payment("card").build();
        Features right = features().email("maria.lopes@x.com", "maria.lopes@x.com", "maria.lopes", "marialopes", "x.com")
                .payment("card").build();

        Assessment assessment = assess(left, right, f);

        assertThat(assessment.rule()).isEqualTo(Scorer.RULE_EMAIL_VARIATION_PAYMENT);
        assertThat(assessment.identitySeed()).isTrue();
    }

    @Test
    void commonEmailNeedsACorroboratingSignal() {
        Frequencies f = frequencies();
        repeat(30, () -> f.incrementEmail("info@shop.com")); // a shared mailbox
        repeat(30, () -> f.incrementDevice("dev"));
        Features withDevice = features().email("info@shop.com", "info@shop.com", "info", "info", "shop.com")
                .device("dev").build();
        Features alone = email("info@shop.com", "info@shop.com", "info", "shop.com");

        assertThat(assess(withDevice, withDevice, f).rule()).isEqualTo(Scorer.RULE_COMMON_EMAIL_CORROBORATED);
        assertThat(assess(alone, alone, f).eligible()).isFalse();
    }

    @Test
    void sameNameAtDifferentDomainsIsOnlyWeakEvidence() {
        Frequencies f = frequencies();
        f.incrementEmail("kevin99@gmail.com");
        f.incrementEmail("kevin99@yahoo.com");
        Features left = email("kevin99@gmail.com", "kevin99@gmail.com", "kevin99", "gmail.com");
        Features right = email("kevin99@yahoo.com", "kevin99@yahoo.com", "kevin99", "yahoo.com");

        Assessment assessment = assess(left, right, f);

        assertThat(assessment.evidence().sameEmailLocal()).isTrue();
        assertThat(assessment.evidence().fuzzyEmail()).isFalse();
        assertThat(assessment.eligible()).isFalse();
    }

    @Test
    void skeletonMatchNeedsAtLeastFiveCharacters() {
        Features left = features().email("ab.c@x.com", "ab.c@x.com", "ab.c", "abc", "x.com").build();
        Features right = features().email("a-bc@y.com", "a-bc@y.com", "a-bc", "abc", "y.com").build();
        Features longLeft = features().email("j.o.h.n.s@x.com", "j.o.h.n.s@x.com", "j.o.h.n.s", "johns", "x.com").build();
        Features longRight = features().email("john_s@y.com", "john_s@y.com", "john_s", "johns", "y.com").build();

        assertThat(assess(left, right, frequencies()).evidence().sameEmailSkeleton()).isFalse();
        assertThat(assess(longLeft, longRight, frequencies()).evidence().sameEmailSkeleton()).isTrue();
    }

    // ---- exact scores --------------------------------------------------------

    @Test
    void rarePaymentScoreFollowsTheRarityFormula() {
        // adjusted(0.94, 2, 0.78) = 0.94 * (0.78 + 0.22 / sqrt(2)) = 0.94 * 0.935563 = 0.879429
        Frequencies f = frequencies();
        repeat(2, () -> f.incrementPayment("card"));
        Features side = features().payment("card").build();

        assertThat(assess(side, side, f).score()).isCloseTo(0.879429, within(1e-5));
    }

    @Test
    void exactEmailOutweighsCanonicalEmail() {
        // exact: adjusted(0.98, 2, 0.82) = 0.98 * (0.82 + 0.18/sqrt2) = 0.928342
        // canonical only: adjusted(0.95, 2, 0.80) = 0.95 * (0.80 + 0.20/sqrt2) = 0.894350
        Frequencies f = frequencies();
        repeat(2, () -> f.incrementEmail("jd@gmail.com"));
        Features exact = email("jd@gmail.com", "jd@gmail.com", "jd", "gmail.com");
        Features variant = email("j.d@gmail.com", "jd@gmail.com", "jd", "gmail.com");

        assertThat(assess(exact, exact, f).score()).isCloseTo(0.928342, within(1e-5));
        assertThat(assess(exact, variant, f).score()).isCloseTo(0.894350, within(1e-5));
    }

    @Test
    void deviceAndIpWeightsCombineByNoisyOr() {
        // device adjusted(0.76, 2, 0.72) = 0.76 * (0.72 + 0.28/sqrt2) = 0.697668
        // same IP adjusted(0.12, 2, 0.45) = 0.12 * (0.45 + 0.55/sqrt2) = 0.100669
        // noisy-OR = 1 - (1 - 0.697668)(1 - 0.100669) = 0.728104
        Frequencies f = frequencies();
        repeat(2, () -> f.incrementDevice("dev"));
        repeat(2, () -> f.incrementIp("10.0.0.1"));
        repeat(2, () -> f.incrementIpPrefix("10.0.0.0/24"));
        Features side = features().device("dev").ip("10.0.0.1", "10.0.0.0/24").build();

        assertThat(assess(side, side, f).score()).isCloseTo(0.728104, within(1e-5));
    }

    @Test
    void subnetOnlyWeightIsUsedWhenIpsDiffer() {
        // device 0.697668 (as above); subnet adjusted(0.035, 2, 0.40) = 0.035 * (0.40 + 0.60/sqrt2) = 0.028849
        // noisy-OR = 1 - 0.302332 * 0.971151 = 0.706390
        Frequencies f = frequencies();
        repeat(2, () -> f.incrementDevice("dev"));
        repeat(2, () -> f.incrementIpPrefix("10.0.0.0/24"));
        Features left = features().device("dev").ip("10.0.0.1", "10.0.0.0/24").build();
        Features right = features().device("dev").ip("10.0.0.2", "10.0.0.0/24").build();

        Assessment assessment = assess(left, right, f);

        assertThat(assessment.evidence().sameIp()).isFalse();
        assertThat(assessment.evidence().sameIpPrefix()).isTrue();
        assertThat(assessment.score()).isCloseTo(0.706390, within(1e-5));
    }

    @ParameterizedTest(name = "{0} apart adds {1}")
    @CsvSource({
            "PT1H, 0.08",
            "PT1H0.001S, 0.05",
            "PT24H, 0.05",
            "PT24H0.001S, 0.02",
            "P7D, 0.02",
            "PT168H0.001S, 0.0",
    })
    void signUpTimeAddsASmallWeightByCloseness(String gap, double timeWeight) {
        // Card shared by 2: p = 0.879429. Combined = 1 - (1 - p)(1 - timeWeight).
        Frequencies f = frequencies();
        repeat(2, () -> f.incrementPayment("card"));
        Features left = features().payment("card").createdAt(T0).build();
        Features right = features().payment("card").createdAt(T0.plus(Duration.parse(gap))).build();

        double expected = 1 - (1 - 0.879429) * (1 - timeWeight);
        assertThat(assess(left, right, f).score()).isCloseTo(expected, within(1e-5));
    }

    @Test
    void scoreBelowTheMergeFloorIsNeverEligibleOrASeed() {
        ScoringConfig strict = new ScoringConfig(0.95, 24, 8, 16, 8, Duration.ofHours(24), Duration.ofHours(1),
                3, 8, 0.75, 3, 8, 0.75, 0.82);
        Frequencies f = frequencies();
        repeat(2, () -> f.incrementPayment("card"));
        Features side = features().payment("card").build();

        Assessment assessment = Scorer.assess(side, side, new ScoringContext(f, false, 0, 0), strict);

        assertThat(assessment.eligible()).isFalse();
        assertThat(assessment.identitySeed()).isFalse();
        assertThat(assessment.rule()).isEmpty();
    }

    @Test
    void scoreExactlyAtTheMergeFloorIsEligible() {
        // Floor set to the exact rare-payment score, so the comparison must be inclusive.
        Frequencies f = frequencies();
        repeat(2, () -> f.incrementPayment("card"));
        Features side = features().payment("card").build();
        double exactScore = Scorer.assess(side, side, new ScoringContext(f, false, 0, 0), CONFIG).score();
        ScoringConfig atFloor = new ScoringConfig(exactScore, 24, 8, 16, 8, Duration.ofHours(24), Duration.ofHours(1),
                3, 8, 0.75, 3, 8, 0.75, 0.82);

        assertThat(Scorer.assess(side, side, new ScoringContext(f, false, 0, 0), atFloor).eligible()).isTrue();
    }

    // ---- velocity burst boundaries -------------------------------------------

    @ParameterizedTest(name = "burst {0}, subnet {1}, distinct ratio {2} -> velocity? {3}")
    @CsvSource({
            "3, 8, 0.75, true",
            "2, 8, 0.75, false",
            "3, 9, 0.75, false",
            "3, 8, 0.74, false",
    })
    void velocityBurstLimitsAreInclusive(int burst, int subnetSize, double distinctRatio, boolean expected) {
        Frequencies f = frequencies();
        repeat(subnetSize, () -> f.incrementIpPrefix("194.181.67.0/24"));
        Features left = features().ip("194.181.67.10", "194.181.67.0/24").createdAt(T0).build();
        Features right = features().ip("194.181.67.20", "194.181.67.0/24").createdAt(T0.plusSeconds(600)).build();

        Assessment assessment = Scorer.assess(left, right, new ScoringContext(f, false, burst, distinctRatio), CONFIG);

        assertThat(assessment.rule().equals(Scorer.RULE_VELOCITY)).isEqualTo(expected);
        assertThat(assessment.evidence().velocityBurst()).isEqualTo(expected);
    }

    // ---- frequencies seen by a streaming (not yet stored) account -----------

    @Test
    void projectedAccountIsCountedInEverySharedSignal() {
        Frequencies f = frequencies();
        f.incrementEmail("a@x.com");
        f.incrementDevice("dev");
        f.incrementPayment("card");
        f.incrementIp("10.0.0.1");
        f.incrementIpPrefix("10.0.0.0/24");
        Features side = features().email("a@x.com", "a@x.com", "a", "a", "x.com").device("dev").payment("card")
                .ip("10.0.0.1", "10.0.0.0/24").build();

        Evidence evidence = Scorer.assess(side, side, new ScoringContext(f, true, 0, 0), CONFIG).evidence();

        assertThat(evidence.emailFrequency()).isEqualTo(2);
        assertThat(evidence.deviceFrequency()).isEqualTo(2);
        assertThat(evidence.paymentFrequency()).isEqualTo(2);
        assertThat(evidence.ipFrequency()).isEqualTo(2);
        assertThat(evidence.prefixFrequency()).isEqualTo(2);
    }

    @Test
    void differentEmailsReportTheMoreCommonOneIncludingTheIncomingAccount() {
        Frequencies f = frequencies();
        repeat(3, () -> f.incrementEmail("left@x.com"));
        repeat(4, () -> f.incrementEmail("right@x.com"));
        Features left = email("left@x.com", "left@x.com", "left", "x.com");
        Features right = email("right@x.com", "right@x.com", "right", "x.com");

        assertThat(Scorer.assess(left, right, new ScoringContext(f, false, 0, 0), CONFIG).evidence().emailFrequency())
                .isEqualTo(4);
        assertThat(Scorer.assess(left, right, new ScoringContext(f, true, 0, 0), CONFIG).evidence().emailFrequency())
                .as("the incoming right-hand account adds one").isEqualTo(5);
        assertThat(Scorer.assess(right, left, new ScoringContext(f, true, 0, 0), CONFIG).evidence().emailFrequency())
                .as("left 4, right 3 + 1").isEqualTo(4);
    }

    @Test
    void missingSignalsHaveZeroFrequencyAndNoEvidence() {
        Features empty = features().build();

        Evidence evidence = assess(empty, empty, frequencies()).evidence();

        assertThat(evidence.exactEmail()).isFalse();
        assertThat(evidence.sameDevice()).isFalse();
        assertThat(evidence.samePayment()).isFalse();
        assertThat(evidence.sameIp()).isFalse();
        assertThat(evidence.emailFrequency()).isZero();
        assertThat(evidence.timeDelta()).isEqualTo(Duration.ZERO);
    }

    @Test
    void clampKeepsExactlyOneAndZero() {
        assertThat(Scorer.clamp(1.0)).isEqualTo(1.0);
        assertThat(Scorer.clamp(0.0)).isEqualTo(0.0);
        assertThat(Scorer.clamp(1.0000001)).isEqualTo(1.0);
    }

    @Test
    void frequencyOfOneKeepsTheFullBaseWeight() {
        assertThat(Scorer.adjusted(0.5, 1, 0.1)).isEqualTo(0.5);
        assertThat(Scorer.adjusted(0.5, 2, 0.1)).isLessThan(0.5);
    }
}
