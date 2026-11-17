package io.github.rainyinsaigon.fraudlinker.service;

import io.github.rainyinsaigon.fraudlinker.service.engine.LinkerEngine;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;

/**
 * End-to-end scenarios through real HTTP and a real database. Subclasses choose
 * the database (H2 locally, PostgreSQL via Testcontainers).
 *
 * <p>Tests share one application and database, so each test uses its own random
 * account IDs and signal values and never asserts on global totals.
 */
abstract class ApiScenarios {

    @DynamicPropertySource
    static void tokens(DynamicPropertyRegistry registry) {
        TestTokens.configure(registry);
    }


    @LocalServerPort
    int port;

    @Autowired
    LinkerEngine engine;

    @Autowired
    JdbcClient jdbc;

    /** Unique per test, so tests never link to each other's accounts. */
    String run;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        run = UUID.randomUUID().toString().substring(0, 8);
        TestTokens.sendByDefault(run);
    }

    String id(String name) {
        return name + "_" + run;
    }

    static String accountJson(String accountId, String email, String device, String ip, String payment) {
        return """
                {"account_id":"%s","email":"%s","device_id":%s,"ip":"%s","payment_fingerprint":%s,
                 "created_at":"2026-03-14T09:22:11Z"}
                """.formatted(accountId, email, device == null ? "null" : '"' + device + '"', ip,
                payment == null ? "null" : '"' + payment + '"');
    }

    /**
     * A random IP in its own /24. Test accounts all share one sign-up time, so
     * putting them in one subnet would look like a sign-up burst and link
     * accounts from unrelated tests.
     */
    static String isolatedIp() {
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        return "10." + random.nextInt(256) + "." + random.nextInt(256) + ".1";
    }

    ValidatableResponse register(String accountId, String device, String payment) {
        return given().contentType(ContentType.JSON)
                .body(accountJson(accountId, accountId.replace('_', '.') + "@example.com", device, isolatedIp(),
                        payment))
                .post("/api/v1/accounts")
                .then();
    }

    ValidatableResponse decide(String clusterId, String outcome) {
        return given().contentType(ContentType.JSON)
                .body("{\"outcome\":\"" + outcome + "\",\"note\":\"checked\"}")
                .post("/api/v1/clusters/{id}/decisions", clusterId)
                .then();
    }

    ValidatableResponse addConstraint(String a, String b) {
        return given().contentType(ContentType.JSON)
                .body("{\"account_a\":\"" + a + "\",\"account_b\":\"" + b + "\"}")
                .post("/api/v1/constraints")
                .then();
    }

    @Test
    void fraudRingLifecycleFromFirstSignUpToRestriction() {
        String card = "card_" + run;
        String clusterId = register(id("a1"), null, card)
                .statusCode(201)
                .body("risk_action", equalTo("ALLOW"))
                .body("cluster_size", equalTo(1))
                .extract().path("cluster_id");

        register(id("a2"), null, card)
                .statusCode(201)
                .body("cluster_id", equalTo(clusterId))
                .body("cluster_size", equalTo(2))
                .body("risk_action", equalTo("REVIEW"));

        given().get("/api/v1/clusters/{id}", clusterId).then()
                .statusCode(200)
                .body("account_ids", contains(id("a1"), id("a2")))
                .body("links", hasSize(1))
                .body("links[0].rule", equalTo("rare_payment"))
                .body("links[0].shared_signals", hasItem("same_payment_method"))
                .body("latest_decision", equalTo(null));

        decide(clusterId, "CONFIRMED_FRAUD")
                .statusCode(201)
                .body("risk_action", equalTo("RESTRICT"))
                .body("decision.account_ids", containsInAnyOrder(id("a1"), id("a2")));

        // A new account using the same card is restricted the moment it arrives.
        register(id("a3"), null, card)
                .statusCode(201)
                .body("cluster_id", equalTo(clusterId))
                .body("risk_action", equalTo("RESTRICT"));

        given().get("/api/v1/accounts/{id}", id("a3")).then()
                .statusCode(200)
                .body("payment_fingerprint", equalTo(card))
                .body("risk_action", equalTo("RESTRICT"));

        given().get("/api/v1/clusters/{id}/decisions", clusterId).then()
                .statusCode(200)
                .body("", hasSize(1))
                .body("[0].outcome", equalTo("CONFIRMED_FRAUD"));

        given().queryParam("cluster_id", clusterId).get("/api/v1/audit-events").then()
                .statusCode(200)
                .body("event_type", contains("account.linked", "decision.recorded", "account.linked",
                        "account.linked"))
                .body("[0].details.risk_action", equalTo("RESTRICT"))
                .body("[1].actor", equalTo("analyst-" + run));
    }

    @Test
    void reviewQueueListsLinkedClustersUntilAnAnalystDecides() {
        String card = "card_" + run;
        String clusterId = register(id("q1"), null, card).statusCode(201).extract().path("cluster_id");
        given().queryParam("limit", 500).get("/api/v1/clusters").then()
                .statusCode(200)
                .body("cluster_id", org.hamcrest.Matchers.not(hasItem(clusterId)));

        register(id("q2"), null, card).statusCode(201);
        given().queryParam("limit", 500).get("/api/v1/clusters").then()
                .body("find { it.cluster_id == '" + clusterId + "' }.size", equalTo(2))
                .body("find { it.cluster_id == '" + clusterId + "' }.risk_action", equalTo("REVIEW"));

        decide(clusterId, "CONFIRMED_FRAUD").statusCode(201);
        given().queryParam("limit", 500).get("/api/v1/clusters").then()
                .body("cluster_id", org.hamcrest.Matchers.not(hasItem(clusterId)));

        given().queryParam("limit", 0).get("/api/v1/clusters").then().statusCode(400);
    }

    @Test
    void newMembersAfterANotFraudReviewGoBackToReview() {
        String device = "dev_" + run;
        String card = "card_" + run;
        String clusterId = register(id("f1"), device, card).statusCode(201).extract().path("cluster_id");
        register(id("f2"), device, card).statusCode(201).body("cluster_id", equalTo(clusterId));

        decide(clusterId, "NOT_FRAUD").statusCode(201).body("risk_action", equalTo("ALLOW"));
        given().get("/api/v1/accounts/{id}", id("f2")).then().body("risk_action", equalTo("ALLOW"));

        register(id("f3"), device, card)
                .statusCode(201)
                .body("cluster_id", equalTo(clusterId))
                .body("risk_action", equalTo("REVIEW"))
                .body("reason", startsWith("1 account(s) joined since"));
    }

    @Test
    void verifiedDistinctPairsBlockLinksAndCannotSplitAnExistingCluster() {
        String card = "card_" + run;
        // Recorded before b2 even exists: identity checks can arrive first.
        addConstraint(id("b1"), id("b2")).statusCode(201).body("status", equalTo("added"));
        addConstraint(id("b2"), id("b1")).statusCode(200).body("status", equalTo("already_present"));

        String first = register(id("b1"), null, card).statusCode(201).extract().path("cluster_id");
        register(id("b2"), null, card).statusCode(201)
                .body("cluster_id", org.hamcrest.Matchers.not(equalTo(first)));

        // b3 joins one of them; it is then too late to declare b3 distinct from that cluster.
        String b3Cluster = register(id("b3"), null, card).statusCode(201).extract().path("cluster_id");
        String partner = b3Cluster.equals(first) ? id("b1") : id("b2");
        addConstraint(id("b3"), partner).statusCode(409).body("title", equalTo("Conflict"));

        addConstraint(id("b1"), id("b1")).statusCode(400);
    }

    @Test
    void duplicatesAndUnknownIdsAreReportedClearly() {
        register(id("d1"), null, null).statusCode(201);
        register(id("d1"), null, null).statusCode(409).body("detail", equalTo("Account " + id("d1") + " already exists."));

        given().get("/api/v1/accounts/{id}", id("missing")).then().statusCode(404);
        given().get("/api/v1/clusters/c999999999").then().statusCode(404);
        decide("c999999999", "CONFIRMED_FRAUD").statusCode(404);
        given().get("/api/v1/clusters/not-a-cluster").then().statusCode(400);
        given().contentType(ContentType.JSON).body("{\"outcome\":\"MAYBE\"}")
                .post("/api/v1/clusters/c1/decisions").then().statusCode(400);
    }

    @Test
    void linksAndDecisionsSurviveARebuildFromTheDatabase() {
        String card = "card_" + run;
        String clusterId = register(id("r1"), null, card).statusCode(201).extract().path("cluster_id");
        register(id("r2"), null, card).statusCode(201);
        decide(clusterId, "CONFIRMED_FRAUD").statusCode(201);

        // Same as a restart: relink everything from the stored accounts.
        engine.withLock(session -> {
            session.rebuildFromDatabase();
            return null;
        });

        // Cluster numbers may change after a rebuild; membership and decisions must not.
        String after = given().get("/api/v1/accounts/{id}", id("r1")).then()
                .statusCode(200)
                .body("risk_action", equalTo("RESTRICT"))
                .extract().path("cluster_id");
        given().get("/api/v1/clusters/{id}", after).then()
                .body("account_ids", contains(id("r1"), id("r2")))
                .body("risk_action", equalTo("RESTRICT"));
    }

    @Test
    void concurrentSignUpsFromOneRingAllLandInOneCluster() throws Exception {
        int ringSize = 12;
        String device = "dev_" + run;
        String card = "card_" + run;
        List<Callable<String>> calls = new ArrayList<>();
        for (int i = 0; i < ringSize; i++) {
            String accountId = id("c" + i);
            calls.add(() -> register(accountId, device, card).statusCode(201).extract().path("cluster_id"));
        }

        Set<String> clusterIds = new HashSet<>();
        ExecutorService pool = Executors.newFixedThreadPool(ringSize);
        try {
            for (Future<String> result : pool.invokeAll(calls)) {
                clusterIds.add(result.get());
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(clusterIds).hasSize(1);
        String clusterId = clusterIds.iterator().next();
        given().get("/api/v1/clusters/{id}", clusterId).then().body("size", equalTo(ringSize));
        given().queryParam("cluster_id", clusterId).queryParam("limit", 100).get("/api/v1/audit-events").then()
                .body("findAll { it.event_type == 'account.linked' }", hasSize(ringSize));
    }

    /**
     * A ring split by arrival order: r1 and r3 share a phone and near-identical emails,
     * r2 and r3 share a card. r1 and r2 start separate clusters; r3 joins one, and
     * the other is proposed for merging.
     *
     * @return the ID of the proposal opened by r3
     */
    String registerSplitRing() {
        String phone = "phone_" + run;
        String card = "card_" + run;
        register(id("r1"), "maria.lopez." + run + "@x.com", phone, null).statusCode(201)
                .body("merge_proposal_ids", hasSize(0));
        register(id("r2"), "zz.other." + run + "@y.com", null, card).statusCode(201)
                .body("merge_proposal_ids", hasSize(0));
        return register(id("r3"), "maria.lopes." + run + "@x.com", phone, card).statusCode(201)
                .body("merge_proposal_ids", hasSize(1))
                .extract().path("merge_proposal_ids[0]");
    }

    ValidatableResponse register(String accountId, String email, String device, String payment) {
        return given().contentType(ContentType.JSON)
                .body(accountJson(accountId, email, device, isolatedIp(), payment))
                .post("/api/v1/accounts")
                .then();
    }

    ValidatableResponse decideProposal(String proposalId, String decision) {
        return given().contentType(ContentType.JSON)
                .body("{\"note\":\"same ring\"}")
                .post("/api/v1/merge-proposals/{id}/" + decision, proposalId)
                .then();
    }

    String clusterOf(String accountId) {
        return given().get("/api/v1/accounts/{id}", accountId).then().statusCode(200).extract().path("cluster_id");
    }

    static String find(String proposalId) {
        return "find { it.proposal_id == '" + proposalId + "' }";
    }

    @Test
    void aRingSplitByArrivalOrderIsProposedAndAnAcceptedMergeRecoversIt() {
        String proposalId = registerSplitRing();
        assertThat(clusterOf(id("r1"))).isNotEqualTo(clusterOf(id("r2")));

        given().queryParam("limit", 500).get("/api/v1/merge-proposals").then()
                .statusCode(200)
                .body(find(proposalId) + ".status", equalTo("OPEN"))
                .body(find(proposalId) + ".trigger_account_id", equalTo(id("r3")))
                .body(find(proposalId) + ".rule", org.hamcrest.Matchers.not(org.hamcrest.Matchers.emptyString()));

        decideProposal(proposalId, "accept").statusCode(200)
                .body("merged", equalTo(true))
                .body("cluster_size", equalTo(3))
                .body("risk_action", equalTo("REVIEW"))
                .body("proposal.status", equalTo("ACCEPTED"))
                .body("proposal.decided_by", equalTo("analyst-" + run))
                .body("proposal.note", equalTo("same ring"));

        String cluster = clusterOf(id("r1"));
        assertThat(clusterOf(id("r2"))).isEqualTo(cluster);
        given().get("/api/v1/clusters/{id}", cluster).then()
                .body("account_ids", containsInAnyOrder(id("r1"), id("r2"), id("r3")));
        given().queryParam("cluster_id", cluster).get("/api/v1/audit-events").then()
                .body("find { it.event_type == 'merge.accepted' }.actor", equalTo("analyst-" + run));

        // A decision is final.
        decideProposal(proposalId, "accept").statusCode(409);
        decideProposal(proposalId, "reject").statusCode(409);
        given().get("/api/v1/merge-proposals/{id}", proposalId).then()
                .statusCode(200)
                .body("status", equalTo("ACCEPTED"))
                .body("account_ids_a", hasSize(2))
                .body("account_ids_b", hasSize(1));
        given().queryParam("status", "ACCEPTED").queryParam("limit", 500).get("/api/v1/merge-proposals").then()
                .body(find(proposalId) + ".current_cluster_a", equalTo(cluster))
                .body(find(proposalId) + ".current_cluster_b", equalTo(cluster));

        // New ring members now land in the merged cluster, with nothing left to propose.
        register(id("r4"), "zz.other2." + run + "@y.com", null, "card_" + run).statusCode(201)
                .body("cluster_id", equalTo(cluster))
                .body("merge_proposal_ids", hasSize(0));
    }

    @Test
    void aRejectedProposalKeepsTheClustersApartAndIsNotProposedAgain() {
        String proposalId = registerSplitRing();

        decideProposal(proposalId, "reject").statusCode(200).body("status", equalTo("REJECTED"));

        assertThat(clusterOf(id("r1"))).isNotEqualTo(clusterOf(id("r2")));
        // r5 links both clusters again, but an analyst already said no.
        register(id("r5"), "maria.lopez." + run + "+2@x.com", "phone_" + run, "card_" + run).statusCode(201)
                .body("merge_proposal_ids", hasSize(0));
        decideProposal(proposalId, "accept").statusCode(409);
    }

    @Test
    void acceptingIsRefusedWhenItWouldJoinVerifiedDistinctAccounts() {
        String proposalId = registerSplitRing();
        addConstraint(id("r1"), id("r2")).statusCode(201);

        decideProposal(proposalId, "accept").statusCode(409)
                .body("detail", startsWith("Merging these clusters would link accounts verified as different people"));

        assertThat(clusterOf(id("r1"))).isNotEqualTo(clusterOf(id("r2")));
        given().queryParam("limit", 500).get("/api/v1/merge-proposals").then()
                .body(find(proposalId) + ".status", equalTo("OPEN"));
        decideProposal(proposalId, "reject").statusCode(200);
    }

    @Test
    void acceptedMergesAreReplayedWhenTheEngineRebuilds() {
        // Two accounts with nothing in common: batch linking alone would never join them.
        register(id("u1"), null, null).statusCode(201);
        register(id("u2"), null, null).statusCode(201);
        UUID proposalId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO merge_proposals (proposal_id, status, trigger_account_id, cluster_a, cluster_b,
                                                     score, rule, proposed_at, decided_by, decided_at)
                        VALUES (:id, 'ACCEPTED', :trigger, 'c1', 'c2', 0.9, 'test', CURRENT_TIMESTAMP, 'analyst',
                                CURRENT_TIMESTAMP)
                        """)
                .param("id", proposalId)
                .param("trigger", id("u2"))
                .update();
        insertProposalAccount(proposalId, "A", id("u1"));
        insertProposalAccount(proposalId, "B", id("u2"));
        assertThat(clusterOf(id("u1"))).isNotEqualTo(clusterOf(id("u2")));

        // Same as a restart.
        engine.withLock(session -> {
            session.rebuildFromDatabase();
            return null;
        });

        assertThat(clusterOf(id("u1"))).isEqualTo(clusterOf(id("u2")));
    }

    private void insertProposalAccount(UUID proposalId, String side, String accountId) {
        jdbc.sql("INSERT INTO merge_proposal_accounts (proposal_id, side, account_id) VALUES (:id, :side, :account)")
                .param("id", proposalId)
                .param("side", side)
                .param("account", accountId)
                .update();
    }

    @Test
    void badProposalRequestsAreReportedClearly() {
        decideProposal(UUID.randomUUID().toString(), "accept").statusCode(404);
        given().get("/api/v1/merge-proposals/{id}", UUID.randomUUID()).then().statusCode(404);
        decideProposal("not-a-uuid", "accept").statusCode(400);
        given().queryParam("status", "MAYBE").get("/api/v1/merge-proposals").then().statusCode(400);
        given().queryParam("limit", 0).get("/api/v1/merge-proposals").then().statusCode(400);
        given().contentType(ContentType.JSON).body("{\"note\":\"" + "x".repeat(2001) + "\"}")
                .post("/api/v1/merge-proposals/{id}/reject", UUID.randomUUID()).then().statusCode(400);
    }
}
