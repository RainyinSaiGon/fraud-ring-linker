package io.github.rainyinsaigon.fraudlinker.service.app;

import io.github.rainyinsaigon.fraudlinker.mules.MuleReason;
import io.github.rainyinsaigon.fraudlinker.mules.MuleScore;
import io.github.rainyinsaigon.fraudlinker.service.engine.LinkerEngine;
import io.github.rainyinsaigon.fraudlinker.service.persistence.AuditRepository;
import io.github.rainyinsaigon.fraudlinker.service.persistence.MuleFlagRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Records the first time each held account is flagged as a likely mule, and
 * audits it once. Later transfers that keep it flagged add nothing.
 */
@Component
class MuleFlagger {
    private final MuleFlagRepository flags;
    private final AuditRepository audit;

    MuleFlagger(MuleFlagRepository flags, AuditRepository audit) {
        this.flags = flags;
        this.audit = audit;
    }

    /**
     * Caller must be inside {@link WriteThrough}.
     *
     * @return accounts flagged for the first time, ordered by ID
     */
    List<String> recordNew(LinkerEngine.Session session, Instant now) {
        List<MuleScore> flagged = session.muleScores().values().stream().filter(MuleScore::flagged).toList();
        if (flagged.isEmpty()) {
            return List.of();
        }
        Map<String, Instant> known = flags.findFlaggedAt(flagged.stream().map(MuleScore::accountId).toList());
        List<String> created = new ArrayList<>();
        for (MuleScore score : flagged) {
            if (known.containsKey(score.accountId())) {
                continue;
            }
            String codes = score.reasons().stream().map(MuleReason::code).collect(Collectors.joining(","));
            flags.insert(score.accountId(), now, score.score(), codes);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("score", score.score());
            details.put("reasons", score.reasons().stream().map(MuleReason::code).toList());
            details.put("description", RiskAssessor.describe(score));
            audit.record(now, AuditRepository.MULE_FLAGGED, score.accountId(),
                    session.state().clusterIdOf(score.accountId()).orElse(null), AuditRepository.SYSTEM, details);
            created.add(score.accountId());
        }
        return List.copyOf(created);
    }
}
