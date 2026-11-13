package scoring_test

import (
	"net/netip"
	"testing"
	"time"

	"accountlinker/internal/domain"
	"accountlinker/internal/scoring"
)

func TestAssessRejectsIPOnlyEvidence(t *testing.T) {
	t.Parallel()

	ip := netip.MustParseAddr("203.0.113.7")
	prefix := netip.MustParsePrefix("203.0.113.0/24")
	context := emptyContext()
	context.Frequencies.IP[ip] = 2
	context.Frequencies.IPPrefix[prefix] = 2

	config := scoring.DefaultConfig()
	config.MinMergeScore = 0
	assessment := scoring.Assess(
		domain.Features{IP: ip, IPPrefix: prefix},
		domain.Features{IP: ip, IPPrefix: prefix},
		context,
		config,
	)

	if !assessment.Evidence.SameIP {
		t.Fatal("SameIP evidence = false, want true")
	}
	if assessment.Eligible {
		t.Fatalf("IP-only assessment is eligible with rule %q and score %.4f", assessment.Rule, assessment.Score)
	}
}

func TestAssessRejectsDeviceOnlyEvidence(t *testing.T) {
	t.Parallel()

	context := emptyContext()
	context.Frequencies.Device["dev_shared"] = 2

	config := scoring.DefaultConfig()
	config.MinMergeScore = 0
	assessment := scoring.Assess(
		domain.Features{DeviceID: "dev_shared"},
		domain.Features{DeviceID: "dev_shared"},
		context,
		config,
	)

	if !assessment.Evidence.SameDevice {
		t.Fatal("SameDevice evidence = false, want true")
	}
	if assessment.Eligible {
		t.Fatalf("device-only assessment is eligible with rule %q and score %.4f", assessment.Rule, assessment.Score)
	}
}

func TestAssessAllowsRareCanonicalEmail(t *testing.T) {
	t.Parallel()

	const canonical = "johndoe@gmail.com"
	context := emptyContext()
	context.Frequencies.Email[canonical] = 2

	assessment := scoring.Assess(
		domain.Features{
			EmailExact:     "john.doe+promo@gmail.com",
			EmailCanonical: canonical,
			EmailBaseLocal: "johndoe",
			EmailDomain:    "gmail.com",
		},
		domain.Features{
			EmailExact:     canonical,
			EmailCanonical: canonical,
			EmailBaseLocal: "johndoe",
			EmailDomain:    "gmail.com",
		},
		context,
		scoring.DefaultConfig(),
	)

	if assessment.Evidence.ExactEmail {
		t.Fatal("ExactEmail evidence = true, want a canonical-only match")
	}
	if !assessment.Evidence.CanonicalEmail {
		t.Fatal("CanonicalEmail evidence = false, want true")
	}
	assertEligibleRule(t, assessment, "rare_exact_or_canonical_email")
}

func TestAssessAllowsRarePayment(t *testing.T) {
	t.Parallel()

	context := emptyContext()
	context.Frequencies.Payment["pf_shared"] = 2

	assessment := scoring.Assess(
		domain.Features{Payment: "pf_shared"},
		domain.Features{Payment: "pf_shared"},
		context,
		scoring.DefaultConfig(),
	)

	if !assessment.Evidence.SamePayment {
		t.Fatal("SamePayment evidence = false, want true")
	}
	assertEligibleRule(t, assessment, "rare_payment")
}

func TestAssessAllowsFuzzyEmailWithDeviceCorroboration(t *testing.T) {
	t.Parallel()

	context := emptyContext()
	context.Frequencies.Email["johndoe@gmail.com"] = 1
	context.Frequencies.Email["johndo@gmail.com"] = 1
	context.Frequencies.Device["dev_shared"] = 2

	assessment := scoring.Assess(
		domain.Features{
			EmailExact:     "johndoe@gmail.com",
			EmailCanonical: "johndoe@gmail.com",
			EmailBaseLocal: "johndoe",
			EmailSkeleton:  "johndoe",
			EmailDomain:    "gmail.com",
			DeviceID:       "dev_shared",
		},
		domain.Features{
			EmailExact:     "johndo@gmail.com",
			EmailCanonical: "johndo@gmail.com",
			EmailBaseLocal: "johndo",
			EmailSkeleton:  "johndo",
			EmailDomain:    "gmail.com",
			DeviceID:       "dev_shared",
		},
		context,
		scoring.DefaultConfig(),
	)

	if !assessment.Evidence.FuzzyEmail {
		t.Fatal("FuzzyEmail evidence = false, want true")
	}
	if !assessment.Evidence.SameDevice {
		t.Fatal("SameDevice evidence = false, want true")
	}
	assertEligibleRule(t, assessment, "device_and_email")
}

func TestAssessAllowsDiversifiedPrefixBurst(t *testing.T) {
	t.Parallel()

	prefix := netip.MustParsePrefix("194.181.67.0/24")
	start := time.Date(2026, 4, 29, 22, 50, 0, 0, time.UTC)
	context := emptyContext()
	context.Frequencies.IPPrefix[prefix] = 5
	context.BurstSize = 5
	context.DistinctIPRatio = 1

	assessment := scoring.Assess(
		domain.Features{
			IP:        netip.MustParseAddr("194.181.67.10"),
			IPPrefix:  prefix,
			CreatedAt: start,
		},
		domain.Features{
			IP:        netip.MustParseAddr("194.181.67.99"),
			IPPrefix:  prefix,
			CreatedAt: start.Add(34 * time.Minute),
		},
		context,
		scoring.DefaultConfig(),
	)

	if !assessment.Evidence.SameIPPrefix {
		t.Fatal("SameIPPrefix evidence = false, want true")
	}
	if !assessment.Evidence.VelocityBurst {
		t.Fatal("VelocityBurst evidence = false, want true")
	}
	assertEligibleRule(t, assessment, "diversified_prefix_velocity")
}

func TestAssessRetainsIdentitySeedBehindVelocityRule(t *testing.T) {
	t.Parallel()

	prefix := netip.MustParsePrefix("194.181.67.0/24")
	start := time.Date(2026, 4, 29, 22, 50, 0, 0, time.UTC)
	context := emptyContext()
	context.Frequencies.Payment["shared-payment"] = 2
	context.Frequencies.IPPrefix[prefix] = 3
	context.BurstSize = 3
	context.DistinctIPRatio = 1

	assessment := scoring.Assess(
		domain.Features{
			Payment:   "shared-payment",
			IP:        netip.MustParseAddr("194.181.67.10"),
			IPPrefix:  prefix,
			CreatedAt: start,
		},
		domain.Features{
			Payment:   "shared-payment",
			IP:        netip.MustParseAddr("194.181.67.20"),
			IPPrefix:  prefix,
			CreatedAt: start.Add(10 * time.Minute),
		},
		context,
		scoring.DefaultConfig(),
	)

	assertEligibleRule(t, assessment, "diversified_prefix_velocity")
	if !assessment.IdentitySeed {
		t.Fatal("strong payment evidence was not retained as an identity seed")
	}
}

func emptyContext() scoring.Context {
	return scoring.Context{
		Frequencies: domain.Frequencies{
			Email:    make(map[string]int),
			Device:   make(map[string]int),
			Payment:  make(map[string]int),
			IP:       make(map[netip.Addr]int),
			IPPrefix: make(map[netip.Prefix]int),
		},
	}
}

func assertEligibleRule(t *testing.T, assessment domain.Assessment, wantRule string) {
	t.Helper()
	if !assessment.Eligible {
		t.Fatalf("assessment is ineligible: rule=%q score=%.4f evidence=%+v", assessment.Rule, assessment.Score, assessment.Evidence)
	}
	if assessment.Rule != wantRule {
		t.Fatalf("assessment rule = %q, want %q", assessment.Rule, wantRule)
	}
}
