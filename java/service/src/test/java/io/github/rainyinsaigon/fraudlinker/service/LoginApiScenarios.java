package io.github.rainyinsaigon.fraudlinker.service;

import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Logins and account takeovers, end to end. Runs on H2 and PostgreSQL through
 * the subclasses. Devices carry the test's run suffix, so tests never share one.
 */
abstract class LoginApiScenarios extends TransferApiScenarios {
    static final double[] SYDNEY = {-33.87, 151.21};
    static final double[] SYDNEY_WEST = {-33.82, 150.99};
    static final double[] LONDON = {51.51, -0.13};
    static final int DAY = 24 * 60;

    private int loginSeq;

    String login(String account, String device, boolean success, int minutesAfter, double[] place) {
        return """
                {"login_id":"%s","account_id":"%s","device_id":"%s","ip":"203.0.113.9","success":%s,
                 "occurred_at":"%s","latitude":%s,"longitude":%s}"""
                .formatted(id("l" + loginSeq++), account, device, success,
                        T0.plus(Duration.ofMinutes(minutesAfter)), place == null ? "null" : place[0],
                        place == null ? "null" : place[1]);
    }

    ValidatableResponse sendLogins(List<String> logins) {
        return given().contentType(ContentType.JSON)
                .body("{\"logins\":[" + String.join(",", logins) + "]}")
                .post("/api/v1/logins")
                .then();
    }

    ValidatableResponse decideTakeover(String accountId, String outcome) {
        return given().contentType(ContentType.JSON)
                .body("{\"outcome\":\"" + outcome + "\",\"analyst\":\"analyst-" + run + "\",\"note\":\"called them\"}")
                .post("/api/v1/accounts/{id}/takeover-decisions", accountId)
                .then();
    }

    /** One login a day for six days from the customer's own phone, at home. */
    List<String> history(String account, String device) {
        List<String> logins = new ArrayList<>();
        for (int day = 0; day < 6; day++) {
            logins.add(login(account, device, true, day * DAY, SYDNEY));
        }
        return logins;
    }

    /** The customer logs in at home; 45 minutes later a new device in London gets in. */
    List<String> phished(String account) {
        List<String> logins = history(account, id("dev_own"));
        logins.add(login(account, id("dev_own"), true, 7 * DAY, SYDNEY));
        logins.add(login(account, id("dev_attacker"), true, 7 * DAY + 45, LONDON));
        return logins;
    }

    String accountRisk(String accountId) {
        return given().get("/api/v1/accounts/{id}", accountId).then().statusCode(200).extract().path("risk_action");
    }

    ValidatableResponse takeovers() {
        return given().queryParam("limit", 500).get("/api/v1/takeovers").then().statusCode(200);
    }

    @Test
    void aTakeoverIsFlaggedWithReasonsAndAConfirmedOneStepsUpOnlyThatAccount() {
        String victim = id("victim");
        String partner = id("partner");
        register(victim, null, "card_" + run).statusCode(201);
        register(partner, null, "card_" + run).statusCode(201);
        String cluster = clusterOf(victim);
        String partnerBefore = accountRisk(partner);
        String clusterBefore = given().get("/api/v1/clusters/{id}", cluster).then().extract().path("risk_action");

        sendLogins(phished(victim)).statusCode(201)
                .body("accepted", equalTo(8))
                .body("flagged_now", contains(victim))
                .body("takeover_scores.account_id", contains(victim))
                .body("takeover_scores[0].takeover_score", equalTo(0.82f))
                .body("takeover_scores[0].takeover_flagged", equalTo(true));

        given().get("/api/v1/accounts/{id}", victim).then().statusCode(200)
                .body("takeover_flagged", equalTo(true))
                .body("takeover_score", equalTo(0.82f))
                .body("takeover_reasons.code", contains("new_device", "impossible_travel"))
                .body("takeover_reasons[0].description", equalTo("first login from a new device after 7 logins"))
                .body("takeover_reasons[0].at", equalTo(T0.plus(Duration.ofMinutes(7 * DAY + 45)).toString()))
                .body("risk_action", equalTo("REVIEW"))
                .body("reason", startsWith("Account " + victim + " may have been taken over: first login from a "
                        + "new device after 7 logins, "));
        takeovers()
                .body("find { it.account_id == '" + victim + "' }.takeover_reasons.code",
                        contains("new_device", "impossible_travel"))
                .body("find { it.account_id == '" + victim + "' }.flagged_at", notNullValue())
                .body("find { it.account_id == '" + victim + "' }.reached_at",
                        equalTo(T0.plus(Duration.ofMinutes(7 * DAY + 45)).toString()))
                .body("find { it.account_id == '" + victim + "' }.cluster_size", equalTo(2))
                .body("account_id", not(hasItem(partner)));
        assertThat(auditCount(victim, "takeover.flagged")).isEqualTo(1);
        // The partner never logged in: nothing changes for it.
        assertThat(accountRisk(partner)).isEqualTo(partnerBefore);

        decideTakeover(victim, "CONFIRMED_TAKEOVER").statusCode(201)
                .body("decision.account_id", equalTo(victim))
                .body("decision.outcome", equalTo("CONFIRMED_TAKEOVER"))
                .body("risk_action", equalTo("STEP_UP"));

        given().get("/api/v1/accounts/{id}", victim).then()
                .body("risk_action", equalTo("STEP_UP"))
                .body("reason", containsString("re-verify the customer"));
        assertThat(accountRisk(partner)).isEqualTo(partnerBefore);
        given().get("/api/v1/clusters/{id}", cluster).then().body("risk_action", equalTo(clusterBefore));
        takeovers().body("account_id", not(hasItem(victim)));
        assertThat(auditCount(victim, "takeover.decided")).isEqualTo(1);
        given().get("/api/v1/accounts/{id}/takeover-decisions", victim).then().statusCode(200)
                .body("outcome", contains("CONFIRMED_TAKEOVER"))
                .body("[0].analyst", equalTo("analyst-" + run));
    }

    @Test
    void notTakeoverClearsAFlagAndRecordsARevalidatedCustomer() {
        String customer = id("traveller");
        register(customer, null, null).statusCode(201);
        sendLogins(phished(customer)).statusCode(201).body("flagged_now", contains(customer));

        decideTakeover(customer, "NOT_TAKEOVER").statusCode(201).body("risk_action", equalTo("ALLOW"));
        given().get("/api/v1/accounts/{id}", customer).then()
                .body("takeover_flagged", equalTo(true))
                .body("risk_action", equalTo("ALLOW"));
        takeovers().body("account_id", not(hasItem(customer)));

        // Confirmed after all, then re-verified: the latest decision wins.
        decideTakeover(customer, "CONFIRMED_TAKEOVER").statusCode(201).body("risk_action", equalTo("STEP_UP"));
        decideTakeover(customer, "NOT_TAKEOVER").statusCode(201).body("risk_action", equalTo("ALLOW"));
        given().get("/api/v1/accounts/{id}/takeover-decisions", customer).then()
                .body("outcome", contains("NOT_TAKEOVER", "CONFIRMED_TAKEOVER", "NOT_TAKEOVER"));
    }

    @Test
    void aNewPhoneIsNotFlagged() {
        String customer = id("upgrader");
        register(customer, null, null).statusCode(201);
        List<String> logins = history(customer, id("dev_old"));
        logins.add(login(customer, id("dev_new"), true, 7 * DAY, SYDNEY_WEST));
        logins.add(login(customer, id("dev_new"), true, 8 * DAY, SYDNEY));

        sendLogins(logins).statusCode(201).body("flagged_now", empty())
                .body("takeover_scores[0].takeover_score", equalTo(0.4f));

        given().get("/api/v1/accounts/{id}", customer).then()
                .body("takeover_flagged", equalTo(false))
                .body("takeover_reasons.code", contains("new_device"))
                .body("risk_action", equalTo("ALLOW"));
        takeovers().body("account_id", not(hasItem(customer)));
    }

    @Test
    void confirmedFraudStillRestrictsATakenOverAccount() {
        String customer = id("both");
        register(customer, null, null).statusCode(201);
        sendLogins(phished(customer)).statusCode(201);
        decideTakeover(customer, "CONFIRMED_TAKEOVER").statusCode(201);

        decide(clusterOf(customer), "CONFIRMED_FRAUD").statusCode(201);

        assertThat(accountRisk(customer)).isEqualTo("RESTRICT");
    }

    @Test
    void duplicateLoginsAreRefusedAndTheWholeBatchIsKeptOut() {
        String customer = id("dup");
        register(customer, null, null).statusCode(201);
        String first = login(customer, id("dev"), true, 0, SYDNEY);
        sendLogins(List.of(first)).statusCode(201);

        String fresh = login(customer, id("dev"), true, 1, SYDNEY);
        sendLogins(List.of(fresh, first)).statusCode(409)
                .body("detail", equalTo("Login " + id("l0") + " already exists."));
        sendLogins(List.of(fresh, fresh)).statusCode(409)
                .body("detail", equalTo("Login " + id("l1") + " appears twice in this batch."));

        given().get("/api/v1/accounts/{id}/logins", customer).then().statusCode(200)
                .body("login_id", contains(id("l0")));
    }

    @Test
    void scoresAndFlagsSurviveARebuild() {
        String customer = id("rebuilt");
        register(customer, null, null).statusCode(201);
        sendLogins(phished(customer)).statusCode(201);
        String flaggedAt = takeovers().extract().path("find { it.account_id == '" + customer + "' }.flagged_at");

        engine.withLock(session -> {
            session.rebuildFromDatabase();
            return null;
        });

        given().get("/api/v1/accounts/{id}", customer).then()
                .body("takeover_flagged", equalTo(true))
                .body("takeover_reasons.code", contains("new_device", "impossible_travel"));
        takeovers().body("find { it.account_id == '" + customer + "' }.flagged_at", equalTo(flaggedAt));
        assertThat(auditCount(customer, "takeover.flagged")).isEqualTo(1);
    }

    @Test
    void loginsNeverChangeAnyCluster() {
        // One device logs in to four unrelated accounts: a shared-device signal, but never a link.
        List<String> accounts = new ArrayList<>();
        List<String> logins = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String account = id("shared" + i);
            register(account, null, null).statusCode(201);
            accounts.add(account);
            logins.add(login(account, id("dev_bot"), true, i, LONDON));
        }
        List<String> before = accounts.stream().map(this::clusterOf).toList();
        long clusters = engine.withLock(session -> (long) session.state().clusterCount());

        sendLogins(logins).statusCode(201);

        assertThat(accounts.stream().map(this::clusterOf).toList()).isEqualTo(before);
        long clustersAfter = engine.withLock(session -> (long) session.state().clusterCount());
        assertThat(clustersAfter).isEqualTo(clusters);
        given().get("/api/v1/accounts/{id}", accounts.get(0)).then()
                .body("cluster_size", equalTo(1))
                .body("takeover_reasons.code", contains("shared_device"));
    }

    @Test
    void anAccountWhoseLoginsArriveFirstIsFlaggedWhenItRegisters() {
        String late = id("late");
        sendLogins(phished(late)).statusCode(201).body("flagged_now", empty()).body("takeover_scores", empty());

        register(late, null, null).statusCode(201).body("risk_action", equalTo("REVIEW"));

        assertThat(auditCount(late, "takeover.flagged")).isEqualTo(1);
        given().get("/api/v1/accounts/{id}", late).then()
                .body("takeover_flagged", equalTo(true))
                .body("reason", containsString("may have been taken over"));
    }

    @Test
    void listsAnAccountsLoginsNewestFirst() {
        String customer = id("lister");
        register(customer, null, null).statusCode(201);
        sendLogins(List.of(login(customer, id("dev"), false, 0, SYDNEY), login(customer, id("dev"), true, 1, null)))
                .statusCode(201);

        given().queryParam("limit", 1).get("/api/v1/accounts/{id}/logins", customer).then().statusCode(200)
                .body("login_id", contains(id("l1")))
                .body("[0].success", equalTo(true))
                .body("[0].latitude", equalTo(null));
        given().get("/api/v1/accounts/{id}/logins", customer).then()
                .body("success", contains(true, false))
                .body("[1].latitude", equalTo(-33.87f))
                .body("[1].device_id", equalTo(id("dev")));
        given().get("/api/v1/accounts/{id}/logins", id("nobody")).then().statusCode(404);
        given().get("/api/v1/accounts/{id}/takeover-decisions", id("nobody")).then().statusCode(404);
        decideTakeover(id("nobody"), "NOT_TAKEOVER").statusCode(404);
    }

    @Test
    void badLoginsAndDecisionsGetClearErrors() {
        String a = id("bad");
        register(a, null, null).statusCode(201);
        sendLogins(List.of("""
                {"login_id":"x","account_id":"a","success":true,"occurred_at":"2026-11-03T10:00:00Z",
                 "latitude":10.0}""")).statusCode(400)
                .body("detail", containsString("needs both a latitude and a longitude"));
        sendLogins(List.of("""
                {"login_id":"x","account_id":"a","success":true,"occurred_at":"2026-11-03T10:00:00Z",
                 "latitude":91.0,"longitude":0}""")).statusCode(400)
                .body("detail", containsString("outside the globe"));
        sendLogins(List.of("""
                {"login_id":"x","account_id":"a","occurred_at":"2026-11-03T10:00:00Z"}""")).statusCode(400);
        sendLogins(List.of("""
                {"login_id":"x y","account_id":"a","success":true,"occurred_at":"2026-11-03T10:00:00Z"}"""))
                .statusCode(400);
        sendLogins(List.of()).statusCode(400);
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            tooMany.add(login(a, id("dev"), true, i, null));
        }
        sendLogins(tooMany).statusCode(400);
        decideTakeover(a, "MAYBE").statusCode(400);
        given().contentType(ContentType.JSON).body("{\"outcome\":\"NOT_TAKEOVER\"}")
                .post("/api/v1/accounts/{id}/takeover-decisions", a).then().statusCode(400);
        given().queryParam("limit", 0).get("/api/v1/takeovers").then().statusCode(400);
        given().queryParam("limit", 501).get("/api/v1/accounts/{id}/logins", a).then().statusCode(400);
        given().get("/api/v1/accounts/{id}/logins", a).then().body("login_id", empty());
    }
}
