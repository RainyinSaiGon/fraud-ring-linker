package io.github.rainyinsaigon.fraudlinker.service.api;

import io.github.rainyinsaigon.fraudlinker.domain.Account;
import io.github.rainyinsaigon.fraudlinker.service.risk.AnalystDecision;
import io.github.rainyinsaigon.fraudlinker.service.risk.Outcome;
import io.github.rainyinsaigon.fraudlinker.service.risk.RiskAction;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Request and response bodies. JSON field names are snake_case (see
 * {@code spring.jackson.property-naming-strategy}), matching the engine's JSONL format.
 */
public final class ApiModels {
    private ApiModels() {
    }

    /** Account IDs end up in URLs and logs, so only a safe character set is accepted. */
    static final String ACCOUNT_ID_PATTERN = "^[A-Za-z0-9._:-]{1,128}$";

    public record AccountRequest(
            @NotBlank @Pattern(regexp = ACCOUNT_ID_PATTERN) String accountId,
            @NotNull @Size(max = 320) String email,
            @Size(max = 256) String deviceId,
            @Size(max = 64) String ip,
            @Size(max = 256) String paymentFingerprint,
            @NotNull OffsetDateTime createdAt) {

        /** Missing optional signals become null (device, payment) or "" (IP), as in JSONL input. */
        Account toAccount() {
            return new Account(accountId, email, blankToNull(deviceId), ip == null ? "" : ip,
                    blankToNull(paymentFingerprint), createdAt.toInstant());
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value;
        }
    }

    public record AccountLinkResponse(
            String accountId,
            String clusterId,
            double confidence,
            int clusterSize,
            RiskAction riskAction,
            String reason) {
    }

    public record AccountResponse(
            String accountId,
            String email,
            String deviceId,
            String ip,
            String paymentFingerprint,
            Instant createdAt,
            String clusterId,
            int clusterSize,
            RiskAction riskAction,
            String reason) {
    }

    public record LinkResponse(String accountA, String accountB, String rule, double score, List<String> sharedSignals) {
    }

    public record ClusterResponse(
            String clusterId,
            int size,
            List<String> accountIds,
            boolean accountIdsTruncated,
            double confidence,
            RiskAction riskAction,
            String reason,
            List<LinkResponse> links,
            boolean linksTruncated,
            DecisionResponse latestDecision) {
    }

    public record DecisionRequest(
            @NotNull Outcome outcome,
            @NotBlank @Size(max = 128) String analyst,
            @Size(max = 2000) String note) {
    }

    public record DecisionResponse(
            UUID decisionId,
            String clusterId,
            Outcome outcome,
            String analyst,
            String note,
            Instant decidedAt,
            List<String> accountIds) {

        static DecisionResponse of(AnalystDecision decision) {
            return new DecisionResponse(decision.decisionId(), decision.clusterId(), decision.outcome(),
                    decision.analyst(), decision.note(), decision.decidedAt(), decision.accountIds());
        }
    }

    public record DecisionResultResponse(DecisionResponse decision, RiskAction riskAction, String reason) {
    }

    public record ConstraintRequest(
            @NotBlank @Pattern(regexp = ACCOUNT_ID_PATTERN) String accountA,
            @NotBlank @Pattern(regexp = ACCOUNT_ID_PATTERN) String accountB,
            @NotBlank @Size(max = 128) String actor) {
    }

    public record ConstraintResponse(String accountA, String accountB, String relation, String status) {
    }

    public record AuditEventResponse(
            long eventId,
            Instant occurredAt,
            String eventType,
            String accountId,
            String clusterId,
            String actor,
            Map<String, Object> details) {
    }
}
