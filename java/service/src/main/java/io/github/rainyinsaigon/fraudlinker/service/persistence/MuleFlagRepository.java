package io.github.rainyinsaigon.fraudlinker.service.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/** When each held account was first flagged as a likely mule. */
@Repository
public class MuleFlagRepository {
    private final JdbcClient jdbc;

    public MuleFlagRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records the first flag for an account.
     *
     * @param reasons the reason codes, comma-separated, for example {@code fan_in,pass_through}
     */
    public void insert(String accountId, Instant flaggedAt, double score, String reasons) {
        jdbc.sql("""
                        INSERT INTO mule_flags (account_id, flagged_at, score, reasons)
                        VALUES (:account, :flaggedAt, :score, :reasons)
                        """)
                .param("account", accountId)
                .param("flaggedAt", AccountRepository.utc(flaggedAt))
                .param("score", score)
                .param("reasons", reasons)
                .update();
    }

    /** First-flag times for whichever of these accounts have been flagged. */
    public Map<String, Instant> findFlaggedAt(Collection<String> accountIds) {
        Map<String, Instant> flagged = new HashMap<>();
        if (accountIds.isEmpty()) {
            return flagged;
        }
        jdbc.sql("SELECT account_id, flagged_at FROM mule_flags WHERE account_id IN (:ids)")
                .param("ids", accountIds)
                .query((row, n) -> Map.entry(row.getString("account_id"),
                        row.getObject("flagged_at", OffsetDateTime.class).toInstant()))
                .list()
                .forEach(entry -> flagged.put(entry.getKey(), entry.getValue()));
        return flagged;
    }
}
