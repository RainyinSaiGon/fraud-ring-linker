package io.github.rainyinsaigon.fraudlinker.service.security;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Every API call needs a bearer token (a signed JWT) with a role that may make it:
 *
 * <ul>
 *   <li>{@code ingest}: register accounts, constraints, transfers and logins; nothing else</li>
 *   <li>{@code analyst}: read everything and make decisions</li>
 *   <li>{@code auditor}: read everything; no decisions</li>
 * </ul>
 *
 * Roles come from the token's {@code roles} claim, a list of strings; unknown roles
 * are ignored. Health and info stay open for probes. Anything not listed here is
 * refused, so a new endpoint is closed until someone opens it on purpose.
 */
@Configuration
public class SecurityConfig {
    public static final String INGEST = "INGEST";
    public static final String ANALYST = "ANALYST";
    public static final String AUDITOR = "AUDITOR";
    static final Set<String> ROLES = Set.of(INGEST, ANALYST, AUDITOR);
    static final String ROLES_CLAIM = "roles";

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, JwtDecoder decoder) throws Exception {
        AuthenticationEntryPoint unauthorized = (request, response, e) -> problem(response, HttpStatus.UNAUTHORIZED,
                "Unauthorized", "A valid bearer token is required.");
        AccessDeniedHandler forbidden = (request, response, e) -> problem(response, HttpStatus.FORBIDDEN,
                "Forbidden", "Your role can't do this.");
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(rules -> rules
                        // Error pages render the status of a request that already passed or failed these rules.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**", "/actuator/info")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/accounts", "/api/v1/constraints",
                                "/api/v1/transfers", "/api/v1/logins").hasRole(INGEST)
                        .requestMatchers(HttpMethod.POST, "/api/v1/clusters/*/decisions",
                                "/api/v1/merge-proposals/*/accept", "/api/v1/merge-proposals/*/reject",
                                "/api/v1/accounts/*/takeover-decisions").hasRole(ANALYST)
                        .requestMatchers(HttpMethod.GET, "/api/v1/**").hasAnyRole(ANALYST, AUDITOR)
                        .anyRequest().denyAll())
                .oauth2ResourceServer(server -> server
                        .jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(authentication()))
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden));
        return http.build();
    }

    /**
     * Checks the signature, the issuer, the audience and the expiry (with a minute of
     * clock skew). A token without an expiry is refused.
     */
    @Bean
    JwtDecoder jwtDecoder(SecurityProperties properties) {
        boolean jwks = properties.jwkSetUri() != null;
        boolean pem = properties.publicKey() != null;
        if (jwks == pem) {
            throw new IllegalStateException("Set exactly one of linker.security.jwk-set-uri (the identity "
                    + "provider's signing keys) and linker.security.public-key (a PEM file, for local runs)");
        }
        NimbusJwtDecoder decoder = jwks
                ? NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri().toString()).build()
                : NimbusJwtDecoder.withPublicKey(readKey(properties)).build();
        OAuth2TokenValidator<Jwt> expiry = new JwtClaimValidator<Instant>(JwtClaimNames.EXP, Objects::nonNull);
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                aud -> aud != null && aud.contains(properties.audience()));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuer()), expiry, audience));
        return decoder;
    }

    private static RSAPublicKey readKey(SecurityProperties properties) {
        try (InputStream in = properties.publicKey().getInputStream()) {
            return RsaKeyConverters.x509().convert(in);
        } catch (IOException e) {
            throw new UncheckedIOException("read linker.security.public-key " + properties.publicKey(), e);
        }
    }

    static JwtAuthenticationConverter authentication() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(SecurityConfig::roles);
        return converter;
    }

    /** {@code ROLE_ANALYST} and so on, from the {@code roles} claim. Anything else in the claim is ignored. */
    static Collection<GrantedAuthority> roles(Jwt jwt) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        if (jwt.getClaims().get(ROLES_CLAIM) instanceof Collection<?> values) {
            for (Object value : values) {
                if (value instanceof String role && ROLES.contains(role.toUpperCase(Locale.ROOT))) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase(Locale.ROOT)));
                }
            }
        }
        return authorities;
    }

    /** An RFC 9457 problem, like the API's other errors. It never says which check failed. */
    private static void problem(HttpServletResponse response, HttpStatus status, String title, String detail)
            throws IOException {
        response.setStatus(status.value());
        if (status == HttpStatus.UNAUTHORIZED) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("""
                {"type":"about:blank","title":"%s","status":%d,"detail":"%s"}""".formatted(title, status.value(),
                detail));
    }
}
