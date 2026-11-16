package io.github.rainyinsaigon.fraudlinker.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.rainyinsaigon.fraudlinker.LinkerException;
import io.github.rainyinsaigon.fraudlinker.evaluation.TakeoverCostModel;
import io.github.rainyinsaigon.fraudlinker.evaluation.TakeoverEvaluation;
import io.github.rainyinsaigon.fraudlinker.evaluation.TakeoverSweep;
import io.github.rainyinsaigon.fraudlinker.evaluation.TakeoverSweep.Knob;
import io.github.rainyinsaigon.fraudlinker.evaluation.TakeoverTruth;
import io.github.rainyinsaigon.fraudlinker.io.JsonlReader;
import io.github.rainyinsaigon.fraudlinker.takeover.TakeoverConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code takeovers}: measures takeover detection against the simulator's planted
 * takeovers, then sweeps its main thresholds one at a time.
 *
 * <pre>
 * linker takeovers --datasets DIR[,DIR...]
 *                  [--min-history 3,5,8] [--max-speeds 500,900,1500] [--burst-failures 3,5,8]
 *                  [--shared-device-accounts 3,4,6] [--flag-thresholds 0.5,0.6,0.7,0.8,0.9]
 *                  [--costs 50:5000,50:1000,50:200] [--format text|json]
 * </pre>
 *
 * Each dataset directory holds {@code accounts.jsonl}, {@code constraints.jsonl},
 * {@code logins.jsonl} and {@code truth.json} (schema version 3), as written by
 * {@code tools/simulator export}. Costs are {@code falseFlag:missedTakeover}. The
 * current setting is always in the grid. Lines end in a plain newline on every OS.
 */
final class TakeoversCommand {
    static final Set<String> FLAGS = Set.of("datasets", "min-history", "max-speeds", "burst-failures",
            "shared-device-accounts", "flag-thresholds", "costs", "format");

    static final String DEFAULT_HISTORY = "3,5,8";
    static final String DEFAULT_SPEEDS = "500,900,1500";
    static final String DEFAULT_BURSTS = "3,5,8";
    static final String DEFAULT_SHARED = "3,4,6";
    static final String DEFAULT_THRESHOLDS = "0.5,0.6,0.7,0.8,0.9";
    static final String DEFAULT_COSTS = "50:5000,50:1000,50:200";

    private TakeoversCommand() {
    }

    static String run(Map<String, String> flags) {
        String datasetList = flags.get("datasets");
        if (datasetList == null || datasetList.isBlank()) {
            throw new LinkerException("takeovers requires --datasets");
        }
        TakeoverConfig current = TakeoverConfig.defaults();
        Map<Knob, List<Double>> values = new EnumMap<>(Knob.class);
        values.put(Knob.MIN_HISTORY, parseNumbers(flags.getOrDefault("min-history", DEFAULT_HISTORY),
                "--min-history", 1, true, current.minHistory()));
        values.put(Knob.MAX_SPEED_KMH, parseNumbers(flags.getOrDefault("max-speeds", DEFAULT_SPEEDS),
                "--max-speeds", 1, false, current.maxSpeedKmh()));
        values.put(Knob.BURST_FAILURES, parseNumbers(flags.getOrDefault("burst-failures", DEFAULT_BURSTS),
                "--burst-failures", 2, true, current.burstFailures()));
        values.put(Knob.SHARED_DEVICE_ACCOUNTS, parseNumbers(flags.getOrDefault("shared-device-accounts",
                DEFAULT_SHARED), "--shared-device-accounts", 2, true, current.sharedDeviceAccounts()));
        List<Double> thresholds = SweepCommand.parseScores(flags.getOrDefault("flag-thresholds", DEFAULT_THRESHOLDS),
                "--flag-thresholds", current.flagThreshold());
        if (thresholds.contains(0.0)) {
            throw new LinkerException("--flag-thresholds must be above 0");
        }
        List<TakeoverCostModel> prices = new ArrayList<>();
        SweepCommand.parseCosts(flags.getOrDefault("costs", DEFAULT_COSTS))
                .forEach(c -> prices.add(new TakeoverCostModel(c.wrongLinkCost(), c.missedRingCost())));
        String format = flags.getOrDefault("format", "text");
        if (!format.equals("text") && !format.equals("json")) {
            throw new LinkerException("--format must be text or json");
        }

        List<TakeoverEvaluation.Dataset> datasets = new ArrayList<>();
        for (String dir : datasetList.split(",")) {
            datasets.add(readDataset(Path.of(dir.trim())));
        }
        List<TakeoverSweep.Point> points = TakeoverSweep.run(datasets, values, thresholds, current);
        TakeoverSweep.Point now = points.stream().filter(p -> p.config().equals(current)).findFirst().orElseThrow();
        return format.equals("json")
                ? json(datasets, current, now, points, prices)
                : text(datasets, current, now, values, thresholds, points, prices);
    }

    private static TakeoverEvaluation.Dataset readDataset(Path dir) {
        Path name = dir.getFileName();
        return new TakeoverEvaluation.Dataset(name == null ? dir.toString() : name.toString(),
                JsonlReader.readAccounts(dir.resolve("accounts.jsonl")),
                JsonlReader.readConstraints(dir.resolve("constraints.jsonl")),
                JsonlReader.readLogins(dir.resolve("logins.jsonl")),
                TakeoverTruth.read(dir.resolve("truth.json")));
    }

    /** Numbers of at least {@code min} (whole numbers when {@code whole}), plus the current value, sorted. */
    static List<Double> parseNumbers(String list, String flag, double min, boolean whole, double currentValue) {
        TreeSet<Double> values = new TreeSet<>();
        values.add(currentValue);
        String kind = whole ? "whole numbers" : "numbers";
        for (String part : list.split(",")) {
            double value;
            try {
                value = whole ? Integer.parseInt(part.trim()) : Double.parseDouble(part.trim());
            } catch (NumberFormatException e) {
                throw new LinkerException(flag + " must be " + kind + " of " + fmt(min) + " or more, got \""
                        + part.trim() + "\"");
            }
            if (!(value >= min) || Double.isInfinite(value)) {
                throw new LinkerException(flag + " must be " + kind + " of " + fmt(min) + " or more, got \""
                        + part.trim() + "\"");
            }
            values.add(value);
        }
        return List.copyOf(values);
    }

    private static String fmt(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private static String setting(TakeoverConfig c) {
        return String.format(Locale.ROOT, "history %d, speed %s km/h, burst %d, shared device %d, flag at %.2f",
                c.minHistory(), fmt(c.maxSpeedKmh()), c.burstFailures(), c.sharedDeviceAccounts(), c.flagThreshold());
    }

    private static String text(List<TakeoverEvaluation.Dataset> datasets, TakeoverConfig current,
                               TakeoverSweep.Point now, Map<Knob, List<Double>> values, List<Double> thresholds,
                               List<TakeoverSweep.Point> points, List<TakeoverCostModel> prices) {
        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT, "Datasets: %d (%s)\n", datasets.size(),
                String.join(", ", datasets.stream().map(TakeoverEvaluation.Dataset::name).toList())));
        out.append("Current setting (*): ").append(setting(current)).append("\n\n");

        TakeoverEvaluation.Result r = now.result();
        out.append("At the current setting, summed over all datasets:\n");
        out.append(String.format(Locale.ROOT, "  %-34s %d\n", "Takeovers planted", r.planted()));
        out.append(String.format(Locale.ROOT, "  %-34s %d (recall %.1f%%)\n", "Takeovers flagged", r.flagged(),
                100 * r.recall()));
        r.plantedByKind().forEach((kind, n) -> out.append(String.format(Locale.ROOT, "    %-32s %d of %d\n", kind,
                r.flaggedByKind().getOrDefault(kind, 0), n)));
        out.append(String.format(Locale.ROOT, "  %-34s %d (precision %.1f%%)\n", "Legitimate accounts flagged",
                r.falseFlags(), 100 * r.precision()));
        Set<String> kinds = new TreeSet<>(r.hardCasesByCase().keySet());
        kinds.addAll(r.falseFlagsByCase().keySet());
        kinds.remove(TakeoverEvaluation.OTHER);
        for (String kind : kinds) {
            out.append(String.format(Locale.ROOT, "    %-32s %d of %d\n", kind,
                    r.falseFlagsByCase().getOrDefault(kind, 0), r.hardCasesByCase().getOrDefault(kind, 0)));
        }
        out.append(String.format(Locale.ROOT, "    %-32s %d\n", "other accounts",
                r.falseFlagsByCase().getOrDefault(TakeoverEvaluation.OTHER, 0)));
        r.falseFlagsByReasons().forEach((reasons, n) ->
                out.append(String.format(Locale.ROOT, "  %-34s %d\n", "False flags by " + reasons, n)));
        out.append(String.format(Locale.ROOT, "  %-34s %d\n", "Reviews (all flags)", r.reviews()));
        out.append(String.format(Locale.ROOT, "  %-34s mean %.2f", "Attacker sessions up to the flag",
                r.meanSessionsBeforeFlag()));
        r.sessionsBeforeFlag().forEach((n, count) -> out.append(String.format(Locale.ROOT, "; %d: %d", n, count)));
        out.append('\n');
        for (String missed : r.missed().subList(0, Math.min(5, r.missed().size()))) {
            out.append("  Missed takeover: ").append(missed).append('\n');
        }

        out.append("\nTakeovers flagged / legitimate accounts flagged, one threshold changed at a time,"
                + " by flag threshold\n\n");
        out.append(String.format(Locale.ROOT, "%-24s", "threshold"));
        for (double t : thresholds) {
            out.append(String.format(Locale.ROOT, "%-10s", label(t, t == current.flagThreshold())));
        }
        out.append('\n');
        int i = 0;
        for (Map.Entry<Knob, List<Double>> knob : values.entrySet()) {
            for (double v : knob.getValue()) {
                boolean isCurrent = v == knob.getKey().valueIn(current);
                out.append(String.format(Locale.ROOT, "%-24s",
                        knob.getKey().label + " " + fmt(v) + (isCurrent ? "*" : "")));
                for (int k = 0; k < thresholds.size(); k++) {
                    TakeoverEvaluation.Result cell = points.get(i++).result();
                    out.append(String.format(Locale.ROOT, "%-10s", cell.flagged() + " / " + cell.falseFlags()));
                }
                out.append('\n');
            }
        }

        for (TakeoverCostModel price : prices) {
            TakeoverSweep.Point best = TakeoverSweep.cheapest(points, price, current);
            TakeoverEvaluation.Result b = best.result();
            out.append(String.format(Locale.ROOT, "\nAt $%,d per false flag and $%,d per missed takeover:\n",
                    price.falseFlagCost(), price.missedTakeoverCost()));
            out.append(String.format(Locale.ROOT, "  cheapest is %s: $%,d (current $%,d)\n", setting(best.config()),
                    best.cost(price), now.cost(price)));
            out.append(String.format(Locale.ROOT,
                    "  recall %.1f%%, precision %.1f%%, %d reviews, %.2f attacker sessions up to the flag\n",
                    100 * b.recall(), 100 * b.precision(), b.reviews(), b.meanSessionsBeforeFlag()));
        }
        return out.toString();
    }

    private static String label(double value, boolean isCurrent) {
        return String.format(Locale.ROOT, "%.2f%s", value, isCurrent ? "*" : "");
    }

    private static String json(List<TakeoverEvaluation.Dataset> datasets, TakeoverConfig current,
                               TakeoverSweep.Point now, List<TakeoverSweep.Point> points,
                               List<TakeoverCostModel> prices) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("datasets", datasets.stream().map(TakeoverEvaluation.Dataset::name).toList());
        body.put("current", settingJson(current));
        body.put("at_current", result(now.result()));
        List<Map<String, Object>> grid = new ArrayList<>();
        for (TakeoverSweep.Point p : points) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("varied", p.knob().name().toLowerCase(Locale.ROOT));
            row.putAll(settingJson(p.config()));
            row.put("takeovers_flagged", p.result().flagged());
            row.put("false_flags", p.result().falseFlags());
            row.put("mean_sessions_before_flag", p.result().meanSessionsBeforeFlag());
            grid.add(row);
        }
        body.put("points", grid);
        List<Map<String, Object>> answers = new ArrayList<>();
        for (TakeoverCostModel price : prices) {
            TakeoverSweep.Point best = TakeoverSweep.cheapest(points, price, current);
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("false_flag", price.falseFlagCost());
            answer.put("missed_takeover", price.missedTakeoverCost());
            Map<String, Object> cheapest = settingJson(best.config());
            cheapest.put("cost", best.cost(price));
            answer.put("cheapest", cheapest);
            answer.put("current_cost", now.cost(price));
            answers.add(answer);
        }
        body.put("prices", answers);
        try {
            return new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(body) + "\n";
        } catch (IOException e) {
            throw new LinkerException("encode takeover report: " + e.getMessage(), e);
        }
    }

    private static Map<String, Object> result(TakeoverEvaluation.Result r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("takeovers_planted", r.planted());
        m.put("takeovers_flagged", r.flagged());
        m.put("planted_by_kind", r.plantedByKind());
        m.put("flagged_by_kind", r.flaggedByKind());
        m.put("missed_takeovers", r.missed());
        m.put("false_flags", r.falseFlags());
        m.put("false_flags_by_case", r.falseFlagsByCase());
        m.put("false_flags_by_reasons", r.falseFlagsByReasons());
        m.put("hard_cases_by_case", r.hardCasesByCase());
        m.put("reviews", r.reviews());
        m.put("sessions_before_flag", r.sessionsBeforeFlag());
        m.put("mean_sessions_before_flag", r.meanSessionsBeforeFlag());
        m.put("recall", r.recall());
        m.put("precision", r.precision());
        return m;
    }

    private static Map<String, Object> settingJson(TakeoverConfig c) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("min_history", c.minHistory());
        s.put("max_speed_kmh", c.maxSpeedKmh());
        s.put("burst_failures", c.burstFailures());
        s.put("shared_device_accounts", c.sharedDeviceAccounts());
        s.put("flag_threshold", c.flagThreshold());
        return s;
    }
}
