package domain

import "time"

// Evidence records the independent facts behind a pair score. Keeping the
// evidence composition explicit prevents a numeric score from hiding unsafe
// single-signal rules.
type Evidence struct {
	ExactEmail        bool
	CanonicalEmail    bool
	FuzzyEmail        bool
	SameEmailLocal    bool
	SameEmailSkeleton bool
	SameDevice        bool
	SamePayment       bool
	SameIP            bool
	SameIPPrefix      bool
	VelocityBurst     bool
	SeededPrefix      bool

	EmailFrequency   int
	DeviceFrequency  int
	PaymentFrequency int
	IPFrequency      int
	PrefixFrequency  int
	BurstSize        int
	PrefixSeedSize   int

	TimeDelta time.Duration
}

// Assessment is the result of scoring a candidate account pair.
type Assessment struct {
	Evidence     Evidence
	Score        float64
	Eligible     bool
	IdentitySeed bool
	Rule         string
}
