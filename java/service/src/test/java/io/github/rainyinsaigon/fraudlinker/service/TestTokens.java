package io.github.rainyinsaigon.fraudlinker.service;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.config.HeaderConfig;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Signs test tokens with a key pair made when the tests start. Nothing is
 * committed: the service reads the public half from a temporary file.
 */
public final class TestTokens {
    public static final String ISSUER = "https://issuer.test/fraud-ring-linker";
    public static final String AUDIENCE = "fraud-ring-linker";

    private static final KeyPair KEYS = generate();
    private static final Path PUBLIC_KEY = writePublicKey(KEYS);

    private TestTokens() {
    }

    /** Points the service at the test issuer and key. Call from a {@code @DynamicPropertySource} method. */
    public static void configure(DynamicPropertyRegistry registry) {
        registry.add("linker.security.issuer", () -> ISSUER);
        registry.add("linker.security.audience", () -> AUDIENCE);
        registry.add("linker.security.public-key", () -> PUBLIC_KEY.toUri().toString());
    }

    /**
     * Every request sends a token with all three roles unless a test sets its own
     * {@code Authorization} header, which then replaces it. The token names analyst
     * {@code analyst-<run>} and client {@code ingest-<run>}, so each test can tell its
     * own audit events apart.
     */
    public static void sendByDefault(String run) {
        RestAssured.config = RestAssured.config().headerConfig(HeaderConfig.headerConfig()
                .overwriteHeadersWithName("Authorization", "Content-Type", "Accept"));
        RestAssured.requestSpecification = new RequestSpecBuilder()
                .addHeader("Authorization", bearer(token("user-" + run, claims -> claims
                        .claim("preferred_username", "analyst-" + run)
                        .claim("azp", "ingest-" + run)
                        .claim("roles", List.of("ingest", "analyst", "auditor")))))
                .addFilter(PiiScan.INSTANCE)
                .build();
    }

    public static String bearer(String token) {
        return "Bearer " + token;
    }

    /** A valid token for {@code subject} with these roles, expiring in 5 minutes. */
    public static String token(String subject, String... roles) {
        return token(subject, claims -> claims.claim("roles", List.of(roles)));
    }

    /** A valid token, then changed by {@code change} (for example to expire it or drop a claim). */
    public static String token(String subject, UnaryOperator<JWTClaimsSet.Builder> change) {
        return sign(change.apply(valid(subject)).build(), KEYS.getPrivate());
    }

    /** A token signed with a key the service doesn't know. */
    public static String signedByStranger(String subject, String... roles) {
        return sign(valid(subject).claim("roles", List.of(roles)).build(), generate().getPrivate());
    }

    private static JWTClaimsSet.Builder valid(String subject) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .subject(subject)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofMinutes(5))));
    }

    private static String sign(JWTClaimsSet claims, PrivateKey key) {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        try {
            jwt.sign(new RSASSASigner(key));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        return jwt.serialize();
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path writePublicKey(KeyPair keys) {
        try {
            Path file = Files.createTempFile("fraud-ring-linker-test-key", ".pem");
            file.toFile().deleteOnExit();
            String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keys.getPublic().getEncoded());
            Files.writeString(file, "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n");
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
