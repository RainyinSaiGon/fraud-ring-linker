package io.github.rainyinsaigon.fraudlinker.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.rainyinsaigon.fraudlinker.LinkerException;
import io.github.rainyinsaigon.fraudlinker.clustering.ClusteringConfig;
import io.github.rainyinsaigon.fraudlinker.evaluation.CostModel;
import io.github.rainyinsaigon.fraudlinker.evaluation.CostSweep;
import io.github.rainyinsaigon.fraudlinker.evaluation.Truth;
import io.github.rainyinsaigon.fraudlinker.io.JsonlReader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code sweep}: finds the cheapest thresholds over several labelled datasets.
 *
 * <pre>
 * linker sweep --datasets DIR[,DIR...]
 *              [--merge-scores 0.55,...] [--stream-thresholds 0.70,...]
 *              [--costs 50:2000,50:500,50:50] [--format text|json]
 * </pre>
 *
 * Each dataset directory holds {@code accounts.jsonl}, {@code constraints.jsonl}
 * and {@code truth.json}, as written by {@code tools/simulator export}. The
 * current setting is always included in the grid, so it can be compared.
 */
final class SweepCommand {
    static final Set<String> FLAGS = Set.of("datasets", "merge-scores", "stream-thresholds", "costs", "format");

    static final String DEFAULT_MERGE_SCORES = "0.55,0.60,0.65,0.70,0.75,0.80,0.85";
    static final String DEFAULT_STREAM_THRESHOLDS = "0.70,0.74,0.78,0.82,0.86,0.90,0.94";
    static final String DEFAULT_COSTS = "50:2000,50:500,50:50";

    private SweepCommand() {
    }

    static String run(Map<String, String> flags) {
        String datasetList = flags.get("datasets");
        if (datasetList == null || datasetList.isBlank()) {
            throw new LinkerException("sweep requires --datasets");
        }
        ClusteringConfig current = ClusteringConfig.defaults();
        List<Double> merges = parseScores(flags.getOrDefault("merge-scores", DEFAULT_MERGE_SCORES),
                "--merge-scores", current.scoring().minMergeScore());
        List<Double> streams = parseScores(flags.getOrDefault("stream-thresholds", DEFAULT_STREAM_THRESHOLDS),
                "--stream-thresholds", current.streamThreshold());
        List<CostModel> prices = parseCosts(flags.getOrDefault("costs", DEFAULT_COSTS));
        String format = flags.getOrDefault("format", "text");
        if (!format.equals("text") && !format.equals("json")) {
            throw new LinkerException("--format must be text or json");
        }

        List<CostSweep.Dataset> datasets = new ArrayList<>();
        for (String dir : datasetList.split(",")) {
            datasets.add(readDataset(Path.of(dir.trim())));
        }
        List<CostSweep.Point> points = CostSweep.run(datasets, merges, streams, current);
        return format.equals("json")
                ? json(datasets, current, points, prices)
                : text(datasets, current, merges, streams, points, prices);
    }

    private static CostSweep.Dataset readDataset(Path dir) {
        Path name = dir.getFileName();
        return new CostSweep.Dataset(name == null ? dir.toString() : name.toString(),
                JsonlReader.readAccounts(dir.resolve("accounts.jsonl")),
                JsonlReader.readConstraints(dir.resolve("constraints.jsonl")),
                Truth.read(dir.resolve("truth.json")));
    }

    /** Parses a comma-separated list of scores in [0, 1], adds the current value, and sorts it. */
    static List<Double> parseScores(String list, String flag, double currentValue) {
        TreeSet<Double> scores = new TreeSet<>();
        scores.add(currentValue);
        for (String part : list.split(",")) {
            double score;
            try {
                score = Double.parseDouble(part.trim());
            } catch (NumberFormatException e) {
                throw new LinkerException(flag + " must be numbers between 0 and 1, got \"" + part.trim() + "\"");
            }
            if (!(score >= 0 && score <= 1)) {
                throw new LinkerException(flag + " must be numbers between 0 and 1, got \"" + part.trim() + "\"");
            }
            scores.add(score);
        }
        return List.copyOf(scores);
    }

    /** Parses {@code wrongLink:missedRing} pairs, for example {@code 50:2000}. */
    static List<CostModel> parseCosts(String list) {
        List<CostModel> prices = new ArrayList<>();
        for (String part : list.split(",")) {
            String[] sides = part.trim().split(":");
            try {
                if (sides.length != 2) {
                    throw new NumberFormatException();
                }
                prices.add(new CostModel(Long.parseLong(sides[0]), Long.parseLong(sides[1])));
            } catch (NumberFormatException e) {
                throw new LinkerException("--costs must look like 50:2000,50:500, got \"" + part.trim() + "\"");
            }
        }
        return prices;
    }

    private static String text(List<CostSweep.Dataset> datasets, ClusteringConfig current, List<Double> merges,
                               List<Double> streams, List<CostSweep.Point> points, List<CostModel> prices) {
        StringBuilder out = new StringBuilder();
        List<String> names = datasets.stream().map(CostSweep.Dataset::name).toList();
        out.append(String.format(Locale.ROOT, "Datasets: %d (%s)%n", datasets.size(), String.join(", ", names)));
        out.append(String.format(Locale.ROOT, "Current setting (*): merge score %.2f, stream threshold %.2f%n%n",
                current.scoring().minMergeScore(), current.streamThreshold()));

        out.append("Mistakes summed over all datasets: wrongly linked accounts / multi-account actors split\n\n");
        out.append(String.format(Locale.ROOT, "%-12s %-10s", "merge score", "batch"));
        out.append("stream, by stream threshold\n");
        out.append(String.format(Locale.ROOT, "%-12s %-10s", "", ""));
        for (double s : streams) {
            out.append(String.format(Locale.ROOT, "%-10s", label(s, s == current.streamThreshold())));
        }
        out.append('\n');
        int i = 0;
        for (double m : merges) {
            CostSweep.Point first = points.get(i);
            out.append(String.format(Locale.ROOT, "%-12s %-10s", label(m, m == current.scoring().minMergeScore()),
                    first.batchWrong() + " / " + first.batchSplit()));
            for (int j = 0; j < streams.size(); j++) {
                CostSweep.Point p = points.get(i++);
                out.append(String.format(Locale.ROOT, "%-10s", p.streamWrong() + " / " + p.streamSplit()));
            }
            out.append('\n');
        }

        CostSweep.Point now = points.stream().filter(p -> p.minMergeScore() == current.scoring().minMergeScore()
                && p.streamThreshold() == current.streamThreshold()).findFirst().orElseThrow();
        for (CostModel price : prices) {
            CostSweep.Point stream = CostSweep.cheapestStream(points, price, current);
            CostSweep.Point batch = CostSweep.cheapestBatch(points, price, current);
            out.append(String.format(Locale.ROOT, "%nAt $%,d per wrongly linked account and $%,d per missed ring (%s):%n",
                    price.wrongLinkCost(), price.missedRingCost(), ratio(price)));
            out.append(String.format(Locale.ROOT,
                    "  Stream: cheapest is merge score %.2f, stream threshold %.2f: $%,d (current $%,d)%n",
                    stream.minMergeScore(), stream.streamThreshold(), stream.streamCost(price), now.streamCost(price)));
            out.append(String.format(Locale.ROOT, "  Batch:  cheapest is merge score %.2f: $%,d (current $%,d)%n",
                    batch.minMergeScore(), batch.batchCost(price), now.batchCost(price)));
        }
        return out.toString();
    }

    private static String label(double value, boolean isCurrent) {
        return String.format(Locale.ROOT, "%.2f%s", value, isCurrent ? "*" : "");
    }

    private static String ratio(CostModel price) {
        if (price.wrongLinkCost() == 0) {
            return "wrong links free";
        }
        double r = (double) price.missedRingCost() / price.wrongLinkCost();
        return r == Math.rint(r) ? String.format(Locale.ROOT, "%d:1", (long) r) : String.format(Locale.ROOT, "%.2f:1", r);
    }

    private static String json(List<CostSweep.Dataset> datasets, ClusteringConfig current,
                               List<CostSweep.Point> points, List<CostModel> prices) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("datasets", datasets.stream().map(CostSweep.Dataset::name).toList());
        body.put("current", setting(current.scoring().minMergeScore(), current.streamThreshold()));
        List<Map<String, Object>> grid = new ArrayList<>();
        for (CostSweep.Point p : points) {
            Map<String, Object> row = setting(p.minMergeScore(), p.streamThreshold());
            row.put("batch_wrongly_linked", p.batchWrong());
            row.put("batch_split_actors", p.batchSplit());
            row.put("stream_wrongly_linked", p.streamWrong());
            row.put("stream_split_actors", p.streamSplit());
            grid.add(row);
        }
        body.put("points", grid);
        List<Map<String, Object>> answers = new ArrayList<>();
        for (CostModel price : prices) {
            CostSweep.Point stream = CostSweep.cheapestStream(points, price, current);
            CostSweep.Point batch = CostSweep.cheapestBatch(points, price, current);
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("wrong_link", price.wrongLinkCost());
            answer.put("missed_ring", price.missedRingCost());
            Map<String, Object> cheapestStream = setting(stream.minMergeScore(), stream.streamThreshold());
            cheapestStream.put("cost", stream.streamCost(price));
            answer.put("cheapest_stream", cheapestStream);
            Map<String, Object> cheapestBatch = new LinkedHashMap<>();
            cheapestBatch.put("min_merge_score", batch.minMergeScore());
            cheapestBatch.put("cost", batch.batchCost(price));
            answer.put("cheapest_batch", cheapestBatch);
            answers.add(answer);
        }
        body.put("prices", answers);
        try {
            return new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(body) + "\n";
        } catch (IOException e) {
            throw new LinkerException("encode sweep report: " + e.getMessage(), e);
        }
    }

    private static Map<String, Object> setting(double merge, double stream) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("min_merge_score", merge);
        s.put("stream_threshold", stream);
        return s;
    }
}
