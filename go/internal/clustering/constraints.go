package clustering

import (
	"fmt"

	"accountlinker/internal/domain"
)

// Constraints stores symmetric, deduplicated cannot-link adjacency. Unknown
// endpoints remain present so future streaming accounts are protected.
type Constraints struct {
	neighbors map[string]map[string]struct{}
}

func NewConstraints(records []domain.Constraint) (*Constraints, error) {
	result := &Constraints{neighbors: make(map[string]map[string]struct{})}
	for _, record := range records {
		if record.Relation != "verified_distinct" {
			return nil, fmt.Errorf(
				"unsupported relation %q for %q and %q",
				record.Relation, record.AccountA, record.AccountB,
			)
		}
		if record.AccountA == record.AccountB {
			return nil, fmt.Errorf("self constraint for account %q is unsatisfiable", record.AccountA)
		}
		result.add(record.AccountA, record.AccountB)
		result.add(record.AccountB, record.AccountA)
	}
	return result, nil
}

func (constraints *Constraints) add(left, right string) {
	if constraints.neighbors[left] == nil {
		constraints.neighbors[left] = make(map[string]struct{})
	}
	constraints.neighbors[left][right] = struct{}{}
}

func (constraints *Constraints) Neighbors(accountID string) map[string]struct{} {
	if constraints == nil {
		return nil
	}
	return constraints.neighbors[accountID]
}

func (constraints *Constraints) IsDistinct(left, right string) bool {
	_, exists := constraints.Neighbors(left)[right]
	return exists
}
