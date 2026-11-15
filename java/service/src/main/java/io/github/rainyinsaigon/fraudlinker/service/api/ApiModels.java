package io.github.rainyinsaigon.fraudlinker.service.api;

import io.github.rainyinsaigon.fraudlinker.domain.Account;
import io.github.rainyinsaigon.fraudlinker.mules.MuleReason;
import io.github.rainyinsaigon.fraudlinker.mules.MuleScore;
import io.github.rainyinsaigon.fraudlinker.mules.Transfer;
import io.github.rainyinsaigon.fraudlinker.service.app.MergeProposal;
import io.github.rainyinsaigon.fraudlinker.service.app.MergeProposalService;
import io.github.rainyinsaigon.fraudlinker.service.risk.AnalystDecision;
import io.github.rainyinsaigon.fraudlinker.service.risk.Outcome;
import io.github.rainyinsaigon.fraudlinker.service.risk.RiskAction;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
    static final int MAX_TRANSFER_BATCH = 500;
    /** One trillion dollars: far above any real payment, and far below overflow when summed. */
    static final long MAX_TRANSFER_CENTS = 100_000_000_000_000L;

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

    /** @param mergeProposalIds proposals opened because the account also linked other clusters */
    public record AccountLinkResponse(
            String accountId,
            String clusterId,
            double confidence,
            int clusterSize,
            RiskAction riskAction,
            String reason,
            List<UUID> mergeProposalIds) {
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
            String reason,
            double muleScore,
            boolean muleFlagged,
            List<MuleReasonResponse> muleReasons) {
    }

    public record MuleReasonResponse(String code, String description) {
        /** No score (an account with no transfers) gives no reasons. */
        static List<MuleReasonResponse> of(MuleScore score) {
            return score == null ? List.of() : score.reasons().stream().map(MuleReasonResponse::of).toList();
        }

        private static MuleReasonResponse of(MuleReason reason) {
            return new MuleReasonResponse(reason.code(), reason.describe());
        }
    }

    /** One account's mule score; accounts with no transfers have none. */
    public record MuleScoreResponse(String accountId, double muleScore, boolean muleFlagged,
                                    List<MuleReasonResponse> muleReasons) {
        static MuleScoreResponse of(MuleScore score) {
            return new MuleScoreResponse(score.accountId(), score.score(), score.flagged(),
                    MuleReasonResponse.of(score));
        }
    }

    public record TransferRequest(
            @NotBlank @Pattern(regexp = ACCOUNT_ID_PATTERN) String transferId,
            @NotBlank @Pattern(regexp = ACCOUNT_ID_PATTERN) String fromAccount,
            @NotBlank @Pattern(regexp = ACCOUNT_ID_PATTERN) String toAccount,
            @NotNull @Min(1) @Max(MAX_TRANSFER_CENTS) Long amountCents,
            @NotNull OffsetDateTime occurredAt) {

        /** @throws io.github.rainyinsaigon.fraudlinker.LinkerException if both sides are the same account */
        Transfer toTransfer() {
            return new Transfer(transferId, fromAccount, toAccount, amountCents, occurredAt.toInstant());
        }
    }

    /** Up to {@value #MAX_TRANSFER_BATCH} transfers, stored all or nothing. */
    public record TransferBatchRequest(
            @NotNull @Size(min = 1, max = MAX_TRANSFER_BATCH) List<@Valid @NotNull TransferRequest> transfers) {
    }

    /**
     * @param flaggedNow accounts flagged as likely mules for the first time by this batch
     * @param muleScores current scores of the accounts the bank holds that this batch touched
     */
    public record TransferBatchResponse(int accepted, List<String> flaggedNow, List<MuleScoreResponse> muleScores) {
    }

    public enum Direction { IN, OUT }

    public record TransferResponse(String transferId, String fromAccount, String toAccount, long amountCents,
                                   Instant occurredAt, Direction direction) {
        static TransferResponse of(Transfer transfer, String accountId) {
            return new TransferResponse(transfer.transferId(), transfer.fromAccount(), transfer.toAccount(),
                    transfer.amountCents(), transfer.occurredAt(),
                    transfer.toAccount().equals(accountId) ? Direction.IN : Direction.OUT);
        }
    }

    /** A likely mule waiting for an analyst. */
    public record MuleResponse(String accountId, String clusterId, int clusterSize, double muleScore,
                               List<MuleReasonResponse> muleReasons, Instant flaggedAt, RiskAction riskAction,
                               String reason) {
    }

    public record ClusterSummaryResponse(String clusterId, int size, double confidence, RiskAction riskAction,
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
            DecisionResponse latestDecision,
            List<MuleScoreResponse> muleScores) {
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

    public record MergeDecisionRequest(
            @NotBlank @Size(max = 128) String analyst,
            @Size(max = 2000) String note) {
    }

    /**
     * @param clusterA        cluster ID of side A when proposed
     * @param currentClusterA the cluster side A is in now (IDs can change on a rebuild)
     */
    public record MergeProposalResponse(
            UUID proposalId,
            MergeProposal.Status status,
            String triggerAccountId,
            double score,
            String rule,
            Instant proposedAt,
            String clusterA,
            String clusterB,
            String currentClusterA,
            String currentClusterB,
            List<String> accountIdsA,
            List<String> accountIdsB,
            String decidedBy,
            String note,
            Instant decidedAt) {

        static MergeProposalResponse of(MergeProposalService.ProposalView view) {
            MergeProposal p = view.proposal();
            return new MergeProposalResponse(p.proposalId(), p.status(), p.triggerAccountId(), p.score(), p.rule(),
                    p.proposedAt(), p.clusterA(), p.clusterB(), view.currentClusterA(), view.currentClusterB(),
                    p.accountsA(), p.accountsB(), p.decidedBy(), p.note(), p.decidedAt());
        }
    }

    public record MergeAcceptResponse(MergeProposalResponse proposal, boolean merged, String clusterId,
                                      int clusterSize, RiskAction riskAction, String reason) {
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
