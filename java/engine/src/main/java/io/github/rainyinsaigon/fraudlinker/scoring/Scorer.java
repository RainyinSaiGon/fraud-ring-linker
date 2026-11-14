package io.github.rainyinsaigon.fraudlinker.scoring;

import io.github.rainyinsaigon.fraudlinker.domain.Assessment;
import io.github.rainyinsaigon.fraudlinker.domain.Evidence;
import io.github.rainyinsaigon.fraudlinker.domain.Features;
import io.github.rainyinsaigon.fraudlinker.normalize.EditDistance;
import io.github.rainyinsaigon.fraudlinker.normalize.Text;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Scores a candidate account pair.
 *
 * <p>Each signal family contributes one weight, reduced when the value is
 * shared by many accounts (rarity). Weights combine with a noisy-OR. A score
 * alone never makes a pair mergeable: a named rule must also fire, so IP, time
 * or device evidence on its own can never link two accounts.
 */
public final class Scorer {
    public static final String RULE_VELOCITY = "diversified_prefix_velocity";
    public static final String RULE_STRONG_EMAIL = "rare_exact_or_canonical_email";
    public static final String RULE_STRONG_PAYMENT = "rare_payment";
    public static final String RULE_DEVICE_PAYMENT = "device_and_payment";
    public static final String RULE_DEVICE_EMAIL = "device_and_email";
    public static final String RULE_DEVICE_NETWORK_TIME = "device_network_and_time";
    public static final String RULE_EMAIL_VARIATION_PAYMENT = "email_variation_and_payment";
    public static final String RULE_COMMON_EMAIL_CORROBORATED = "common_email_with_corroboration";
    public static final String RULE_SEEDED_PREFIX = "strong_seeded_prefix_cohort";

    private Scorer() {
    }

    public static Assessment assess(Features left, Features right, ScoringContext context, ScoringConfig config) {
        Evidence evidence = evidenceFor(left, right, context);

        double emailWeight = 0;
        if (evidence.exactEmail()) {
            emailWeight = adjusted(0.98, evidence.emailFrequency(), 0.82);
        } else if (evidence.canonicalEmail()) {
            emailWeight = adjusted(0.95, evidence.emailFrequency(), 0.80);
        } else if (evidence.fuzzyEmail()) {
            emailWeight = adjusted(0.62, evidence.emailFrequency(), 0.72);
        } else if (evidence.sameEmailLocal()) {
            emailWeight = adjusted(0.60, evidence.emailFrequency(), 0.72);
        } else if (evidence.sameEmailSkeleton()) {
            emailWeight = adjusted(0.56, evidence.emailFrequency(), 0.70);
        }

        List<Double> weights = new ArrayList<>(5);
        if (emailWeight > 0) {
            weights.add(emailWeight);
        }
        if (evidence.samePayment()) {
            weights.add(adjusted(0.94, evidence.paymentFrequency(), 0.78));
        }
        if (evidence.sameDevice()) {
            weights.add(adjusted(0.76, evidence.deviceFrequency(), 0.72));
        }
        if (evidence.sameIp()) {
            weights.add(adjusted(0.12, evidence.ipFrequency(), 0.45));
        } else if (evidence.sameIpPrefix()) {
            weights.add(adjusted(0.035, evidence.prefixFrequency(), 0.40));
        }
        if (left.createdAt() != null && right.createdAt() != null) {
            Duration delta = evidence.timeDelta();
            if (delta.compareTo(Duration.ofHours(1)) <= 0) {
                weights.add(0.08);
            } else if (delta.compareTo(Duration.ofHours(24)) <= 0) {
                weights.add(0.05);
            } else if (delta.compareTo(Duration.ofDays(7)) <= 0) {
                weights.add(0.02);
            }
        }

        double score = noisyOr(weights);
        String rule = "";
        boolean eligible = false;

        boolean exactOrCanonical = evidence.exactEmail() || evidence.canonicalEmail();
        boolean emailVariation = evidence.fuzzyEmail() || evidence.sameEmailLocal() || evidence.sameEmailSkeleton();
        boolean strongEmail = exactOrCanonical && evidence.emailFrequency() <= config.strongEmailFrequencyLimit();
        boolean strongPayment = evidence.samePayment()
                && evidence.paymentFrequency() <= config.strongPaymentFrequencyLimit();
        boolean emailCorroboration = exactOrCanonical || emailVariation;
        boolean rareDevice = evidence.sameDevice() && evidence.deviceFrequency() <= config.deviceFrequencyLimit();
        boolean rareIpWithTime = evidence.sameIp()
                && evidence.ipFrequency() <= config.supportingIpFrequencyLimit()
                && evidence.timeDelta().compareTo(config.tightTimeWindow()) <= 0;
        boolean identitySeed = strongEmail
                || strongPayment
                || (rareDevice && evidence.samePayment())
                || (rareDevice && emailCorroboration)
                || (emailVariation && evidence.samePayment())
                || (exactOrCanonical && (evidence.sameDevice() || evidence.samePayment()));

        if (context.burstSize() >= config.burstMinSize()
                && evidence.prefixFrequency() <= config.burstMaxPrefix()
                && context.distinctIpRatio() >= config.burstMinDistinctIpRatio()) {
            evidence = evidence.withVelocityBurst(true);
            score = Math.max(score, adjusted(0.90, evidence.prefixFrequency(), 0.88));
            eligible = true;
            rule = RULE_VELOCITY;
        } else if (strongEmail) {
            eligible = true;
            rule = RULE_STRONG_EMAIL;
        } else if (strongPayment) {
            eligible = true;
            rule = RULE_STRONG_PAYMENT;
        } else if (rareDevice && evidence.samePayment()) {
            eligible = true;
            rule = RULE_DEVICE_PAYMENT;
        } else if (rareDevice && emailCorroboration) {
            eligible = true;
            rule = RULE_DEVICE_EMAIL;
        } else if (rareDevice && rareIpWithTime) {
            eligible = true;
            rule = RULE_DEVICE_NETWORK_TIME;
        } else if (emailVariation && evidence.samePayment()) {
            eligible = true;
            rule = RULE_EMAIL_VARIATION_PAYMENT;
        } else if (exactOrCanonical && (evidence.sameDevice() || evidence.samePayment())) {
            eligible = true;
            rule = RULE_COMMON_EMAIL_CORROBORATED;
        }

        score = clamp(score);
        if (score < config.minMergeScore()) {
            eligible = false;
            identitySeed = false;
            rule = "";
        }
        return new Assessment(evidence, score, eligible, identitySeed, rule);
    }

    /**
     * A bounded group-level rule: a small network cohort with diverse IPs that
     * already contains an established identity. It is never available for an
     * arbitrary prefix pair.
     */
    public static Assessment seededPrefixAssessment(int prefixFrequency, int seedSize, ScoringConfig config) {
        Evidence evidence = new Evidence(false, false, false, false, false, false, false, false,
                true, false, true, 0, 0, 0, 0, prefixFrequency, 0, seedSize, Duration.ZERO);
        return new Assessment(evidence, clamp(config.seededPrefixScore()), true, false, RULE_SEEDED_PREFIX);
    }

    static Evidence evidenceFor(Features left, Features right, ScoringContext context) {
        boolean exactEmail = !left.emailExact().isEmpty() && left.emailExact().equals(right.emailExact());
        boolean canonicalEmail = !left.emailCanonical().isEmpty()
                && left.emailCanonical().equals(right.emailCanonical());
        boolean fuzzyEmail = !canonicalEmail
                && !left.emailDomain().isEmpty() && left.emailDomain().equals(right.emailDomain())
                && EditDistance.withinOneEdit(left.emailBaseLocal(), right.emailBaseLocal());
        boolean sameEmailLocal = !canonicalEmail
                && !left.emailBaseLocal().isEmpty() && left.emailBaseLocal().equals(right.emailBaseLocal());
        boolean sameEmailSkeleton = !canonicalEmail
                && !sameEmailLocal
                && Text.codePointLength(left.emailSkeleton()) >= 5
                && EditDistance.withinOneEdit(left.emailSkeleton(), right.emailSkeleton());

        boolean sameDevice = !left.deviceId().isEmpty() && left.deviceId().equals(right.deviceId());
        boolean samePayment = !left.payment().isEmpty() && left.payment().equals(right.payment());
        boolean sameIp = left.ip() != null && left.ip().equals(right.ip());
        boolean sameIpPrefix = left.ipPrefix() != null && left.ipPrefix().equals(right.ipPrefix());
        Duration timeDelta = left.createdAt() != null && right.createdAt() != null
                ? Duration.between(left.createdAt(), right.createdAt()).abs()
                : Duration.ZERO;

        int projected = context.projectedIncoming() ? 1 : 0;
        int deviceFrequency = sameDevice ? context.frequencies().device(left.deviceId()) + projected : 0;
        int paymentFrequency = samePayment ? context.frequencies().payment(left.payment()) + projected : 0;
        int ipFrequency = sameIp ? context.frequencies().ip(left.ip()) + projected : 0;
        int prefixFrequency = sameIpPrefix ? context.frequencies().ipPrefix(left.ipPrefix()) + projected : 0;

        return new Evidence(exactEmail, canonicalEmail, fuzzyEmail, sameEmailLocal, sameEmailSkeleton,
                sameDevice, samePayment, sameIp, sameIpPrefix, false, false,
                emailFrequency(left, right, context), deviceFrequency, paymentFrequency, ipFrequency,
                prefixFrequency, context.burstSize(), 0, timeDelta);
    }

    private static int emailFrequency(Features left, Features right, ScoringContext context) {
        if (left.emailCanonical().isEmpty() || right.emailCanonical().isEmpty()) {
            return 0;
        }
        int projected = context.projectedIncoming() ? 1 : 0;
        if (left.emailCanonical().equals(right.emailCanonical())) {
            return context.frequencies().email(left.emailCanonical()) + projected;
        }
        int leftCount = context.frequencies().email(left.emailCanonical());
        int rightCount = context.frequencies().email(right.emailCanonical()) + projected;
        return Math.max(leftCount, rightCount);
    }

    /**
     * Scales a base weight down as more accounts share the value. A value seen
     * once keeps its full weight; a very common one approaches {@code base * floorScale}.
     */
    static double adjusted(double base, int frequency, double floorScale) {
        if (frequency <= 1) {
            return base;
        }
        double rarity = 1 / Math.sqrt(frequency);
        double scale = floorScale + (1 - floorScale) * rarity;
        return base * scale;
    }

    /** Probability that at least one independent signal is right: 1 - product(1 - w). */
    static double noisyOr(List<Double> weights) {
        double remaining = 1.0;
        for (double weight : weights) {
            remaining *= 1 - clamp(weight);
        }
        return 1 - remaining;
    }

    static double clamp(double value) {
        if (Double.isNaN(value) || value < 0) {
            return 0;
        }
        return Math.min(value, 1);
    }

    /** Rounds to four decimals for stable, readable output without changing decisions. */
    public static double roundConfidence(double value) {
        return Math.round(clamp(value) * 10000) / 10000.0;
    }
}
