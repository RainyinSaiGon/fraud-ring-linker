package io.github.rainyinsaigon.fraudlinker.service.persistence;

import io.github.rainyinsaigon.fraudlinker.takeover.Login;
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
public class LoginRepository {
    private final JdbcClient jdbc;

    public LoginRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts a login. A duplicate {@code login_id} fails on the unique constraint. */
    public void insert(Login login, Instant receivedAt) {
        jdbc.sql("""
                        INSERT INTO logins (login_id, account_id, device_id, ip, success, occurred_at, latitude,
                                            longitude, received_at)
                        VALUES (:id, :account, :device, :ip, :success, :occurredAt, :latitude, :longitude,
                                :receivedAt)
                        """)
                .param("id", login.loginId())
                .param("account", login.accountId())
                .param("device", login.deviceId())
                .param("ip", login.ip())
                .param("success", login.success())
                .param("occurredAt", AccountRepository.utc(login.occurredAt()))
                .param("latitude", login.latitude())
                .param("longitude", login.longitude())
                .param("receivedAt", AccountRepository.utc(receivedAt))
                .update();
    }

    /** Which of these login IDs are already stored. */
    public Set<String> findExistingIds(Collection<String> loginIds) {
        if (loginIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("SELECT login_id FROM logins WHERE login_id IN (:ids)")
                .param("ids", loginIds)
                .query(String.class)
                .list());
    }

    /** Every stored login, in the order it arrived. */
    public List<Login> findAll() {
        return jdbc.sql("SELECT * FROM logins ORDER BY seq").query(LoginRepository::map).list();
    }

    /** One account's logins, newest first. */
    public List<Login> findByAccount(String accountId, int limit) {
        return jdbc.sql("""
                        SELECT * FROM logins
                        WHERE account_id = :account
                        ORDER BY occurred_at DESC, seq DESC
                        LIMIT :limit
                        """)
                .param("account", accountId)
                .param("limit", limit)
                .query(LoginRepository::map)
                .list();
    }

    private static Login map(ResultSet row, int rowNumber) throws SQLException {
        return new Login(
                row.getString("login_id"),
                row.getString("account_id"),
                row.getString("device_id"),
                row.getString("ip"),
                row.getBoolean("success"),
                row.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                row.getObject("latitude", Double.class),
                row.getObject("longitude", Double.class));
    }
}
