package io.github.rainyinsaigon.fraudlinker.service.persistence;

import io.github.rainyinsaigon.fraudlinker.mules.Transfer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Repository
public class TransferRepository {
    private final JdbcClient jdbc;

    public TransferRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts a transfer. A duplicate {@code transfer_id} fails on the unique constraint. */
    public void insert(Transfer transfer, Instant receivedAt) {
        jdbc.sql("""
                        INSERT INTO transfers (transfer_id, from_account, to_account, amount_cents, occurred_at,
                                               received_at)
                        VALUES (:id, :from, :to, :amount, :occurredAt, :receivedAt)
                        """)
                .param("id", transfer.transferId())
                .param("from", transfer.fromAccount())
                .param("to", transfer.toAccount())
                .param("amount", transfer.amountCents())
                .param("occurredAt", AccountRepository.utc(transfer.occurredAt()))
                .param("receivedAt", AccountRepository.utc(receivedAt))
                .update();
    }

    /** Which of these transfer IDs are already stored. */
    public Set<String> findExistingIds(Collection<String> transferIds) {
        if (transferIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("SELECT transfer_id FROM transfers WHERE transfer_id IN (:ids)")
                .param("ids", transferIds)
                .query(String.class)
                .list());
    }

    /** Every stored transfer, in the order it arrived. */
    public List<Transfer> findAll() {
        return jdbc.sql("SELECT * FROM transfers ORDER BY seq").query(TransferRepository::map).list();
    }

    /** Money into or out of one account, newest first. */
    public List<Transfer> findByAccount(String accountId, int limit) {
        return jdbc.sql("""
                        SELECT * FROM transfers
                        WHERE from_account = :account OR to_account = :account
                        ORDER BY occurred_at DESC, seq DESC
                        LIMIT :limit
                        """)
                .param("account", accountId)
                .param("limit", limit)
                .query(TransferRepository::map)
                .list();
    }

    private static Transfer map(ResultSet row, int rowNumber) throws SQLException {
        return new Transfer(
                row.getString("transfer_id"),
                row.getString("from_account"),
                row.getString("to_account"),
                row.getLong("amount_cents"),
                row.getObject("occurred_at", OffsetDateTime.class).toInstant());
    }
}
