package io.github.rainyinsaigon.fraudlinker.service.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Append-only audit log. There are deliberately no update or delete methods,
 * and on PostgreSQL a trigger rejects them too.
 */
@Repository
public class AuditRepository {
    /** Event types written by the service. */
    public static final String ACCOUNT_LINKED = "account.linked";
    public static final String DECISION_RECORDED = "decision.recorded";
    public static final String CONSTRAINT_ADDED = "constraint.added";

    /** Actor for automated decisions made by the engine. */
    public static final String SYSTEM = "system";

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public AuditRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** One stored audit event; {@code details} is a JSON object. */
    public record AuditEvent(long eventId, Instant occurredAt, String eventType, String accountId, String clusterId,
                             String actor, String details) {
    }

    public void record(Instant occurredAt, String eventType, String accountId, String clusterId, String actor,
                       Map<String, ?> details) {
        jdbc.sql("""
                        INSERT INTO audit_events (occurred_at, event_type, account_id, cluster_id, actor, details)
                        VALUES (:occurredAt, :eventType, :accountId, :clusterId, :actor, :details)
                        """)
                .param("occurredAt", AccountRepository.utc(occurredAt))
                .param("eventType", eventType)
                .param("accountId", accountId)
                .param("clusterId", clusterId)
                .param("actor", actor)
                .param("details", json.writeValueAsString(details))
                .update();
    }

    /** Most recent events first, optionally filtered by account and/or cluster. */
    public List<AuditEvent> find(String accountId, String clusterId, int limit) {
        return jdbc.sql("""
                        SELECT * FROM audit_events
                        WHERE (CAST(:accountId AS VARCHAR(128)) IS NULL OR account_id = :accountId)
                          AND (CAST(:clusterId AS VARCHAR(32)) IS NULL OR cluster_id = :clusterId)
                        ORDER BY event_id DESC
                        LIMIT :limit
                        """)
                .param("accountId", accountId)
                .param("clusterId", clusterId)
                .param("limit", limit)
                .query((row, n) -> new AuditEvent(
                        row.getLong("event_id"),
                        row.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                        row.getString("event_type"),
                        row.getString("account_id"),
                        row.getString("cluster_id"),
                        row.getString("actor"),
                        row.getString("details")))
                .list();
    }
}
