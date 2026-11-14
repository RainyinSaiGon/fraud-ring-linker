package io.github.rainyinsaigon.fraudlinker.service.app;

import io.github.rainyinsaigon.fraudlinker.clustering.LinkState;
import io.github.rainyinsaigon.fraudlinker.clustering.LinkState.MergeOutcome;
import io.github.rainyinsaigon.fraudlinker.domain.Assignment;
import io.github.rainyinsaigon.fraudlinker.domain.MergeCandidate;
import io.github.rainyinsaigon.fraudlinker.service.app.MergeProposal.Status;
import io.github.rainyinsaigon.fraudlinker.service.engine.ClusterMerger;
import io.github.rainyinsaigon.fraudlinker.service.engine.LinkerEngine;
import io.github.rainyinsaigon.fraudlinker.service.persistence.AuditRepository;
import io.github.rainyinsaigon.fraudlinker.service.persistence.DecisionRepository;
import io.github.rainyinsaigon.fraudlinker.service.persistence.MergeProposalRepository;
import io.github.rainyinsaigon.fraudlinker.service.risk.RiskAssessment;
import io.github.rainyinsaigon.fraudlinker.service.risk.RiskPolicy;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Merge proposals: clusters that a new account linked strongly to, besides the
 * one it joined. Streaming never merges clusters by itself; an analyst decides.
 */
@Service
public class MergeProposalService {
    private final LinkerEngine engine;
    private final WriteThrough writeThrough;
    private final MergeProposalRepository proposals;
    private final DecisionRepository decisions;
    private final AuditRepository audit;
    private final Clock clock;

    public MergeProposalService(LinkerEngine engine, WriteThrough writeThrough, MergeProposalRepository proposals,
                                DecisionRepository decisions, AuditRepository audit, Clock clock) {
        this.engine = engine;
        this.writeThrough = writeThrough;
        this.proposals = proposals;
        this.decisions = decisions;
        this.audit = audit;
        this.clock = clock;
    }

    /** A proposal plus the clusters its two sides are in now (null if unknown). */
    public record ProposalView(MergeProposal proposal, String currentClusterA, String currentClusterB) {
    }

    public record AcceptResult(ProposalView proposal, boolean merged, String clusterId, int clusterSize,
                               RiskAssessment risk) {
    }

    /**
     * Stores one proposal per merge candidate, unless an open or rejected proposal
     * already covers the same two clusters. Caller must be inside {@link WriteThrough}.
     *
     * @return the proposals created
     */
    List<MergeProposal> propose(LinkState state, Assignment assignment, List<MergeCandidate> candidates, Instant now) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<MergeProposal> known = proposals.findNotAccepted();
        List<MergeProposal> created = new ArrayList<>();
        List<String> joined = state.memberIds(assignment.clusterId());
        for (MergeCandidate candidate : candidates) {
            Set<String> pair = Set.of(assignment.clusterId(), candidate.clusterId());
            boolean alreadyProposed = known.stream().anyMatch(p -> pair.equals(currentPair(state, p)));
            if (alreadyProposed) {
                continue;
            }
            MergeProposal proposal = new MergeProposal(UUID.randomUUID(), Status.OPEN, assignment.accountId(),
                    assignment.clusterId(), candidate.clusterId(), candidate.score(), candidate.rule(), now,
                    joined, state.memberIds(candidate.clusterId()), null, null, null);
            proposals.insert(proposal);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("proposal_id", proposal.proposalId().toString());
            details.put("other_cluster_id", candidate.clusterId());
            details.put("score", candidate.score());
            details.put("rule", candidate.rule());
            audit.record(now, AuditRepository.MERGE_PROPOSED, assignment.accountId(), assignment.clusterId(),
                    AuditRepository.SYSTEM, details);
            created.add(proposal);
        }
        return created;
    }

    private static Set<String> currentPair(LinkState state, MergeProposal proposal) {
        String a = currentCluster(state, proposal.accountsA());
        String b = currentCluster(state, proposal.accountsB());
        return a == null || b == null ? Set.of() : Set.of(a, b);
    }

    private static String currentCluster(LinkState state, List<String> accountIds) {
        return accountIds.isEmpty() ? null : state.clusterIdOf(accountIds.get(0)).orElse(null);
    }

    private static ProposalView view(LinkState state, MergeProposal proposal) {
        return new ProposalView(proposal, currentCluster(state, proposal.accountsA()),
                currentCluster(state, proposal.accountsB()));
    }

    /** Proposals with this status, oldest first. */
    public List<ProposalView> list(Status status, int limit) {
        return engine.withLock(session -> proposals.findByStatus(status, limit).stream()
                .map(proposal -> view(session.state(), proposal))
                .toList());
    }

    /**
     * Merges the clusters that the proposal's accounts are in now, and marks it accepted.
     *
     * @throws NotFoundException if the proposal is unknown
     * @throws ConflictException if it is not open, or the merge would put a verified-distinct pair together
     */
    public AcceptResult accept(UUID proposalId, String analyst, String note) {
        return writeThrough.execute(session -> {
            LinkState state = session.state();
            MergeProposal proposal = openProposal(proposalId);
            List<String> accounts = new ArrayList<>(proposal.accountsA());
            accounts.addAll(proposal.accountsB());
            MergeOutcome outcome = ClusterMerger.mergeAccounts(state, accounts, proposal.score());
            if (outcome == MergeOutcome.CONFLICTS_WITH_CONSTRAINT) {
                throw new ConflictException("Merging these clusters would link accounts verified as different people. "
                        + "Reject the proposal instead.");
            }
            Instant now = clock.instant();
            proposals.decide(proposalId, Status.ACCEPTED, analyst, note, now);

            String clusterId = currentCluster(state, proposal.accountsA());
            List<String> members = state.memberIds(clusterId);
            RiskAssessment risk = RiskPolicy.assess(members, decisions.findLatestCovering(members));
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("proposal_id", proposalId.toString());
            details.put("merged", outcome == MergeOutcome.MERGED);
            details.put("cluster_size", members.size());
            details.put("risk_action", risk.action().name());
            audit.record(now, AuditRepository.MERGE_ACCEPTED, null, clusterId, analyst, details);

            MergeProposal decided = proposals.findById(proposalId).orElseThrow();
            return new AcceptResult(view(state, decided), outcome == MergeOutcome.MERGED, clusterId, members.size(),
                    risk);
        });
    }

    /**
     * Marks the proposal rejected. The clusters stay as they are, and the same pair is not proposed again.
     *
     * @throws NotFoundException if the proposal is unknown
     * @throws ConflictException if it is not open
     */
    public ProposalView reject(UUID proposalId, String analyst, String note) {
        return writeThrough.execute(session -> {
            openProposal(proposalId);
            Instant now = clock.instant();
            proposals.decide(proposalId, Status.REJECTED, analyst, note, now);
            audit.record(now, AuditRepository.MERGE_REJECTED, null, null, analyst,
                    Map.of("proposal_id", proposalId.toString()));
            return view(session.state(), proposals.findById(proposalId).orElseThrow());
        });
    }

    private MergeProposal openProposal(UUID proposalId) {
        MergeProposal proposal = proposals.findById(proposalId)
                .orElseThrow(() -> new NotFoundException("Merge proposal " + proposalId + " not found."));
        if (proposal.status() != Status.OPEN) {
            throw new ConflictException("Merge proposal " + proposalId + " is already "
                    + proposal.status().name().toLowerCase(java.util.Locale.ROOT) + ".");
        }
        return proposal;
    }
}
