package io.github.rainyinsaigon.fraudlinker.service.persistence;

import io.github.rainyinsaigon.fraudlinker.service.risk.AnalystDecision;
import io.github.rainyinsaigon.fraudlinker.service.risk.Outcome;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class DecisionRepository {
    private final JdbcClient jdbc;

    public DecisionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(AnalystDecision decision) {
        jdbc.sql("""
                        INSERT INTO analyst_decisions (decision_id, cluster_id, outcome, analyst, note, decided_at)
                        VALUES (:id, :clusterId, :outcome, :analyst, :note, :decidedAt)
                        """)
                .param("id", decision.decisionId())
                .param("clusterId", decision.clusterId())
                .param("outcome", decision.outcome().name())
                .param("analyst", decision.analyst())
                .param("note", decision.note())
                .param("decidedAt", AccountRepository.utc(decision.decidedAt()))
                .update();
        for (String accountId : decision.accountIds()) {
            jdbc.sql("INSERT INTO decision_accounts (decision_id, account_id) VALUES (:id, :accountId)")
                    .param("id", decision.decisionId())
                    .param("accountId", accountId)
                    .update();
        }
    }

    /** The most recent decision that covered any of these accounts. */
    public Optional<AnalystDecision> findLatestCovering(Collection<String> accountIds) {
        return findCovering(accountIds, 1).stream().findFirst();
    }

    /** Decisions that covered any of these accounts, newest first. */
    public List<AnalystDecision> findCovering(Collection<String> accountIds, int limit) {
        if (accountIds.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = jdbc.sql("""
                        SELECT d.decision_id
                        FROM analyst_decisions d
                        WHERE d.decision_id IN (SELECT da.decision_id FROM decision_accounts da WHERE da.account_id IN (:accountIds))
                        ORDER BY d.decided_at DESC, d.decision_id
                        LIMIT :limit
                        """)
                .param("accountIds", accountIds)
                .param("limit", limit)
                .query((row, n) -> row.getObject("decision_id", UUID.class))
                .list();
        return load(ids);
    }

    private List<AnalystDecision> load(List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<String>> accountsByDecision = new LinkedHashMap<>();
        ids.forEach(id -> accountsByDecision.put(id, new ArrayList<>()));
        jdbc.sql("SELECT decision_id, account_id FROM decision_accounts WHERE decision_id IN (:ids) ORDER BY account_id")
                .param("ids", ids)
                .query((row, n) -> {
                    accountsByDecision.get(row.getObject("decision_id", UUID.class)).add(row.getString("account_id"));
                    return null;
                })
                .list();
        Map<UUID, AnalystDecision> byId = new LinkedHashMap<>();
        jdbc.sql("SELECT * FROM analyst_decisions WHERE decision_id IN (:ids)")
                .param("ids", ids)
                .query((row, n) -> map(row, accountsByDecision))
                .list()
                .forEach(decision -> byId.put(decision.decisionId(), decision));
        return ids.stream().map(byId::get).toList();
    }

    private static AnalystDecision map(ResultSet row, Map<UUID, List<String>> accounts) throws SQLException {
        UUID id = row.getObject("decision_id", UUID.class);
        return new AnalystDecision(
                id,
                row.getString("cluster_id"),
                Outcome.valueOf(row.getString("outcome")),
                row.getString("analyst"),
                row.getString("note"),
                row.getObject("decided_at", OffsetDateTime.class).toInstant(),
                accounts.get(id));
    }
}
