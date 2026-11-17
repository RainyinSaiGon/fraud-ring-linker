package io.github.rainyinsaigon.fraudlinker.service.api;

import io.github.rainyinsaigon.fraudlinker.service.security.Caller;
import io.github.rainyinsaigon.fraudlinker.service.api.ApiModels.LoginBatchRequest;
import io.github.rainyinsaigon.fraudlinker.service.api.ApiModels.LoginBatchResponse;
import io.github.rainyinsaigon.fraudlinker.service.api.ApiModels.LoginResponse;
import io.github.rainyinsaigon.fraudlinker.service.api.ApiModels.TakeoverDecisionRequest;
import io.github.rainyinsaigon.fraudlinker.service.api.ApiModels.TakeoverDecisionResponse;
import io.github.rainyinsaigon.fraudlinker.service.api.ApiModels.TakeoverDecisionResultResponse;
import io.github.rainyinsaigon.fraudlinker.service.api.ApiModels.TakeoverReasonResponse;
import io.github.rainyinsaigon.fraudlinker.service.api.ApiModels.TakeoverResponse;
import io.github.rainyinsaigon.fraudlinker.service.api.ApiModels.TakeoverScoreResponse;
import io.github.rainyinsaigon.fraudlinker.service.app.LoginService;
import io.github.rainyinsaigon.fraudlinker.takeover.Login;
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
 * Logins and possible account takeovers. Logins never change a cluster; they
 * only feed takeover scoring. A flag asks for review; only an analyst's
 * CONFIRMED_TAKEOVER steps the account up, and only that account.
 */
@Validated
@RestController
public class LoginController {
    private final LoginService logins;

    public LoginController(LoginService logins) {
        this.logins = logins;
    }

    /** Stores a batch of logins, all or nothing, and rescores the accounts involved. */
    @PostMapping("/api/v1/logins")
    @ResponseStatus(HttpStatus.CREATED)
    public LoginBatchResponse record(@Valid @RequestBody LoginBatchRequest request,
                                     @AuthenticationPrincipal Jwt jwt) {
        List<Login> batch = request.logins().stream().map(ApiModels.LoginRequest::toLogin).toList();
        LoginService.RecordResult result = logins.record(batch, Caller.client(jwt));
        return new LoginBatchResponse(result.accepted(), result.flaggedNow(),
                result.scores().stream().map(TakeoverScoreResponse::of).toList());
    }

    /** Possible takeovers no analyst has decided on since they were flagged, highest score first. */
    @GetMapping("/api/v1/takeovers")
    public List<TakeoverResponse> openFlags(@RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit) {
        return logins.openFlags(limit).stream()
                .map(view -> new TakeoverResponse(view.accountId(), view.clusterId(), view.clusterSize(),
                        view.score().score(), TakeoverReasonResponse.of(view.score()), view.flag().flaggedAt(),
                        view.score().reachedAt(), view.risk().action(), view.risk().reason()))
                .toList();
    }

    /** One account's logins, newest first. */
    @GetMapping("/api/v1/accounts/{accountId}/logins")
    public List<LoginResponse> loginsOf(
            @PathVariable @Pattern(regexp = ApiModels.ACCOUNT_ID_PATTERN) String accountId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit) {
        return logins.loginsOf(accountId, limit).stream().map(LoginResponse::of).toList();
    }

    /** Records an analyst's takeover decision about one account; the rest of its cluster is untouched. */
    @PostMapping("/api/v1/accounts/{accountId}/takeover-decisions")
    @ResponseStatus(HttpStatus.CREATED)
    public TakeoverDecisionResultResponse decide(
            @PathVariable @Pattern(regexp = ApiModels.ACCOUNT_ID_PATTERN) String accountId,
            @Valid @RequestBody TakeoverDecisionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        LoginService.DecisionResult result = logins.decide(accountId, request.outcome(), Caller.person(jwt),
                request.note());
        return new TakeoverDecisionResultResponse(TakeoverDecisionResponse.of(result.decision()),
                result.risk().action(), result.risk().reason());
    }

    /** The account's takeover decisions, newest first. */
    @GetMapping("/api/v1/accounts/{accountId}/takeover-decisions")
    public List<TakeoverDecisionResponse> decisionsOf(
            @PathVariable @Pattern(regexp = ApiModels.ACCOUNT_ID_PATTERN) String accountId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int limit) {
        return logins.decisionsOf(accountId, limit).stream().map(TakeoverDecisionResponse::of).toList();
    }
}
