package io.github.rainyinsaigon.fraudlinker.service.api;

import io.github.rainyinsaigon.fraudlinker.domain.Account;
import io.github.rainyinsaigon.fraudlinker.domain.Assignment;
import io.github.rainyinsaigon.fraudlinker.service.app.AccountService;
import io.github.rainyinsaigon.fraudlinker.service.app.ConflictException;
import io.github.rainyinsaigon.fraudlinker.service.app.NotFoundException;
import io.github.rainyinsaigon.fraudlinker.service.engine.EngineUnavailableException;
import io.github.rainyinsaigon.fraudlinker.service.risk.RiskAction;
import io.github.rainyinsaigon.fraudlinker.service.risk.RiskAssessment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP contract of the accounts endpoints: JSON shape, validation and error mapping. The service is mocked. */
@WebMvcTest(AccountController.class)
class AccountControllerWebTest {
    private static final String VALID = """
            {"account_id":"acc_1","email":"alice@example.com","device_id":null,"ip":"203.0.113.7",
             "payment_fingerprint":"pf_1","created_at":"2026-03-14T09:22:11+07:00"}
            """;

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AccountService accounts;

    @Test
    void registersAndReturnsSnakeCaseBodyWithLocation() throws Exception {
        when(accounts.register(any())).thenReturn(new AccountService.LinkResult(
                new Assignment("acc_1", "c7", 0.91), 3, new RiskAssessment(RiskAction.REVIEW, "Linked.")));

        mvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/accounts/acc_1"))
                .andExpect(jsonPath("$.account_id").value("acc_1"))
                .andExpect(jsonPath("$.cluster_id").value("c7"))
                .andExpect(jsonPath("$.confidence").value(0.91))
                .andExpect(jsonPath("$.cluster_size").value(3))
                .andExpect(jsonPath("$.risk_action").value("REVIEW"));
    }

    @Test
    void convertsTimestampOffsetToUtcAndMissingSignalsToAbsent() throws Exception {
        when(accounts.register(any())).thenAnswer(invocation -> {
            Account account = invocation.getArgument(0);
            org.assertj.core.api.Assertions.assertThat(account.createdAt()).isEqualTo(Instant.parse("2026-03-14T02:22:11Z"));
            org.assertj.core.api.Assertions.assertThat(account.deviceId()).isNull();
            org.assertj.core.api.Assertions.assertThat(account.ip()).isEmpty();
            return new AccountService.LinkResult(new Assignment("acc_1", "c1", 1), 1,
                    new RiskAssessment(RiskAction.ALLOW, "ok"));
        });

        mvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON).content("""
                        {"account_id":"acc_1","email":"a@b.com","device_id":"  ","created_at":"2026-03-14T09:22:11+07:00"}
                        """))
                .andExpect(status().isCreated());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "{\"email\":\"a@b.com\",\"created_at\":\"2026-03-14T09:22:11Z\"}",
            "{\"account_id\":\"has space\",\"email\":\"a@b.com\",\"created_at\":\"2026-03-14T09:22:11Z\"}",
            "{\"account_id\":\"acc_1\",\"created_at\":\"2026-03-14T09:22:11Z\"}",
            "{\"account_id\":\"acc_1\",\"email\":\"a@b.com\"}",
    })
    void rejectsInvalidBodiesWithFieldErrors(String body) throws Exception {
        mvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.errors[0].field").exists());
        verify(accounts, never()).register(any());
    }

    @Test
    void reportsFieldNamesInSnakeCase() throws Exception {
        mvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account_id\":\"acc_1\",\"email\":\"a@b.com\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("created_at"));
    }

    @Test
    void rejectsMalformedJsonAndBadTimestamp() throws Exception {
        mvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account_id\":\"acc_1\",\"email\":\"a@b.com\",\"created_at\":\"yesterday\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void duplicateAccountIsConflict() throws Exception {
        when(accounts.register(any())).thenThrow(new ConflictException("Account acc_1 already exists."));

        mvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Account acc_1 already exists."));
    }

    @Test
    void unknownAccountIsNotFound() throws Exception {
        when(accounts.find("acc_404")).thenThrow(new NotFoundException("Account acc_404 not found."));

        mvc.perform(get("/api/v1/accounts/acc_404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void invalidPathIdIsRejectedBeforeTheService() throws Exception {
        mvc.perform(get("/api/v1/accounts/{id}", "x".repeat(200)))
                .andExpect(status().isBadRequest());
        verify(accounts, never()).find(any());
    }

    @Test
    void unavailableEngineIs503WithoutInternalDetail() throws Exception {
        when(accounts.register(any())).thenThrow(new EngineUnavailableException(new IllegalStateException("db password wrong")));

        mvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("Linking is temporarily unavailable. Retry later."));
    }
}
