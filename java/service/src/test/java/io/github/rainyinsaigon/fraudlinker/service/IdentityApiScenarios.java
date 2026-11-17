package io.github.rainyinsaigon.fraudlinker.service;

import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.UnaryOperator;

import com.nimbusds.jwt.JWTClaimsSet;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

/**
 * Who acted comes from the token, never the request body: the analyst for a
 * decision, the client for whatever an ingest call causes.
 */
abstract class IdentityApiScenarios extends SecurityApiScenarios {

    private static String bearer(String subject, UnaryOperator<JWTClaimsSet.Builder> claims) {
        return TestTokens.bearer(TestTokens.token(subject, c -> claims.apply(
                c.claim("roles", List.of("ingest", "analyst", "auditor")))));
    }

    /** Registers two accounts on one card as {@code authorization}; returns their cluster. */
    private String pairAs(String authorization, String name) {
        String card = "card_" + name + "_" + run;
        String clusterId = "";
        for (String n : List.of("1", "2")) {
            clusterId = given().header("Authorization", authorization).contentType(ContentType.JSON)
                    .body(accountJson(id(name + n), id(name + n).replace('_', '.') + "@example.com", null,
                            isolatedIp(), card))
                    .post("/api/v1/accounts").then().statusCode(201).extract().path("cluster_id");
        }
        return clusterId;
    }

    @Test
    void aNameInTheRequestBodyIsRefused() {
        String clusterId = pairAs(TestTokens.bearer(TestTokens.token("u-" + run, "ingest")), "body");
        String why = "comes from your sign-in; remove it from the request";
        given().contentType(ContentType.JSON).body("{\"outcome\":\"CONFIRMED_FRAUD\",\"analyst\":\"mallory\"}")
                .post("/api/v1/clusters/{id}/decisions", clusterId).then()
                .statusCode(400).body("errors", contains(java.util.Map.of("field", "analyst", "message", why)));
        given().contentType(ContentType.JSON).body("{\"outcome\":\"NOT_TAKEOVER\",\"analyst\":\"mallory\"}")
                .post("/api/v1/accounts/{id}/takeover-decisions", id("body1")).then()
                .statusCode(400).body("errors.field", contains("analyst"));
        for (String action : List.of("accept", "reject")) {
            given().contentType(ContentType.JSON).body("{\"analyst\":\"mallory\"}")
                    .post("/api/v1/merge-proposals/{id}/" + action, java.util.UUID.randomUUID()).then()
                    .statusCode(400).body("errors.field", contains("analyst"));
        }
        given().contentType(ContentType.JSON)
                .body("{\"account_a\":\"" + id("body1") + "\",\"account_b\":\"" + id("x") + "\",\"actor\":7}")
                .post("/api/v1/constraints").then()
                .statusCode(400).body("errors.field", contains("actor"));

        // Nothing was decided.
        given().get("/api/v1/clusters/{id}/decisions", clusterId).then().body("", org.hamcrest.Matchers.empty());
    }

    @Test
    void aDecisionRecordsTheSignedInAnalyst() {
        String clusterId = pairAs(TestTokens.bearer(TestTokens.token("u-" + run, "ingest")), "who");
        given().header("Authorization", bearer("user-7-" + run, c -> c.claim("preferred_username", "Alice " + run)))
                .contentType(ContentType.JSON).body("{\"outcome\":\"NOT_FRAUD\"}")
                .post("/api/v1/clusters/{id}/decisions", clusterId).then()
                .statusCode(201).body("decision.analyst", equalTo("Alice " + run));

        given().queryParam("cluster_id", clusterId).get("/api/v1/audit-events").then()
                .body("find { it.event_type == 'decision.recorded' }.actor", equalTo("Alice " + run));
    }

    @Test
    void theSubjectStandsInWhenThereIsNoUsableUsername() {
        for (String username : new String[] {null, "   ", "x".repeat(129)}) {
            String clusterId = pairAs(TestTokens.bearer(TestTokens.token("u-" + run, "ingest")),
                    "sub" + (username == null ? 0 : username.length()));
            given().header("Authorization", bearer("sub-" + run, c -> c.claim("preferred_username", username)))
                    .contentType(ContentType.JSON).body("{\"outcome\":\"NOT_FRAUD\"}")
                    .post("/api/v1/clusters/{id}/decisions", clusterId).then()
                    .statusCode(201).body("decision.analyst", equalTo("sub-" + run));
        }
    }

    @Test
    void whatAnIngestCallCausesRecordsTheClient() {
        String azp = bearer("svc-" + run, c -> c.claim("azp", "kyc-" + run).claim("client_id", "ignored"));
        String clusterId = pairAs(azp, "azp");
        given().queryParam("cluster_id", clusterId).get("/api/v1/audit-events").then()
                .body("findAll { it.event_type == 'account.linked' }.actor", contains("kyc-" + run, "kyc-" + run));

        String clientId = bearer("svc-" + run, c -> c.claim("client_id", "onboarding-" + run));
        given().queryParam("cluster_id", pairAs(clientId, "cid")).get("/api/v1/audit-events").then()
                .body("actor", hasItem("onboarding-" + run));

        String subjectOnly = TestTokens.bearer(TestTokens.token("batch-job-" + run, "ingest"));
        given().queryParam("cluster_id", pairAs(subjectOnly, "subj")).get("/api/v1/audit-events").then()
                .body("actor", hasItem("batch-job-" + run));

        given().header("Authorization", azp).contentType(ContentType.JSON)
                .body("{\"account_a\":\"" + id("azp1") + "\",\"account_b\":\"" + id("cid1") + "\"}")
                .post("/api/v1/constraints").then().statusCode(201);
        given().queryParam("account_id", id("azp1"))
                .get("/api/v1/audit-events").then()
                .body("find { it.event_type == 'constraint.added' }.actor", equalTo("kyc-" + run));
    }

    @Test
    void aTokenWithoutAUsableSubjectIsRefused() {
        for (String subject : new String[] {null, " ", "s".repeat(129)}) {
            given().header("Authorization", TestTokens.bearer(TestTokens.token("placeholder",
                            c -> c.subject(subject).claim("roles", List.of("analyst")))))
                    .get("/api/v1/clusters").then().statusCode(401);
        }
        given().header("Authorization", TestTokens.bearer(TestTokens.token("s".repeat(128), "analyst")))
                .get("/api/v1/clusters").then().statusCode(200);
    }
}
