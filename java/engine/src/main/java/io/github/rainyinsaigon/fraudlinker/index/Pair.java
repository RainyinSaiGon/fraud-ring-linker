package io.github.rainyinsaigon.fraudlinker.index;

/** An unordered pair of account indexes, stored with {@code a < b}. */
public record Pair(int a, int b) {
    public static Pair of(int left, int right) {
        return left <= right ? new Pair(left, right) : new Pair(right, left);
    }
}
