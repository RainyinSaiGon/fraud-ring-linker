package clustering

import (
	"math"

	"accountlinker/internal/domain"
)

// DSU is a union-find that also retains component members and bottleneck
// confidence so hard component-wide constraints can be checked before union.
type DSU struct {
	parent     []int
	size       []int
	members    [][]int
	confidence []float64
}

func NewDSU(count int) *DSU {
	dsu := &DSU{}
	for i := 0; i < count; i++ {
		dsu.Add()
	}
	return dsu
}

func (dsu *DSU) Add() int {
	index := len(dsu.parent)
	dsu.parent = append(dsu.parent, index)
	dsu.size = append(dsu.size, 1)
	dsu.members = append(dsu.members, []int{index})
	dsu.confidence = append(dsu.confidence, 1)
	return index
}

func (dsu *DSU) Len() int {
	return len(dsu.parent)
}

func (dsu *DSU) Find(index int) int {
	root := index
	for dsu.parent[root] != root {
		root = dsu.parent[root]
	}
	for dsu.parent[index] != index {
		next := dsu.parent[index]
		dsu.parent[index] = root
		index = next
	}
	return root
}

func (dsu *DSU) Members(index int) []int {
	return dsu.members[dsu.Find(index)]
}

func (dsu *DSU) Confidence(index int) float64 {
	return dsu.confidence[dsu.Find(index)]
}

func (dsu *DSU) CanMerge(
	left, right int,
	accounts []domain.Account,
	byID map[string]int,
	constraints *Constraints,
) bool {
	leftRoot := dsu.Find(left)
	rightRoot := dsu.Find(right)
	if leftRoot == rightRoot {
		return true
	}
	smallRoot, otherRoot := leftRoot, rightRoot
	if dsu.size[smallRoot] > dsu.size[otherRoot] {
		smallRoot, otherRoot = otherRoot, smallRoot
	}
	for _, member := range dsu.members[smallRoot] {
		for forbiddenID := range constraints.Neighbors(accounts[member].AccountID) {
			forbiddenIndex, exists := byID[forbiddenID]
			if !exists {
				continue
			}
			if dsu.Find(forbiddenIndex) == otherRoot {
				return false
			}
		}
	}
	return true
}

// Union assumes CanMerge has already approved the operation.
func (dsu *DSU) Union(left, right int, edgeConfidence float64) (int, bool) {
	leftRoot := dsu.Find(left)
	rightRoot := dsu.Find(right)
	if leftRoot == rightRoot {
		return leftRoot, false
	}
	if dsu.size[leftRoot] < dsu.size[rightRoot] {
		leftRoot, rightRoot = rightRoot, leftRoot
	}
	dsu.parent[rightRoot] = leftRoot
	dsu.size[leftRoot] += dsu.size[rightRoot]
	dsu.members[leftRoot] = append(dsu.members[leftRoot], dsu.members[rightRoot]...)
	dsu.members[rightRoot] = nil
	dsu.confidence[leftRoot] = math.Min(
		math.Min(dsu.confidence[leftRoot], dsu.confidence[rightRoot]),
		edgeConfidence,
	)
	return leftRoot, true
}
