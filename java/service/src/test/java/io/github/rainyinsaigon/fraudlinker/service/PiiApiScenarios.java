package io.github.rainyinsaigon.fraudlinker.service;

import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;

/**
 * Emails and IPs are masked in every response; an analyst can reveal one
 * account's details, and each reveal is audited. {@link PiiScan} also checks every
 * response in every scenario for a raw value.
 */
abstract class PiiApiScenarios extends IdentityApiScenarios {

    private String registerWith(String accountId, String email, String ip) {
        given().contentType(ContentType.JSON).body(accountJson(accountId, email, null, ip, null))
                .post("/api/v1/accounts").then().statusCode(201);
        return accountId;
    }

    private static String randomIp() {
        String ip = isolatedIp();
        return ip.substring(0, ip.length() - 1) + "77";
    }

    @Test
    void accountsAndLoginsShowMaskedDetails() {
        String ip = randomIp();
        String accountId = registerWith(id("pii"), "jane.doe." + run + "@example.com", ip);
        String masked = ip.substring(0, ip.lastIndexOf('.')) + ".x";

        given().get("/api/v1/accounts/{id}", accountId).then()
                .statusCode(200)
                .body("email", equalTo("j***@example.com"))
                .body("ip", equalTo(masked));

        given().contentType(ContentType.JSON).body("""
                {"logins":[{"login_id":"%s","account_id":"%s","device_id":"d1","ip":"%s","success":true,
                 "occurred_at":"2026-11-03T10:00:00Z"}]}""".formatted(id("pl"), accountId, ip))
                .post("/api/v1/logins").then().statusCode(201);
        given().get("/api/v1/accounts/{id}/logins", accountId).then()
                .body("ip", contains(masked));
    }

    @Test
    void anAnalystRevealsOneAccountAndTheRevealIsAudited() {
        String ip = randomIp();
        String email = "reveal." + run + "@example.com";
        String accountId = registerWith(id("rev"), email, ip);

        given().contentType(ContentType.JSON).body("{\"reason\":\"  Customer called to confirm identity  \"}")
                .post("/api/v1/accounts/{id}/reveal", accountId).then()
                .statusCode(200)
                .body("account_id", equalTo(accountId))
                .body("email", equalTo(email))
                .body("ip", equalTo(ip));

        given().queryParam("account_id", accountId).get("/api/v1/audit-events").then()
                .body("find { it.event_type == 'pii.revealed' }.actor", equalTo("analyst-" + run))
                .body("find { it.event_type == 'pii.revealed' }.details.fields", contains("email", "ip"))
                .body("find { it.event_type == 'pii.revealed' }.details.reason",
                        equalTo("Customer called to confirm identity"));
    }

    @Test
    void aRevealNeedsNoReasonButEveryOneIsRecorded() {
        String accountId = registerWith(id("rev2"), "r2." + run + "@example.com", randomIp());

        // No body at all is fine too.
        given().contentType(ContentType.JSON).post("/api/v1/accounts/{id}/reveal", accountId).then().statusCode(200);
        given().contentType(ContentType.JSON).body("{}").post("/api/v1/accounts/{id}/reveal", accountId).then()
                .statusCode(200);

        given().queryParam("account_id", accountId).get("/api/v1/audit-events").then()
                .body("findAll { it.event_type == 'pii.revealed' }.size()", equalTo(2))
                .body("find { it.event_type == 'pii.revealed' }.details", not(hasKey("reason")));
    }

    @Test
    void anAuditorCantRevealAndAFailedRevealRecordsNothing() {
        String accountId = registerWith(id("rev3"), "r3." + run + "@example.com", randomIp());

        given().header("Authorization", TestTokens.bearer(TestTokens.token("auditor-" + run, "auditor")))
                .contentType(ContentType.JSON).post("/api/v1/accounts/{id}/reveal", accountId).then().statusCode(403);
        given().contentType(ContentType.JSON).body("{\"reason\":\"" + "x".repeat(501) + "\"}")
                .post("/api/v1/accounts/{id}/reveal", accountId).then().statusCode(400);
        given().contentType(ContentType.JSON).post("/api/v1/accounts/{id}/reveal", id("nobody")).then().statusCode(404);

        given().queryParam("account_id", accountId).get("/api/v1/audit-events").then()
                .body("findAll { it.event_type == 'pii.revealed' }", org.hamcrest.Matchers.empty());
    }

    @Test
    void theScanCatchesARawValue() {
        PiiScan.rememberForTest("leak." + run + "@example.com");

        assertThatThrownBy(() -> PiiScan.check("GET /x", "{\"email\":\"leak." + run + "@example.com\"}"))
                .isInstanceOf(AssertionError.class).hasMessageContaining("raw value");
        PiiScan.check("GET /x", "{\"email\":\"l***@example.com\"}");
    }
}
