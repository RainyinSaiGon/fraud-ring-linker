package io.github.rainyinsaigon.fraudlinker.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.rainyinsaigon.fraudlinker.LinkerException;
import io.github.rainyinsaigon.fraudlinker.evaluation.MuleCostModel;
import io.github.rainyinsaigon.fraudlinker.evaluation.MuleEvaluation;
import io.github.rainyinsaigon.fraudlinker.evaluation.MuleSweep;
import io.github.rainyinsaigon.fraudlinker.evaluation.MuleTruth;
import io.github.rainyinsaigon.fraudlinker.io.JsonlReader;
import io.github.rainyinsaigon.fraudlinker.mules.MuleConfig;

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
 * {@code mules}: measures mule detection against the simulator's planted mule
 * chains, then sweeps its main thresholds.
 *
 * <pre>
 * linker mules --datasets DIR[,DIR...]
 *              [--fan-in-payers 3,4,5,6,8] [--pass-through-shares 0.6,0.7,0.8,0.9]
 *              [--flag-thresholds 0.7,0.75,0.8,0.85,0.9] [--costs 50:2000,50:500,50:50]
 *              [--format text|json]
 * </pre>
 *
 * Each dataset directory holds {@code accounts.jsonl}, {@code constraints.jsonl},
 * {@code transfers.jsonl} and {@code truth.json} (schema version 2), as written by
 * {@code tools/simulator export}. Costs are {@code falseFlag:missedMule}. The
 * current setting is always in the grid. Lines end in a plain newline on every OS.
 */
final class MulesCommand {
    static final Set<String> FLAGS = Set.of("datasets", "fan-in-payers", "pass-through-shares", "flag-thresholds",
            "costs", "format");

    static final String DEFAULT_PAYERS = "3,4,5,6,8";
    static final String DEFAULT_SHARES = "0.6,0.7,0.8,0.9";
    static final String DEFAULT_THRESHOLDS = "0.7,0.75,0.8,0.85,0.9";
    static final String DEFAULT_COSTS = "50:2000,50:500,50:50";

    private MulesCommand() {
    }

    static String run(Map<String, String> flags) {
        String datasetList = flags.get("datasets");
        if (datasetList == null || datasetList.isBlank()) {
            throw new LinkerException("mules requires --datasets");
        }
        MuleConfig current = MuleConfig.defaults();
        List<Integer> payers = parsePayers(flags.getOrDefault("fan-in-payers", DEFAULT_PAYERS),
                current.fanInMinPayers());
        List<Double> shares = SweepCommand.parseScores(flags.getOrDefault("pass-through-shares", DEFAULT_SHARES),
                "--pass-through-shares", current.passThroughMinShare());
        List<Double> thresholds = SweepCommand.parseScores(flags.getOrDefault("flag-thresholds", DEFAULT_THRESHOLDS),
                "--flag-thresholds", current.flagThreshold());
        if (shares.contains(0.0) || thresholds.contains(0.0)) {
            throw new LinkerException("--pass-through-shares and --flag-thresholds must be above 0");
        }
        List<MuleCostModel> prices = new ArrayList<>();
        SweepCommand.parseCosts(flags.getOrDefault("costs", DEFAULT_COSTS))
                .forEach(c -> prices.add(new MuleCostModel(c.wrongLinkCost(), c.missedRingCost())));
        String format = flags.getOrDefault("format", "text");
        if (!format.equals("text") && !format.equals("json")) {
            throw new LinkerException("--format must be text or json");
        }

        List<MuleEvaluation.Dataset> datasets = new ArrayList<>();
        for (String dir : datasetList.split(",")) {
            datasets.add(readDataset(Path.of(dir.trim())));
        }
        List<MuleSweep.Point> points = MuleSweep.run(datasets, payers, shares, thresholds, current);
        MuleSweep.Point now = points.stream().filter(p -> p.fanInMinPayers() == current.fanInMinPayers()
                        && p.passThroughMinShare() == current.passThroughMinShare()
                        && p.flagThreshold() == current.flagThreshold())
                .findFirst().orElseThrow();
        return format.equals("json")
                ? json(datasets, current, now, points, prices)
                : text(datasets, current, now, payers, shares, thresholds, points, prices);
    }

    private static MuleEvaluation.Dataset readDataset(Path dir) {
        Path name = dir.getFileName();
        return new MuleEvaluation.Dataset(name == null ? dir.toString() : name.toString(),
                JsonlReader.readAccounts(dir.resolve("accounts.jsonl")),
                JsonlReader.readConstraints(dir.resolve("constraints.jsonl")),
                JsonlReader.readTransfers(dir.resolve("transfers.jsonl")),
                MuleTruth.read(dir.resolve("truth.json")));
    }

    /** Whole numbers of 2 or more, plus the current value, sorted. */
    static List<Integer> parsePayers(String list, int currentValue) {
        TreeSet<Integer> values = new TreeSet<>();
        values.add(currentValue);
        for (String part : list.split(",")) {
            int value;
            try {
                value = Integer.parseInt(part.trim());
            } catch (NumberFormatException e) {
                throw new LinkerException("--fan-in-payers must be whole numbers of 2 or more, got \"" + part.trim()
                        + "\"");
            }
            if (value < 2) {
                throw new LinkerException("--fan-in-payers must be whole numbers of 2 or more, got \"" + part.trim()
                        + "\"");
            }
            values.add(value);
        }
        return List.copyOf(values);
    }

    private static String text(List<MuleEvaluation.Dataset> datasets, MuleConfig current, MuleSweep.Point now,
                               List<Integer> payers, List<Double> shares, List<Double> thresholds,
                               List<MuleSweep.Point> points, List<MuleCostModel> prices) {
        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT, "Datasets: %d (%s)\n", datasets.size(),
                String.join(", ", datasets.stream().map(MuleEvaluation.Dataset::name).toList())));
        out.append(String.format(Locale.ROOT,
                "Current setting (*): fan-in %d payers, pass-through %.2f, flag at %.2f\n\n",
                current.fanInMinPayers(), current.passThroughMinShare(), current.flagThreshold()));

        MuleEvaluation.Result r = now.result();
        out.append("At the current setting, summed over all datasets:\n");
        out.append(String.format(Locale.ROOT, "  %-32s %d\n", "Mules planted", r.mulesPlanted()));
        out.append(String.format(Locale.ROOT, "  %-32s %d (recall %.1f%%)\n", "Mules flagged", r.mulesFlagged(),
                100 * r.recall()));
        out.append(String.format(Locale.ROOT, "  %-32s %d (precision %.1f%%)\n", "Legitimate accounts flagged",
                r.falseFlags(), 100 * r.precision()));
        Set<String> kinds = new TreeSet<>(r.hardCasesByCase().keySet());
        kinds.addAll(r.falseFlagsByCase().keySet());
        kinds.remove(MuleEvaluation.OTHER);
        for (String kind : kinds) {
            out.append(String.format(Locale.ROOT, "    %-30s %d of %d\n", kind,
                    r.falseFlagsByCase().getOrDefault(kind, 0), r.hardCasesByCase().getOrDefault(kind, 0)));
        }
        out.append(String.format(Locale.ROOT, "    %-30s %d\n", "other accounts",
                r.falseFlagsByCase().getOrDefault(MuleEvaluation.OTHER, 0)));
        r.falseFlagsByReasons().forEach((reasons, n) ->
                out.append(String.format(Locale.ROOT, "  %-32s %d\n", "False flags by " + reasons, n)));
        for (String missed : r.missedMules().subList(0, Math.min(5, r.missedMules().size()))) {
            out.append("  Missed mule: ").append(missed).append('\n');
        }

        out.append("\nMules flagged / legitimate accounts flagged, by flag threshold\n\n");
        out.append(String.format(Locale.ROOT, "%-8s %-8s", "payers", "share"));
        for (double t : thresholds) {
            out.append(String.format(Locale.ROOT, "%-10s", label(t, t == current.flagThreshold())));
        }
        out.append('\n');
        int i = 0;
        for (int p : payers) {
            for (double s : shares) {
                out.append(String.format(Locale.ROOT, "%-8s %-8s", p + (p == current.fanInMinPayers() ? "*" : ""),
                        label(s, s == current.passThroughMinShare())));
                for (int k = 0; k < thresholds.size(); k++) {
                    MuleEvaluation.Result cell = points.get(i++).result();
                    out.append(String.format(Locale.ROOT, "%-10s", cell.mulesFlagged() + " / " + cell.falseFlags()));
                }
                out.append('\n');
            }
        }

        for (MuleCostModel price : prices) {
            MuleSweep.Point best = MuleSweep.cheapest(points, price, current);
            out.append(String.format(Locale.ROOT, "\nAt $%,d per false flag and $%,d per missed mule:\n",
                    price.falseFlagCost(), price.missedMuleCost()));
            out.append(String.format(Locale.ROOT,
                    "  cheapest is fan-in %d payers, pass-through %.2f, flag at %.2f: $%,d (current $%,d)\n",
                    best.fanInMinPayers(), best.passThroughMinShare(), best.flagThreshold(), best.cost(price),
                    now.cost(price)));
        }
        return out.toString();
    }

    private static String label(double value, boolean isCurrent) {
        return String.format(Locale.ROOT, "%.2f%s", value, isCurrent ? "*" : "");
    }

    private static String json(List<MuleEvaluation.Dataset> datasets, MuleConfig current, MuleSweep.Point now,
                               List<MuleSweep.Point> points, List<MuleCostModel> prices) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("datasets", datasets.stream().map(MuleEvaluation.Dataset::name).toList());
        body.put("current", setting(current.fanInMinPayers(), current.passThroughMinShare(), current.flagThreshold()));
        body.put("at_current", result(now.result()));
        List<Map<String, Object>> grid = new ArrayList<>();
        for (MuleSweep.Point p : points) {
            Map<String, Object> row = setting(p.fanInMinPayers(), p.passThroughMinShare(), p.flagThreshold());
            row.put("mules_flagged", p.result().mulesFlagged());
            row.put("false_flags", p.result().falseFlags());
            grid.add(row);
        }
        body.put("points", grid);
        List<Map<String, Object>> answers = new ArrayList<>();
        for (MuleCostModel price : prices) {
            MuleSweep.Point best = MuleSweep.cheapest(points, price, current);
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("false_flag", price.falseFlagCost());
            answer.put("missed_mule", price.missedMuleCost());
            Map<String, Object> cheapest = setting(best.fanInMinPayers(), best.passThroughMinShare(),
                    best.flagThreshold());
            cheapest.put("cost", best.cost(price));
            answer.put("cheapest", cheapest);
            answer.put("current_cost", now.cost(price));
            answers.add(answer);
        }
        body.put("prices", answers);
        try {
            return new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(body) + "\n";
        } catch (IOException e) {
            throw new LinkerException("encode mule report: " + e.getMessage(), e);
        }
    }

    private static Map<String, Object> result(MuleEvaluation.Result r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mules_planted", r.mulesPlanted());
        m.put("mules_flagged", r.mulesFlagged());
        m.put("missed_mules", r.missedMules());
        m.put("false_flags", r.falseFlags());
        m.put("false_flags_by_case", r.falseFlagsByCase());
        m.put("false_flags_by_reasons", r.falseFlagsByReasons());
        m.put("hard_cases_by_case", r.hardCasesByCase());
        m.put("recall", r.recall());
        m.put("precision", r.precision());
        return m;
    }

    private static Map<String, Object> setting(int payers, double share, double threshold) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("fan_in_min_payers", payers);
        s.put("pass_through_min_share", share);
        s.put("flag_threshold", threshold);
        return s;
    }
}
