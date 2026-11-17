package io.github.rainyinsaigon.fraudlinker.service.api;

/**
 * Masks customer details in API responses, for every role. Linking and storage
 * keep the full values; only what leaves the service is masked. An analyst who
 * needs the full details asks for them with an audited reveal.
 *
 * <p>A mask hides enough that a screenshot or a shared screen doesn't give away
 * who the customer is, and keeps enough to tell obviously different values apart
 * (another domain, another network). Two equal masks don't prove two equal
 * values; the cluster's link explanations say which signals really match.
 */
public final class Pii {
    static final String HIDDEN = "***";

    private Pii() {
    }

    /** {@code jane.doe@example.com} becomes {@code j***@example.com}. */
    public static String email(String email) {
        if (email == null || email.isEmpty()) {
            return email;
        }
        int at = email.lastIndexOf('@');
        if (at <= 0 || at == email.length() - 1) {
            return HIDDEN;
        }
        return email.substring(0, 1) + HIDDEN + email.substring(at);
    }

    /**
     * {@code 203.0.113.7} becomes {@code 203.0.113.x}, and an IPv6 address keeps
     * its first three groups ({@code 2001:db8:85a3:x}). Anything else is hidden.
     */
    public static String ip(String ip) {
        if (ip == null || ip.isEmpty()) {
            return ip;
        }
        if (ip.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            return ip.substring(0, ip.lastIndexOf('.')) + ".x";
        }
        if (ip.indexOf(':') >= 0 && ip.matches("[0-9A-Fa-f:]+")) {
            String[] groups = ip.split(":", -1);
            if (groups.length >= 4 && !groups[0].isEmpty() && !groups[1].isEmpty() && !groups[2].isEmpty()) {
                return groups[0] + ":" + groups[1] + ":" + groups[2] + ":x";
            }
        }
        return HIDDEN;
    }
}
