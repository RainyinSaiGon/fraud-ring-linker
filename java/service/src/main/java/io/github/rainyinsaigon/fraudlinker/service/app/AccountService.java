package io.github.rainyinsaigon.fraudlinker.service.app;

import io.github.rainyinsaigon.fraudlinker.clustering.LinkState;
import io.github.rainyinsaigon.fraudlinker.domain.Account;
import io.github.rainyinsaigon.fraudlinker.domain.Assignment;
import io.github.rainyinsaigon.fraudlinker.mules.MuleScore;
import io.github.rainyinsaigon.fraudlinker.service.engine.LinkerEngine;
import io.github.rainyinsaigon.fraudlinker.service.persistence.AccountRepository;
import io.github.rainyinsaigon.fraudlinker.service.persistence.AuditRepository;
import io.github.rainyinsaigon.fraudlinker.service.risk.RiskAssessment;
import io.github.rainyinsaigon.fraudlinker.streaming.StreamResult;
import io.github.rainyinsaigon.fraudlinker.takeover.TakeoverScore;
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
    private final RiskAssessor risk;
    private final AuditRepository audit;
    private final MergeProposalService mergeProposals;
    private final MuleFlagger muleFlagger;
    private final TakeoverFlagger takeoverFlagger;
    private final Clock clock;

    AccountService(LinkerEngine engine, WriteThrough writeThrough, AccountRepository accounts, RiskAssessor risk,
                   AuditRepository audit, MergeProposalService mergeProposals, MuleFlagger muleFlagger,
                   TakeoverFlagger takeoverFlagger, Clock clock) {
        this.engine = engine;
        this.mergeProposals = mergeProposals;
        this.writeThrough = writeThrough;
        this.accounts = accounts;
        this.risk = risk;
        this.audit = audit;
        this.muleFlagger = muleFlagger;
        this.takeoverFlagger = takeoverFlagger;
        this.clock = clock;
    }

    public record LinkResult(Assignment assignment, int clusterSize, RiskAssessment risk,
                             List<MergeProposal> mergeProposals) {
    }

    /**
     * @param risk          the action for this account, including its own takeover rules
     * @param muleScore     null when the account has sent and received no money
     * @param takeoverScore null when the account has no logins
     */
    public record AccountView(Account account, String clusterId, int clusterSize, RiskAssessment risk,
                              MuleScore muleScore, TakeoverScore takeoverScore) {
    }

    /**
     * Stores a new account, links it, and audits the outcome. If it also linked
     * strongly to other clusters, a merge proposal is opened for each. If transfers
     * that name it arrived before it did, it may be flagged as a likely mule straight away,
     * and logins that arrived before it may flag it as a possible takeover.
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
            StreamResult result = session.streamLinker().assignWithCandidates(account);
            Assignment assignment = result.assignment();
            List<String> members = state.memberIds(assignment.clusterId());
            muleFlagger.recordNew(session, now);
            takeoverFlagger.recordNew(session, now);
            RiskAssessment risk = this.risk.assessAccount(session, account.accountId(), members);

            Map<String, Object> details = new LinkedHashMap<>();
            details.put("confidence", assignment.confidence());
            details.put("cluster_size", members.size());
            details.put("risk_action", risk.action().name());
            details.put("reason", risk.reason());
            audit.record(now, AuditRepository.ACCOUNT_LINKED, account.accountId(), assignment.clusterId(),
                    AuditRepository.SYSTEM, details);
            List<MergeProposal> proposals = mergeProposals.propose(state, assignment, result.mergeCandidates(), now);
            return new LinkResult(assignment, members.size(), risk, proposals);
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
            return new AccountView(account, clusterId, members.size(), risk.assessAccount(session, accountId, members),
                    session.muleScores().get(accountId), session.takeoverScores().get(accountId));
        });
    }
}
