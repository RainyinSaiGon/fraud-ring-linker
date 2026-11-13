package io.github.rainyinsaigon.fraudlinker.service;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Service limits, under the {@code linker.*} prefix.
 *
 * @param maxExplainMembers         members whose pairs are scored when explaining a cluster
 * @param maxClusterMembersReturned largest member list the API returns in full
 */
@Validated
@ConfigurationProperties(prefix = "linker")
public record LinkerProperties(
        @Min(2) @Max(200) int maxExplainMembers,
        @Min(1) @Max(10_000) int maxClusterMembersReturned) {
}
