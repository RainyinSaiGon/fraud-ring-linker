// Package evaluation contains offline-only helpers for comparing linker output
// with labeled truth. The production linker does not import this package.
package evaluation

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"sort"
	"strings"

	"accountlinker/internal/domain"
	"accountlinker/internal/jsonl"
)

// Truth is the labeled sample format. Hubs and any future fields are
// intentionally ignored: only actor membership and fraud labels affect the
// metrics.
type Truth struct {
	AccountToActor map[string]string `json:"account_to_actor"`
	FraudActorIDs  []string          `json:"fraud_actor_ids"`
}

// Metrics reports both ordinary clustering metrics and the two explicitly
// documented business-cost proxies. Monetary costs are integer dollars.
type Metrics struct {
	AccountCount int `json:"account_count"`
	ActorCount   int `json:"actor_count"`
	ClusterCount int `json:"cluster_count"`

	TruePairCount      int64   `json:"true_pair_count"`
	PredictedPairCount int64   `json:"predicted_pair_count"`
	TruePositivePairs  int64   `json:"true_positive_pairs"`
	FalsePositivePairs int64   `json:"false_positive_pairs"`
	FalseNegativePairs int64   `json:"false_negative_pairs"`
	PairPrecision      float64 `json:"pair_precision"`
	PairRecall         float64 `json:"pair_recall"`
	PairF1             float64 `json:"pair_f1"`

	// Impurity is the sum, over predicted clusters, of members outside that
	// cluster's largest true-actor group.
	Impurity int64 `json:"impurity"`

	MultiAccountActorCount           int `json:"multi_account_actor_count"`
	FullyRecoveredMultiAccountActors int `json:"fully_recovered_multi_account_actors"`
	FragmentedMultiAccountActors     int `json:"fragmented_multi_account_actors"`

	FraudRingCount           int     `json:"fraud_ring_count"`
	FullyRecoveredFraudRings int     `json:"fully_recovered_fraud_rings"`
	FragmentedFraudActors    int     `json:"fragmented_fraud_actors"`
	FraudRingRecovery        float64 `json:"fraud_ring_recovery"`
	NonFraudActorsWithFraud  int     `json:"nonfraud_actors_sharing_cluster_with_fraud"`

	LargestCluster       int   `json:"largest_cluster"`
	ConstraintViolations int   `json:"constraint_violations"`
	GeneralProxyCost     int64 `json:"general_proxy_cost"`
	LiteralProxyCost     int64 `json:"literal_proxy_cost"`
}

// EvaluateFiles loads an output file, labeled truth, and optionally a
// constraints JSONL file before evaluating the complete partition.
func EvaluateFiles(clustersPath, truthPath, constraintsPath string) (Metrics, error) {
	clusters, err := ReadClusters(clustersPath)
	if err != nil {
		return Metrics{}, err
	}
	truth, err := ReadTruth(truthPath)
	if err != nil {
		return Metrics{}, err
	}

	var constraints []domain.Constraint
	if constraintsPath != "" {
		constraints, err = jsonl.ReadConstraints(constraintsPath)
		if err != nil {
			return Metrics{}, err
		}
	}
	return Evaluate(clusters, truth, constraints)
}

// ReadClusters reads the required clusters.json envelope.
func ReadClusters(path string) (domain.ClustersOutput, error) {
	file, err := os.Open(path)
	if err != nil {
		return domain.ClustersOutput{}, fmt.Errorf("open clusters %q: %w", path, err)
	}
	defer file.Close()

	var output domain.ClustersOutput
	if err := decodeOneJSON(file, &output); err != nil {
		return domain.ClustersOutput{}, fmt.Errorf("decode clusters %q: %w", path, err)
	}
	return output, nil
}

// ReadTruth reads the offline labeled truth file.
func ReadTruth(path string) (Truth, error) {
	file, err := os.Open(path)
	if err != nil {
		return Truth{}, fmt.Errorf("open truth %q: %w", path, err)
	}
	defer file.Close()

	var truth Truth
	if err := decodeOneJSON(file, &truth); err != nil {
		return Truth{}, fmt.Errorf("decode truth %q: %w", path, err)
	}
	return truth, nil
}

// Evaluate validates the partition and hard constraints, then computes
// clustering and cost-proxy metrics. Any invalid partition or constraint
// violation is returned as an error because the assignment rejects it
// outright.
func Evaluate(
	clusters domain.ClustersOutput,
	truth Truth,
	constraints []domain.Constraint,
) (Metrics, error) {
	actorMembers, fraudActors, err := validateTruth(truth)
	if err != nil {
		return Metrics{}, err
	}

	clusterByAccount, clusterActorCounts, largestCluster, err :=
		validatePartition(clusters, truth.AccountToActor)
	if err != nil {
		return Metrics{}, err
	}

	violations, err := countConstraintViolations(
		constraints,
		truth.AccountToActor,
		clusterByAccount,
	)
	if err != nil {
		return Metrics{}, err
	}
	if violations != 0 {
		return Metrics{}, fmt.Errorf(
			"constraint validation failed: %d verified-distinct pair(s) share a cluster",
			violations,
		)
	}

	metrics := Metrics{
		AccountCount:         len(truth.AccountToActor),
		ActorCount:           len(actorMembers),
		ClusterCount:         len(clusters.Clusters),
		LargestCluster:       largestCluster,
		ConstraintViolations: violations,
	}

	for _, members := range actorMembers {
		metrics.TruePairCount += chooseTwo(int64(len(members)))
	}

	nonFraudActorsWithFraud := make(map[string]struct{})
	for _, actorCounts := range clusterActorCounts {
		var clusterSize int64
		var largestActorGroup int64
		containsFraud := false
		for actorID, count := range actorCounts {
			n := int64(count)
			clusterSize += n
			metrics.TruePositivePairs += chooseTwo(n)
			if n > largestActorGroup {
				largestActorGroup = n
			}
			if _, fraud := fraudActors[actorID]; fraud {
				containsFraud = true
			}
		}
		metrics.PredictedPairCount += chooseTwo(clusterSize)
		metrics.Impurity += clusterSize - largestActorGroup

		if containsFraud {
			for actorID := range actorCounts {
				if _, fraud := fraudActors[actorID]; !fraud {
					nonFraudActorsWithFraud[actorID] = struct{}{}
				}
			}
		}
	}
	metrics.FalsePositivePairs =
		metrics.PredictedPairCount - metrics.TruePositivePairs
	metrics.FalseNegativePairs =
		metrics.TruePairCount - metrics.TruePositivePairs
	metrics.PairPrecision = safeRatio(
		metrics.TruePositivePairs,
		metrics.PredictedPairCount,
	)
	metrics.PairRecall = safeRatio(
		metrics.TruePositivePairs,
		metrics.TruePairCount,
	)
	metrics.PairF1 = harmonicMean(metrics.PairPrecision, metrics.PairRecall)

	for actorID, members := range actorMembers {
		if len(members) <= 1 {
			continue
		}
		metrics.MultiAccountActorCount++
		fragmented := actorClusterCount(members, clusterByAccount) > 1
		if fragmented {
			metrics.FragmentedMultiAccountActors++
		} else {
			metrics.FullyRecoveredMultiAccountActors++
		}

		if _, fraud := fraudActors[actorID]; fraud {
			metrics.FraudRingCount++
			if fragmented {
				metrics.FragmentedFraudActors++
			} else {
				metrics.FullyRecoveredFraudRings++
			}
		}
	}
	metrics.FraudRingRecovery = safeRatio(
		int64(metrics.FullyRecoveredFraudRings),
		int64(metrics.FraudRingCount),
	)
	metrics.NonFraudActorsWithFraud = len(nonFraudActorsWithFraud)
	metrics.GeneralProxyCost =
		50*metrics.Impurity +
			2000*int64(metrics.FragmentedMultiAccountActors)
	metrics.LiteralProxyCost =
		50*int64(metrics.NonFraudActorsWithFraud) +
			2000*int64(metrics.FragmentedFraudActors)

	return metrics, nil
}

func validateTruth(
	truth Truth,
) (map[string][]string, map[string]struct{}, error) {
	if len(truth.AccountToActor) == 0 {
		return nil, nil, errors.New("truth contains no accounts")
	}

	actorMembers := make(map[string][]string)
	for accountID, actorID := range truth.AccountToActor {
		if strings.TrimSpace(accountID) == "" {
			return nil, nil, errors.New("truth contains an empty account ID")
		}
		if strings.TrimSpace(actorID) == "" {
			return nil, nil, fmt.Errorf(
				"truth account %q has an empty actor ID",
				accountID,
			)
		}
		actorMembers[actorID] = append(actorMembers[actorID], accountID)
	}

	fraudActors := make(map[string]struct{}, len(truth.FraudActorIDs))
	for _, actorID := range truth.FraudActorIDs {
		if strings.TrimSpace(actorID) == "" {
			return nil, nil, errors.New("truth contains an empty fraud actor ID")
		}
		if _, exists := actorMembers[actorID]; !exists {
			return nil, nil, fmt.Errorf(
				"fraud actor %q has no account in truth",
				actorID,
			)
		}
		if _, duplicate := fraudActors[actorID]; duplicate {
			return nil, nil, fmt.Errorf(
				"duplicate fraud actor ID %q",
				actorID,
			)
		}
		fraudActors[actorID] = struct{}{}
	}
	return actorMembers, fraudActors, nil
}

func validatePartition(
	clusters domain.ClustersOutput,
	accountToActor map[string]string,
) (map[string]int, []map[string]int, int, error) {
	clusterByAccount := make(map[string]int, len(accountToActor))
	clusterActorCounts := make([]map[string]int, len(clusters.Clusters))
	clusterIDs := make(map[string]struct{}, len(clusters.Clusters))
	largestCluster := 0

	for clusterIndex, cluster := range clusters.Clusters {
		if strings.TrimSpace(cluster.ClusterID) == "" {
			return nil, nil, 0, fmt.Errorf(
				"cluster at index %d has an empty cluster_id",
				clusterIndex,
			)
		}
		if _, duplicate := clusterIDs[cluster.ClusterID]; duplicate {
			return nil, nil, 0, fmt.Errorf(
				"duplicate cluster_id %q",
				cluster.ClusterID,
			)
		}
		clusterIDs[cluster.ClusterID] = struct{}{}
		if len(cluster.AccountIDs) == 0 {
			return nil, nil, 0, fmt.Errorf(
				"cluster %q contains no accounts",
				cluster.ClusterID,
			)
		}
		if len(cluster.AccountIDs) > largestCluster {
			largestCluster = len(cluster.AccountIDs)
		}

		actorCounts := make(map[string]int)
		for _, accountID := range cluster.AccountIDs {
			if strings.TrimSpace(accountID) == "" {
				return nil, nil, 0, fmt.Errorf(
					"cluster %q contains an empty account ID",
					cluster.ClusterID,
				)
			}
			actorID, known := accountToActor[accountID]
			if !known {
				return nil, nil, 0, fmt.Errorf(
					"cluster %q contains unknown account %q",
					cluster.ClusterID,
					accountID,
				)
			}
			if previous, duplicate := clusterByAccount[accountID]; duplicate {
				return nil, nil, 0, fmt.Errorf(
					"account %q appears more than once (clusters %q and %q)",
					accountID,
					clusters.Clusters[previous].ClusterID,
					cluster.ClusterID,
				)
			}
			clusterByAccount[accountID] = clusterIndex
			actorCounts[actorID]++
		}
		clusterActorCounts[clusterIndex] = actorCounts
	}

	if len(clusterByAccount) != len(accountToActor) {
		missing := make([]string, 0, len(accountToActor)-len(clusterByAccount))
		for accountID := range accountToActor {
			if _, assigned := clusterByAccount[accountID]; !assigned {
				missing = append(missing, accountID)
			}
		}
		sort.Strings(missing)
		return nil, nil, 0, fmt.Errorf(
			"incomplete partition: %d truth account(s) missing: %s",
			len(missing),
			summarizeIDs(missing),
		)
	}
	return clusterByAccount, clusterActorCounts, largestCluster, nil
}

func countConstraintViolations(
	constraints []domain.Constraint,
	accountToActor map[string]string,
	clusterByAccount map[string]int,
) (int, error) {
	seen := make(map[string]struct{}, len(constraints))
	violations := 0
	for _, constraint := range constraints {
		if constraint.Relation != "verified_distinct" {
			return 0, fmt.Errorf(
				"unsupported constraint relation %q for %q and %q",
				constraint.Relation,
				constraint.AccountA,
				constraint.AccountB,
			)
		}
		if constraint.AccountA == constraint.AccountB {
			return 0, fmt.Errorf(
				"self constraint for account %q is unsatisfiable",
				constraint.AccountA,
			)
		}
		_, leftKnown := accountToActor[constraint.AccountA]
		_, rightKnown := accountToActor[constraint.AccountB]
		if !leftKnown || !rightKnown {
			// The production linker deliberately retains future-account
			// constraints. An offline truth file can only evaluate endpoints it
			// actually labels.
			continue
		}

		left, right := constraint.AccountA, constraint.AccountB
		if left > right {
			left, right = right, left
		}
		key := left + "\x00" + right
		if _, duplicate := seen[key]; duplicate {
			continue
		}
		seen[key] = struct{}{}
		if clusterByAccount[left] == clusterByAccount[right] {
			violations++
		}
	}
	return violations, nil
}

func actorClusterCount(
	members []string,
	clusterByAccount map[string]int,
) int {
	clusters := make(map[int]struct{})
	for _, accountID := range members {
		clusters[clusterByAccount[accountID]] = struct{}{}
	}
	return len(clusters)
}

func chooseTwo(value int64) int64 {
	return value * (value - 1) / 2
}

func safeRatio(numerator, denominator int64) float64 {
	if denominator == 0 {
		return 1
	}
	return float64(numerator) / float64(denominator)
}

func harmonicMean(left, right float64) float64 {
	if left+right == 0 {
		return 0
	}
	return 2 * left * right / (left + right)
}

func decodeOneJSON(reader io.Reader, destination any) error {
	decoder := json.NewDecoder(reader)
	if err := decoder.Decode(destination); err != nil {
		return err
	}
	var trailing any
	if err := decoder.Decode(&trailing); err != io.EOF {
		if err == nil {
			return errors.New("multiple JSON values")
		}
		return fmt.Errorf("trailing data: %w", err)
	}
	return nil
}

func summarizeIDs(ids []string) string {
	const limit = 5
	if len(ids) <= limit {
		return strings.Join(ids, ", ")
	}
	return fmt.Sprintf(
		"%s, ... (%d more)",
		strings.Join(ids[:limit], ", "),
		len(ids)-limit,
	)
}
