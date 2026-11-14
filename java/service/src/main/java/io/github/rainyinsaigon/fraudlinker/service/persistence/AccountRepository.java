package io.github.rainyinsaigon.fraudlinker.service.persistence;

import io.github.rainyinsaigon.fraudlinker.domain.Account;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Repository
public class AccountRepository {
    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts an account. A duplicate {@code account_id} fails on the unique constraint. */
    public void insert(Account account, Instant receivedAt) {
        jdbc.sql("""
                        INSERT INTO accounts (account_id, email, device_id, ip, payment_fingerprint, created_at, received_at)
                        VALUES (:accountId, :email, :deviceId, :ip, :payment, :createdAt, :receivedAt)
                        """)
                .param("accountId", account.accountId())
                .param("email", account.email())
                .param("deviceId", account.deviceId())
                .param("ip", account.ip())
                .param("payment", account.paymentFingerprint())
                .param("createdAt", utc(account.createdAt()))
                .param("receivedAt", utc(receivedAt))
                .update();
    }

    public Optional<Account> findById(String accountId) {
        return jdbc.sql("SELECT * FROM accounts WHERE account_id = :accountId")
                .param("accountId", accountId)
                .query(AccountRepository::map)
                .optional();
    }

    /** Every account in the order it arrived, which is the order the engine replays them in. */
    public List<Account> findAllInArrivalOrder() {
        return jdbc.sql("SELECT * FROM accounts ORDER BY seq").query(AccountRepository::map).list();
    }

    public long count() {
        return jdbc.sql("SELECT COUNT(*) FROM accounts").query(Long.class).single();
    }

    private static Account map(ResultSet row, int rowNumber) throws SQLException {
        return new Account(
                row.getString("account_id"),
                row.getString("email"),
                row.getString("device_id"),
                row.getString("ip"),
                row.getString("payment_fingerprint"),
                row.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
