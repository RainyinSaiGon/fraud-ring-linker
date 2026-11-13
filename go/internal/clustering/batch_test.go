package clustering

import (
	"fmt"
	"reflect"
	"sort"
	"strings"
	"testing"
	"time"

	"accountlinker/internal/domain"
)

func TestBuildHonorsDirectConstraintDespiteStrongEvidence(t *testing.T) {
	base := time.Date(2026, time.August, 1, 10, 0, 0, 0, time.UTC)
	accounts := []domain.Account{
		batchAccount("acc_a", "same.person+one@gmail.com", "shared-device", "10.0.0.1", "shared-payment", base),
		batchAccount("acc_b", "sameperson@gmail.com", "shared-device", "10.0.0.1", "shared-payment", base.Add(time.Minute)),
	}
	constraints := mustConstraints(t, []domain.Constraint{{
		AccountA: "acc_a",
		AccountB: "acc_b",
		Relation: "verified_distinct",
	}})

	state, err := Build(accounts, constraints, DefaultConfig())
	if err != nil {
		t.Fatalf("Build() error = %v", err)
	}
	assertValidCompletePartition(t, state)

	clusters := clustersByAccount(t, state)
	if clusters["acc_a"] == clusters["acc_b"] {
		t.Fatalf("directly constrained accounts share cluster %q", clusters["acc_a"])
	}
}

func TestBuildEmptyInputProducesEmptyPartition(t *testing.T) {
	state, err := Build(nil, mustConstraints(t, nil), DefaultConfig())
	if err != nil {
		t.Fatalf("Build() error = %v", err)
	}
	if err := state.ValidateInvariants(); err != nil {
		t.Fatalf("ValidateInvariants() error = %v", err)
	}
	output := state.Clusters()
	if output.Clusters == nil {
		t.Fatal("empty output clusters must encode as [] rather than null")
	}
	if len(output.Clusters) != 0 {
		t.Fatalf("empty input produced %d clusters", len(output.Clusters))
	}
}

func TestBuildPreservesStrongSideOfTransitiveConstraintBridge(t *testing.T) {
	base := time.Date(2026, time.August, 2, 10, 0, 0, 0, time.UTC)
	accounts := []domain.Account{
		batchAccount("acc_a", "anchor@example.com", "", "10.1.1.1", "", base),
		batchAccount("acc_x", "anchor@example.com", "bridge-device", "20.1.1.1", "", base.Add(time.Minute)),
		batchAccount("acc_b", "anch0r@example.com", "bridge-device", "30.1.1.1", "", base.Add(2*time.Minute)),
	}
	constraints := mustConstraints(t, []domain.Constraint{{
		AccountA: "acc_a",
		AccountB: "acc_b",
		Relation: "verified_distinct",
	}})

	state, err := Build(accounts, constraints, DefaultConfig())
	if err != nil {
		t.Fatalf("Build() error = %v", err)
	}
	assertValidCompletePartition(t, state)

	clusters := clustersByAccount(t, state)
	if clusters["acc_a"] != clusters["acc_x"] {
		t.Fatalf("strong edge was not retained: A=%q X=%q", clusters["acc_a"], clusters["acc_x"])
	}
	if clusters["acc_a"] == clusters["acc_b"] {
		t.Fatalf("transitive bridge violated A != B in cluster %q", clusters["acc_a"])
	}
}

func TestDSURejectsConstraintAcrossExistingComponents(t *testing.T) {
	base := time.Date(2026, time.August, 3, 10, 0, 0, 0, time.UTC)
	accounts := []domain.Account{
		batchAccount("acc_a", "a@example.com", "", "10.1.0.1", "", base),
		batchAccount("acc_x", "x@example.com", "", "10.2.0.1", "", base),
		batchAccount("acc_b", "b@example.com", "", "10.3.0.1", "", base),
		batchAccount("acc_y", "y@example.com", "", "10.4.0.1", "", base),
	}
	byID := map[string]int{"acc_a": 0, "acc_x": 1, "acc_b": 2, "acc_y": 3}
	constraints := mustConstraints(t, []domain.Constraint{{
		AccountA: "acc_a",
		AccountB: "acc_y",
		Relation: "verified_distinct",
	}})
	dsu := NewDSU(len(accounts))
	dsu.Union(0, 1, 0.96)
	dsu.Union(2, 3, 0.94)

	if dsu.CanMerge(1, 2, accounts, byID, constraints) {
		t.Fatal("CanMerge accepted components containing constrained non-endpoint members")
	}
	if dsu.Find(0) == dsu.Find(2) {
		t.Fatal("components unexpectedly merged")
	}
}

func TestBuildProducesCompleteDeterministicPartition(t *testing.T) {
	base := time.Date(2026, time.August, 4, 10, 0, 0, 0, time.UTC)
	accounts := []domain.Account{
		batchAccount("acc_z", "z@example.com", "", "10.0.0.1", "", base),
		batchAccount("acc_c", "c@example.com", "", "20.0.0.1", "payment-bc", base),
		batchAccount("acc_a", "alias.person@gmail.com", "", "30.0.0.1", "", base),
		batchAccount("acc_d", "aliasperson+promo@gmail.com", "", "40.0.0.1", "", base),
		batchAccount("acc_b", "b@example.com", "", "50.0.0.1", "payment-bc", base),
	}
	permuted := []domain.Account{accounts[3], accounts[0], accounts[4], accounts[1], accounts[2]}
	constraints := mustConstraints(t, nil)

	first, err := Build(accounts, constraints, DefaultConfig())
	if err != nil {
		t.Fatalf("first Build() error = %v", err)
	}
	second, err := Build(permuted, constraints, DefaultConfig())
	if err != nil {
		t.Fatalf("second Build() error = %v", err)
	}
	assertValidCompletePartition(t, first)
	assertValidCompletePartition(t, second)

	firstOutput := first.Clusters()
	if again := first.Clusters(); !reflect.DeepEqual(firstOutput, again) {
		t.Fatalf("repeated Clusters() changed output:\nfirst: %#v\nagain: %#v", firstOutput, again)
	}
	if secondOutput := second.Clusters(); !reflect.DeepEqual(firstOutput, secondOutput) {
		t.Fatalf("input permutation changed deterministic output:\nfirst:  %#v\nsecond: %#v", firstOutput, secondOutput)
	}

	cluster := clustersByAccount(t, first)
	if cluster["acc_a"] != cluster["acc_d"] {
		t.Fatal("canonical email pair was not linked")
	}
	if cluster["acc_b"] != cluster["acc_c"] {
		t.Fatal("payment pair was not linked")
	}
	if cluster["acc_z"] == cluster["acc_a"] || cluster["acc_z"] == cluster["acc_b"] {
		t.Fatal("singleton was absorbed into an unrelated component")
	}
}

func TestBuildDoesNotLinkHotSharedIP(t *testing.T) {
	base := time.Date(2026, time.August, 5, 10, 0, 0, 0, time.UTC)
	accounts := make([]domain.Account, 64)
	for index := range accounts {
		accounts[index] = batchAccount(
			fmt.Sprintf("acc_%03d", index),
			fmt.Sprintf("unrelated-%03d@example.com", index),
			"",
			"190.236.206.122",
			"",
			base.Add(time.Duration(index)*time.Hour),
		)
	}

	state, err := Build(accounts, mustConstraints(t, nil), DefaultConfig())
	if err != nil {
		t.Fatalf("Build() error = %v", err)
	}
	assertValidCompletePartition(t, state)

	output := state.Clusters()
	if len(output.Clusters) != len(accounts) {
		t.Fatalf("hot shared IP produced %d clusters, want %d singletons", len(output.Clusters), len(accounts))
	}
	for _, cluster := range output.Clusters {
		if len(cluster.AccountIDs) != 1 {
			t.Fatalf("hot IP cluster %q has unrelated members %v", cluster.ClusterID, cluster.AccountIDs)
		}
	}
}

func TestBuildLinksDiversifiedVelocityCohort(t *testing.T) {
	base := time.Date(2026, time.August, 6, 10, 0, 0, 0, time.UTC)
	accounts := make([]domain.Account, 5)
	for index := range accounts {
		accounts[index] = batchAccount(
			fmt.Sprintf("acc_velocity_%d", index),
			fmt.Sprintf("different-%d@example.com", index),
			"",
			fmt.Sprintf("194.181.67.%d", index+10),
			"",
			base.Add(time.Duration(index)*8*time.Minute),
		)
	}

	state, err := Build(accounts, mustConstraints(t, nil), DefaultConfig())
	if err != nil {
		t.Fatalf("Build() error = %v", err)
	}
	assertValidCompletePartition(t, state)

	output := state.Clusters()
	if len(output.Clusters) != 1 {
		t.Fatalf("velocity cohort produced %d clusters, want 1: %#v", len(output.Clusters), output)
	}
	if got := len(output.Clusters[0].AccountIDs); got != len(accounts) {
		t.Fatalf("velocity cohort cluster size = %d, want %d", got, len(accounts))
	}
}

func TestBuildExpandsStrongSeededPrefixCohort(t *testing.T) {
	base := time.Date(2026, time.August, 7, 10, 0, 0, 0, time.UTC)
	accounts := []domain.Account{
		batchAccount("acc_seed_a", "seed-a@example.com", "", "199.59.25.10", "seed-payment", base),
		batchAccount("acc_seed_b", "seed-b@example.com", "", "199.59.25.20", "seed-payment", base.Add(10*24*time.Hour)),
		batchAccount("acc_prefix_c", "unrelated-c@example.com", "", "199.59.25.30", "", base.Add(20*24*time.Hour)),
		batchAccount("acc_prefix_d", "unrelated-d@example.com", "", "199.59.25.40", "", base.Add(30*24*time.Hour)),
	}

	state, err := Build(accounts, mustConstraints(t, nil), DefaultConfig())
	if err != nil {
		t.Fatalf("Build() error = %v", err)
	}
	assertValidCompletePartition(t, state)

	output := state.Clusters()
	if len(output.Clusters) != 1 {
		t.Fatalf("seeded prefix cohort produced %d clusters, want 1: %#v", len(output.Clusters), output)
	}
	if got := len(output.Clusters[0].AccountIDs); got != len(accounts) {
		t.Fatalf("seeded prefix cohort cluster size = %d, want %d", got, len(accounts))
	}
}

func TestBuildDoesNotExpandPrefixWithSecondEstablishedIdentity(t *testing.T) {
	base := time.Date(2026, time.August, 7, 10, 0, 0, 0, time.UTC)
	accounts := []domain.Account{
		batchAccount("acc_a1", "a1@example.com", "", "199.59.25.10", "payment-a", base),
		batchAccount("acc_a2", "a2@example.com", "", "199.59.25.20", "payment-a", base.Add(10*24*time.Hour)),
		batchAccount("acc_b1", "b1@example.com", "", "199.59.25.30", "payment-b", base.Add(20*24*time.Hour)),
		batchAccount("acc_b2", "b2@example.com", "", "198.51.100.30", "payment-b", base.Add(30*24*time.Hour)),
		batchAccount("acc_unlinked", "unlinked@example.com", "", "199.59.25.40", "", base.Add(40*24*time.Hour)),
	}

	state, err := Build(accounts, mustConstraints(t, nil), DefaultConfig())
	if err != nil {
		t.Fatalf("Build() error = %v", err)
	}
	assertValidCompletePartition(t, state)

	cluster := clustersByAccount(t, state)
	if cluster["acc_a1"] != cluster["acc_a2"] {
		t.Fatal("first established identity was not linked")
	}
	if cluster["acc_b1"] != cluster["acc_b2"] {
		t.Fatal("second established identity was not linked")
	}
	if cluster["acc_a1"] == cluster["acc_b1"] {
		t.Fatal("two established identities were merged through a shared prefix")
	}
	if cluster["acc_unlinked"] == cluster["acc_a1"] ||
		cluster["acc_unlinked"] == cluster["acc_b1"] {
		t.Fatal("ambiguous prefix expansion absorbed an unlinked account")
	}
}

func TestValidateInvariantsDetectsForcedConstraintViolation(t *testing.T) {
	base := time.Date(2026, time.August, 8, 10, 0, 0, 0, time.UTC)
	accounts := []domain.Account{
		batchAccount("acc_a", "a@example.com", "", "10.1.1.1", "", base),
		batchAccount("acc_b", "b@example.com", "", "20.1.1.1", "", base),
	}
	constraints := mustConstraints(t, []domain.Constraint{{
		AccountA: "acc_a",
		AccountB: "acc_b",
		Relation: "verified_distinct",
	}})
	state, err := Build(accounts, constraints, DefaultConfig())
	if err != nil {
		t.Fatalf("Build() error = %v", err)
	}

	state.DSU.Union(state.Store.ByID["acc_a"], state.Store.ByID["acc_b"], 0.99)
	err = state.ValidateInvariants()
	if err == nil {
		t.Fatal("ValidateInvariants accepted a forced cannot-link violation")
	}
	if !strings.Contains(err.Error(), "constraint invariant violated") {
		t.Fatalf("ValidateInvariants error = %q, want constraint violation", err)
	}
}

func batchAccount(
	id, email, device, ip, payment string,
	createdAt time.Time,
) domain.Account {
	account := domain.Account{
		AccountID: id,
		Email:     email,
		IP:        ip,
		CreatedAt: createdAt,
	}
	if device != "" {
		account.DeviceID = stringPointer(device)
	}
	if payment != "" {
		account.PaymentFingerprint = stringPointer(payment)
	}
	return account
}

func stringPointer(value string) *string {
	return &value
}

func mustConstraints(t *testing.T, records []domain.Constraint) *Constraints {
	t.Helper()
	constraints, err := NewConstraints(records)
	if err != nil {
		t.Fatalf("NewConstraints() error = %v", err)
	}
	return constraints
}

func clustersByAccount(t *testing.T, state *State) map[string]string {
	t.Helper()
	result := make(map[string]string, len(state.Store.Accounts))
	for _, cluster := range state.Clusters().Clusters {
		for _, accountID := range cluster.AccountIDs {
			if previous, exists := result[accountID]; exists {
				t.Fatalf("account %q appears in clusters %q and %q", accountID, previous, cluster.ClusterID)
			}
			result[accountID] = cluster.ClusterID
		}
	}
	return result
}

func assertValidCompletePartition(t *testing.T, state *State) {
	t.Helper()
	if err := state.ValidateInvariants(); err != nil {
		t.Fatalf("ValidateInvariants() error = %v", err)
	}
	output := state.Clusters()
	seen := make(map[string]string, len(state.Store.Accounts))
	for _, cluster := range output.Clusters {
		if cluster.ClusterID == "" {
			t.Fatal("cluster has an empty ID")
		}
		if cluster.Confidence < 0 || cluster.Confidence > 1 {
			t.Fatalf("cluster %q confidence = %v, want [0,1]", cluster.ClusterID, cluster.Confidence)
		}
		if !sort.StringsAreSorted(cluster.AccountIDs) {
			t.Fatalf("cluster %q members are not sorted: %v", cluster.ClusterID, cluster.AccountIDs)
		}
		for _, accountID := range cluster.AccountIDs {
			if previous, exists := seen[accountID]; exists {
				t.Fatalf("account %q occurs in both %q and %q", accountID, previous, cluster.ClusterID)
			}
			seen[accountID] = cluster.ClusterID
		}
	}
	if len(seen) != len(state.Store.Accounts) {
		t.Fatalf("partition covers %d accounts, want %d", len(seen), len(state.Store.Accounts))
	}
	for _, account := range state.Store.Accounts {
		if _, exists := seen[account.AccountID]; !exists {
			t.Errorf("account %q is missing from partition", account.AccountID)
		}
	}
}
