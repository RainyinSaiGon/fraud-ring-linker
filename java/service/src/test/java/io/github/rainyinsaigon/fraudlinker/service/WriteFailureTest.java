package io.github.rainyinsaigon.fraudlinker.service;

import io.github.rainyinsaigon.fraudlinker.service.persistence.AuditRepository;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

/**
 * If the database write fails after the engine already linked the account, the
 * request fails and the engine must forget the account too, so memory and
 * database never disagree.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class WriteFailureTest {

    @DynamicPropertySource
    static void tokens(DynamicPropertyRegistry registry) {
        TestTokens.configure(registry);
    }


    @LocalServerPort
    int port;

    @MockitoSpyBean
    AuditRepository audit;

    String run;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        run = UUID.randomUUID().toString().substring(0, 8);
        TestTokens.sendByDefault(run);
    }

    private io.restassured.response.ValidatableResponse register(String accountId, String card) {
        return given().contentType(ContentType.JSON)
                .body(ApiScenarios.accountJson(accountId, accountId + "@example.com", null, ApiScenarios.isolatedIp(), card))
                .post("/api/v1/accounts")
                .then();
    }

    @Test
    void failedAuditWriteRollsBackTheDatabaseAndTheEngine() {
        String card = "card_" + run;
        String doomed = "doomed_" + run;
        doThrow(new IllegalStateException("simulated disk full"))
                .when(audit).record(any(), eq(AuditRepository.ACCOUNT_LINKED), eq(doomed), any(), any(), any());

        register(doomed, card).statusCode(500);

        // Not stored, not linked.
        given().get("/api/v1/accounts/{id}", doomed).then().statusCode(404);
        register("next_" + run, card).statusCode(201).body("cluster_size", equalTo(1));
    }

    @Test
    void failedMuleFlagWriteRollsBackTheTransfersToo() {
        String mule = "mule_" + run;
        register(mule, null).statusCode(201);
        doThrow(new IllegalStateException("simulated disk full"))
                .when(audit).record(any(), eq(AuditRepository.MULE_FLAGGED), eq(mule), any(), any(), any());
        StringBuilder batch = new StringBuilder("{\"transfers\":[");
        for (int i = 0; i < 6; i++) {
            batch.append(transferJson("v" + i + "_" + run, "ext_v" + i + "_" + run, mule, 50_000,
                    "2026-11-03T10:0" + i + ":00Z")).append(',');
        }
        batch.append(transferJson("out_" + run, mule, "ext_out_" + run, 280_000, "2026-11-03T10:30:00Z"));
        batch.append("]}");

        given().contentType(ContentType.JSON).body(batch.toString()).post("/api/v1/transfers").then().statusCode(500);

        // Neither stored nor scored: the engine was rebuilt without the batch.
        given().get("/api/v1/accounts/{id}/transfers", mule).then().statusCode(200).body("size()", equalTo(0));
        given().get("/api/v1/accounts/{id}", mule).then()
                .body("mule_score", equalTo(0.0f))
                .body("risk_action", equalTo("ALLOW"));
    }

    private static String transferJson(String id, String from, String to, long cents, String at) {
        return """
                {"transfer_id":"%s","from_account":"%s","to_account":"%s","amount_cents":%d,"occurred_at":"%s"}"""
                .formatted(id, from, to, cents, at);
    }

    @Test
    void failureBeforeAnyChangeDoesNotTriggerARebuild() {
        String accountId = "dup_" + run;
        register(accountId, null).statusCode(201);
        register(accountId, null).statusCode(409);
        // The engine is still usable and consistent after the rejected duplicate.
        given().get("/api/v1/accounts/{id}", accountId).then().statusCode(200).body("cluster_size", equalTo(1));
    }
}
