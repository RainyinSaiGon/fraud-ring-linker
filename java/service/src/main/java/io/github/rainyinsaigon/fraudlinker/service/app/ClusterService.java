package io.github.rainyinsaigon.fraudlinker.service.app;

import io.github.rainyinsaigon.fraudlinker.clustering.ClusterExplainer;
import io.github.rainyinsaigon.fraudlinker.clustering.LinkState;
import io.github.rainyinsaigon.fraudlinker.domain.Cluster;
import io.github.rainyinsaigon.fraudlinker.mules.MuleScore;
import io.github.rainyinsaigon.fraudlinker.service.LinkerProperties;
import io.github.rainyinsaigon.fraudlinker.service.risk.RiskAction;
import io.github.rainyinsaigon.fraudlinker.service.engine.LinkerEngine;
import io.github.rainyinsaigon.fraudlinker.service.persistence.AuditRepository;
import io.github.rainyinsaigon.fraudlinker.service.persistence.DecisionRepository;
import io.github.rainyinsaigon.fraudlinker.service.risk.AnalystDecision;
import io.github.rainyinsaigon.fraudlinker.service.risk.Outcome;
import io.github.rainyinsaigon.fraudlinker.service.risk.RiskAssessment;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
    private final RiskAssessor risk;
    private final Clock clock;

    ClusterService(LinkerEngine engine, WriteThrough writeThrough, DecisionRepository decisions,
                   AuditRepository audit, LinkerProperties properties, RiskAssessor risk, Clock clock) {
        this.engine = engine;
        this.writeThrough = writeThrough;
        this.decisions = decisions;
        this.risk = risk;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    /** @param muleScores mule scores of the listed members that sent or received money, in member order */
    public record ClusterView(ClusterExplainer.Explanation explanation, RiskAssessment risk,
                              Optional<AnalystDecision> latestDecision, List<MuleScore> muleScores) {
    }

    /** @throws NotFoundException if the cluster is unknown */
    public ClusterView explain(String clusterId) {
        return engine.withLock(session -> {
            ClusterExplainer.Explanation explanation = ClusterExplainer
                    .explain(session.state(), clusterId, properties.maxExplainMembers())
                    .orElseThrow(() -> notFound(clusterId));
            List<String> members = session.state().memberIds(clusterId);
            Optional<AnalystDecision> latest = decisions.findLatestCovering(members);
            Map<String, MuleScore> scores = session.muleScores();
            List<MuleScore> muleScores = explanation.accountIds().stream()
                    .map(scores::get)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            return new ClusterView(explanation, risk.assess(session, members, latest), latest, muleScores);
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

    public record QueueItem(String clusterId, int size, double confidence, RiskAssessment risk) {
    }

    /**
     * Clusters an analyst should look at: two or more accounts and a REVIEW
     * action. Largest first, then oldest cluster number.
     *
     * <p>Checks every multi-account cluster's decisions one by one, which is fine
     * at demo scale. A production queue would be maintained incrementally.
     */
    public List<QueueItem> reviewQueue(int limit) {
        return engine.withLock(session -> {
            LinkState state = session.state();
            List<QueueItem> items = new ArrayList<>();
            for (Cluster cluster : state.clusters().clusters()) {
                List<String> members = cluster.accountIds();
                if (members.size() < 2) {
                    continue;
                }
                RiskAssessment assessment = risk.assess(session, members);
                if (assessment.action() == RiskAction.REVIEW) {
                    items.add(new QueueItem(cluster.clusterId(), members.size(), cluster.confidence(), assessment));
                }
            }
            items.sort(Comparator.comparingInt(QueueItem::size).reversed()
                    .thenComparingInt(item -> LinkState.clusterNumber(item.clusterId())));
            return List.copyOf(items.subList(0, Math.min(limit, items.size())));
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

            RiskAssessment assessment = risk.assess(session, members, Optional.of(decision));
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("decision_id", decision.decisionId().toString());
            details.put("outcome", outcome.name());
            details.put("account_count", members.size());
            details.put("risk_action", assessment.action().name());
            audit.record(now, AuditRepository.DECISION_RECORDED, null, clusterId, analyst, details);
            return new DecisionResult(decision, assessment);
        });
    }

    private static NotFoundException notFound(String clusterId) {
        return new NotFoundException("Cluster " + clusterId + " not found.");
    }
}
