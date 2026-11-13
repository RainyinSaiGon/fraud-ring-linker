package io.github.rainyinsaigon.fraudlinker.service.app;

import io.github.rainyinsaigon.fraudlinker.clustering.LinkState;
import io.github.rainyinsaigon.fraudlinker.domain.Account;
import io.github.rainyinsaigon.fraudlinker.domain.Assignment;
import io.github.rainyinsaigon.fraudlinker.service.engine.LinkerEngine;
import io.github.rainyinsaigon.fraudlinker.service.persistence.AccountRepository;
import io.github.rainyinsaigon.fraudlinker.service.persistence.AuditRepository;
import io.github.rainyinsaigon.fraudlinker.service.persistence.DecisionRepository;
import io.github.rainyinsaigon.fraudlinker.service.risk.RiskAssessment;
import io.github.rainyinsaigon.fraudlinker.service.risk.RiskPolicy;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Registers new accounts and answers "which cluster is this account in, and what should we do?". */
@Service
public class AccountService {
    private final LinkerEngine engine;
    private final WriteThrough writeThrough;
    private final AccountRepository accounts;
    private final DecisionRepository decisions;
    private final AuditRepository audit;
    private final Clock clock;

    public AccountService(LinkerEngine engine, WriteThrough writeThrough, AccountRepository accounts,
                          DecisionRepository decisions, AuditRepository audit, Clock clock) {
        this.engine = engine;
        this.writeThrough = writeThrough;
        this.accounts = accounts;
        this.decisions = decisions;
        this.audit = audit;
        this.clock = clock;
    }

    public record LinkResult(Assignment assignment, int clusterSize, RiskAssessment risk) {
    }

    public record AccountView(Account account, String clusterId, int clusterSize, RiskAssessment risk) {
    }

    /**
     * Stores a new account, links it, and audits the outcome.
     *
     * @throws ConflictException if the account ID already exists
     */
    public LinkResult register(Account account) {
        return writeThrough.execute(session -> {
            LinkState state = session.state();
            if (state.store().indexOf(account.accountId()) >= 0) {
                throw new ConflictException("Account " + account.accountId() + " already exists.");
            }
            Instant now = clock.instant();
            accounts.insert(account, now);
            Assignment assignment = session.streamLinker().assign(account);
            List<String> members = state.memberIds(assignment.clusterId());
            RiskAssessment risk = RiskPolicy.assess(members, decisions.findLatestCovering(members));

            Map<String, Object> details = new LinkedHashMap<>();
            details.put("confidence", assignment.confidence());
            details.put("cluster_size", members.size());
            details.put("risk_action", risk.action().name());
            details.put("reason", risk.reason());
            audit.record(now, AuditRepository.ACCOUNT_LINKED, account.accountId(), assignment.clusterId(),
                    AuditRepository.SYSTEM, details);
            return new LinkResult(assignment, members.size(), risk);
        });
    }

    /** @throws NotFoundException if the account is unknown */
    public AccountView find(String accountId) {
        return engine.withLock(session -> {
            LinkState state = session.state();
            String clusterId = state.clusterIdOf(accountId)
                    .orElseThrow(() -> new NotFoundException("Account " + accountId + " not found."));
            Account account = accounts.findById(accountId)
                    .orElseThrow(() -> new NotFoundException("Account " + accountId + " not found."));
            List<String> members = state.memberIds(clusterId);
            RiskAssessment risk = RiskPolicy.assess(members, decisions.findLatestCovering(members));
            return new AccountView(account, clusterId, members.size(), risk);
        });
    }
}
