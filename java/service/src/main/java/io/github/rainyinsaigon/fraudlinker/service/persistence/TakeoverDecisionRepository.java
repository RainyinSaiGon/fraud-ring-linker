package io.github.rainyinsaigon.fraudlinker.service.persistence;

import io.github.rainyinsaigon.fraudlinker.service.risk.TakeoverDecision;
import io.github.rainyinsaigon.fraudlinker.service.risk.TakeoverOutcome;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Analysts' takeover decisions, one account each. */
@Repository
public class TakeoverDecisionRepository {
    private final JdbcClient jdbc;

    public TakeoverDecisionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(TakeoverDecision decision) {
        jdbc.sql("""
                        INSERT INTO takeover_decisions (decision_id, account_id, outcome, analyst, note, decided_at)
                        VALUES (:id, :account, :outcome, :analyst, :note, :decidedAt)
                        """)
                .param("id", decision.decisionId())
                .param("account", decision.accountId())
                .param("outcome", decision.outcome().name())
                .param("analyst", decision.analyst())
                .param("note", decision.note())
                .param("decidedAt", AccountRepository.utc(decision.decidedAt()))
                .update();
    }

    /** The account's most recent takeover decision. */
    public Optional<TakeoverDecision> findLatest(String accountId) {
        return findByAccount(accountId, 1).stream().findFirst();
    }

    /** The account's takeover decisions, newest first. */
    public List<TakeoverDecision> findByAccount(String accountId, int limit) {
        return jdbc.sql("""
                        SELECT * FROM takeover_decisions
                        WHERE account_id = :account
                        ORDER BY decided_at DESC, decision_id
                        LIMIT :limit
                        """)
                .param("account", accountId)
                .param("limit", limit)
                .query(TakeoverDecisionRepository::map)
                .list();
    }

    private static TakeoverDecision map(ResultSet row, int rowNumber) throws SQLException {
        return new TakeoverDecision(
                row.getObject("decision_id", UUID.class),
                row.getString("account_id"),
                TakeoverOutcome.valueOf(row.getString("outcome")),
                row.getString("analyst"),
                row.getString("note"),
                row.getObject("decided_at", OffsetDateTime.class).toInstant());
    }
}
