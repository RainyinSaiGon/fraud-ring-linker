package io.github.rainyinsaigon.fraudlinker.normalize;

/**
 * Strict, offline IP literal parsing.
 *
 * <p>{@code InetAddress.getByName} is deliberately not used: for some malformed
 * input it falls back to a DNS lookup, which would add network latency to the
 * streaming path. This parser never touches the network.
 *
 * <p>Rules: IPv4 is four decimal fields 0-255 with no leading zeros. IPv6 follows
 * the RFC 4291 text form, with an optional embedded IPv4 tail and an optional
 * {@code %zone}. IPv4-mapped IPv6 addresses ({@code ::ffff:a.b.c.d}) are unmapped
 * to IPv4 so both spellings share one key.
 */
public final class IpAddresses {
    private IpAddresses() {
    }

    /** A parsed address in canonical text form plus its linking prefix (/24 or /64). */
    public record Parsed(String address, String prefix) {
    }

    /** Parses an IP literal, or returns null when the value is missing or malformed. */
    public static Parsed parse(String raw) {
        String value = Text.trimSpace(raw);
        if (value.isEmpty()) {
            return null;
        }
        if (value.indexOf(':') < 0) {
            byte[] v4 = parseIpv4(value);
            return v4 == null ? null : ipv4Result(v4);
        }

        String zone = "";
        int percent = value.indexOf('%');
        if (percent >= 0) {
            zone = value.substring(percent + 1);
            value = value.substring(0, percent);
            if (zone.isEmpty()) {
                return null;
            }
        }
        byte[] v6 = parseIpv6(value);
        if (v6 == null) {
            return null;
        }
        if (isIpv4Mapped(v6)) {
            Parsed unmapped = ipv4Result(new byte[] {v6[12], v6[13], v6[14], v6[15]});
            // A zone stays part of the address identity but never of its network prefix.
            return zone.isEmpty() ? unmapped : new Parsed(unmapped.address() + "%" + zone, unmapped.prefix());
        }
        String address = formatIpv6(v6) + (zone.isEmpty() ? "" : "%" + zone);
        byte[] masked = new byte[16];
        System.arraycopy(v6, 0, masked, 0, 8);
        return new Parsed(address, formatIpv6(masked) + "/64");
    }

    private static Parsed ipv4Result(byte[] v4) {
        String prefix = (v4[0] & 0xFF) + "." + (v4[1] & 0xFF) + "." + (v4[2] & 0xFF) + ".0/24";
        return new Parsed(formatIpv4(v4), prefix);
    }

    static byte[] parseIpv4(String value) {
        byte[] result = new byte[4];
        int index = 0;
        for (int field = 0; field < 4; field++) {
            if (field > 0) {
                if (index >= value.length() || value.charAt(index) != '.') {
                    return null;
                }
                index++;
            }
            int start = index;
            int number = 0;
            while (index < value.length() && isDecimal(value.charAt(index))) {
                number = number * 10 + (value.charAt(index) - '0');
                index++;
                if (index - start > 3 || number > 255) {
                    return null;
                }
            }
            int digits = index - start;
            if (digits == 0 || (digits > 1 && value.charAt(start) == '0')) {
                return null;
            }
            result[field] = (byte) number;
        }
        return index == value.length() ? result : null;
    }

    static byte[] parseIpv6(String value) {
        byte[] result = new byte[16];
        int written = 0;
        int ellipsis = -1;
        int index = 0;
        if (value.startsWith("::")) {
            ellipsis = 0;
            index = 2;
            if (index == value.length()) {
                return result;
            }
        }
        while (written < 16) {
            int start = index;
            int group = 0;
            while (index < value.length() && hexValue(value.charAt(index)) >= 0) {
                group = (group << 4) | hexValue(value.charAt(index));
                index++;
                if (index - start > 4) {
                    return null;
                }
            }
            if (index == start) {
                return null;
            }
            if (index < value.length() && value.charAt(index) == '.') {
                // Embedded IPv4 tail: it must fill exactly the last 32 bits.
                if ((ellipsis < 0 && written != 12) || written + 4 > 16) {
                    return null;
                }
                byte[] v4 = parseIpv4(value.substring(start));
                if (v4 == null) {
                    return null;
                }
                System.arraycopy(v4, 0, result, written, 4);
                written += 4;
                index = value.length();
                break;
            }
            result[written++] = (byte) (group >> 8);
            result[written++] = (byte) group;
            if (index == value.length()) {
                break;
            }
            if (value.charAt(index) != ':') {
                return null;
            }
            index++;
            if (index < value.length() && value.charAt(index) == ':') {
                if (ellipsis >= 0) {
                    return null;
                }
                ellipsis = written;
                index++;
                if (index == value.length()) {
                    break;
                }
            } else if (index == value.length()) {
                return null;
            }
        }
        if (index != value.length()) {
            return null;
        }
        if (written < 16) {
            if (ellipsis < 0) {
                return null;
            }
            int shift = 16 - written;
            for (int i = written - 1; i >= ellipsis; i--) {
                result[i + shift] = result[i];
            }
            for (int i = ellipsis; i < ellipsis + shift; i++) {
                result[i] = 0;
            }
        } else if (ellipsis >= 0) {
            // "::" must stand for at least one zero group.
            return null;
        }
        return result;
    }

    private static boolean isDecimal(char c) {
        return c >= '0' && c <= '9';
    }

    private static int hexValue(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    private static boolean isIpv4Mapped(byte[] v6) {
        for (int i = 0; i < 10; i++) {
            if (v6[i] != 0) {
                return false;
            }
        }
        return (v6[10] & 0xFF) == 0xFF && (v6[11] & 0xFF) == 0xFF;
    }

    private static String formatIpv4(byte[] v4) {
        return (v4[0] & 0xFF) + "." + (v4[1] & 0xFF) + "." + (v4[2] & 0xFF) + "." + (v4[3] & 0xFF);
    }

    /** RFC 5952 form: lower case, longest run of 2+ zero groups compressed, leftmost on ties. */
    static String formatIpv6(byte[] v6) {
        int[] groups = new int[8];
        for (int i = 0; i < 8; i++) {
            groups[i] = ((v6[2 * i] & 0xFF) << 8) | (v6[2 * i + 1] & 0xFF);
        }
        int bestStart = -1;
        int bestLength = 1;
        int i = 0;
        while (i < 8) {
            if (groups[i] != 0) {
                i++;
                continue;
            }
            int start = i;
            while (i < 8 && groups[i] == 0) {
                i++;
            }
            if (i - start > bestLength) {
                bestStart = start;
                bestLength = i - start;
            }
        }
        StringBuilder builder = new StringBuilder();
        for (int group = 0; group < 8; group++) {
            if (group == bestStart) {
                builder.append("::");
                group += bestLength - 1;
                continue;
            }
            if (builder.length() > 0 && builder.charAt(builder.length() - 1) != ':') {
                builder.append(':');
            }
            builder.append(Integer.toHexString(groups[group]));
        }
        return builder.toString();
    }
}
