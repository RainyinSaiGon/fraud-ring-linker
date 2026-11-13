package streaming

import (
	"fmt"
	"reflect"
	"strings"
	"testing"
	"time"

	"accountlinker/internal/clustering"
	"accountlinker/internal/domain"
	"accountlinker/internal/normalize"
)

func BenchmarkAssignLocal(b *testing.B) {
	base := time.Date(2026, time.August, 1, 0, 0, 0, 0, time.UTC)
	initial := make([]domain.Account, 10_000)
	for index := range initial {
		initial[index] = streamAccount(
			fmt.Sprintf("history_%d", index),
			fmt.Sprintf("history-%d@example.com", index),
			"",
			fmt.Sprintf("10.%d.%d.%d", (index/65536)%256, (index/256)%256, index%256),
			"",
			base.Add(time.Duration(index)*time.Second),
		)
	}
	state, err := clustering.Build(initial, streamConstraints(b, nil), clustering.DefaultConfig())
	if err != nil {
		b.Fatal(err)
	}
	linker := New(state)
	b.ReportAllocs()
	b.ResetTimer()
	for index := 0; index < b.N; index++ {
		account := streamAccount(
			fmt.Sprintf("event_%d", index),
			fmt.Sprintf("event-%d@example.net", index),
			"",
			fmt.Sprintf("198.18.%d.%d", (index/256)%256, index%256),
			"",
			base.Add(time.Duration(index)*time.Second),
		)
		if _, err := linker.Assign(account); err != nil {
			b.Fatal(err)
		}
	}
}

func TestAssignHonorsConstraintForFutureAccountID(t *testing.T) {
	base := time.Date(2026, time.August, 10, 10, 0, 0, 0, time.UTC)
	initial := []domain.Account{
		streamAccount("acc_existing", "same.person@gmail.com", "shared-device", "10.1.1.1", "shared-payment", base),
	}
	constraints := streamConstraints(t, []domain.Constraint{{
		AccountA: "acc_existing",
		AccountB: "acc_future",
		Relation: "verified_distinct",
	}})
	state := streamState(t, initial, constraints)
	existingCluster := streamClustersByAccount(t, state)["acc_existing"]

	assignment, err := New(state).Assign(
		streamAccount("acc_future", "sameperson+promo@gmail.com", "shared-device", "10.1.1.1", "shared-payment", base.Add(time.Minute)),
	)
	if err != nil {
		t.Fatalf("Assign() error = %v", err)
	}
	if assignment.ClusterID == existingCluster {
		t.Fatalf("future constrained account joined forbidden cluster %q", existingCluster)
	}
	assertStreamingInvariants(t, state)
}

func TestAssignDuplicateIsRejectedWithoutStateMutation(t *testing.T) {
	base := time.Date(2026, time.August, 11, 10, 0, 0, 0, time.UTC)
	state := streamState(t, []domain.Account{
		streamAccount("acc_existing", "existing@example.com", "", "10.1.1.1", "", base),
	}, streamConstraints(t, nil))
	linker := New(state)
	incoming := streamAccount("acc_new", "new@example.com", "", "20.1.1.1", "", base.Add(time.Hour))

	if _, err := linker.Assign(incoming); err != nil {
		t.Fatalf("first Assign() error = %v", err)
	}
	beforeAccounts := len(state.Store.Accounts)
	beforeDSU := state.DSU.Len()
	beforeClusters := state.Clusters()

	_, err := linker.Assign(incoming)
	if err == nil {
		t.Fatal("duplicate Assign() unexpectedly succeeded")
	}
	if !strings.Contains(err.Error(), "duplicate account_id") {
		t.Fatalf("duplicate Assign() error = %q, want duplicate account_id", err)
	}
	if got := len(state.Store.Accounts); got != beforeAccounts {
		t.Fatalf("duplicate changed account count to %d, want %d", got, beforeAccounts)
	}
	if got := state.DSU.Len(); got != beforeDSU {
		t.Fatalf("duplicate changed DSU length to %d, want %d", got, beforeDSU)
	}
	if after := state.Clusters(); !reflect.DeepEqual(beforeClusters, after) {
		t.Fatalf("duplicate changed clusters:\nbefore: %#v\nafter:  %#v", beforeClusters, after)
	}
	assertStreamingInvariants(t, state)
}

func TestAssignFallsBackToBestFeasibleCluster(t *testing.T) {
	base := time.Date(2026, time.August, 12, 10, 0, 0, 0, time.UTC)
	initial := []domain.Account{
		streamAccount("acc_best", "winner@gmail.com", "", "10.1.1.1", "", base),
		streamAccount("acc_fallback", "fallback@example.com", "", "20.1.1.1", "fallback-payment", base),
	}
	constraints := streamConstraints(t, []domain.Constraint{{
		AccountA: "acc_incoming",
		AccountB: "acc_best",
		Relation: "verified_distinct",
	}})
	state := streamState(t, initial, constraints)
	before := streamClustersByAccount(t, state)

	assignment, err := New(state).Assign(
		streamAccount("acc_incoming", "winner@gmail.com", "", "30.1.1.1", "fallback-payment", base.Add(time.Minute)),
	)
	if err != nil {
		t.Fatalf("Assign() error = %v", err)
	}
	if assignment.ClusterID != before["acc_fallback"] {
		t.Fatalf(
			"Assign() cluster = %q, want feasible fallback %q (forbidden best was %q)",
			assignment.ClusterID,
			before["acc_fallback"],
			before["acc_best"],
		)
	}
	after := streamClustersByAccount(t, state)
	if after["acc_incoming"] == after["acc_best"] {
		t.Fatal("incoming account joined its forbidden highest-scoring cluster")
	}
	assertStreamingInvariants(t, state)
}

func TestAssignIsMonotonicAndKeepsStableClusterIDs(t *testing.T) {
	base := time.Date(2026, time.August, 13, 10, 0, 0, 0, time.UTC)
	initial := []domain.Account{
		streamAccount("acc_a", "preferred@gmail.com", "", "10.1.1.1", "", base),
		streamAccount("acc_b", "other@example.com", "", "20.1.1.1", "secondary-payment", base),
	}
	state := streamState(t, initial, streamConstraints(t, nil))
	linker := New(state)
	original := streamClustersByAccount(t, state)
	if original["acc_a"] == original["acc_b"] {
		t.Fatal("fixture accounts unexpectedly started in one cluster")
	}

	matched, err := linker.Assign(
		streamAccount("acc_matches_both", "preferred@gmail.com", "", "30.1.1.1", "secondary-payment", base.Add(time.Minute)),
	)
	if err != nil {
		t.Fatalf("Assign(matching both) error = %v", err)
	}
	if matched.ClusterID != original["acc_a"] {
		t.Fatalf("matching event joined %q, want stronger cluster %q", matched.ClusterID, original["acc_a"])
	}
	afterMatch := streamClustersByAccount(t, state)
	if afterMatch["acc_a"] != original["acc_a"] || afterMatch["acc_b"] != original["acc_b"] {
		t.Fatalf("existing cluster IDs changed: before=%v after=%v", original, afterMatch)
	}
	if afterMatch["acc_a"] == afterMatch["acc_b"] {
		t.Fatal("one stream event merged two previously-existing clusters")
	}
	assertStreamingInvariants(t, state)

	singleton, err := linker.Assign(
		streamAccount("acc_singleton", "brand-new@example.net", "", "40.1.1.1", "", base.Add(2*time.Hour)),
	)
	if err != nil {
		t.Fatalf("Assign(singleton) error = %v", err)
	}
	if singleton.ClusterID == original["acc_a"] || singleton.ClusterID == original["acc_b"] {
		t.Fatalf("new singleton reused existing cluster ID %q", singleton.ClusterID)
	}

	joined, err := linker.Assign(
		streamAccount("acc_singleton_alias", "brand-new@example.net", "", "50.1.1.1", "", base.Add(3*time.Hour)),
	)
	if err != nil {
		t.Fatalf("Assign(singleton alias) error = %v", err)
	}
	if joined.ClusterID != singleton.ClusterID {
		t.Fatalf("later exact match joined %q, want streamed cluster %q", joined.ClusterID, singleton.ClusterID)
	}
	final := streamClustersByAccount(t, state)
	if final["acc_a"] != original["acc_a"] || final["acc_b"] != original["acc_b"] {
		t.Fatalf("later events changed initial IDs: before=%v final=%v", original, final)
	}
	assertStreamingInvariants(t, state)
}

func TestAssignProjectedVelocityJoinsOneClusterWithoutMergingOldClusters(t *testing.T) {
	base := time.Date(2026, time.August, 14, 10, 0, 0, 0, time.UTC)
	initial := []domain.Account{
		streamAccount("acc_a", "a@example.com", "", "194.181.67.10", "", base),
		streamAccount("acc_b", "b@example.com", "", "194.181.67.20", "", base.Add(10*time.Minute)),
	}
	state := streamState(t, initial, streamConstraints(t, nil))
	linker := New(state)
	before := streamClustersByAccount(t, state)
	if before["acc_a"] == before["acc_b"] {
		t.Fatal("two-account prefix unexpectedly met the velocity minimum")
	}

	assignment, err := linker.Assign(
		streamAccount("acc_c", "c@example.com", "", "194.181.67.30", "", base.Add(20*time.Minute)),
	)
	if err != nil {
		t.Fatalf("Assign() error = %v", err)
	}
	if assignment.ClusterID != before["acc_a"] {
		t.Fatalf("velocity tie joined %q, want deterministic lower cluster %q", assignment.ClusterID, before["acc_a"])
	}
	after := streamClustersByAccount(t, state)
	if after["acc_a"] != before["acc_a"] || after["acc_b"] != before["acc_b"] {
		t.Fatalf("velocity event changed old IDs: before=%v after=%v", before, after)
	}
	if after["acc_a"] == after["acc_b"] {
		t.Fatal("projected velocity event merged two old clusters")
	}
	if after["acc_c"] != after["acc_a"] {
		t.Fatalf("velocity event cluster = %q, want %q", after["acc_c"], after["acc_a"])
	}
	assertStreamingInvariants(t, state)
}

func TestProjectedDistinctIPRatioSkipsOversizedPrefix(t *testing.T) {
	base := time.Date(2026, time.August, 14, 10, 0, 0, 0, time.UTC)
	initial := make([]domain.Account, 8)
	for index := range initial {
		initial[index] = streamAccount(
			fmt.Sprintf("acc_prefix_%d", index),
			fmt.Sprintf("person-%d@example.com", index),
			"",
			fmt.Sprintf("203.0.113.%d", index+1),
			"",
			base.Add(time.Duration(index)*time.Minute),
		)
	}
	state := streamState(t, initial, streamConstraints(t, nil))
	incoming := streamAccount(
		"acc_incoming",
		"incoming@example.net",
		"",
		"203.0.113.99",
		"",
		base.Add(9*time.Minute),
	)
	features := normalize.Account(incoming)
	projectedSize := state.Store.Frequencies.IPPrefix[features.IPPrefix] + 1

	if got := projectedDistinctIPRatio(state, features, projectedSize); got != 0 {
		t.Fatalf(
			"projectedDistinctIPRatio() = %v for oversized prefix, want 0",
			got,
		)
	}
}

func TestAssignDoesNotExpandPrefixWithSecondEstablishedIdentity(t *testing.T) {
	base := time.Date(2026, time.August, 15, 10, 0, 0, 0, time.UTC)
	initial := []domain.Account{
		streamAccount("acc_a1", "a1@example.com", "", "203.0.113.10", "payment-a", base),
		streamAccount("acc_a2", "a2@example.com", "", "203.0.113.20", "payment-a", base.Add(10*24*time.Hour)),
		streamAccount("acc_b1", "b1@example.com", "", "203.0.113.30", "payment-b", base.Add(20*24*time.Hour)),
		streamAccount("acc_b2", "b2@example.com", "", "198.51.100.30", "payment-b", base.Add(30*24*time.Hour)),
	}
	state := streamState(t, initial, streamConstraints(t, nil))
	before := streamClustersByAccount(t, state)

	assignment, err := New(state).Assign(streamAccount(
		"acc_incoming",
		"incoming@example.net",
		"",
		"203.0.113.40",
		"",
		base.Add(40*24*time.Hour),
	))
	if err != nil {
		t.Fatalf("Assign() error = %v", err)
	}
	if assignment.ClusterID == before["acc_a1"] ||
		assignment.ClusterID == before["acc_b1"] {
		t.Fatalf(
			"incoming account joined established identity through ambiguous prefix: %+v",
			assignment,
		)
	}
	assertStreamingInvariants(t, state)
}

func streamState(
	t *testing.T,
	accounts []domain.Account,
	constraints *clustering.Constraints,
) *clustering.State {
	t.Helper()
	state, err := clustering.Build(accounts, constraints, clustering.DefaultConfig())
	if err != nil {
		t.Fatalf("clustering.Build() error = %v", err)
	}
	assertStreamingInvariants(t, state)
	return state
}

func streamConstraints(t testing.TB, records []domain.Constraint) *clustering.Constraints {
	t.Helper()
	constraints, err := clustering.NewConstraints(records)
	if err != nil {
		t.Fatalf("clustering.NewConstraints() error = %v", err)
	}
	return constraints
}

func streamAccount(
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
		account.DeviceID = streamStringPointer(device)
	}
	if payment != "" {
		account.PaymentFingerprint = streamStringPointer(payment)
	}
	return account
}

func streamStringPointer(value string) *string {
	return &value
}

func streamClustersByAccount(t *testing.T, state *clustering.State) map[string]string {
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
	if len(result) != len(state.Store.Accounts) {
		t.Fatalf("partition covers %d accounts, want %d", len(result), len(state.Store.Accounts))
	}
	return result
}

func assertStreamingInvariants(t *testing.T, state *clustering.State) {
	t.Helper()
	if err := state.ValidateInvariants(); err != nil {
		t.Fatalf("ValidateInvariants() error = %v", err)
	}
	streamClustersByAccount(t, state)
}
