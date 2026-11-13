package index

import (
	"fmt"
	"net/netip"
	"sort"
	"time"

	"accountlinker/internal/domain"
	"accountlinker/internal/normalize"
)

// Config bounds every posting list that can generate candidate pairs. Values
// above a limit may still contribute to scoring when another block finds the
// pair, but cannot cause quadratic candidate expansion.
type Config struct {
	EmailBlockLimit     int
	DeviceBlockLimit    int
	PaymentBlockLimit   int
	IPBlockLimit        int
	PrefixBlockLimit    int
	SignatureBlockLimit int
	MaxEmailRunes       int
	MaxCandidates       int
}

func DefaultConfig() Config {
	return Config{
		EmailBlockLimit:     64,
		DeviceBlockLimit:    24,
		PaymentBlockLimit:   32,
		IPBlockLimit:        10,
		PrefixBlockLimit:    32,
		SignatureBlockLimit: 32,
		MaxEmailRunes:       64,
		MaxCandidates:       4096,
	}
}

// Pair is a normalized account-index pair.
type Pair struct {
	A int
	B int
}

// Store owns normalized historical state and all query-oriented indexes.
type Store struct {
	Accounts []domain.Account
	Features []domain.Features
	ByID     map[string]int

	Frequencies domain.Frequencies

	ByCanonicalEmail map[string][]int
	ByDevice         map[string][]int
	ByPayment        map[string][]int
	ByIP             map[netip.Addr][]int
	ByIPPrefix       map[netip.Prefix][]int
	ByEmailSignature map[string][]int

	config Config
}

func New(config Config) *Store {
	return &Store{
		ByID: make(map[string]int),
		Frequencies: domain.Frequencies{
			Email:    make(map[string]int),
			Device:   make(map[string]int),
			Payment:  make(map[string]int),
			IP:       make(map[netip.Addr]int),
			IPPrefix: make(map[netip.Prefix]int),
		},
		ByCanonicalEmail: make(map[string][]int),
		ByDevice:         make(map[string][]int),
		ByPayment:        make(map[string][]int),
		ByIP:             make(map[netip.Addr][]int),
		ByIPPrefix:       make(map[netip.Prefix][]int),
		ByEmailSignature: make(map[string][]int),
		config:           config,
	}
}

func (store *Store) Config() Config {
	return store.config
}

// Add persists an already validated account and its normalized features.
func (store *Store) Add(account domain.Account, features domain.Features) (int, error) {
	if _, exists := store.ByID[account.AccountID]; exists {
		return -1, fmt.Errorf("duplicate account_id %q", account.AccountID)
	}
	index := len(store.Accounts)
	store.Accounts = append(store.Accounts, account)
	store.Features = append(store.Features, features)
	store.ByID[account.AccountID] = index

	if features.EmailCanonical != "" {
		store.Frequencies.Email[features.EmailCanonical]++
		store.ByCanonicalEmail[features.EmailCanonical] = append(store.ByCanonicalEmail[features.EmailCanonical], index)
	}
	if features.DeviceID != "" {
		store.Frequencies.Device[features.DeviceID]++
		store.ByDevice[features.DeviceID] = append(store.ByDevice[features.DeviceID], index)
	}
	if features.Payment != "" {
		store.Frequencies.Payment[features.Payment]++
		store.ByPayment[features.Payment] = append(store.ByPayment[features.Payment], index)
	}
	if features.IP.IsValid() {
		store.Frequencies.IP[features.IP]++
		store.ByIP[features.IP] = append(store.ByIP[features.IP], index)
	}
	if features.IPPrefix.IsValid() {
		store.Frequencies.IPPrefix[features.IPPrefix]++
		store.ByIPPrefix[features.IPPrefix] = append(store.ByIPPrefix[features.IPPrefix], index)
	}
	if features.EmailDomain != "" {
		for _, signature := range normalize.DeletionSignatures(features.EmailBaseLocal, store.config.MaxEmailRunes) {
			key := signatureKey(features.EmailDomain, signature)
			store.ByEmailSignature[key] = append(store.ByEmailSignature[key], index)
		}
	}
	return index, nil
}

// CandidatesFor returns deterministic local candidates for a feature vector.
// projected is true for a not-yet-indexed streaming record; posting limits then
// include that incoming occurrence.
func (store *Store) CandidatesFor(features domain.Features, projected bool) []int {
	candidates := make(map[int]struct{})
	addPosting := func(posting []int, limit int) {
		projectedLength := len(posting)
		if projected {
			projectedLength++
		}
		if limit <= 0 || projectedLength > limit {
			return
		}
		for _, candidate := range posting {
			if len(candidates) >= store.config.MaxCandidates {
				return
			}
			candidates[candidate] = struct{}{}
		}
	}

	if features.EmailCanonical != "" {
		addPosting(store.ByCanonicalEmail[features.EmailCanonical], store.config.EmailBlockLimit)
	}
	if features.DeviceID != "" {
		addPosting(store.ByDevice[features.DeviceID], store.config.DeviceBlockLimit)
	}
	if features.Payment != "" {
		addPosting(store.ByPayment[features.Payment], store.config.PaymentBlockLimit)
	}
	if features.IP.IsValid() {
		addPosting(store.ByIP[features.IP], store.config.IPBlockLimit)
	}
	if features.IPPrefix.IsValid() {
		addPosting(store.ByIPPrefix[features.IPPrefix], store.config.PrefixBlockLimit)
	}
	if features.EmailDomain != "" {
		for _, signature := range normalize.DeletionSignatures(features.EmailBaseLocal, store.config.MaxEmailRunes) {
			addPosting(store.ByEmailSignature[signatureKey(features.EmailDomain, signature)], store.config.SignatureBlockLimit)
		}
	}

	result := make([]int, 0, len(candidates))
	for candidate := range candidates {
		result = append(result, candidate)
	}
	sort.Slice(result, func(i, j int) bool {
		return store.Accounts[result[i]].AccountID < store.Accounts[result[j]].AccountID
	})
	return result
}

// CandidatePairs creates the sparse batch pair set after all global
// frequencies and posting sizes are known.
func (store *Store) CandidatePairs() []Pair {
	pairs := make(map[Pair]struct{})
	for i, features := range store.Features {
		for _, j := range store.CandidatesFor(features, false) {
			if i == j {
				continue
			}
			pair := Pair{A: i, B: j}
			if pair.A > pair.B {
				pair.A, pair.B = pair.B, pair.A
			}
			pairs[pair] = struct{}{}
		}
	}
	result := make([]Pair, 0, len(pairs))
	for pair := range pairs {
		result = append(result, pair)
	}
	sort.Slice(result, func(i, j int) bool {
		leftA, leftB := orderedIDs(store, result[i])
		rightA, rightB := orderedIDs(store, result[j])
		if leftA != rightA {
			return leftA < rightA
		}
		return leftB < rightB
	})
	return result
}

func orderedIDs(store *Store, pair Pair) (string, string) {
	a := store.Accounts[pair.A].AccountID
	b := store.Accounts[pair.B].AccountID
	if a > b {
		return b, a
	}
	return a, b
}

func signatureKey(domainName, signature string) string {
	return domainName + "\x00" + signature
}

// BurstSize returns the largest number of accounts in a prefix within one
// window that also contains both endpoints. incoming is included when it has
// not yet been committed to the store.
func (store *Store) BurstSize(left, right domain.Features, window time.Duration, incoming *domain.Features) int {
	if !left.IPPrefix.IsValid() || left.IPPrefix != right.IPPrefix ||
		left.CreatedAt.IsZero() || right.CreatedAt.IsZero() {
		return 0
	}
	delta := left.CreatedAt.Sub(right.CreatedAt)
	if delta < 0 {
		delta = -delta
	}
	if delta > window {
		return 0
	}

	posting := store.ByIPPrefix[left.IPPrefix]
	times := make([]time.Time, 0, len(posting)+1)
	for _, index := range posting {
		if !store.Features[index].CreatedAt.IsZero() {
			times = append(times, store.Features[index].CreatedAt)
		}
	}
	if incoming != nil {
		times = append(times, incoming.CreatedAt)
	}
	sort.Slice(times, func(i, j int) bool { return times[i].Before(times[j]) })

	low, high := left.CreatedAt, right.CreatedAt
	if high.Before(low) {
		low, high = high, low
	}
	best := 0
	for start := 0; start < len(times); start++ {
		if times[start].After(low) || times[start].Add(window).Before(high) {
			continue
		}
		end := start
		for end < len(times) && !times[end].After(times[start].Add(window)) {
			end++
		}
		if end-start > best {
			best = end - start
		}
	}
	return best
}

// PrefixPosting returns the internal read-only posting for group evidence.
func (store *Store) PrefixPosting(prefix netip.Prefix) []int {
	return store.ByIPPrefix[prefix]
}

// DistinctIPRatio reports how much a prefix cohort represents distinct hosts.
func (store *Store) DistinctIPRatio(indices []int) float64 {
	if len(indices) == 0 {
		return 0
	}
	unique := make(map[netip.Addr]struct{}, len(indices))
	for _, index := range indices {
		if store.Features[index].IP.IsValid() {
			unique[store.Features[index].IP] = struct{}{}
		}
	}
	return float64(len(unique)) / float64(len(indices))
}
