package io.github.rainyinsaigon.fraudlinker.normalize;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Bounded fuzzy-matching helpers for email local parts. All positions are code points. */
public final class EditDistance {
    private EditDistance() {
    }

    /**
     * Returns the original value plus every one-code-point deletion, deduplicated
     * and in a stable order. Two strings within one edit share at least one
     * signature, which makes fuzzy matching an index lookup instead of a scan.
     * Returns an empty list for an empty value or one longer than {@code maxRunes}.
     */
    public static List<String> deletionSignatures(String local, int maxRunes) {
        if (local.isEmpty() || maxRunes <= 0 || Text.codePointLength(local) > maxRunes) {
            return List.of();
        }
        int[] codePoints = local.codePoints().toArray();
        Set<String> result = new LinkedHashSet<>();
        result.add(local);
        for (int skip = 0; skip < codePoints.length; skip++) {
            StringBuilder builder = new StringBuilder(local.length());
            for (int i = 0; i < codePoints.length; i++) {
                if (i != skip) {
                    builder.appendCodePoint(codePoints[i]);
                }
            }
            result.add(builder.toString());
        }
        return new ArrayList<>(result);
    }

    /**
     * Reports whether two strings have Levenshtein distance of at most one.
     * A transposition deliberately counts as two edits.
     */
    public static boolean withinOneEdit(String left, String right) {
        int[] a = left.codePoints().toArray();
        int[] b = right.codePoints().toArray();
        if (Math.abs(a.length - b.length) > 1) {
            return false;
        }
        if (a.length > b.length) {
            int[] swap = a;
            a = b;
            b = swap;
        }
        if (a.length == b.length) {
            int differences = 0;
            for (int i = 0; i < a.length; i++) {
                if (a[i] != b[i] && ++differences > 1) {
                    return false;
                }
            }
            return true;
        }
        int i = 0;
        int j = 0;
        boolean skipped = false;
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) {
                i++;
                j++;
                continue;
            }
            if (skipped) {
                return false;
            }
            skipped = true;
            j++;
        }
        return true;
    }
}
