package io.github.rainyinsaigon.fraudlinker.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rainyinsaigon.fraudlinker.LinkerException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Who really owns each account: the ground truth a clustering is scored against.
 *
 * <p>Reads the simulator's {@code truth.json}. Only {@code account_to_actor} and
 * {@code fraud_actor_ids} are required, which is also the original evaluator's
 * format, so older files work too. When the file has an {@code actors} list
 * (schema version 1), each actor's {@code kind} is kept for per-population
 * breakdowns.
 *
 * @param accountToActor the actor that owns each account
 * @param fraudActorIds  actors labelled as fraud
 * @param actorKinds     each actor's kind, such as {@code customer} or {@code fraud_ring}; empty if not given
 */
public record Truth(Map<String, String> accountToActor, Set<String> fraudActorIds, Map<String, String> actorKinds) {
    public static final String UNKNOWN_KIND = "unknown";

    public Truth {
        accountToActor = Map.copyOf(accountToActor);
        fraudActorIds = Set.copyOf(fraudActorIds);
        actorKinds = Map.copyOf(actorKinds);
    }

    /** The kind of the actor owning an account, or {@link #UNKNOWN_KIND}. */
    public String kindOfAccount(String accountId) {
        String actor = accountToActor.get(accountId);
        return actor == null ? UNKNOWN_KIND : actorKinds.getOrDefault(actor, UNKNOWN_KIND);
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static Truth read(Path path) {
        JsonNode root;
        try {
            root = MAPPER.readTree(path.toFile());
        } catch (IOException e) {
            throw new LinkerException("read truth \"" + path + "\": " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new LinkerException("truth \"" + path + "\" must be a JSON object");
        }
        Map<String, String> accountToActor = new HashMap<>();
        JsonNode mapping = root.path("account_to_actor");
        if (!mapping.isObject()) {
            throw new LinkerException("truth \"" + path + "\" has no account_to_actor object");
        }
        mapping.fields().forEachRemaining(entry -> accountToActor.put(entry.getKey(), entry.getValue().asText()));

        Set<String> fraud = new LinkedHashSet<>();
        for (JsonNode id : root.path("fraud_actor_ids")) {
            if (!fraud.add(id.asText())) {
                throw new LinkerException("duplicate fraud actor ID \"" + id.asText() + "\"");
            }
        }

        Map<String, String> kinds = new HashMap<>();
        for (JsonNode actor : root.path("actors")) {
            String id = actor.path("actor_id").asText("");
            String kind = actor.path("kind").asText("");
            if (!id.isEmpty() && !kind.isEmpty()) {
                kinds.put(id, kind);
            }
        }
        Truth truth = new Truth(accountToActor, fraud, kinds);
        truth.validate();
        return truth;
    }

    /** The same checks as the original evaluator: no empty IDs, every fraud actor owns an account. */
    void validate() {
        if (accountToActor.isEmpty()) {
            throw new LinkerException("truth contains no accounts");
        }
        Set<String> actors = new LinkedHashSet<>();
        accountToActor.forEach((account, actor) -> {
            if (account.isBlank()) {
                throw new LinkerException("truth contains an empty account ID");
            }
            if (actor.isBlank()) {
                throw new LinkerException("truth account \"" + account + "\" has an empty actor ID");
            }
            actors.add(actor);
        });
        for (String fraudActor : fraudActorIds) {
            if (fraudActor.isBlank()) {
                throw new LinkerException("truth contains an empty fraud actor ID");
            }
            if (!actors.contains(fraudActor)) {
                throw new LinkerException("fraud actor \"" + fraudActor + "\" has no account in truth");
            }
        }
    }
}
