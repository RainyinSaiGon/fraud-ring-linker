package io.github.rainyinsaigon.fraudlinker.service.api;

import io.github.rainyinsaigon.fraudlinker.service.security.Caller;
import io.github.rainyinsaigon.fraudlinker.mules.Transfer;
import io.github.rainyinsaigon.fraudlinker.service.api.ApiModels.MuleReasonResponse;
import io.github.rainyinsaigon.fraudlinker.service.api.ApiModels.MuleResponse;
import io.github.rainyinsaigon.fraudlinker.service.api.ApiModels.MuleScoreResponse;
import io.github.rainyinsaigon.fraudlinker.service.api.ApiModels.TransferBatchRequest;
import io.github.rainyinsaigon.fraudlinker.service.api.ApiModels.TransferBatchResponse;
import io.github.rainyinsaigon.fraudlinker.service.api.ApiModels.TransferResponse;
import io.github.rainyinsaigon.fraudlinker.service.app.TransferService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Money movement and mule flags. Transfers never change a cluster; they only
 * feed mule scoring. A flag asks for review and never restricts by itself.
 */
@Validated
@RestController
public class TransferController {
    private final TransferService transfers;

    public TransferController(TransferService transfers) {
        this.transfers = transfers;
    }

    /** Stores a batch of transfers, all or nothing, and rescores the accounts involved. */
    @PostMapping("/api/v1/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    public TransferBatchResponse record(@Valid @RequestBody TransferBatchRequest request,
                                        @AuthenticationPrincipal Jwt jwt) {
        List<Transfer> batch = request.transfers().stream().map(ApiModels.TransferRequest::toTransfer).toList();
        TransferService.RecordResult result = transfers.record(batch, Caller.client(jwt));
        return new TransferBatchResponse(result.accepted(), result.flaggedNow(),
                result.scores().stream().map(MuleScoreResponse::of).toList());
    }

    /** Likely mules no analyst has decided on since they were flagged, highest score first. */
    @GetMapping("/api/v1/mules")
    public List<MuleResponse> openFlags(@RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit) {
        return transfers.openFlags(limit).stream()
                .map(view -> new MuleResponse(view.accountId(), view.clusterId(), view.clusterSize(),
                        view.score().score(), MuleReasonResponse.of(view.score()), view.flag().flaggedAt(),
                        view.risk().action(), view.risk().reason()))
                .toList();
    }

    /** Money into or out of one account, newest first. */
    @GetMapping("/api/v1/accounts/{accountId}/transfers")
    public List<TransferResponse> transfersOf(
            @PathVariable @Pattern(regexp = ApiModels.ACCOUNT_ID_PATTERN) String accountId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit) {
        return transfers.transfersOf(accountId, limit).stream()
                .map(transfer -> TransferResponse.of(transfer, accountId))
                .toList();
    }
}
