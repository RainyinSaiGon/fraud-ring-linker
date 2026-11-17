package io.github.rainyinsaigon.fraudlinker.service.security;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Who made a request, from its token. Never from the request body: a body can name
 * anyone, a signed token can't. Names are what the audit log and decisions record,
 * so they're capped at the 128 characters those columns hold.
 */
public final class Caller {
    /** The longest name stored; the {@code sub} validator refuses longer subjects. */
    public static final int MAX_NAME = 128;

    private Caller() {
    }

    /** A person: {@code preferred_username}, or {@code sub} when that's missing, blank or too long. */
    public static String person(Jwt jwt) {
        return first(jwt, "preferred_username", "sub");
    }

    /**
     * A system calling with the {@code ingest} role: the OAuth2 client ({@code azp}, or
     * {@code client_id}), or {@code sub} when the token names no client.
     */
    public static String client(Jwt jwt) {
        return first(jwt, "azp", "client_id", "sub");
    }

    private static String first(Jwt jwt, String... claims) {
        for (String claim : claims) {
            if (jwt.getClaims().get(claim) instanceof String value && usable(value)) {
                return value.strip();
            }
        }
        // The decoder only accepts tokens with a usable sub, so this means a caller skipped it.
        throw new IllegalStateException("token has no usable subject");
    }

    static boolean usable(String name) {
        return name != null && !name.isBlank() && name.strip().length() <= MAX_NAME;
    }
}
