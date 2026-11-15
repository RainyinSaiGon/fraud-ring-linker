package io.github.rainyinsaigon.fraudlinker.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rainyinsaigon.fraudlinker.LinkerException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Which accounts really are mules, and which are hard legitimate cases a mule
 * detector must not flag. Read from a simulator {@code truth.json}, schema
 * version 2 or later.
 *
 * @param muleAccountIds every account in a planted mule chain
 * @param caseOf         hard legitimate accounts by case, such as {@code small_business}
 */
public record MuleTruth(Set<String> muleAccountIds, Map<String, String> caseOf) {

    public MuleTruth {
        muleAccountIds = Set.copyOf(muleAccountIds);
        caseOf = Map.copyOf(caseOf);
        for (String id : caseOf.keySet()) {
            if (muleAccountIds.contains(id)) {
                throw new LinkerException("account " + id + " is both a mule and a legitimate case");
            }
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static MuleTruth read(Path path) {
        JsonNode root;
        try {
            root = MAPPER.readTree(path.toFile());
        } catch (IOException e) {
            throw new LinkerException("read truth \"" + path + "\": " + e.getMessage(), e);
        }
        if (root == null || !root.isObject() || !root.path("mule_account_ids").isArray()) {
            throw new LinkerException("truth \"" + path + "\" has no mule_account_ids; export it with the simulator "
                    + "(schema version 2 or later)");
        }
        Set<String> mules = new LinkedHashSet<>();
        for (JsonNode id : root.path("mule_account_ids")) {
            if (id.asText("").isBlank() || !mules.add(id.asText())) {
                throw new LinkerException("truth \"" + path + "\" has an empty or repeated mule account ID");
            }
        }
        Map<String, String> cases = new LinkedHashMap<>();
        for (JsonNode c : root.path("transfer_cases")) {
            String id = c.path("account_id").asText("");
            String kind = c.path("case").asText("");
            if (id.isBlank() || kind.isBlank() || cases.put(id, kind) != null) {
                throw new LinkerException("truth \"" + path + "\" has an incomplete or repeated transfer case");
            }
        }
        return new MuleTruth(mules, cases);
    }
}
