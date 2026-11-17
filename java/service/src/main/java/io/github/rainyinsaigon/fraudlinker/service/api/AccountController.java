package io.github.rainyinsaigon.fraudlinker.service.api;

import io.github.rainyinsaigon.fraudlinker.service.security.Caller;
import io.github.rainyinsaigon.fraudlinker.domain.Account;
import io.github.rainyinsaigon.fraudlinker.service.api.ApiModels.AccountLinkResponse;
import io.github.rainyinsaigon.fraudlinker.service.api.ApiModels.AccountRequest;
import io.github.rainyinsaigon.fraudlinker.service.api.ApiModels.AccountResponse;
import io.github.rainyinsaigon.fraudlinker.service.api.ApiModels.RevealRequest;
import io.github.rainyinsaigon.fraudlinker.service.api.ApiModels.RevealResponse;
import io.github.rainyinsaigon.fraudlinker.service.app.AccountService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@Validated
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {
    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    /** Registers a new account and returns its cluster and recommended action. */
    @PostMapping
    public ResponseEntity<AccountLinkResponse> register(@Valid @RequestBody AccountRequest request,
                                                        @AuthenticationPrincipal Jwt jwt) {
        AccountService.LinkResult result = accounts.register(request.toAccount(), Caller.client(jwt));
        AccountLinkResponse body = new AccountLinkResponse(
                result.assignment().accountId(),
                result.assignment().clusterId(),
                result.assignment().confidence(),
                result.clusterSize(),
                result.risk().action(),
                result.risk().reason(),
                result.mergeProposals().stream().map(proposal -> proposal.proposalId()).toList());
        return ResponseEntity.created(URI.create("/api/v1/accounts/" + body.accountId()))
                .body(body);
    }

    /**
     * The account's full email and sign-up IP, for an analyst who needs them. Every
     * reveal is recorded in the audit log with who asked and why.
     */
    @PostMapping("/{accountId}/reveal")
    public RevealResponse reveal(@PathVariable @Pattern(regexp = ApiModels.ACCOUNT_ID_PATTERN) String accountId,
                                 @Valid @RequestBody(required = false) RevealRequest request,
                                 @AuthenticationPrincipal Jwt jwt) {
        Account account = accounts.reveal(accountId, Caller.person(jwt), request == null ? null : request.reason());
        return new RevealResponse(account.accountId(), account.email(), account.ip());
    }

    @GetMapping("/{accountId}")
    public AccountResponse find(@PathVariable @Pattern(regexp = ApiModels.ACCOUNT_ID_PATTERN) String accountId) {
        AccountService.AccountView view = accounts.find(accountId);
        Account account = view.account();
        return new AccountResponse(account.accountId(), Pii.email(account.email()), account.deviceId(),
                Pii.ip(account.ip()),
                account.paymentFingerprint(), account.createdAt(), view.clusterId(), view.clusterSize(),
                view.risk().action(), view.risk().reason(),
                view.muleScore() == null ? 0 : view.muleScore().score(),
                view.muleScore() != null && view.muleScore().flagged(),
                ApiModels.MuleReasonResponse.of(view.muleScore()),
                view.takeoverScore() == null ? 0 : view.takeoverScore().score(),
                view.takeoverScore() != null && view.takeoverScore().flagged(),
                ApiModels.TakeoverReasonResponse.of(view.takeoverScore()));
    }
}
