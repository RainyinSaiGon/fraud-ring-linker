package io.github.rainyinsaigon.fraudlinker.service.api;

import io.github.rainyinsaigon.fraudlinker.domain.Account;
import io.github.rainyinsaigon.fraudlinker.service.api.ApiModels.AccountLinkResponse;
import io.github.rainyinsaigon.fraudlinker.service.api.ApiModels.AccountRequest;
import io.github.rainyinsaigon.fraudlinker.service.api.ApiModels.AccountResponse;
import io.github.rainyinsaigon.fraudlinker.service.app.AccountService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
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
    public ResponseEntity<AccountLinkResponse> register(@Valid @RequestBody AccountRequest request) {
        AccountService.LinkResult result = accounts.register(request.toAccount());
        AccountLinkResponse body = new AccountLinkResponse(
                result.assignment().accountId(),
                result.assignment().clusterId(),
                result.assignment().confidence(),
                result.clusterSize(),
                result.risk().action(),
                result.risk().reason());
        return ResponseEntity.created(URI.create("/api/v1/accounts/" + body.accountId()))
                .body(body);
    }

    @GetMapping("/{accountId}")
    public AccountResponse find(@PathVariable @Pattern(regexp = ApiModels.ACCOUNT_ID_PATTERN) String accountId) {
        AccountService.AccountView view = accounts.find(accountId);
        Account account = view.account();
        return new AccountResponse(account.accountId(), account.email(), account.deviceId(), account.ip(),
                account.paymentFingerprint(), account.createdAt(), view.clusterId(), view.clusterSize(),
                view.risk().action(), view.risk().reason());
    }
}
