package io.github.rainyinsaigon.fraudlinker.service.app;

import io.github.rainyinsaigon.fraudlinker.mules.MuleReason;
import io.github.rainyinsaigon.fraudlinker.mules.MuleScore;
import io.github.rainyinsaigon.fraudlinker.service.engine.LinkerEngine;
import io.github.rainyinsaigon.fraudlinker.service.persistence.DecisionRepository;
import io.github.rainyinsaigon.fraudlinker.service.persistence.MuleFlagRepository;
import io.github.rainyinsaigon.fraudlinker.service.risk.AnalystDecision;
import io.github.rainyinsaigon.fraudlinker.service.risk.MuleFlag;
import io.github.rainyinsaigon.fraudlinker.service.risk.RiskAssessment;
import io.github.rainyinsaigon.fraudlinker.service.risk.RiskPolicy;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/** Gathers what {@link RiskPolicy} needs for a cluster: its latest decision and its mule flags. */
@Component
public class RiskAssessor {
    private final DecisionRepository decisions;
    private final MuleFlagRepository flags;

    public RiskAssessor(DecisionRepository decisions, MuleFlagRepository flags) {
        this.decisions = decisions;
        this.flags = flags;
    }

    /** Caller must be inside the engine lock. */
    public RiskAssessment assess(LinkerEngine.Session session, List<String> members) {
        return assess(session, members, decisions.findLatestCovering(members));
    }

    /** Caller must be inside the engine lock. */
    public RiskAssessment assess(LinkerEngine.Session session, List<String> members,
                                 Optional<AnalystDecision> latestDecision) {
        return RiskPolicy.assess(members, latestDecision, muleFlags(session, members));
    }

    /** Members currently flagged as likely mules, with when each was first flagged. */
    public List<MuleFlag> muleFlags(LinkerEngine.Session session, List<String> members) {
        Map<String, MuleScore> scores = session.muleScores();
        List<MuleScore> flagged = members.stream()
                .map(scores::get)
                .filter(Objects::nonNull)
                .filter(MuleScore::flagged)
                .toList();
        if (flagged.isEmpty()) {
            return List.of();
        }
        Map<String, Instant> flaggedAt = flags.findFlaggedAt(flagged.stream().map(MuleScore::accountId).toList());
        return flagged.stream()
                .map(score -> new MuleFlag(score.accountId(), score.score(), describe(score),
                        flaggedAt.get(score.accountId())))
                .toList();
    }

    /** The reasons in plain words, for example "6 unrelated payers in 25 min, 90% sent on within 30 min". */
    public static String describe(MuleScore score) {
        return score.reasons().stream().map(MuleReason::describe).collect(Collectors.joining(", "));
    }
}
