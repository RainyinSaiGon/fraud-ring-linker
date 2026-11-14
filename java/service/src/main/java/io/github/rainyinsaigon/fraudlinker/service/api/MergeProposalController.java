package io.github.rainyinsaigon.fraudlinker.service.api;

import io.github.rainyinsaigon.fraudlinker.service.api.ApiModels.MergeAcceptResponse;
import io.github.rainyinsaigon.fraudlinker.service.api.ApiModels.MergeDecisionRequest;
import io.github.rainyinsaigon.fraudlinker.service.api.ApiModels.MergeProposalResponse;
import io.github.rainyinsaigon.fraudlinker.service.app.MergeProposal;
import io.github.rainyinsaigon.fraudlinker.service.app.MergeProposalService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Merge proposals: when a new account links strongly to two clusters, it joins
 * one and the other is proposed here. An analyst accepts (the clusters merge)
 * or rejects (they stay apart and the pair is not proposed again).
 */
@Validated
@RestController
@RequestMapping("/api/v1/merge-proposals")
public class MergeProposalController {
    private final MergeProposalService proposals;

    public MergeProposalController(MergeProposalService proposals) {
        this.proposals = proposals;
    }

    /** Proposals with this status, oldest first. */
    @GetMapping
    public List<MergeProposalResponse> list(
            @RequestParam(defaultValue = "OPEN") MergeProposal.Status status,
            @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit) {
        return proposals.list(status, limit).stream().map(MergeProposalResponse::of).toList();
    }

    @PostMapping("/{proposalId}/accept")
    public MergeAcceptResponse accept(@PathVariable UUID proposalId, @Valid @RequestBody MergeDecisionRequest request) {
        MergeProposalService.AcceptResult result = proposals.accept(proposalId, request.analyst().strip(),
                request.note());
        return new MergeAcceptResponse(MergeProposalResponse.of(result.proposal()), result.merged(),
                result.clusterId(), result.clusterSize(), result.risk().action(), result.risk().reason());
    }

    @PostMapping("/{proposalId}/reject")
    public MergeProposalResponse reject(@PathVariable UUID proposalId, @Valid @RequestBody MergeDecisionRequest request) {
        return MergeProposalResponse.of(proposals.reject(proposalId, request.analyst().strip(), request.note()));
    }
}
