package io.github.rainyinsaigon.fraudlinker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.filter.Filter;
import io.restassured.filter.FilterContext;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Watches every request the scenario tests make. It remembers each email and IP
 * the tests send in, and fails any response that gives one back, except a reveal,
 * whose job is to. So every endpoint, in every scenario, is checked for leaks.
 */
final class PiiScan implements Filter {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> SENT = ConcurrentHashMap.newKeySet();

    static final PiiScan INSTANCE = new PiiScan();

    private PiiScan() {
    }

    @Override
    public Response filter(FilterableRequestSpecification request, FilterableResponseSpecification response,
                           FilterContext context) {
        remember(request.getURI(), request.getBody());
        Response result = context.next(request, response);
        if (!request.getURI().contains("/reveal")) {
            check(request.getMethod() + " " + request.getURI(), result.asString());
        }
        return result;
    }

    private static void remember(String uri, Object body) {
        if (!(body instanceof String text) || !(uri.endsWith("/api/v1/accounts") || uri.endsWith("/api/v1/logins"))) {
            return;
        }
        try {
            JsonNode root = JSON.readTree(text);
            List<JsonNode> records = root.has("logins") ? List.copyOf(toList(root.get("logins"))) : List.of(root);
            for (JsonNode record : records) {
                for (String field : List.of("email", "ip")) {
                    String value = record.path(field).asText("");
                    if (value.length() >= 7) {
                        SENT.add(value);
                    }
                }
            }
        } catch (Exception e) {
            // Not JSON: a test of bad input, which sends nothing worth remembering.
        }
    }

    private static List<JsonNode> toList(JsonNode array) {
        List<JsonNode> list = new java.util.ArrayList<>();
        array.forEach(list::add);
        return list;
    }

    /** Fails when {@code body} contains any email or IP a test has sent. */
    static void check(String where, String body) {
        for (String value : SENT) {
            if (body.contains(value)) {
                throw new AssertionError(where + " returned the raw value \"" + value + "\": " + body);
            }
        }
    }

    /** For a test of this scan itself. */
    static void rememberForTest(String value) {
        SENT.add(value);
    }
}
