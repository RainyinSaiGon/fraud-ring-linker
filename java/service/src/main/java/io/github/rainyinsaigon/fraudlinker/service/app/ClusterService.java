package io.github.rainyinsaigon.fraudlinker.service.app;

import io.github.rainyinsaigon.fraudlinker.clustering.ClusterExplainer;
import io.github.rainyinsaigon.fraudlinker.clustering.LinkState;
import io.github.rainyinsaigon.fraudlinker.service.LinkerProperties;
import io.github.rainyinsaigon.fraudlinker.service.engine.LinkerEngine;
import io.github.rainyinsaigon.fraudlinker.service.persistence.AuditRepository;
import io.github.rainyinsaigon.fraudlinker.service.persistence.DecisionRepository;
import io.github.rainyinsaigon.fraudlinker.service.risk.AnalystDecision;
import io.github.rainyinsaigon.fraudlinker.service.risk.Outcome;
import io.github.rainyinsaigon.fraudlinker.service.risk.RiskAssessment;
import io.github.rainyinsaigon.fraudlinker.service.risk.RiskPolicy;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Explains clusters to analysts and records their decisions. */
@Service
public class ClusterService {
    private final LinkerEngine engine;
    private final WriteThrough writeThrough;
    private final DecisionRepository decisions;
    private final AuditRepository audit;
    private final LinkerProperties properties;
    private final Clock clock;

    public ClusterService(LinkerEngine engine, WriteThrough writeThrough, DecisionRepository decisions,
                          AuditRepository audit, LinkerProperties properties, Clock clock) {
        this.engine = engine;
        this.writeThrough = writeThrough;
        this.decisions = decisions;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    public record ClusterView(ClusterExplainer.Explanation explanation, RiskAssessment risk,
                              Optional<AnalystDecision> latestDecision) {
    }

    /** @throws NotFoundException if the cluster is unknown */
    public ClusterView explain(String clusterId) {
        return engine.withLock(session -> {
            ClusterExplainer.Explanation explanation = ClusterExplainer
                    .explain(session.state(), clusterId, properties.maxExplainMembers())
                    .orElseThrow(() -> notFound(clusterId));
            Optional<AnalystDecision> latest = decisions.findLatestCovering(explanation.accountIds());
            return new ClusterView(explanation, RiskPolicy.assess(explanation.accountIds(), latest), latest);
        });
    }

    /** Decision history for the cluster's current members, newest first. */
    public List<AnalystDecision> history(String clusterId, int limit) {
        return engine.withLock(session -> {
            List<String> members = session.state().memberIds(clusterId);
            if (members.isEmpty()) {
                throw notFound(clusterId);
            }
            return decisions.findCovering(members, limit);
        });
    }

    public record DecisionResult(AnalystDecision decision, RiskAssessment risk) {
    }

    /**
     * Records an analyst's decision on every account currently in the cluster.
     *
     * @throws NotFoundException if the cluster is unknown
     */
    public DecisionResult decide(String clusterId, Outcome outcome, String analyst, String note) {
        return writeThrough.execute(session -> {
            LinkState state = session.state();
            List<String> members = state.memberIds(clusterId);
            if (members.isEmpty()) {
                throw notFound(clusterId);
            }
            Instant now = clock.instant();
            AnalystDecision decision = new AnalystDecision(UUID.randomUUID(), clusterId, outcome, analyst, note, now,
                    members);
            decisions.insert(decision);

            RiskAssessment risk = RiskPolicy.assess(members, Optional.of(decision));
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("decision_id", decision.decisionId().toString());
            details.put("outcome", outcome.name());
            details.put("account_count", members.size());
            details.put("risk_action", risk.action().name());
            audit.record(now, AuditRepository.DECISION_RECORDED, null, clusterId, analyst, details);
            return new DecisionResult(decision, risk);
        });
    }

    private static NotFoundException notFound(String clusterId) {
        return new NotFoundException("Cluster " + clusterId + " not found.");
    }
}
