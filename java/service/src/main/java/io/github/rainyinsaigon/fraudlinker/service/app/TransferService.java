package io.github.rainyinsaigon.fraudlinker.service.app;

import io.github.rainyinsaigon.fraudlinker.clustering.LinkState;
import io.github.rainyinsaigon.fraudlinker.mules.MuleScore;
import io.github.rainyinsaigon.fraudlinker.mules.Transfer;
import io.github.rainyinsaigon.fraudlinker.service.engine.LinkerEngine;
import io.github.rainyinsaigon.fraudlinker.service.persistence.DecisionRepository;
import io.github.rainyinsaigon.fraudlinker.service.persistence.TransferRepository;
import io.github.rainyinsaigon.fraudlinker.service.risk.AnalystDecision;
import io.github.rainyinsaigon.fraudlinker.service.risk.MuleFlag;
import io.github.rainyinsaigon.fraudlinker.service.risk.RiskAction;
import io.github.rainyinsaigon.fraudlinker.service.risk.RiskAssessment;
import io.github.rainyinsaigon.fraudlinker.service.risk.RiskPolicy;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Money movement: stores transfers, keeps mule scores current, and lists the
 * accounts that look like mules.
 *
 * <p>Transfers never change a cluster. They only feed mule scoring, which reads
 * the clusters to decide which accounts are related.
 */
@Service
public class TransferService {
    private final LinkerEngine engine;
    private final WriteThrough writeThrough;
    private final TransferRepository transfers;
    private final DecisionRepository decisions;
    private final RiskAssessor risk;
    private final MuleFlagger flagger;
    private final Clock clock;

    TransferService(LinkerEngine engine, WriteThrough writeThrough, TransferRepository transfers,
                    DecisionRepository decisions, RiskAssessor risk, MuleFlagger flagger, Clock clock) {
        this.engine = engine;
        this.writeThrough = writeThrough;
        this.transfers = transfers;
        this.decisions = decisions;
        this.risk = risk;
        this.flagger = flagger;
        this.clock = clock;
    }

    /**
     * @param flaggedNow accounts flagged as likely mules for the first time by this batch
     * @param scores     current scores of the accounts the bank holds that this batch touched, by ID
     */
    public record RecordResult(int accepted, List<String> flaggedNow, List<MuleScore> scores) {
    }

    /**
     * Stores a batch of transfers, all or nothing.
     *
     * @throws ConflictException if a transfer ID repeats within the batch or is already stored
     */
    public RecordResult record(List<Transfer> batch, String client) {
        Set<String> ids = new HashSet<>();
        for (Transfer transfer : batch) {
            if (!ids.add(transfer.transferId())) {
                throw new ConflictException("Transfer " + transfer.transferId() + " appears twice in this batch.");
            }
        }
        return writeThrough.execute(session -> {
            Set<String> existing = new TreeSet<>(transfers.findExistingIds(ids));
            if (!existing.isEmpty()) {
                throw new ConflictException("Transfer " + existing.iterator().next() + " already exists.");
            }
            Instant now = clock.instant();
            Set<String> touched = new TreeSet<>();
            for (Transfer transfer : batch) {
                transfers.insert(transfer, now);
                session.addTransfer(transfer);
                touched.add(transfer.fromAccount());
                touched.add(transfer.toAccount());
            }
            List<String> flaggedNow = flagger.recordNew(session, now, client);
            Map<String, MuleScore> scores = session.muleScores();
            List<MuleScore> touchedScores = touched.stream().map(scores::get).filter(s -> s != null).toList();
            return new RecordResult(batch.size(), flaggedNow, touchedScores);
        });
    }

    /** A flagged account an analyst has not decided on since it was flagged. */
    public record MuleView(String accountId, String clusterId, int clusterSize, MuleScore score, MuleFlag flag,
                           RiskAssessment risk) {
    }

    /** Likely mules waiting for an analyst, highest score first, then by account ID. */
    public List<MuleView> openFlags(int limit) {
        return engine.withLock(session -> {
            LinkState state = session.state();
            List<MuleView> views = new ArrayList<>();
            for (MuleScore score : session.muleScores().values()) {
                if (!score.flagged()) {
                    continue;
                }
                String clusterId = state.clusterIdOf(score.accountId()).orElseThrow();
                List<String> members = state.memberIds(clusterId);
                Optional<AnalystDecision> latest = decisions.findLatestCovering(members);
                MuleFlag flag = risk.muleFlags(session, List.of(score.accountId())).get(0);
                RiskAssessment assessment = risk.assess(session, members, latest);
                if (assessment.action() == RiskAction.RESTRICT || RiskPolicy.clears(latest, flag)) {
                    continue;
                }
                views.add(new MuleView(score.accountId(), clusterId, members.size(), score, flag, assessment));
            }
            views.sort(Comparator.comparingDouble((MuleView v) -> v.score().score()).reversed()
                    .thenComparing(MuleView::accountId));
            return List.copyOf(views.subList(0, Math.min(limit, views.size())));
        });
    }

    /**
     * Money into or out of an account the bank holds, newest first.
     *
     * @throws NotFoundException if the account is unknown
     */
    public List<Transfer> transfersOf(String accountId, int limit) {
        return engine.withLock(session -> {
            if (session.state().store().indexOf(accountId) < 0) {
                throw new NotFoundException("Account " + accountId + " not found.");
            }
            return transfers.findByAccount(accountId, limit);
        });
    }
}
