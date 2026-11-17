package io.github.rainyinsaigon.fraudlinker.service.security;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.validation.annotation.Validated;

import java.net.URI;

/**
 * Where tokens come from, under {@code linker.security.*}. The service only checks
 * tokens; an identity provider issues them. There are no defaults, so the service
 * can't start open by mistake.
 *
 * @param issuer    the {@code iss} every token must carry
 * @param audience  a value the token's {@code aud} must contain, naming this service
 * @param jwkSetUri the identity provider's signing keys (JWKS); give this or {@code publicKey}
 * @param publicKey a PEM file with one RSA public key, for local runs and tests
 */
@Validated
@ConfigurationProperties(prefix = "linker.security")
public record SecurityProperties(
        @NotBlank String issuer,
        @NotBlank String audience,
        URI jwkSetUri,
        Resource publicKey) {
}
