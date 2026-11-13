package scoring

import (
	"math"
	"strings"
	"time"

	"accountlinker/internal/domain"
	"accountlinker/internal/normalize"
)

// Config is the single, documented operating point for both batch and stream.
type Config struct {
	MinMergeScore float64

	StrongEmailFrequencyLimit   int
	StrongPaymentFrequencyLimit int
	DeviceFrequencyLimit        int
	SupportingIPFrequencyLimit  int

	TightTimeWindow         time.Duration
	BurstWindow             time.Duration
	BurstMinSize            int
	BurstMaxPrefix          int
	BurstMinDistinctIPRatio float64

	SeededPrefixMinSize            int
	SeededPrefixMaxSize            int
	SeededPrefixMinDistinctIPRatio float64
	SeededPrefixScore              float64
}

func DefaultConfig() Config {
	return Config{
		MinMergeScore:                  0.70,
		StrongEmailFrequencyLimit:      24,
		StrongPaymentFrequencyLimit:    8,
		DeviceFrequencyLimit:           16,
		SupportingIPFrequencyLimit:     8,
		TightTimeWindow:                24 * time.Hour,
		BurstWindow:                    time.Hour,
		BurstMinSize:                   3,
		BurstMaxPrefix:                 8,
		BurstMinDistinctIPRatio:        0.75,
		SeededPrefixMinSize:            3,
		SeededPrefixMaxSize:            8,
		SeededPrefixMinDistinctIPRatio: 0.75,
		SeededPrefixScore:              0.82,
	}
}

// Context supplies global and cohort state without coupling the scorer to an
// index implementation.
type Context struct {
	Frequencies       domain.Frequencies
	ProjectedIncoming bool
	BurstSize         int
	DistinctIPRatio   float64
}

// Assess combines evidence families without double-counting variants from the
// same email family. Eligibility remains composition-aware: a score alone can
// never make IP, time, or device-only evidence mergeable.
func Assess(left, right domain.Features, context Context, config Config) domain.Assessment {
	evidence := evidenceFor(left, right, context)

	emailWeight := 0.0
	switch {
	case evidence.ExactEmail:
		emailWeight = adjusted(0.98, evidence.EmailFrequency, 0.82)
	case evidence.CanonicalEmail:
		emailWeight = adjusted(0.95, evidence.EmailFrequency, 0.80)
	case evidence.FuzzyEmail:
		emailWeight = adjusted(0.62, evidence.EmailFrequency, 0.72)
	case evidence.SameEmailLocal:
		emailWeight = adjusted(0.60, evidence.EmailFrequency, 0.72)
	case evidence.SameEmailSkeleton:
		emailWeight = adjusted(0.56, evidence.EmailFrequency, 0.70)
	}

	weights := make([]float64, 0, 5)
	if emailWeight > 0 {
		weights = append(weights, emailWeight)
	}
	if evidence.SamePayment {
		weights = append(weights, adjusted(0.94, evidence.PaymentFrequency, 0.78))
	}
	if evidence.SameDevice {
		weights = append(weights, adjusted(0.76, evidence.DeviceFrequency, 0.72))
	}
	if evidence.SameIP {
		weights = append(weights, adjusted(0.12, evidence.IPFrequency, 0.45))
	} else if evidence.SameIPPrefix {
		weights = append(weights, adjusted(0.035, evidence.PrefixFrequency, 0.40))
	}
	if !left.CreatedAt.IsZero() && !right.CreatedAt.IsZero() {
		switch {
		case evidence.TimeDelta <= time.Hour:
			weights = append(weights, 0.08)
		case evidence.TimeDelta <= 24*time.Hour:
			weights = append(weights, 0.05)
		case evidence.TimeDelta <= 7*24*time.Hour:
			weights = append(weights, 0.02)
		}
	}

	score := noisyOR(weights...)
	rule := ""
	eligible := false

	strongEmail := (evidence.ExactEmail || evidence.CanonicalEmail) &&
		evidence.EmailFrequency <= config.StrongEmailFrequencyLimit
	strongPayment := evidence.SamePayment &&
		evidence.PaymentFrequency <= config.StrongPaymentFrequencyLimit
	emailCorroboration := evidence.ExactEmail || evidence.CanonicalEmail ||
		evidence.FuzzyEmail || evidence.SameEmailLocal || evidence.SameEmailSkeleton
	rareDevice := evidence.SameDevice &&
		evidence.DeviceFrequency <= config.DeviceFrequencyLimit
	rareIPWithTime := evidence.SameIP &&
		evidence.IPFrequency <= config.SupportingIPFrequencyLimit &&
		evidence.TimeDelta <= config.TightTimeWindow
	identitySeed := strongEmail ||
		strongPayment ||
		(rareDevice && evidence.SamePayment) ||
		(rareDevice && emailCorroboration) ||
		((evidence.FuzzyEmail ||
			evidence.SameEmailLocal ||
			evidence.SameEmailSkeleton) &&
			evidence.SamePayment) ||
		((evidence.ExactEmail || evidence.CanonicalEmail) &&
			(evidence.SameDevice || evidence.SamePayment))

	switch {
	case context.BurstSize >= config.BurstMinSize &&
		evidence.PrefixFrequency <= config.BurstMaxPrefix &&
		context.DistinctIPRatio >= config.BurstMinDistinctIPRatio:
		evidence.VelocityBurst = true
		score = math.Max(score, adjusted(0.90, evidence.PrefixFrequency, 0.88))
		eligible = true
		rule = "diversified_prefix_velocity"
	case strongEmail:
		eligible = true
		rule = "rare_exact_or_canonical_email"
	case strongPayment:
		eligible = true
		rule = "rare_payment"
	case rareDevice && evidence.SamePayment:
		eligible = true
		rule = "device_and_payment"
	case rareDevice && emailCorroboration:
		eligible = true
		rule = "device_and_email"
	case rareDevice && rareIPWithTime:
		eligible = true
		rule = "device_network_and_time"
	case (evidence.FuzzyEmail || evidence.SameEmailLocal || evidence.SameEmailSkeleton) &&
		evidence.SamePayment:
		eligible = true
		rule = "email_variation_and_payment"
	case (evidence.ExactEmail || evidence.CanonicalEmail) &&
		(evidence.SameDevice || evidence.SamePayment):
		eligible = true
		rule = "common_email_with_corroboration"
	}

	score = clamp(score)
	if score < config.MinMergeScore {
		eligible = false
		identitySeed = false
		rule = ""
	}
	return domain.Assessment{
		Evidence:     evidence,
		Score:        score,
		Eligible:     eligible,
		IdentitySeed: identitySeed,
		Rule:         rule,
	}
}

// SeededPrefixAssessment represents a bounded group-level rule: a small,
// diversified network cohort with an already-established strong identity
// seed. It is intentionally unavailable for arbitrary prefix pairs.
func SeededPrefixAssessment(prefixFrequency, seedSize int, config Config) domain.Assessment {
	return domain.Assessment{
		Evidence: domain.Evidence{
			SameIPPrefix:    true,
			SeededPrefix:    true,
			PrefixFrequency: prefixFrequency,
			PrefixSeedSize:  seedSize,
		},
		Score:    clamp(config.SeededPrefixScore),
		Eligible: true,
		Rule:     "strong_seeded_prefix_cohort",
	}
}

func evidenceFor(left, right domain.Features, context Context) domain.Evidence {
	evidence := domain.Evidence{}

	evidence.ExactEmail = left.EmailExact != "" && left.EmailExact == right.EmailExact
	evidence.CanonicalEmail = left.EmailCanonical != "" &&
		left.EmailCanonical == right.EmailCanonical
	evidence.FuzzyEmail = !evidence.CanonicalEmail &&
		left.EmailDomain != "" && left.EmailDomain == right.EmailDomain &&
		normalize.WithinOneEdit(left.EmailBaseLocal, right.EmailBaseLocal)
	evidence.SameEmailLocal = !evidence.CanonicalEmail &&
		left.EmailBaseLocal != "" && left.EmailBaseLocal == right.EmailBaseLocal
	evidence.SameEmailSkeleton = !evidence.CanonicalEmail &&
		!evidence.SameEmailLocal &&
		len([]rune(left.EmailSkeleton)) >= 5 &&
		normalize.WithinOneEdit(left.EmailSkeleton, right.EmailSkeleton)

	evidence.SameDevice = left.DeviceID != "" && left.DeviceID == right.DeviceID
	evidence.SamePayment = left.Payment != "" && left.Payment == right.Payment
	evidence.SameIP = left.IP.IsValid() && left.IP == right.IP
	evidence.SameIPPrefix = left.IPPrefix.IsValid() && left.IPPrefix == right.IPPrefix
	evidence.TimeDelta = absoluteDuration(left.CreatedAt.Sub(right.CreatedAt))

	evidence.EmailFrequency = emailFrequency(left, right, context)
	evidence.DeviceFrequency = matchingFrequency(
		left.DeviceID, right.DeviceID, context.Frequencies.Device, context.ProjectedIncoming,
	)
	evidence.PaymentFrequency = matchingFrequency(
		left.Payment, right.Payment, context.Frequencies.Payment, context.ProjectedIncoming,
	)
	if evidence.SameIP {
		evidence.IPFrequency = context.Frequencies.IP[left.IP]
		if context.ProjectedIncoming {
			evidence.IPFrequency++
		}
	}
	if evidence.SameIPPrefix {
		evidence.PrefixFrequency = context.Frequencies.IPPrefix[left.IPPrefix]
		if context.ProjectedIncoming {
			evidence.PrefixFrequency++
		}
	}
	evidence.BurstSize = context.BurstSize
	return evidence
}

func emailFrequency(left, right domain.Features, context Context) int {
	if left.EmailCanonical == "" || right.EmailCanonical == "" {
		return 0
	}
	if left.EmailCanonical == right.EmailCanonical {
		count := context.Frequencies.Email[left.EmailCanonical]
		if context.ProjectedIncoming {
			count++
		}
		return count
	}
	leftCount := context.Frequencies.Email[left.EmailCanonical]
	rightCount := context.Frequencies.Email[right.EmailCanonical]
	if context.ProjectedIncoming {
		rightCount++
	}
	return max(leftCount, rightCount)
}

func matchingFrequency(left, right string, frequencies map[string]int, projected bool) int {
	if left == "" || left != right {
		return 0
	}
	count := frequencies[left]
	if projected {
		count++
	}
	return count
}

func adjusted(base float64, frequency int, floorScale float64) float64 {
	if frequency <= 1 {
		return base
	}
	rarity := 1 / math.Sqrt(float64(frequency))
	scale := floorScale + (1-floorScale)*rarity
	return base * scale
}

func noisyOR(weights ...float64) float64 {
	remaining := 1.0
	for _, weight := range weights {
		remaining *= 1 - clamp(weight)
	}
	return 1 - remaining
}

func absoluteDuration(value time.Duration) time.Duration {
	if value < 0 {
		return -value
	}
	return value
}

func clamp(value float64) float64 {
	if math.IsNaN(value) || value < 0 {
		return 0
	}
	if value > 1 {
		return 1
	}
	return value
}

// RoundConfidence keeps JSON stable and readable without changing decisions.
func RoundConfidence(value float64) float64 {
	return math.Round(clamp(value)*10000) / 10000
}

// RulesSummary is used by documentation-facing tests to keep rule names
// stable and human-readable.
func RulesSummary(assessment domain.Assessment) string {
	return strings.ReplaceAll(assessment.Rule, "_", " ")
}
