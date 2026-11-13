package io.github.rainyinsaigon.fraudlinker.io;

import io.github.rainyinsaigon.fraudlinker.domain.Assignment;
import io.github.rainyinsaigon.fraudlinker.domain.Cluster;
import io.github.rainyinsaigon.fraudlinker.domain.ClustersOutput;

import java.math.BigDecimal;
import java.util.List;

/**
 * Writes the two output formats with a fixed layout: two-space indented batch
 * output and one compact line per stream event. The layout matches the Go
 * implementation byte for byte, which lets a parity test compare the two
 * outputs directly.
 */
public final class JsonOutput {
    private JsonOutput() {
    }

    /** The batch {@code clusters.json} document, ending with a newline. */
    public static String clusters(ClustersOutput output) {
        StringBuilder out = new StringBuilder();
        out.append("{\n  \"clusters\": [");
        List<Cluster> clusters = output.clusters();
        if (clusters.isEmpty()) {
            out.append("]\n}\n");
            return out.toString();
        }
        out.append('\n');
        for (int i = 0; i < clusters.size(); i++) {
            Cluster cluster = clusters.get(i);
            out.append("    {\n");
            out.append("      \"cluster_id\": ").append(string(cluster.clusterId())).append(",\n");
            out.append("      \"account_ids\": [");
            List<String> ids = cluster.accountIds();
            if (ids.isEmpty()) {
                out.append("],\n");
            } else {
                out.append('\n');
                for (int j = 0; j < ids.size(); j++) {
                    out.append("        ").append(string(ids.get(j)));
                    out.append(j + 1 < ids.size() ? ",\n" : "\n");
                }
                out.append("      ],\n");
            }
            out.append("      \"confidence\": ").append(number(cluster.confidence())).append('\n');
            out.append(i + 1 < clusters.size() ? "    },\n" : "    }\n");
        }
        out.append("  ]\n}\n");
        return out.toString();
    }

    /** One stream response line, without the trailing newline. */
    public static String assignment(Assignment assignment) {
        return "{\"account_id\":" + string(assignment.accountId())
                + ",\"cluster_id\":" + string(assignment.clusterId())
                + ",\"confidence\":" + number(assignment.confidence()) + "}";
    }

    /** Shortest decimal that round-trips, with no exponent and no trailing ".0" (1.0 is "1"). */
    static String number(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("JSON cannot represent " + value);
        }
        if (value == 0) {
            return "0";
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    /**
     * A quoted JSON string. Besides the required escapes, {@code <}, {@code >},
     * {@code &}, U+2028 and U+2029 are escaped so output is safe to embed in HTML
     * or JavaScript.
     */
    static String string(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '<', '>', '&', ' ', ' ' -> unicodeEscape(out, c);
                default -> {
                    if (c < 0x20) {
                        unicodeEscape(out, c);
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private static void unicodeEscape(StringBuilder out, char c) {
        out.append(String.format("\\u%04x", (int) c));
    }
}
