package io.github.rainyinsaigon.fraudlinker.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rainyinsaigon.fraudlinker.LinkerException;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Which accounts really were taken over, and which are hard legitimate cases a
 * takeover detector must not flag. Read from a simulator {@code truth.json},
 * schema version 3 or later.
 *
 * @param takeovers planted takeovers by account ID
 * @param caseOf    hard legitimate accounts by case, such as {@code new_phone}
 */
public record TakeoverTruth(Map<String, Planted> takeovers, Map<String, String> caseOf) {

    /**
     * One planted takeover.
     *
     * @param kind             such as {@code phishing}
     * @param firstBadAt       the attacker's first successful login
     * @param attackerLoginIds every attacker login on the account, failures included
     */
    public record Planted(String kind, Instant firstBadAt, Set<String> attackerLoginIds) {
        public Planted {
            attackerLoginIds = Set.copyOf(attackerLoginIds);
        }
    }

    public TakeoverTruth {
        takeovers = Map.copyOf(takeovers);
        caseOf = Map.copyOf(caseOf);
        for (String id : caseOf.keySet()) {
            if (takeovers.containsKey(id)) {
                throw new LinkerException("account " + id + " is both a takeover and a legitimate case");
            }
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static TakeoverTruth read(Path path) {
        JsonNode root;
        try {
            root = MAPPER.readTree(path.toFile());
        } catch (IOException e) {
            throw new LinkerException("read truth \"" + path + "\": " + e.getMessage(), e);
        }
        if (root == null || !root.isObject() || !root.path("takeovers").isArray()) {
            throw new LinkerException("truth \"" + path + "\" has no takeovers; export it with the simulator "
                    + "(schema version 3 or later)");
        }
        Map<String, Planted> takeovers = new LinkedHashMap<>();
        for (JsonNode t : root.path("takeovers")) {
            String id = t.path("account_id").asText("");
            String kind = t.path("kind").asText("");
            Instant firstBadAt;
            try {
                firstBadAt = Instant.parse(t.path("first_bad_at").asText(""));
            } catch (DateTimeParseException e) {
                throw new LinkerException("truth \"" + path + "\" has a takeover with no valid first_bad_at");
            }
            Set<String> logins = new LinkedHashSet<>();
            t.path("attacker_login_ids").forEach(login -> logins.add(login.asText("")));
            if (id.isBlank() || kind.isBlank() || logins.isEmpty() || logins.contains("")
                    || takeovers.put(id, new Planted(kind, firstBadAt, logins)) != null) {
                throw new LinkerException("truth \"" + path + "\" has an incomplete or repeated takeover");
            }
        }
        Map<String, String> cases = new LinkedHashMap<>();
        for (JsonNode c : root.path("login_cases")) {
            String id = c.path("account_id").asText("");
            String kind = c.path("case").asText("");
            if (id.isBlank() || kind.isBlank() || cases.put(id, kind) != null) {
                throw new LinkerException("truth \"" + path + "\" has an incomplete or repeated login case");
            }
        }
        return new TakeoverTruth(takeovers, cases);
    }
}
