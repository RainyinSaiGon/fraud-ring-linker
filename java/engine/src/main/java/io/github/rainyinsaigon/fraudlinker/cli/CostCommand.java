package io.github.rainyinsaigon.fraudlinker.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.rainyinsaigon.fraudlinker.LinkerException;
import io.github.rainyinsaigon.fraudlinker.clustering.ClusteringConfig;
import io.github.rainyinsaigon.fraudlinker.domain.Account;
import io.github.rainyinsaigon.fraudlinker.domain.Constraint;
import io.github.rainyinsaigon.fraudlinker.evaluation.CostModel;
import io.github.rainyinsaigon.fraudlinker.evaluation.CostReport;
import io.github.rainyinsaigon.fraudlinker.evaluation.Metrics;
import io.github.rainyinsaigon.fraudlinker.evaluation.Truth;
import io.github.rainyinsaigon.fraudlinker.io.JsonOutput;
import io.github.rainyinsaigon.fraudlinker.io.JsonlReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * {@code cost}: links a labelled dataset in batch and in stream mode, and reports
 * what each mode's mistakes cost against the ground truth.
 *
 * <pre>
 * linker cost --accounts A --constraints C --truth T
 *             [--wrong-link-cost 50] [--missed-ring-cost 2000]
 *             [--format text|json] [--write-clusters DIR]
 * </pre>
 */
final class CostCommand {
    static final Set<String> FLAGS = Set.of("accounts", "constraints", "truth", "wrong-link-cost",
            "missed-ring-cost", "format", "write-clusters");

    private CostCommand() {
    }

    static String run(Map<String, String> flags) {
        String accountsPath = require(flags, "accounts");
        String constraintsPath = require(flags, "constraints");
        String truthPath = require(flags, "truth");
        CostModel costs = new CostModel(
                parseCost(flags, "wrong-link-cost", CostModel.DEFAULT.wrongLinkCost()),
                parseCost(flags, "missed-ring-cost", CostModel.DEFAULT.missedRingCost()));
        String format = flags.getOrDefault("format", "text");
        if (!format.equals("text") && !format.equals("json")) {
            throw new LinkerException("--format must be text or json");
        }

        List<Account> accounts = JsonlReader.readAccounts(Path.of(accountsPath));
        List<Constraint> constraints = JsonlReader.readConstraints(Path.of(constraintsPath));
        Truth truth = Truth.read(Path.of(truthPath));
        CostReport.Result result = CostReport.run(accounts, constraints, truth, ClusteringConfig.defaults(), costs);

        String clustersDir = flags.get("write-clusters");
        if (clustersDir != null) {
            writeClusters(Path.of(clustersDir), result);
        }
        return format.equals("json") ? json(costs, result) : text(costs, result);
    }

    private static String require(Map<String, String> flags, String name) {
        String value = flags.get(name);
        if (value == null || value.isEmpty()) {
            throw new LinkerException("cost requires --accounts, --constraints and --truth");
        }
        return value;
    }

    private static long parseCost(Map<String, String> flags, String name, long fallback) {
        String value = flags.get(name);
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new LinkerException("--" + name + " must be a whole number of dollars");
        }
    }

    private static void writeClusters(Path dir, CostReport.Result result) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("batch-clusters.json"), JsonOutput.clusters(result.batchClusters()));
            Files.writeString(dir.resolve("stream-clusters.json"), JsonOutput.clusters(result.streamClusters()));
        } catch (IOException e) {
            throw new LinkerException("write clusters to \"" + dir + "\": " + e.getMessage(), e);
        }
    }

    private static String json(CostModel costs, CostReport.Result result) {
        ObjectMapper mapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .enable(SerializationFeature.INDENT_OUTPUT)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("costs", Map.of("wrong_link", costs.wrongLinkCost(), "missed_ring", costs.missedRingCost()));
        body.put("batch", result.batch());
        body.put("stream", result.stream());
        try {
            return mapper.writeValueAsString(body) + "\n";
        } catch (IOException e) {
            throw new LinkerException("encode cost report: " + e.getMessage(), e);
        }
    }

    private static String text(CostModel costs, CostReport.Result result) {
        Metrics batch = result.batch();
        Metrics stream = result.stream();
        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT, "Costs: $%,d per wrongly linked account, $%,d per missed ring%n%n",
                costs.wrongLinkCost(), costs.missedRingCost()));
        out.append(String.format(Locale.ROOT, "%-34s %12s %12s%n", "", "batch", "stream"));
        row(out, "Accounts", batch, stream, m -> String.valueOf(m.accountCount()));
        row(out, "Clusters", batch, stream, m -> String.valueOf(m.clusterCount()));
        row(out, "Largest cluster", batch, stream, m -> String.valueOf(m.largestCluster()));
        row(out, "Fraud rings fully linked", batch, stream,
                m -> m.fullyRecoveredFraudRings() + " / " + m.fraudRingCount());
        row(out, "Multi-account actors split", batch, stream, m -> String.valueOf(m.splitMultiAccountActors()));
        row(out, "Wrongly linked accounts", batch, stream, m -> String.valueOf(m.wronglyLinkedAccounts()));
        Set<String> kinds = new TreeSet<>(batch.wronglyLinkedByKind().keySet());
        kinds.addAll(stream.wronglyLinkedByKind().keySet());
        for (String kind : kinds) {
            row(out, "  " + kind, batch, stream, m -> String.valueOf(m.wronglyLinkedByKind().getOrDefault(kind, 0L)));
        }
        row(out, "Pair precision", batch, stream, m -> String.format(Locale.ROOT, "%.4f", m.pairPrecision()));
        row(out, "Pair recall", batch, stream, m -> String.format(Locale.ROOT, "%.4f", m.pairRecall()));
        row(out, "Cost", batch, stream, m -> String.format(Locale.ROOT, "$%,d", m.cost()));
        row(out, "Cost, fraud-list view", batch, stream, m -> String.format(Locale.ROOT, "$%,d", m.fraudListCost()));
        return out.toString();
    }

    private static void row(StringBuilder out, String label, Metrics batch, Metrics stream,
                            Function<Metrics, String> value) {
        out.append(String.format(Locale.ROOT, "%-34s %12s %12s%n", label, value.apply(batch), value.apply(stream)));
    }
}
