package io.github.rainyinsaigon.fraudlinker.normalize;

import io.github.rainyinsaigon.fraudlinker.domain.Account;
import io.github.rainyinsaigon.fraudlinker.domain.Features;

/**
 * Normalizes an account once, for use by both the batch and streaming paths.
 * Malformed optional fraud signals are treated as unavailable, never as errors.
 */
public final class Normalizer {
    private Normalizer() {
    }

    public static Features normalize(Account account) {
        Email email = email(account.email());
        String device = account.deviceId() == null ? "" : Text.trimSpace(account.deviceId());
        String payment = account.paymentFingerprint() == null ? "" : Text.trimSpace(account.paymentFingerprint());
        IpAddresses.Parsed ip = IpAddresses.parse(account.ip());
        return new Features(
                email.exact(), email.canonical(), email.local(), email.baseLocal(), email.skeleton(), email.domain(),
                device, payment,
                ip == null ? null : ip.address(),
                ip == null ? null : ip.prefix(),
                account.createdAt());
    }

    private record Email(String exact, String canonical, String local, String baseLocal, String skeleton,
                         String domain) {
    }

    private static Email email(String raw) {
        String value = Text.toLower(Text.trimSpace(raw));
        int at = value.lastIndexOf('@');
        if (at <= 0 || at == value.length() - 1) {
            return new Email(value, value, value, value, skeleton(value), "");
        }
        String local = value.substring(0, at);
        String domain = Text.trimSpace(value.substring(at + 1));
        String exact = local + "@" + domain;
        String baseLocal = local;
        switch (domain) {
            case "googlemail.com", "gmail.com" -> {
                // Gmail ignores dots and anything after '+'.
                domain = "gmail.com";
                baseLocal = stripPlus(baseLocal).replace(".", "");
            }
            case "outlook.com", "hotmail.com", "live.com", "proton.me", "protonmail.com" ->
                    baseLocal = stripPlus(baseLocal);
            default -> {
                // Other providers may treat '+' and '.' as significant, so keep them.
            }
        }
        return new Email(exact, baseLocal + "@" + domain, local, baseLocal, skeleton(baseLocal), domain);
    }

    private static String stripPlus(String local) {
        int plus = local.indexOf('+');
        return plus >= 0 ? local.substring(0, plus) : local;
    }

    /**
     * Comparison-only evidence: the local part without separators. It is never an
     * exact identity key and only becomes merge-eligible with an independent signal.
     */
    static String skeleton(String local) {
        StringBuilder builder = new StringBuilder(local.length());
        local.codePoints()
                .filter(cp -> cp != '.' && cp != '_' && cp != '-' && !Text.isSpace(cp))
                .forEach(builder::appendCodePoint);
        return builder.toString();
    }
}
