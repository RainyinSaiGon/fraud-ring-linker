package clustering

import (
	"fmt"
	"net/netip"
	"sort"
	"strings"

	"accountlinker/internal/domain"
	linkindex "accountlinker/internal/index"
	"accountlinker/internal/normalize"
	"accountlinker/internal/scoring"
)

// Config keeps the complete operating point in one immutable value.
type Config struct {
	Index           linkindex.Config
	Scoring         scoring.Config
	StreamThreshold float64
}

func DefaultConfig() Config {
	return Config{
		Index:           linkindex.DefaultConfig(),
		Scoring:         scoring.DefaultConfig(),
		StreamThreshold: 0.82,
	}
}

type edge struct {
	A          int
	B          int
	Assessment domain.Assessment
}

// State is the live batch result reused by the streaming linker.
type State struct {
	Store       *linkindex.Store
	DSU         *DSU
	Constraints *Constraints
	Config      Config

	ClusterIDs        map[int]string
	IdentitySeedRoots map[int]bool
	NextClusterNumber int
}

// Build runs sparse candidate generation, evidence scoring, strongest-first
// constraint-aware union, and deterministic cluster-ID assignment.
func Build(accounts []domain.Account, constraints *Constraints, config Config) (*State, error) {
	if constraints == nil {
		constraints, _ = NewConstraints(nil)
	}
	store := linkindex.New(config.Index)
	for position, account := range accounts {
		account.AccountID = strings.TrimSpace(account.AccountID)
		if err := validateAccount(account); err != nil {
			return nil, fmt.Errorf("account %d: %w", position+1, err)
		}
		if _, err := store.Add(account, normalize.Account(account)); err != nil {
			return nil, err
		}
	}

	baseEdges := scoreCandidateEdges(store, config)
	sortEdges(store, baseEdges)

	// A provisional constrained pass over non-network edges establishes which
	// small cohorts contain a real strong-identity seed. A velocity burst is
	// deliberately not allowed to bootstrap long-lived prefix expansion.
	identityProvisional := NewDSU(len(accounts))
	var identityEdges []edge
	for _, candidate := range baseEdges {
		if candidate.Assessment.IdentitySeed {
			identityEdges = append(identityEdges, candidate)
		}
	}
	applyEdges(identityProvisional, identityEdges, store, constraints)

	allEdges := append([]edge(nil), baseEdges...)
	allEdges = append(allEdges, seededPrefixEdges(store, identityProvisional, config)...)
	allEdges = deduplicateEdges(allEdges)
	sortEdges(store, allEdges)

	dsu := NewDSU(len(accounts))
	applyEdges(dsu, allEdges, store, constraints)

	state := &State{
		Store:             store,
		DSU:               dsu,
		Constraints:       constraints,
		Config:            config,
		ClusterIDs:        make(map[int]string),
		IdentitySeedRoots: make(map[int]bool),
	}
	for index := range store.Accounts {
		if len(identityProvisional.Members(index)) >= 2 {
			state.IdentitySeedRoots[dsu.Find(index)] = true
		}
	}
	state.assignInitialClusterIDs()
	if err := state.ValidateInvariants(); err != nil {
		return nil, err
	}
	return state, nil
}

func validateAccount(account domain.Account) error {
	if strings.TrimSpace(account.AccountID) == "" {
		return fmt.Errorf("missing account_id")
	}
	if account.CreatedAt.IsZero() {
		return fmt.Errorf("missing or invalid created_at for %q", account.AccountID)
	}
	return nil
}

func scoreCandidateEdges(store *linkindex.Store, config Config) []edge {
	pairs := store.CandidatePairs()
	edges := make([]edge, 0, len(pairs))
	for _, pair := range pairs {
		left := store.Features[pair.A]
		right := store.Features[pair.B]
		burstSize := 0
		distinctRatio := 0.0
		if left.IPPrefix.IsValid() && left.IPPrefix == right.IPPrefix {
			// Cohort rules are deliberately unavailable on large prefixes.
			// Check the O(1) frequency first so an identity edge that happens
			// to sit behind a carrier/office prefix never scans and sorts that
			// entire posting merely to reject network evidence.
			prefixFrequency := store.Frequencies.IPPrefix[left.IPPrefix]
			if prefixFrequency <= config.Scoring.BurstMaxPrefix {
				posting := store.PrefixPosting(left.IPPrefix)
				burstSize = store.BurstSize(
					left,
					right,
					config.Scoring.BurstWindow,
					nil,
				)
				distinctRatio = store.DistinctIPRatio(posting)
			}
		}
		assessment := scoring.Assess(left, right, scoring.Context{
			Frequencies:     store.Frequencies,
			BurstSize:       burstSize,
			DistinctIPRatio: distinctRatio,
		}, config.Scoring)
		if assessment.Eligible {
			edges = append(edges, edge{A: pair.A, B: pair.B, Assessment: assessment})
		}
	}
	return edges
}

func seededPrefixEdges(store *linkindex.Store, provisional *DSU, config Config) []edge {
	var result []edge
	prefixes := make([]netip.Prefix, 0, len(store.ByIPPrefix))
	for prefix := range store.ByIPPrefix {
		prefixes = append(prefixes, prefix)
	}
	sort.Slice(prefixes, func(i, j int) bool {
		return prefixes[i].String() < prefixes[j].String()
	})

	for _, prefix := range prefixes {
		posting := store.PrefixPosting(prefix)
		if len(posting) < config.Scoring.SeededPrefixMinSize ||
			len(posting) > config.Scoring.SeededPrefixMaxSize ||
			store.DistinctIPRatio(posting) < config.Scoring.SeededPrefixMinDistinctIPRatio {
			continue
		}

		countByRoot := make(map[int]int)
		membersByRoot := make(map[int][]int)
		for _, accountIndex := range posting {
			root := provisional.Find(accountIndex)
			countByRoot[root]++
			membersByRoot[root] = append(membersByRoot[root], accountIndex)
		}
		seedRoot := -1
		establishedRoots := 0
		for root, count := range countByRoot {
			if len(provisional.Members(root)) < 2 {
				continue
			}
			establishedRoots++
			if count >= 2 {
				seedRoot = root
			}
		}
		// Expansion is safe only when exactly one established identity
		// touches the prefix and at least two of its members are in the
		// cohort. Even one prefix member from a second established identity
		// makes the network context ambiguous.
		if establishedRoots != 1 || seedRoot < 0 {
			continue
		}

		seedMembers := membersByRoot[seedRoot]
		sort.Slice(seedMembers, func(i, j int) bool {
			return store.Accounts[seedMembers[i]].AccountID < store.Accounts[seedMembers[j]].AccountID
		})
		representative := seedMembers[0]
		assessment := scoring.SeededPrefixAssessment(len(posting), len(seedMembers), config.Scoring)
		for _, candidate := range posting {
			if provisional.Find(candidate) == seedRoot {
				continue
			}
			result = append(result, edge{
				A:          representative,
				B:          candidate,
				Assessment: assessment,
			})
		}
	}
	return result
}

func deduplicateEdges(edges []edge) []edge {
	best := make(map[linkindex.Pair]edge, len(edges))
	for _, candidate := range edges {
		pair := linkindex.Pair{A: candidate.A, B: candidate.B}
		if pair.A > pair.B {
			pair.A, pair.B = pair.B, pair.A
		}
		candidate.A, candidate.B = pair.A, pair.B
		current, exists := best[pair]
		if !exists || candidate.Assessment.Score > current.Assessment.Score {
			best[pair] = candidate
		}
	}
	result := make([]edge, 0, len(best))
	for _, candidate := range best {
		result = append(result, candidate)
	}
	return result
}

func sortEdges(store *linkindex.Store, edges []edge) {
	sort.SliceStable(edges, func(i, j int) bool {
		if edges[i].Assessment.Score != edges[j].Assessment.Score {
			return edges[i].Assessment.Score > edges[j].Assessment.Score
		}
		leftA, leftB := edgeIDs(store, edges[i])
		rightA, rightB := edgeIDs(store, edges[j])
		if leftA != rightA {
			return leftA < rightA
		}
		return leftB < rightB
	})
}

func edgeIDs(store *linkindex.Store, candidate edge) (string, string) {
	left := store.Accounts[candidate.A].AccountID
	right := store.Accounts[candidate.B].AccountID
	if left > right {
		return right, left
	}
	return left, right
}

func applyEdges(dsu *DSU, edges []edge, store *linkindex.Store, constraints *Constraints) {
	for _, candidate := range edges {
		if dsu.Find(candidate.A) == dsu.Find(candidate.B) {
			continue
		}
		if !dsu.CanMerge(candidate.A, candidate.B, store.Accounts, store.ByID, constraints) {
			continue
		}
		dsu.Union(candidate.A, candidate.B, candidate.Assessment.Score)
	}
}

func (state *State) assignInitialClusterIDs() {
	type component struct {
		root    int
		members []string
	}
	componentsByRoot := make(map[int][]string)
	for index, account := range state.Store.Accounts {
		root := state.DSU.Find(index)
		componentsByRoot[root] = append(componentsByRoot[root], account.AccountID)
	}
	components := make([]component, 0, len(componentsByRoot))
	for root, members := range componentsByRoot {
		sort.Strings(members)
		components = append(components, component{root: root, members: members})
	}
	sort.Slice(components, func(i, j int) bool {
		return components[i].members[0] < components[j].members[0]
	})
	for index, component := range components {
		state.ClusterIDs[component.root] = fmt.Sprintf("c%d", index+1)
	}
	state.NextClusterNumber = len(components) + 1
}

// Clusters returns the complete deterministic current partition.
func (state *State) Clusters() domain.ClustersOutput {
	type component struct {
		root    int
		id      string
		members []string
	}
	componentsByRoot := make(map[int][]string)
	for index, account := range state.Store.Accounts {
		root := state.DSU.Find(index)
		componentsByRoot[root] = append(componentsByRoot[root], account.AccountID)
	}
	components := make([]component, 0, len(componentsByRoot))
	for root, members := range componentsByRoot {
		sort.Strings(members)
		components = append(components, component{
			root:    root,
			id:      state.ClusterIDs[root],
			members: members,
		})
	}
	sort.Slice(components, func(i, j int) bool {
		return clusterNumber(components[i].id) < clusterNumber(components[j].id)
	})
	output := domain.ClustersOutput{Clusters: make([]domain.Cluster, 0, len(components))}
	for _, component := range components {
		output.Clusters = append(output.Clusters, domain.Cluster{
			ClusterID:  component.id,
			AccountIDs: component.members,
			Confidence: scoring.RoundConfidence(state.DSU.Confidence(component.root)),
		})
	}
	return output
}

func clusterNumber(clusterID string) int {
	var number int
	_, _ = fmt.Sscanf(clusterID, "c%d", &number)
	return number
}

// ValidateInvariants checks the hard partition/constraint contract.
func (state *State) ValidateInvariants() error {
	if state.DSU.Len() != len(state.Store.Accounts) {
		return fmt.Errorf("internal partition size mismatch")
	}
	seenRoots := make(map[int]struct{})
	seenClusterIDs := make(map[string]int)
	for index := range state.Store.Accounts {
		root := state.DSU.Find(index)
		if _, seen := seenRoots[root]; seen {
			continue
		}
		seenRoots[root] = struct{}{}
		clusterID := state.ClusterIDs[root]
		if clusterID == "" {
			return fmt.Errorf("component rooted at %d has no cluster ID", root)
		}
		if previousRoot, duplicate := seenClusterIDs[clusterID]; duplicate {
			return fmt.Errorf(
				"cluster ID %q is assigned to roots %d and %d",
				clusterID, previousRoot, root,
			)
		}
		seenClusterIDs[clusterID] = root
	}
	for accountID, forbidden := range state.Constraints.neighbors {
		left, leftExists := state.Store.ByID[accountID]
		if !leftExists {
			continue
		}
		for forbiddenID := range forbidden {
			right, rightExists := state.Store.ByID[forbiddenID]
			if !rightExists {
				continue
			}
			if state.DSU.Find(left) == state.DSU.Find(right) {
				return fmt.Errorf(
					"constraint invariant violated: %q and %q share a cluster",
					accountID, forbiddenID,
				)
			}
		}
	}
	return nil
}
