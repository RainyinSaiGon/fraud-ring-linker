package io.github.rainyinsaigon.fraudlinker.scoring;

import io.github.rainyinsaigon.fraudlinker.domain.Assessment;
import io.github.rainyinsaigon.fraudlinker.domain.Features;
import io.github.rainyinsaigon.fraudlinker.domain.Frequencies;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static io.github.rainyinsaigon.fraudlinker.Fixtures.features;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ScorerTest {
    private static final Instant START = Instant.parse("2026-04-29T22:50:00Z");
    private static final String PREFIX = "194.181.67.0/24";

    private static ScoringConfig withMinMergeScore(double minMergeScore) {
        ScoringConfig d = ScoringConfig.defaults();
        return new ScoringConfig(minMergeScore, d.strongEmailFrequencyLimit(), d.strongPaymentFrequencyLimit(),
                d.deviceFrequencyLimit(), d.supportingIpFrequencyLimit(), d.tightTimeWindow(), d.burstWindow(),
                d.burstMinSize(), d.burstMaxPrefix(), d.burstMinDistinctIpRatio(), d.seededPrefixMinSize(),
                d.seededPrefixMaxSize(), d.seededPrefixMinDistinctIpRatio(), d.seededPrefixScore());
    }

    private static ScoringContext context(Frequencies frequencies) {
        return new ScoringContext(frequencies, false, 0, 0);
    }

    private static void times(Runnable increment, int count) {
        for (int i = 0; i < count; i++) {
            increment.run();
        }
    }

    private static void assertEligibleRule(Assessment assessment, String rule) {
        assertThat(assessment.eligible())
                .as("eligible; rule=%s score=%.4f evidence=%s", assessment.rule(), assessment.score(), assessment.evidence())
                .isTrue();
        assertThat(assessment.rule()).isEqualTo(rule);
    }

    @Test
    void ipOnlyEvidenceIsNeverEligibleEvenWithNoScoreFloor() {
        Frequencies frequencies = new Frequencies();
        times(() -> frequencies.incrementIp("203.0.113.7"), 2);
        times(() -> frequencies.incrementIpPrefix("203.0.113.0/24"), 2);
        Features side = features().ip("203.0.113.7", "203.0.113.0/24").build();

        Assessment assessment = Scorer.assess(side, side, context(frequencies), withMinMergeScore(0));

        assertThat(assessment.evidence().sameIp()).isTrue();
        assertThat(assessment.eligible()).isFalse();
    }

    @Test
    void deviceOnlyEvidenceIsNeverEligibleEvenWithNoScoreFloor() {
        Frequencies frequencies = new Frequencies();
        times(() -> frequencies.incrementDevice("dev_shared"), 2);
        Features side = features().device("dev_shared").build();

        Assessment assessment = Scorer.assess(side, side, context(frequencies), withMinMergeScore(0));

        assertThat(assessment.evidence().sameDevice()).isTrue();
        assertThat(assessment.eligible()).isFalse();
    }

    @Test
    void rareCanonicalEmailIsEligible() {
        String canonical = "johndoe@gmail.com";
        Frequencies frequencies = new Frequencies();
        times(() -> frequencies.incrementEmail(canonical), 2);

        Assessment assessment = Scorer.assess(
                features().email("john.doe+promo@gmail.com", canonical, "johndoe", "", "gmail.com").build(),
                features().email(canonical, canonical, "johndoe", "", "gmail.com").build(),
                context(frequencies), ScoringConfig.defaults());

        assertThat(assessment.evidence().exactEmail()).isFalse();
        assertThat(assessment.evidence().canonicalEmail()).isTrue();
        assertEligibleRule(assessment, Scorer.RULE_STRONG_EMAIL);
        assertThat(assessment.identitySeed()).isTrue();
    }

    @Test
    void rarePaymentIsEligible() {
        Frequencies frequencies = new Frequencies();
        times(() -> frequencies.incrementPayment("pf_shared"), 2);
        Features side = features().payment("pf_shared").build();

        Assessment assessment = Scorer.assess(side, side, context(frequencies), ScoringConfig.defaults());

        assertThat(assessment.evidence().samePayment()).isTrue();
        assertEligibleRule(assessment, Scorer.RULE_STRONG_PAYMENT);
    }

    @Test
    void paymentSharedByTooManyAccountsIsNotStrongOnItsOwn() {
        Frequencies frequencies = new Frequencies();
        times(() -> frequencies.incrementPayment("pf_hot"), 9);
        Features side = features().payment("pf_hot").build();

        Assessment assessment = Scorer.assess(side, side, context(frequencies), ScoringConfig.defaults());

        assertThat(assessment.eligible()).isFalse();
    }

    @Test
    void fuzzyEmailWithDeviceCorroborationIsEligible() {
        Frequencies frequencies = new Frequencies();
        frequencies.incrementEmail("johndoe@gmail.com");
        frequencies.incrementEmail("johndo@gmail.com");
        times(() -> frequencies.incrementDevice("dev_shared"), 2);

        Assessment assessment = Scorer.assess(
                features().email("johndoe@gmail.com", "johndoe@gmail.com", "johndoe", "johndoe", "gmail.com")
                        .device("dev_shared").build(),
                features().email("johndo@gmail.com", "johndo@gmail.com", "johndo", "johndo", "gmail.com")
                        .device("dev_shared").build(),
                context(frequencies), ScoringConfig.defaults());

        assertThat(assessment.evidence().fuzzyEmail()).isTrue();
        assertThat(assessment.evidence().sameDevice()).isTrue();
        assertEligibleRule(assessment, Scorer.RULE_DEVICE_EMAIL);
    }

    @Test
    void diversifiedPrefixBurstIsEligible() {
        Frequencies frequencies = new Frequencies();
        times(() -> frequencies.incrementIpPrefix(PREFIX), 5);

        Assessment assessment = Scorer.assess(
                features().ip("194.181.67.10", PREFIX).createdAt(START).build(),
                features().ip("194.181.67.99", PREFIX).createdAt(START.plus(Duration.ofMinutes(34))).build(),
                new ScoringContext(frequencies, false, 5, 1), ScoringConfig.defaults());

        assertThat(assessment.evidence().sameIpPrefix()).isTrue();
        assertThat(assessment.evidence().velocityBurst()).isTrue();
        assertEligibleRule(assessment, Scorer.RULE_VELOCITY);
    }

    @Test
    void burstFromOneSharedIpIsNotDiverseEnough() {
        Frequencies frequencies = new Frequencies();
        times(() -> frequencies.incrementIpPrefix(PREFIX), 5);

        Assessment assessment = Scorer.assess(
                features().ip("194.181.67.10", PREFIX).createdAt(START).build(),
                features().ip("194.181.67.99", PREFIX).createdAt(START.plusSeconds(60)).build(),
                new ScoringContext(frequencies, false, 5, 0.5), ScoringConfig.defaults());

        assertThat(assessment.eligible()).isFalse();
    }

    @Test
    void identitySeedIsKeptWhenVelocityRuleFiresFirst() {
        Frequencies frequencies = new Frequencies();
        times(() -> frequencies.incrementPayment("shared-payment"), 2);
        times(() -> frequencies.incrementIpPrefix(PREFIX), 3);

        Assessment assessment = Scorer.assess(
                features().payment("shared-payment").ip("194.181.67.10", PREFIX).createdAt(START).build(),
                features().payment("shared-payment").ip("194.181.67.20", PREFIX)
                        .createdAt(START.plus(Duration.ofMinutes(10))).build(),
                new ScoringContext(frequencies, false, 3, 1), ScoringConfig.defaults());

        assertEligibleRule(assessment, Scorer.RULE_VELOCITY);
        assertThat(assessment.identitySeed()).isTrue();
    }

    @Test
    void projectedIncomingCountsTowardsFrequency() {
        Frequencies frequencies = new Frequencies();
        frequencies.incrementPayment("pf");
        Features side = features().payment("pf").build();

        Assessment assessment = Scorer.assess(side, side, new ScoringContext(frequencies, true, 0, 0),
                ScoringConfig.defaults());

        assertThat(assessment.evidence().paymentFrequency()).isEqualTo(2);
    }

    @ParameterizedTest(name = "frequency {1} -> {3}")
    @CsvSource({
            "0.94, 0, 0.78, 0.94",
            "0.94, 1, 0.78, 0.94",
            "0.94, 4, 0.78, 0.8366",
            "0.12, 100, 0.45, 0.0606",
    })
    void rarityAdjustmentShrinksWeightForCommonValues(double base, int frequency, double floor, double expected) {
        assertThat(Scorer.adjusted(base, frequency, floor)).isCloseTo(expected, within(1e-4));
    }

    @Test
    void noisyOrCombinesIndependentSignals() {
        assertThat(Scorer.noisyOr(List.of())).isZero();
        assertThat(Scorer.noisyOr(List.of(0.5, 0.5))).isEqualTo(0.75);
        assertThat(Scorer.noisyOr(List.of(2.0))).isEqualTo(1.0);
    }

    @ParameterizedTest
    @CsvSource({"0.91234, 0.9123", "0.91236, 0.9124", "1.5, 1.0", "-0.2, 0.0", "NaN, 0.0"})
    void roundsConfidenceToFourDecimalsInRange(double input, double expected) {
        assertThat(Scorer.roundConfidence(input)).isEqualTo(expected);
    }
}
