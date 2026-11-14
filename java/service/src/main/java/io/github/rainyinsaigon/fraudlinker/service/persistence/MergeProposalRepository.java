package io.github.rainyinsaigon.fraudlinker.service.persistence;

import io.github.rainyinsaigon.fraudlinker.service.app.MergeProposal;
import io.github.rainyinsaigon.fraudlinker.service.app.MergeProposal.Status;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MergeProposalRepository {
    private final JdbcClient jdbc;

    public MergeProposalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(MergeProposal proposal) {
        jdbc.sql("""
                        INSERT INTO merge_proposals (proposal_id, status, trigger_account_id, cluster_a, cluster_b,
                                                     score, rule, proposed_at)
                        VALUES (:id, :status, :trigger, :clusterA, :clusterB, :score, :rule, :proposedAt)
                        """)
                .param("id", proposal.proposalId())
                .param("status", proposal.status().name())
                .param("trigger", proposal.triggerAccountId())
                .param("clusterA", proposal.clusterA())
                .param("clusterB", proposal.clusterB())
                .param("score", proposal.score())
                .param("rule", proposal.rule())
                .param("proposedAt", AccountRepository.utc(proposal.proposedAt()))
                .update();
        insertSide(proposal.proposalId(), "A", proposal.accountsA());
        insertSide(proposal.proposalId(), "B", proposal.accountsB());
    }

    private void insertSide(UUID id, String side, List<String> accountIds) {
        for (String accountId : accountIds) {
            jdbc.sql("INSERT INTO merge_proposal_accounts (proposal_id, side, account_id) VALUES (:id, :side, :account)")
                    .param("id", id)
                    .param("side", side)
                    .param("account", accountId)
                    .update();
        }
    }

    /**
     * Records a decision on an open proposal.
     *
     * @return false if the proposal was not open, in which case nothing changed
     */
    public boolean decide(UUID id, Status status, String analyst, String note, Instant decidedAt) {
        return jdbc.sql("""
                        UPDATE merge_proposals
                        SET status = :status, decided_by = :analyst, note = :note, decided_at = :decidedAt
                        WHERE proposal_id = :id AND status = 'OPEN'
                        """)
                .param("status", status.name())
                .param("analyst", analyst)
                .param("note", note)
                .param("decidedAt", AccountRepository.utc(decidedAt))
                .param("id", id)
                .update() == 1;
    }

    public Optional<MergeProposal> findById(UUID id) {
        return load(List.of(id)).stream().findFirst();
    }

    /** Proposals with this status, oldest first. */
    public List<MergeProposal> findByStatus(Status status, int limit) {
        return load(jdbc.sql("""
                        SELECT proposal_id FROM merge_proposals WHERE status = :status
                        ORDER BY proposed_at, proposal_id
                        LIMIT :limit
                        """)
                .param("status", status.name())
                .param("limit", limit)
                .query((row, n) -> row.getObject("proposal_id", UUID.class))
                .list());
    }

    /** Every accepted proposal, in the order it was accepted: the order to replay merges in. */
    public List<MergeProposal> findAcceptedInDecisionOrder() {
        return load(jdbc.sql("""
                        SELECT proposal_id FROM merge_proposals WHERE status = 'ACCEPTED'
                        ORDER BY decided_at, proposal_id
                        """)
                .query((row, n) -> row.getObject("proposal_id", UUID.class))
                .list());
    }

    /** Open and rejected proposals, used to avoid proposing the same merge twice. */
    public List<MergeProposal> findNotAccepted() {
        return load(jdbc.sql("SELECT proposal_id FROM merge_proposals WHERE status IN ('OPEN', 'REJECTED')")
                .query((row, n) -> row.getObject("proposal_id", UUID.class))
                .list());
    }

    private List<MergeProposal> load(List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<String>> sideA = new LinkedHashMap<>();
        Map<UUID, List<String>> sideB = new LinkedHashMap<>();
        for (UUID id : ids) {
            sideA.put(id, new ArrayList<>());
            sideB.put(id, new ArrayList<>());
        }
        jdbc.sql("""
                        SELECT proposal_id, side, account_id FROM merge_proposal_accounts
                        WHERE proposal_id IN (:ids)
                        ORDER BY account_id
                        """)
                .param("ids", ids)
                .query((row, n) -> {
                    UUID id = row.getObject("proposal_id", UUID.class);
                    Map<UUID, List<String>> side = "A".equals(row.getString("side")) ? sideA : sideB;
                    side.get(id).add(row.getString("account_id"));
                    return null;
                })
                .list();
        Map<UUID, MergeProposal> byId = new LinkedHashMap<>();
        jdbc.sql("SELECT * FROM merge_proposals WHERE proposal_id IN (:ids)")
                .param("ids", ids)
                .query((row, n) -> map(row, sideA, sideB))
                .list()
                .forEach(proposal -> byId.put(proposal.proposalId(), proposal));
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    private static MergeProposal map(ResultSet row, Map<UUID, List<String>> sideA, Map<UUID, List<String>> sideB)
            throws SQLException {
        UUID id = row.getObject("proposal_id", UUID.class);
        OffsetDateTime decidedAt = row.getObject("decided_at", OffsetDateTime.class);
        return new MergeProposal(
                id,
                Status.valueOf(row.getString("status")),
                row.getString("trigger_account_id"),
                row.getString("cluster_a"),
                row.getString("cluster_b"),
                row.getDouble("score"),
                row.getString("rule"),
                row.getObject("proposed_at", OffsetDateTime.class).toInstant(),
                sideA.get(id),
                sideB.get(id),
                row.getString("decided_by"),
                row.getString("note"),
                decidedAt == null ? null : decidedAt.toInstant());
    }
}
