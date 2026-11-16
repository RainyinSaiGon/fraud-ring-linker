package io.github.rainyinsaigon.fraudlinker.service.app;

import io.github.rainyinsaigon.fraudlinker.clustering.LinkState;
import io.github.rainyinsaigon.fraudlinker.service.engine.LinkerEngine;
import io.github.rainyinsaigon.fraudlinker.service.persistence.AuditRepository;
import io.github.rainyinsaigon.fraudlinker.service.persistence.LoginRepository;
import io.github.rainyinsaigon.fraudlinker.service.persistence.TakeoverDecisionRepository;
import io.github.rainyinsaigon.fraudlinker.service.risk.RiskAction;
import io.github.rainyinsaigon.fraudlinker.service.risk.RiskAssessment;
import io.github.rainyinsaigon.fraudlinker.service.risk.RiskPolicy;
import io.github.rainyinsaigon.fraudlinker.service.risk.TakeoverDecision;
import io.github.rainyinsaigon.fraudlinker.service.risk.TakeoverFlag;
import io.github.rainyinsaigon.fraudlinker.service.risk.TakeoverOutcome;
import io.github.rainyinsaigon.fraudlinker.takeover.Login;
import io.github.rainyinsaigon.fraudlinker.takeover.TakeoverScore;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Logins and account takeovers: stores logins, keeps takeover scores current,
 * lists the accounts that may have been taken over, and records analysts'
 * decisions about them.
 *
 * <p>Logins never change a cluster. They only feed takeover scoring, which reads
 * the clusters to decide which accounts are related. A takeover decision covers
 * one account, never the rest of its cluster.
 */
@Service
public class LoginService {
    private final LinkerEngine engine;
    private final WriteThrough writeThrough;
    private final LoginRepository logins;
    private final TakeoverDecisionRepository decisions;
    private final RiskAssessor risk;
    private final TakeoverFlagger flagger;
    private final AuditRepository audit;
    private final Clock clock;

    LoginService(LinkerEngine engine, WriteThrough writeThrough, LoginRepository logins,
                 TakeoverDecisionRepository decisions, RiskAssessor risk, TakeoverFlagger flagger,
                 AuditRepository audit, Clock clock) {
        this.engine = engine;
        this.writeThrough = writeThrough;
        this.logins = logins;
        this.decisions = decisions;
        this.risk = risk;
        this.flagger = flagger;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * @param flaggedNow accounts flagged as possible takeovers for the first time by this batch
     * @param scores     current scores of the accounts the bank holds that this batch touched, by ID
     */
    public record RecordResult(int accepted, List<String> flaggedNow, List<TakeoverScore> scores) {
    }

    /**
     * Stores a batch of logins, all or nothing.
     *
     * @throws ConflictException if a login ID repeats within the batch or is already stored
     */
    public RecordResult record(List<Login> batch) {
        Set<String> ids = new HashSet<>();
        for (Login login : batch) {
            if (!ids.add(login.loginId())) {
                throw new ConflictException("Login " + login.loginId() + " appears twice in this batch.");
            }
        }
        return writeThrough.execute(session -> {
            Set<String> existing = new TreeSet<>(logins.findExistingIds(ids));
            if (!existing.isEmpty()) {
                throw new ConflictException("Login " + existing.iterator().next() + " already exists.");
            }
            Instant now = clock.instant();
            Set<String> touched = new TreeSet<>();
            for (Login login : batch) {
                logins.insert(login, now);
                session.addLogin(login);
                touched.add(login.accountId());
            }
            List<String> flaggedNow = flagger.recordNew(session, now);
            Map<String, TakeoverScore> scores = session.takeoverScores();
            List<TakeoverScore> touchedScores = touched.stream().map(scores::get).filter(Objects::nonNull).toList();
            return new RecordResult(batch.size(), flaggedNow, touchedScores);
        });
    }

    /** A flagged account no analyst has decided on since it was flagged. */
    public record TakeoverView(String accountId, String clusterId, int clusterSize, TakeoverScore score,
                               TakeoverFlag flag, RiskAssessment risk) {
    }

    /**
     * Possible takeovers waiting for an analyst, highest score first, then by
     * account ID. Accounts in a cluster confirmed as fraud are left out: they are
     * restricted already.
     */
    public List<TakeoverView> openFlags(int limit) {
        return engine.withLock(session -> {
            LinkState state = session.state();
            List<TakeoverView> views = new ArrayList<>();
            for (TakeoverScore score : session.takeoverScores().values()) {
                if (!score.flagged()) {
                    continue;
                }
                String accountId = score.accountId();
                Optional<TakeoverDecision> latest = decisions.findLatest(accountId);
                TakeoverFlag flag = risk.takeoverFlag(session, accountId).orElseThrow();
                if (RiskPolicy.answers(latest, flag)) {
                    continue;
                }
                String clusterId = state.clusterIdOf(accountId).orElseThrow();
                List<String> members = state.memberIds(clusterId);
                RiskAssessment assessment = risk.assessAccount(session, accountId, members, latest);
                if (assessment.action() == RiskAction.RESTRICT) {
                    continue;
                }
                views.add(new TakeoverView(accountId, clusterId, members.size(), score, flag, assessment));
            }
            views.sort(Comparator.comparingDouble((TakeoverView v) -> v.score().score()).reversed()
                    .thenComparing(TakeoverView::accountId));
            return List.copyOf(views.subList(0, Math.min(limit, views.size())));
        });
    }

    /**
     * One account's logins, newest first.
     *
     * @throws NotFoundException if the account is unknown
     */
    public List<Login> loginsOf(String accountId, int limit) {
        return engine.withLock(session -> {
            requireHeld(session, accountId);
            return logins.findByAccount(accountId, limit);
        });
    }

    public record DecisionResult(TakeoverDecision decision, RiskAssessment risk) {
    }

    /**
     * Records an analyst's takeover decision about one account. The rest of its
     * cluster is untouched.
     *
     * @throws NotFoundException if the account is unknown
     */
    public DecisionResult decide(String accountId, TakeoverOutcome outcome, String analyst, String note) {
        return writeThrough.execute(session -> {
            requireHeld(session, accountId);
            Instant now = clock.instant();
            TakeoverDecision decision = new TakeoverDecision(UUID.randomUUID(), accountId, outcome, analyst, note,
                    now);
            decisions.insert(decision);
            String clusterId = session.state().clusterIdOf(accountId).orElseThrow();
            RiskAssessment assessment = risk.assessAccount(session, accountId, session.state().memberIds(clusterId),
                    Optional.of(decision));
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("decision_id", decision.decisionId().toString());
            details.put("outcome", outcome.name());
            details.put("risk_action", assessment.action().name());
            audit.record(now, AuditRepository.TAKEOVER_DECIDED, accountId, clusterId, analyst, details);
            return new DecisionResult(decision, assessment);
        });
    }

    /**
     * The account's takeover decisions, newest first.
     *
     * @throws NotFoundException if the account is unknown
     */
    public List<TakeoverDecision> decisionsOf(String accountId, int limit) {
        return engine.withLock(session -> {
            requireHeld(session, accountId);
            return decisions.findByAccount(accountId, limit);
        });
    }

    private static void requireHeld(LinkerEngine.Session session, String accountId) {
        if (session.state().store().indexOf(accountId) < 0) {
            throw new NotFoundException("Account " + accountId + " not found.");
        }
    }
}
