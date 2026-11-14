package io.github.rainyinsaigon.fraudlinker.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The same API scenarios on a real PostgreSQL, plus checks that only
 * PostgreSQL can make (the append-only audit trigger).
 *
 * <p>Skipped automatically where Docker is not available; CI always runs it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class PostgresApiTest extends ApiScenarios {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    JdbcClient jdbc;

    @Test
    void auditEventsCannotBeChangedOrDeleted() {
        register(id("audit"), null, null).statusCode(201);
        long before = jdbc.sql("SELECT COUNT(*) FROM audit_events").query(Long.class).single();

        assertThatThrownBy(() -> jdbc.sql("UPDATE audit_events SET actor = 'someone-else'").update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM audit_events").update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("TRUNCATE audit_events").update())
                .hasMessageContaining("append-only");

        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_events").query(Long.class).single()).isEqualTo(before);
    }

    @Test
    void storedConstraintsMustBeOrderedAndOfAKnownRelation() {
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO identity_constraints (account_a, account_b, relation, created_at)
                VALUES ('z', 'a', 'verified_distinct', now())""").update())
                .hasMessageContaining("ck_identity_constraints_order");
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO identity_constraints (account_a, account_b, relation, created_at)
                VALUES ('a', 'z', 'same_person', now())""").update())
                .hasMessageContaining("ck_identity_constraints_relation");
    }
}
