package io.github.rainyinsaigon.fraudlinker.service.persistence;

import io.github.rainyinsaigon.fraudlinker.clustering.Constraints;
import io.github.rainyinsaigon.fraudlinker.domain.Constraint;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public class ConstraintRepository {
    private final JdbcClient jdbc;

    public ConstraintRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Stores a verified-distinct pair once, smaller account ID first. */
    public void insert(String accountA, String accountB, Instant createdAt) {
        boolean ordered = accountA.compareTo(accountB) < 0;
        jdbc.sql("""
                        INSERT INTO identity_constraints (account_a, account_b, relation, created_at)
                        VALUES (:a, :b, :relation, :createdAt)
                        """)
                .param("a", ordered ? accountA : accountB)
                .param("b", ordered ? accountB : accountA)
                .param("relation", Constraints.VERIFIED_DISTINCT)
                .param("createdAt", AccountRepository.utc(createdAt))
                .update();
    }

    public List<Constraint> findAll() {
        return jdbc.sql("SELECT account_a, account_b, relation FROM identity_constraints ORDER BY account_a, account_b")
                .query((row, n) -> new Constraint(row.getString("account_a"), row.getString("account_b"),
                        row.getString("relation")))
                .list();
    }
}
