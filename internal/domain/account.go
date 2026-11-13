package domain

import (
	"net/netip"
	"time"
)

// Account is the JSONL input record described by the assignment.
type Account struct {
	AccountID          string    `json:"account_id"`
	Email              string    `json:"email"`
	DeviceID           *string   `json:"device_id"`
	IP                 string    `json:"ip"`
	PaymentFingerprint *string   `json:"payment_fingerprint"`
	CreatedAt          time.Time `json:"created_at"`
}

// Constraint is a hard cannot-link relationship.
type Constraint struct {
	AccountA string `json:"account_a"`
	AccountB string `json:"account_b"`
	Relation string `json:"relation"`
}

// Features contains the normalized representation used by every linking path.
type Features struct {
	EmailExact     string
	EmailCanonical string
	EmailLocal     string
	EmailBaseLocal string
	EmailSkeleton  string
	EmailDomain    string

	DeviceID string
	Payment  string

	IP       netip.Addr
	IPPrefix netip.Prefix

	CreatedAt time.Time
}

// Frequencies contains current global signal counts. Empty signals are never
// represented in these maps.
type Frequencies struct {
	Email    map[string]int
	Device   map[string]int
	Payment  map[string]int
	IP       map[netip.Addr]int
	IPPrefix map[netip.Prefix]int
}
