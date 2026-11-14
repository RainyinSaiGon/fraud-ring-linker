package io.github.rainyinsaigon.fraudlinker.normalize;

/**
 * Text helpers with fixed, locale-independent behaviour.
 *
 * <p>Java's {@code String.strip()}, {@code Character.isWhitespace} and
 * {@code toLowerCase} disagree with Unicode's White_Space property on a few
 * characters (for example a non-breaking space). These helpers follow the
 * Unicode definition so that the same email always normalizes to the same key,
 * whatever the JVM locale.
 */
public final class Text {
    private Text() {
    }

    /** Reports whether a code point has the Unicode White_Space property. */
    public static boolean isSpace(int codePoint) {
        if (codePoint <= 0xFF) {
            return codePoint == '\t' || codePoint == '\n' || codePoint == 0x0B || codePoint == '\f'
                    || codePoint == '\r' || codePoint == ' ' || codePoint == 0x85 || codePoint == 0xA0;
        }
        return codePoint == 0x1680
                || (codePoint >= 0x2000 && codePoint <= 0x200A)
                || codePoint == 0x2028 || codePoint == 0x2029
                || codePoint == 0x202F || codePoint == 0x205F || codePoint == 0x3000;
    }

    /** Removes leading and trailing Unicode white space. Never returns null. */
    public static String trimSpace(String value) {
        if (value == null) {
            return "";
        }
        int start = 0;
        int end = value.length();
        while (start < end) {
            int codePoint = value.codePointAt(start);
            if (!isSpace(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }
        while (end > start) {
            int codePoint = value.codePointBefore(end);
            if (!isSpace(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }
        return value.substring(start, end);
    }

    /** Lower-cases each code point independently, with no locale or context rules. */
    public static String toLower(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> builder.appendCodePoint(Character.toLowerCase(codePoint)));
        return builder.toString();
    }

    /** Number of Unicode code points (not UTF-16 chars). */
    public static int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }
}
