package io.github.rainyinsaigon.fraudlinker.service.security;

import io.github.rainyinsaigon.fraudlinker.service.FraudLinkerApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityConfigTest {

    private static Jwt jwt(Object roles) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "RS256").subject("u")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (roles != null) {
            builder.claim("roles", roles);
        }
        return builder.build();
    }

    private static List<String> roles(Object claim) {
        return SecurityConfig.roles(jwt(claim)).stream().map(GrantedAuthority::getAuthority).toList();
    }

    @Test
    void mapsKnownRolesInAnyCaseAndIgnoresTheRest() {
        assertThat(roles(List.of("analyst", "AUDITOR", "Ingest"))).containsExactly("ROLE_ANALYST", "ROLE_AUDITOR",
                "ROLE_INGEST");
        assertThat(roles(List.of("admin", 7, "analyst"))).containsExactly("ROLE_ANALYST");
        assertThat(roles("analyst")).isEmpty();
        assertThat(roles(Map.of("analyst", true))).isEmpty();
        assertThat(roles(null)).isEmpty();
    }

    @Test
    void needsExactlyOneKeySource() {
        SecurityConfig config = new SecurityConfig();

        assertThatThrownBy(() -> config.jwtDecoder(new SecurityProperties("i", "a", null, null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("exactly one");
        assertThatThrownBy(() -> config.jwtDecoder(new SecurityProperties("i", "a", URI.create("https://idp.test/jwks"),
                new ByteArrayResource(new byte[0])))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one");
        assertThat(config.jwtDecoder(new SecurityProperties("i", "a", URI.create("https://idp.test/jwks"), null)))
                .isNotNull();
    }

    @Test
    void theServiceWontStartWithoutAnIssuer() {
        SpringApplicationBuilder app = new SpringApplicationBuilder(FraudLinkerApplication.class)
                .profiles("h2")
                .properties("server.port=0", "spring.datasource.url=jdbc:h2:mem:nostart;MODE=PostgreSQL",
                        "linker.security.audience=fraud-ring-linker",
                        "linker.security.jwk-set-uri=https://idp.test/jwks");

        assertThatThrownBy(app::run).hasStackTraceContaining("linker.security.issuer");
    }
}
