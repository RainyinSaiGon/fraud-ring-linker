package streaming

import (
	"fmt"
	"net/netip"
	"sort"
	"strings"

	"accountlinker/internal/clustering"
	"accountlinker/internal/domain"
	"accountlinker/internal/normalize"
	"accountlinker/internal/scoring"
)

// Linker performs monotonic online assignment over a live batch State. One
// event can join one existing cluster or create a singleton; it never merges
// two previously-existing clusters.
type Linker struct {
	state *clustering.State
}

func New(state *clustering.State) *Linker {
	return &Linker{state: state}
}

type option struct {
	root         int
	score        float64
	assessment   domain.Assessment
	identitySeed bool
}

// Assign evaluates and then atomically commits one new account.
func (linker *Linker) Assign(account domain.Account) (domain.Assignment, error) {
	account.AccountID = strings.TrimSpace(account.AccountID)
	if account.AccountID == "" {
		return domain.Assignment{}, fmt.Errorf("missing account_id")
	}
	if account.CreatedAt.IsZero() {
		return domain.Assignment{}, fmt.Errorf("missing or invalid created_at for %q", account.AccountID)
	}
	if _, exists := linker.state.Store.ByID[account.AccountID]; exists {
		return domain.Assignment{}, fmt.Errorf("duplicate account_id %q", account.AccountID)
	}

	features := normalize.Account(account)
	candidates := linker.state.Store.CandidatesFor(features, true)
	optionsByRoot := make(map[int]option)

	projectedPrefixSize := 0
	if features.IPPrefix.IsValid() {
		projectedPrefixSize =
			linker.state.Store.Frequencies.IPPrefix[features.IPPrefix] + 1
	}
	distinctRatio := projectedDistinctIPRatio(
		linker.state,
		features,
		projectedPrefixSize,
	)
	for _, candidate := range candidates {
		candidateFeatures := linker.state.Store.Features[candidate]
		burstSize := 0
		if projectedPrefixSize <= linker.state.Config.Scoring.BurstMaxPrefix {
			burstSize = linker.state.Store.BurstSize(
				candidateFeatures,
				features,
				linker.state.Config.Scoring.BurstWindow,
				&features,
			)
		}
		assessment := scoring.Assess(candidateFeatures, features, scoring.Context{
			Frequencies:       linker.state.Store.Frequencies,
			ProjectedIncoming: true,
			BurstSize:         burstSize,
			DistinctIPRatio:   distinctRatio,
		}, linker.state.Config.Scoring)
		if !assessment.Eligible || assessment.Score < linker.state.Config.StreamThreshold {
			continue
		}
		root := linker.state.DSU.Find(candidate)
		current, exists := optionsByRoot[root]
		if !exists || betterAssessment(assessment, current.assessment) {
			optionsByRoot[root] = option{
				root:         root,
				score:        assessment.Score,
				assessment:   assessment,
				identitySeed: assessment.IdentitySeed || current.identitySeed,
			}
		} else if assessment.IdentitySeed && !current.identitySeed {
			current.identitySeed = true
			optionsByRoot[root] = current
		}
	}

	addSeededPrefixOptions(linker.state, features, candidates, distinctRatio, optionsByRoot)

	options := make([]option, 0, len(optionsByRoot))
	for _, candidate := range optionsByRoot {
		options = append(options, candidate)
	}
	sort.Slice(options, func(i, j int) bool {
		if options[i].score != options[j].score {
			return options[i].score > options[j].score
		}
		return clusterNumber(linker.state.ClusterIDs[options[i].root]) <
			clusterNumber(linker.state.ClusterIDs[options[j].root])
	})

	selectedRoot := -1
	selectedScore := 1.0
	selectedIdentitySeed := false
	for _, candidate := range options {
		if linker.canJoin(account.AccountID, candidate.root) {
			selectedRoot = candidate.root
			selectedScore = candidate.score
			selectedIdentitySeed = candidate.identitySeed
			break
		}
	}

	newIndex, err := linker.state.Store.Add(account, features)
	if err != nil {
		return domain.Assignment{}, err
	}
	dsuIndex := linker.state.DSU.Add()
	if newIndex != dsuIndex {
		return domain.Assignment{}, fmt.Errorf("internal stream state index mismatch")
	}

	if selectedRoot < 0 {
		clusterID := fmt.Sprintf("c%d", linker.state.NextClusterNumber)
		linker.state.NextClusterNumber++
		linker.state.ClusterIDs[newIndex] = clusterID
		return domain.Assignment{
			AccountID:  account.AccountID,
			ClusterID:  clusterID,
			Confidence: 1,
		}, nil
	}

	selectedRoot = linker.state.DSU.Find(selectedRoot)
	clusterID := linker.state.ClusterIDs[selectedRoot]
	hadIdentitySeed := linker.state.IdentitySeedRoots[selectedRoot]
	newRoot, _ := linker.state.DSU.Union(selectedRoot, newIndex, selectedScore)
	if newRoot != selectedRoot {
		delete(linker.state.ClusterIDs, selectedRoot)
		delete(linker.state.IdentitySeedRoots, selectedRoot)
	}
	delete(linker.state.ClusterIDs, newIndex)
	linker.state.ClusterIDs[newRoot] = clusterID
	if hadIdentitySeed || selectedIdentitySeed {
		linker.state.IdentitySeedRoots[newRoot] = true
	}

	return domain.Assignment{
		AccountID:  account.AccountID,
		ClusterID:  clusterID,
		Confidence: scoring.RoundConfidence(selectedScore),
	}, nil
}

func betterAssessment(left, right domain.Assessment) bool {
	if left.Score != right.Score {
		return left.Score > right.Score
	}
	return left.Rule < right.Rule
}

func (linker *Linker) canJoin(accountID string, root int) bool {
	for forbiddenID := range linker.state.Constraints.Neighbors(accountID) {
		forbiddenIndex, exists := linker.state.Store.ByID[forbiddenID]
		if !exists {
			continue
		}
		if linker.state.DSU.Find(forbiddenIndex) == root {
			return false
		}
	}
	return true
}

func projectedDistinctIPRatio(
	state *clustering.State,
	incoming domain.Features,
	projectedPrefixSize int,
) float64 {
	if !incoming.IPPrefix.IsValid() {
		return 0
	}
	config := state.Config.Scoring
	maxEligiblePrefixSize := max(
		config.BurstMaxPrefix,
		config.SeededPrefixMaxSize,
	)
	if projectedPrefixSize > maxEligiblePrefixSize {
		return 0
	}
	posting := state.Store.PrefixPosting(incoming.IPPrefix)
	unique := make(map[netip.Addr]struct{}, len(posting)+1)
	for _, index := range posting {
		address := state.Store.Features[index].IP
		if address.IsValid() {
			unique[address] = struct{}{}
		}
	}
	if incoming.IP.IsValid() {
		unique[incoming.IP] = struct{}{}
	}
	return float64(len(unique)) / float64(projectedPrefixSize)
}

func addSeededPrefixOptions(
	state *clustering.State,
	incoming domain.Features,
	candidates []int,
	distinctRatio float64,
	options map[int]option,
) {
	if !incoming.IPPrefix.IsValid() {
		return
	}
	postingSize := len(state.Store.PrefixPosting(incoming.IPPrefix)) + 1
	config := state.Config.Scoring
	if postingSize < config.SeededPrefixMinSize ||
		postingSize > config.SeededPrefixMaxSize ||
		distinctRatio < config.SeededPrefixMinDistinctIPRatio {
		return
	}

	countByRoot := make(map[int]int)
	for _, candidate := range candidates {
		if state.Store.Features[candidate].IPPrefix != incoming.IPPrefix {
			continue
		}
		countByRoot[state.DSU.Find(candidate)]++
	}
	seedRoot := -1
	seedCount := 0
	for root, count := range countByRoot {
		if !state.IdentitySeedRoots[root] {
			continue
		}
		if seedRoot != -1 {
			// Two established identities on one prefix are ambiguous; network
			// evidence must not choose between them.
			return
		}
		seedRoot = root
		seedCount = count
	}
	if seedRoot >= 0 && seedCount >= 2 {
		assessment := scoring.SeededPrefixAssessment(postingSize, seedCount, config)
		if assessment.Score < state.Config.StreamThreshold {
			return
		}
		current, exists := options[seedRoot]
		if !exists || betterAssessment(assessment, current.assessment) {
			options[seedRoot] = option{
				root:         seedRoot,
				score:        assessment.Score,
				assessment:   assessment,
				identitySeed: current.identitySeed,
			}
		}
	}
}

func clusterNumber(clusterID string) int {
	var number int
	_, _ = fmt.Sscanf(clusterID, "c%d", &number)
	return number
}
