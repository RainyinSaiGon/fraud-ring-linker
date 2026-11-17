package io.github.rainyinsaigon.fraudlinker.service;

import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

/**
 * Transfers and mule flags, end to end. Runs on H2 and PostgreSQL through the
 * subclasses. External accounts carry the test's run suffix, so tests never
 * share payers.
 */
abstract class TransferApiScenarios extends ApiScenarios {
    static final Instant T0 = Instant.parse("2026-11-03T10:00:00Z");

    private int seq;

    String transfer(String from, String to, long cents, int minutesAfter) {
        return """
                {"transfer_id":"%s","from_account":"%s","to_account":"%s","amount_cents":%d,"occurred_at":"%s"}"""
                .formatted(id("t" + seq++), from, to, cents, T0.plus(Duration.ofMinutes(minutesAfter)));
    }

    ValidatableResponse send(List<String> transfers) {
        return given().contentType(ContentType.JSON)
                .body("{\"transfers\":[" + String.join(",", transfers) + "]}")
                .post("/api/v1/transfers")
                .then();
    }

    /** Six outside victims pay m1, m1 passes 90% on to m2, and m2 pays it out to an outside cash-out. */
    List<String> muleChain(String m1, String m2) {
        List<String> chain = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            chain.add(transfer(id("ext_victim" + i), m1, 50_000, 5 * i));
        }
        chain.add(transfer(m1, m2, 270_000, 30));
        chain.add(transfer(m2, id("ext_cashout"), 265_000, 45));
        return chain;
    }

    long auditCount(String accountId, String eventType) {
        return jdbc.sql("SELECT COUNT(*) FROM audit_events WHERE account_id = :a AND event_type = :t")
                .param("a", accountId).param("t", eventType).query(Long.class).single();
    }

    String clusterOf(String accountId) {
        return given().get("/api/v1/accounts/{id}", accountId).then().statusCode(200).extract().path("cluster_id");
    }

    @Test
    void aMuleChainIsFlaggedWithItsReasonsAndAuditedOnce() {
        String m1 = id("m1");
        String m2 = id("m2");
        register(m1, null, null).statusCode(201);
        register(m2, null, null).statusCode(201);

        send(muleChain(m1, m2)).statusCode(201)
                .body("accepted", equalTo(8))
                .body("flagged_now", contains(m1, m2))
                // Only accounts the bank holds are scored; the victims and the cash-out are not.
                .body("mule_scores.account_id", contains(m1, m2))
                .body("mule_scores[0].mule_score", equalTo(0.94f));

        given().get("/api/v1/accounts/{id}", m1).then().statusCode(200)
                .body("mule_flagged", equalTo(true))
                .body("mule_reasons.code", contains("fan_in", "pass_through", "chain"))
                .body("mule_reasons[0].description", equalTo("6 unrelated payers in 25 min"))
                .body("risk_action", equalTo("REVIEW"))
                .body("reason", startsWith("Account " + m1 + " looks like a money mule: 6 unrelated payers"));
        given().get("/api/v1/accounts/{id}", m2).then()
                .body("mule_reasons.code", contains("pass_through", "chain"))
                .body("risk_action", equalTo("REVIEW"));

        given().queryParam("limit", 500).get("/api/v1/mules").then().statusCode(200)
                .body("account_id", hasItem(m1))
                .body("account_id", hasItem(m2))
                .body("account_id", not(hasItem(id("ext_victim0"))))
                .body("find { it.account_id == '" + m1 + "' }.mule_reasons.code", contains("fan_in", "pass_through",
                        "chain"))
                .body("find { it.account_id == '" + m1 + "' }.risk_action", equalTo("REVIEW"));

        // More money keeps m1 flagged, but it is audited only the first time.
        send(List.of(transfer(id("ext_victim9"), m1, 10_000, 60))).statusCode(201)
                .body("flagged_now", empty());
        assertThat(auditCount(m1, "mule.flagged")).isEqualTo(1);
        given().queryParam("account_id", m1).get("/api/v1/audit-events").then()
                .body("find { it.event_type == 'mule.flagged' }.details.reasons", contains("fan_in", "pass_through",
                        "chain"))
                // The flag was raised by the client whose transfers showed it.
                .body("find { it.event_type == 'mule.flagged' }.actor", equalTo("ingest-" + run));
    }

    @Test
    void aBusySmallBusinessIsNotFlagged() {
        String shop = id("bakery");
        register(shop, null, null).statusCode(201);
        List<String> sales = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            sales.add(transfer(id("ext_buyer" + i), shop, 1_500, 20 * i));
        }
        sales.add(transfer(shop, id("ext_supplier"), 9_000, 30 * 60));

        send(sales).statusCode(201).body("flagged_now", empty());

        given().get("/api/v1/accounts/{id}", shop).then()
                .body("mule_score", equalTo(0.6f))
                .body("mule_flagged", equalTo(false))
                .body("mule_reasons.code", contains("fan_in"))
                .body("risk_action", equalTo("ALLOW"));
        given().queryParam("limit", 500).get("/api/v1/mules").then().body("account_id", not(hasItem(shop)));
    }

    @Test
    void transfersNeverChangeAnyCluster() {
        String a = id("pair_a");
        String b = id("pair_b");
        register(a, null, "card_" + run).statusCode(201);
        register(b, null, "card_" + run).statusCode(201);
        String cluster = clusterOf(a);
        String before = given().get("/api/v1/clusters/{id}", cluster).then().extract().asString();
        long clusters = engine.withLock(session -> (long) session.state().clusterCount());

        send(muleChain(a, b)).statusCode(201);

        assertThat(clusterOf(b)).isEqualTo(cluster);
        given().get("/api/v1/clusters/{id}", cluster).then()
                .body("account_ids", containsInAnyOrder(a, b))
                .body("links", hasSize(1))
                .body("mule_scores.account_id", containsInAnyOrder(a, b));
        long clustersAfter = engine.withLock(session -> (long) session.state().clusterCount());
        assertThat(clustersAfter).isEqualTo(clusters);
        // a and b share a card, so a paying b moves its own money: a is not pass-through.
        given().get("/api/v1/accounts/{id}", a).then().body("mule_reasons.code", contains("fan_in"));
        assertThat(before).contains("\"mule_scores\":[]");
    }

    @Test
    void decisionsClearOrConfirmAFlag() {
        String m1 = id("m1");
        String m2 = id("m2");
        register(m1, null, null).statusCode(201);
        register(m2, null, null).statusCode(201);
        send(muleChain(m1, m2)).statusCode(201);

        decide(clusterOf(m1), "NOT_FRAUD").statusCode(201).body("risk_action", equalTo("ALLOW"));
        decide(clusterOf(m2), "CONFIRMED_FRAUD").statusCode(201).body("risk_action", equalTo("RESTRICT"));

        given().get("/api/v1/accounts/{id}", m1).then()
                .body("mule_flagged", equalTo(true))
                .body("risk_action", equalTo("ALLOW"));
        given().queryParam("limit", 500).get("/api/v1/mules").then()
                .body("account_id", not(hasItem(m1)))
                .body("account_id", not(hasItem(m2)));
    }

    @Test
    void anAccountThatArrivesAfterItsTransfersIsFlaggedWhenItRegisters() {
        String late = id("late");
        List<String> chain = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            chain.add(transfer(id("ext_victim" + i), late, 50_000, 5 * i));
        }
        chain.add(transfer(late, id("ext_next"), 280_000, 30));
        send(chain).statusCode(201).body("flagged_now", empty()).body("mule_scores", empty());

        register(late, null, null).statusCode(201).body("risk_action", equalTo("REVIEW"));

        assertThat(auditCount(late, "mule.flagged")).isEqualTo(1);
        given().get("/api/v1/accounts/{id}", late).then()
                .body("mule_reasons.code", contains("fan_in", "pass_through"))
                .body("reason", containsString("looks like a money mule"));
    }

    @Test
    void scoresAndFlagsSurviveARebuild() {
        String m1 = id("m1");
        String m2 = id("m2");
        register(m1, null, null).statusCode(201);
        register(m2, null, null).statusCode(201);
        send(muleChain(m1, m2)).statusCode(201);
        String flaggedAt = given().queryParam("limit", 500).get("/api/v1/mules").then()
                .extract().path("find { it.account_id == '" + m1 + "' }.flagged_at");

        engine.withLock(session -> {
            session.rebuildFromDatabase();
            return null;
        });

        given().get("/api/v1/accounts/{id}", m1).then()
                .body("mule_flagged", equalTo(true))
                .body("mule_reasons.code", contains("fan_in", "pass_through", "chain"));
        given().queryParam("limit", 500).get("/api/v1/mules").then()
                .body("find { it.account_id == '" + m1 + "' }.flagged_at", equalTo(flaggedAt));
        assertThat(auditCount(m1, "mule.flagged")).isEqualTo(1);
    }

    @Test
    void duplicateTransfersAreRefusedAndTheWholeBatchIsKeptOut() {
        String a = id("dup");
        register(a, null, null).statusCode(201);
        String first = transfer(id("ext_x"), a, 100, 0);
        send(List.of(first)).statusCode(201);

        String fresh = transfer(id("ext_y"), a, 200, 1);
        send(List.of(fresh, first)).statusCode(409)
                .body("detail", equalTo("Transfer " + id("t0") + " already exists."));
        send(List.of(fresh, fresh)).statusCode(409)
                .body("detail", equalTo("Transfer " + id("t1") + " appears twice in this batch."));

        given().get("/api/v1/accounts/{id}/transfers", a).then().statusCode(200)
                .body("transfer_id", contains(id("t0")));
    }

    @Test
    void listsAnAccountsTransfersNewestFirstWithTheirDirection() {
        String a = id("own");
        register(a, null, null).statusCode(201);
        send(List.of(transfer(id("ext_employer"), a, 500_000, 0), transfer(a, id("ext_rent"), 150_000, 90)))
                .statusCode(201);

        given().queryParam("limit", 1).get("/api/v1/accounts/{id}/transfers", a).then().statusCode(200)
                .body("transfer_id", contains(id("t1")))
                .body("[0].direction", equalTo("OUT"))
                .body("[0].amount_cents", equalTo(150_000));
        given().get("/api/v1/accounts/{id}/transfers", a).then()
                .body("direction", contains("OUT", "IN"))
                .body("[1].from_account", equalTo(id("ext_employer")));
        given().get("/api/v1/accounts/{id}/transfers", id("nobody")).then().statusCode(404);
    }

    @Test
    void badTransfersGetClearErrors() {
        String a = id("bad");
        send(List.of(transfer(a, a, 100, 0))).statusCode(400)
                .body("detail", containsString("sends money from an account to itself"));
        send(List.of()).statusCode(400);
        send(List.of(transfer(id("x"), a, 0, 0))).statusCode(400);
        send(List.of("""
                {"transfer_id":"t","from_account":"a b","to_account":"c","amount_cents":1,
                 "occurred_at":"2026-11-03T10:00:00Z"}""")).statusCode(400);
        send(List.of("""
                {"transfer_id":"t","from_account":"a","to_account":"c","amount_cents":1}""")).statusCode(400);
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            tooMany.add(transfer(id("ext_p" + i), a, 1, 0));
        }
        send(tooMany).statusCode(400);
        given().queryParam("limit", 0).get("/api/v1/mules").then().statusCode(400);
        given().queryParam("limit", 501).get("/api/v1/accounts/{id}/transfers", a).then().statusCode(400);
    }
}
