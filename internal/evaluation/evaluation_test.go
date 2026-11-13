package evaluation

import (
	"strings"
	"testing"

	"accountlinker/internal/domain"
)

func TestEvaluatePerfectPartition(t *testing.T) {
	t.Parallel()

	truth := Truth{
		AccountToActor: map[string]string{
			"a1": "actor_a",
			"a2": "actor_a",
			"b1": "actor_b",
			"c1": "actor_c",
			"c2": "actor_c",
		},
		FraudActorIDs: []string{"actor_a"},
	}
	clusters := domain.ClustersOutput{Clusters: []domain.Cluster{
		{ClusterID: "c1", AccountIDs: []string{"a1", "a2"}},
		{ClusterID: "c2", AccountIDs: []string{"b1"}},
		{ClusterID: "c3", AccountIDs: []string{"c1", "c2"}},
	}}

	got, err := Evaluate(clusters, truth, nil)
	if err != nil {
		t.Fatalf("Evaluate() error = %v", err)
	}
	if got.TruePairCount != 2 ||
		got.PredictedPairCount != 2 ||
		got.TruePositivePairs != 2 ||
		got.FalsePositivePairs != 0 ||
		got.FalseNegativePairs != 0 {
		t.Fatalf("unexpected pair counts: %+v", got)
	}
	if got.PairPrecision != 1 || got.PairRecall != 1 || got.PairF1 != 1 {
		t.Fatalf("unexpected pair metrics: %+v", got)
	}
	if got.MultiAccountActorCount != 2 ||
		got.FullyRecoveredMultiAccountActors != 2 ||
		got.FragmentedMultiAccountActors != 0 {
		t.Fatalf("unexpected actor recovery: %+v", got)
	}
	if got.FraudRingCount != 1 ||
		got.FullyRecoveredFraudRings != 1 ||
		got.FragmentedFraudActors != 0 ||
		got.FraudRingRecovery != 1 {
		t.Fatalf("unexpected fraud recovery: %+v", got)
	}
	if got.Impurity != 0 ||
		got.GeneralProxyCost != 0 ||
		got.LiteralProxyCost != 0 ||
		got.LargestCluster != 2 {
		t.Fatalf("unexpected aggregate metrics: %+v", got)
	}
}

func TestEvaluateMixedAndFragmentedClusters(t *testing.T) {
	t.Parallel()

	truth := Truth{
		AccountToActor: map[string]string{
			"a1": "actor_a",
			"a2": "actor_a",
			"b1": "actor_b",
			"b2": "actor_b",
			"c1": "actor_c",
		},
		FraudActorIDs: []string{"actor_a"},
	}
	clusters := domain.ClustersOutput{Clusters: []domain.Cluster{
		{ClusterID: "c1", AccountIDs: []string{"a1", "b1", "c1"}},
		{ClusterID: "c2", AccountIDs: []string{"a2"}},
		{ClusterID: "c3", AccountIDs: []string{"b2"}},
	}}

	got, err := Evaluate(clusters, truth, nil)
	if err != nil {
		t.Fatalf("Evaluate() error = %v", err)
	}
	if got.TruePairCount != 2 ||
		got.PredictedPairCount != 3 ||
		got.TruePositivePairs != 0 ||
		got.FalsePositivePairs != 3 ||
		got.FalseNegativePairs != 2 {
		t.Fatalf("unexpected pair counts: %+v", got)
	}
	if got.PairPrecision != 0 || got.PairRecall != 0 || got.PairF1 != 0 {
		t.Fatalf("unexpected pair metrics: %+v", got)
	}
	if got.Impurity != 2 ||
		got.FragmentedMultiAccountActors != 2 ||
		got.FullyRecoveredMultiAccountActors != 0 {
		t.Fatalf("unexpected fragmentation metrics: %+v", got)
	}
	if got.FraudRingRecovery != 0 ||
		got.FragmentedFraudActors != 1 ||
		got.NonFraudActorsWithFraud != 2 {
		t.Fatalf("unexpected fraud metrics: %+v", got)
	}
	if got.GeneralProxyCost != 4100 || got.LiteralProxyCost != 2100 {
		t.Fatalf("unexpected costs: %+v", got)
	}
}

func TestEvaluateRejectsInvalidPartitions(t *testing.T) {
	t.Parallel()

	truth := Truth{AccountToActor: map[string]string{
		"a1": "actor_a",
		"a2": "actor_a",
	}}
	tests := []struct {
		name     string
		clusters domain.ClustersOutput
		want     string
	}{
		{
			name: "missing account",
			clusters: domain.ClustersOutput{Clusters: []domain.Cluster{
				{ClusterID: "c1", AccountIDs: []string{"a1"}},
			}},
			want: "incomplete partition",
		},
		{
			name: "unknown account",
			clusters: domain.ClustersOutput{Clusters: []domain.Cluster{
				{ClusterID: "c1", AccountIDs: []string{"a1", "a2", "other"}},
			}},
			want: "unknown account",
		},
		{
			name: "duplicate account",
			clusters: domain.ClustersOutput{Clusters: []domain.Cluster{
				{ClusterID: "c1", AccountIDs: []string{"a1"}},
				{ClusterID: "c2", AccountIDs: []string{"a1", "a2"}},
			}},
			want: "appears more than once",
		},
		{
			name: "duplicate cluster ID",
			clusters: domain.ClustersOutput{Clusters: []domain.Cluster{
				{ClusterID: "c1", AccountIDs: []string{"a1"}},
				{ClusterID: "c1", AccountIDs: []string{"a2"}},
			}},
			want: "duplicate cluster_id",
		},
	}

	for _, test := range tests {
		test := test
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()
			_, err := Evaluate(test.clusters, truth, nil)
			if err == nil || !strings.Contains(err.Error(), test.want) {
				t.Fatalf("Evaluate() error = %v, want substring %q", err, test.want)
			}
		})
	}
}

func TestEvaluateRejectsConstraintViolation(t *testing.T) {
	t.Parallel()

	truth := Truth{AccountToActor: map[string]string{
		"a1": "actor_a",
		"b1": "actor_b",
	}}
	clusters := domain.ClustersOutput{Clusters: []domain.Cluster{
		{ClusterID: "c1", AccountIDs: []string{"a1", "b1"}},
	}}
	constraints := []domain.Constraint{
		{
			AccountA: "a1",
			AccountB: "b1",
			Relation: "verified_distinct",
		},
	}

	_, err := Evaluate(clusters, truth, constraints)
	if err == nil || !strings.Contains(err.Error(), "constraint validation failed") {
		t.Fatalf("Evaluate() error = %v, want constraint validation failure", err)
	}
}
