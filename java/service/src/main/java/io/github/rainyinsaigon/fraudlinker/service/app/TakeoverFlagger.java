package io.github.rainyinsaigon.fraudlinker.service.app;

import io.github.rainyinsaigon.fraudlinker.service.engine.LinkerEngine;
import io.github.rainyinsaigon.fraudlinker.service.persistence.AuditRepository;
import io.github.rainyinsaigon.fraudlinker.service.persistence.TakeoverFlagRepository;
import io.github.rainyinsaigon.fraudlinker.takeover.TakeoverReason;
import io.github.rainyinsaigon.fraudlinker.takeover.TakeoverScore;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Records the first time each held account is flagged as a possible takeover,
 * and audits it once. Later logins that keep it flagged add nothing.
 */
@Component
class TakeoverFlagger {
    private final TakeoverFlagRepository flags;
    private final AuditRepository audit;

    TakeoverFlagger(TakeoverFlagRepository flags, AuditRepository audit) {
        this.flags = flags;
        this.audit = audit;
    }

    /**
     * Caller must be inside {@link WriteThrough}. {@code actor} is the system whose data raised the flag.
     *
     * @return accounts flagged for the first time, ordered by ID
     */
    List<String> recordNew(LinkerEngine.Session session, Instant now, String actor) {
        List<TakeoverScore> flagged = session.takeoverScores().values().stream()
                .filter(TakeoverScore::flagged)
                .toList();
        if (flagged.isEmpty()) {
            return List.of();
        }
        Map<String, Instant> known = flags.findFlaggedAt(flagged.stream().map(TakeoverScore::accountId).toList());
        List<String> created = new ArrayList<>();
        for (TakeoverScore score : flagged) {
            if (known.containsKey(score.accountId())) {
                continue;
            }
            List<String> codes = score.reasons().stream().map(TakeoverReason::code).toList();
            flags.insert(score.accountId(), now, score.score(), String.join(",", codes));
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("score", score.score());
            details.put("reasons", codes);
            details.put("description", RiskAssessor.describe(score));
            details.put("reached_at", score.reachedAt().toString());
            audit.record(now, AuditRepository.TAKEOVER_FLAGGED, score.accountId(),
                    session.state().clusterIdOf(score.accountId()).orElse(null), actor, details);
            created.add(score.accountId());
        }
        return List.copyOf(created);
    }
}
