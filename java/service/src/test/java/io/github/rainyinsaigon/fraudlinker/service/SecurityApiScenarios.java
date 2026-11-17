package io.github.rainyinsaigon.fraudlinker.service;

import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

/**
 * Tokens and roles: which role may call each endpoint, and which tokens are refused.
 * Bodies here are empty or IDs unknown, so an allowed call fails validation (400)
 * or finds nothing (404) and changes no state; only 401 and 403 mean "refused".
 */
abstract class SecurityApiScenarios extends LoginApiScenarios {

    private record Endpoint(String method, String path, Set<String> roles) {
        String pattern() {
            return method + " " + path.replaceAll("/(c999999999|nobody|999999)(?=/|$)", "/{}");
        }
    }

    private static final Set<String> READERS = Set.of("analyst", "auditor");
    private static final Set<String> INGEST = Set.of("ingest");
    private static final Set<String> ANALYST = Set.of("analyst");

    private static final List<Endpoint> ENDPOINTS = List.of(
            new Endpoint("POST", "/api/v1/accounts", INGEST),
            new Endpoint("POST", "/api/v1/constraints", INGEST),
            new Endpoint("POST", "/api/v1/transfers", INGEST),
            new Endpoint("POST", "/api/v1/logins", INGEST),
            new Endpoint("POST", "/api/v1/clusters/c999999999/decisions", ANALYST),
            new Endpoint("POST", "/api/v1/merge-proposals/999999/accept", ANALYST),
            new Endpoint("POST", "/api/v1/merge-proposals/999999/reject", ANALYST),
            new Endpoint("POST", "/api/v1/accounts/nobody/takeover-decisions", ANALYST),
            new Endpoint("GET", "/api/v1/accounts/nobody", READERS),
            new Endpoint("GET", "/api/v1/accounts/nobody/logins", READERS),
            new Endpoint("GET", "/api/v1/accounts/nobody/takeover-decisions", READERS),
            new Endpoint("GET", "/api/v1/accounts/nobody/transfers", READERS),
            new Endpoint("GET", "/api/v1/audit-events", READERS),
            new Endpoint("GET", "/api/v1/clusters", READERS),
            new Endpoint("GET", "/api/v1/clusters/c999999999", READERS),
            new Endpoint("GET", "/api/v1/clusters/c999999999/decisions", READERS),
            new Endpoint("GET", "/api/v1/merge-proposals", READERS),
            new Endpoint("GET", "/api/v1/merge-proposals/999999", READERS),
            new Endpoint("GET", "/api/v1/mules", READERS),
            new Endpoint("GET", "/api/v1/takeovers", READERS));

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    private static Response call(Endpoint endpoint, String authorization) {
        RequestSpecification request = given().header("Authorization", authorization == null ? "" : authorization);
        if (endpoint.method().equals("POST")) {
            request = request.contentType(ContentType.JSON).body("{}");
        }
        return request.request(endpoint.method(), endpoint.path());
    }

    @Test
    void eachRoleMayCallOnlyItsOwnEndpoints() {
        List<String> wrong = new ArrayList<>();
        for (Endpoint endpoint : ENDPOINTS) {
            for (String role : List.of("ingest", "analyst", "auditor")) {
                int status = call(endpoint, TestTokens.bearer(TestTokens.token("u-" + run, role))).statusCode();
                boolean allowed = endpoint.roles().contains(role);
                if (allowed ? status == 401 || status == 403 : status != 403) {
                    wrong.add(endpoint.method() + " " + endpoint.path() + " as " + role + ": " + status);
                }
            }
        }
        assertThat(wrong).isEmpty();
    }

    @Test
    void everyEndpointNeedsAToken() {
        List<String> open = new ArrayList<>();
        for (Endpoint endpoint : ENDPOINTS) {
            int status = call(endpoint, null).statusCode();
            if (status != 401) {
                open.add(endpoint.method() + " " + endpoint.path() + ": " + status);
            }
        }
        assertThat(open).isEmpty();
    }

    /** A new controller method must be added to the table above, so nobody opens an endpoint by accident. */
    @Test
    void theRoleTableCoversEveryEndpoint() {
        Set<String> mapped = new TreeSet<>();
        mappings.getHandlerMethods().keySet().forEach(info -> info.getMethodsCondition().getMethods().forEach(m ->
                info.getPatternValues().forEach(p -> mapped.add(m.name() + " " + p.replaceAll("\\{[^}]+}", "{}")))));
        Set<String> tested = new TreeSet<>();
        ENDPOINTS.forEach(e -> tested.add(e.pattern()));

        assertThat(mapped).filteredOn(m -> m.contains("/api/")).containsExactlyInAnyOrderElementsOf(tested);
    }

    @Test
    void healthAndInfoStayOpenAndOtherPathsAreClosed() {
        given().header("Authorization", "").get("/actuator/health").then().statusCode(200);
        given().header("Authorization", "").get("/actuator/info").then().statusCode(200);
        given().header("Authorization", "").get("/actuator/env").then().statusCode(401);
        given().get("/actuator/env").then().statusCode(403);
        given().get("/api/v1/nothing-here").then().statusCode(404);
        given().delete("/api/v1/clusters/c1").then().statusCode(403);
        given().contentType(ContentType.JSON).body("{}").put("/api/v1/accounts/nobody").then().statusCode(403);
    }

    @Test
    void badTokensAreRefusedWithoutSayingWhy() {
        String subject = "u-" + run;
        List<String> refused = List.of(
                "Bearer not-a-token",
                "Basic dXNlcjpwYXNz",
                TestTokens.bearer(TestTokens.signedByStranger(subject, "analyst")),
                TestTokens.bearer(TestTokens.token(subject, c -> c.claim("roles", List.of("analyst"))
                        .expirationTime(Date.from(Instant.now().minus(Duration.ofMinutes(10)))))),
                TestTokens.bearer(TestTokens.token(subject, c -> c.claim("roles", List.of("analyst"))
                        .expirationTime(null))),
                TestTokens.bearer(TestTokens.token(subject, c -> c.claim("roles", List.of("analyst"))
                        .issuer("https://someone-else.test"))),
                TestTokens.bearer(TestTokens.token(subject, c -> c.claim("roles", List.of("analyst"))
                        .audience("another-service"))),
                TestTokens.bearer(TestTokens.token(subject, c -> c.claim("roles", List.of("analyst"))
                        .notBeforeTime(Date.from(Instant.now().plus(Duration.ofMinutes(10)))))));
        for (String authorization : refused) {
            given().header("Authorization", authorization).get("/api/v1/clusters").then()
                    .statusCode(401)
                    .header("WWW-Authenticate", equalTo("Bearer"))
                    .contentType(startsWith("application/problem+json"))
                    .body("title", equalTo("Unauthorized"))
                    .body("detail", equalTo("A valid bearer token is required."));
        }
    }

    @Test
    void aTokenWithNoKnownRoleIsForbidden() {
        String subject = "u-" + run;
        for (String token : List.of(
                TestTokens.token(subject),
                TestTokens.token(subject, "admin", "root"),
                TestTokens.token(subject, c -> c.claim("roles", "analyst")),
                TestTokens.token(subject, c -> c))) {
            given().header("Authorization", TestTokens.bearer(token)).get("/api/v1/clusters").then()
                    .statusCode(403)
                    .contentType(startsWith("application/problem+json"))
                    .body("detail", equalTo("Your role can't do this."));
        }
    }

    @Test
    void rolesAreCaseInsensitive() {
        given().header("Authorization", TestTokens.bearer(TestTokens.token("u-" + run, "Auditor")))
                .get("/api/v1/clusters").then().statusCode(200);
    }

    @Test
    void aRefusedWriteChangesNothing() {
        String accountId = id("refused");
        given().header("Authorization", TestTokens.bearer(TestTokens.token("u-" + run, "analyst", "auditor")))
                .contentType(ContentType.JSON)
                .body(accountJson(accountId, accountId + "@example.com", null, isolatedIp(), null))
                .post("/api/v1/accounts").then().statusCode(403);

        given().get("/api/v1/accounts/{id}", accountId).then().statusCode(404);
    }
}
