package io.github.rainyinsaigon.fraudlinker.service.engine;

import io.github.rainyinsaigon.fraudlinker.clustering.BatchLinker;
import io.github.rainyinsaigon.fraudlinker.clustering.ClusteringConfig;
import io.github.rainyinsaigon.fraudlinker.clustering.Constraints;
import io.github.rainyinsaigon.fraudlinker.clustering.LinkState;
import io.github.rainyinsaigon.fraudlinker.clustering.LinkState.MergeOutcome;
import io.github.rainyinsaigon.fraudlinker.domain.Account;
import io.github.rainyinsaigon.fraudlinker.domain.Constraint;
import io.github.rainyinsaigon.fraudlinker.mules.MuleConfig;
import io.github.rainyinsaigon.fraudlinker.mules.MuleScore;
import io.github.rainyinsaigon.fraudlinker.mules.MuleScorer;
import io.github.rainyinsaigon.fraudlinker.mules.Transfer;
import io.github.rainyinsaigon.fraudlinker.service.persistence.AccountRepository;
import io.github.rainyinsaigon.fraudlinker.service.app.MergeProposal;
import io.github.rainyinsaigon.fraudlinker.service.persistence.ConstraintRepository;
import io.github.rainyinsaigon.fraudlinker.service.persistence.MergeProposalRepository;
import io.github.rainyinsaigon.fraudlinker.service.persistence.LoginRepository;
import io.github.rainyinsaigon.fraudlinker.service.persistence.TransferRepository;
import io.github.rainyinsaigon.fraudlinker.takeover.Login;
import io.github.rainyinsaigon.fraudlinker.takeover.TakeoverConfig;
import io.github.rainyinsaigon.fraudlinker.takeover.TakeoverScore;
import io.github.rainyinsaigon.fraudlinker.takeover.TakeoverScorer;
import io.github.rainyinsaigon.fraudlinker.streaming.StreamLinker;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Owns the in-memory linking state and serializes every access to it.
 *
 * <p>The database is the source of truth; this state is a cache of it. It is
 * built from the database at startup and rebuilt whenever a database write
 * fails after the state already changed, so the two never drift apart. If a
 * rebuild itself fails (the database is down), the state is dropped and every
 * request gets {@link EngineUnavailableException} until a rebuild succeeds.
 *
 * <p>A rebuild links all stored accounts in one batch, then replays every
 * accepted merge proposal in the order it was accepted, so an analyst's merge
 * survives a restart.
 *
 * <p>Transfers are kept here too, for mule scoring. They never change the
 * linking state; scores are read from it (accounts in one cluster are related)
 * and are cached until the state or the transfers change. Logins are kept the
 * same way, for takeover scoring.
 *
 * <p>One lock guards everything, including reads, because the engine is
 * single-threaded by design (union-find lookups compress paths, even on reads).
 * Engine work is microseconds per call; the database write inside the lock is
 * what limits throughput.
 */
@Component
public class LinkerEngine {
    private static final Logger log = LoggerFactory.getLogger(LinkerEngine.class);

    private final AccountRepository accounts;
    private final ConstraintRepository constraints;
    private final MergeProposalRepository mergeProposals;
    private final TransferRepository transferRepository;
    private final LoginRepository loginRepository;
    private final MuleScorer muleScorer = new MuleScorer(MuleConfig.defaults());
    private final TakeoverScorer takeoverScorer = new TakeoverScorer(TakeoverConfig.defaults());
    private final ReentrantLock lock = new ReentrantLock();

    /** Guarded by {@link #lock}. Null while unavailable. */
    private LinkState state;
    /** Guarded by {@link #lock}. Null while unavailable. */
    private StreamLinker streamLinker;
    /** Guarded by {@link #lock}. Every stored transfer, in arrival order. */
    private List<Transfer> transfers = new ArrayList<>();
    /** Guarded by {@link #lock}. Scores of held accounts, valid while {@link #scoresFingerprint} matches. */
    private Map<String, MuleScore> muleScores;
    private long scoresFingerprint;
    /** Guarded by {@link #lock}. Every stored login, in arrival order. */
    private List<Login> logins = new ArrayList<>();
    /** Guarded by {@link #lock}. Scores of held accounts, valid while {@link #takeoverFingerprint} matches. */
    private Map<String, TakeoverScore> takeoverScores;
    private long takeoverFingerprint;

    public LinkerEngine(AccountRepository accounts, ConstraintRepository constraints,
                        MergeProposalRepository mergeProposals, TransferRepository transferRepository,
                        LoginRepository loginRepository) {
        this.accounts = accounts;
        this.constraints = constraints;
        this.mergeProposals = mergeProposals;
        this.transferRepository = transferRepository;
        this.loginRepository = loginRepository;
    }

    @PostConstruct
    void loadFromDatabase() {
        lock.lock();
        try {
            rebuild();
        } finally {
            lock.unlock();
        }
    }

    /** Access to the engine while holding the lock. Nothing obtained here may be used after {@code work} returns. */
    public interface Session {
        LinkState state();

        StreamLinker streamLinker();

        /** Every stored transfer, in arrival order (read-only). */
        List<Transfer> transfers();

        /** Adds a transfer that the caller has just stored in the database. */
        void addTransfer(Transfer transfer);

        /**
         * Mule scores of every account the bank holds that sent or received money,
         * by account ID. Accounts outside the bank count as payers and payees but are
         * not scored: nobody can review them.
         */
        Map<String, MuleScore> muleScores();

        /** Adds a login that the caller has just stored in the database. */
        void addLogin(Login login);

        /**
         * Takeover scores of every account the bank holds that has logins, by
         * account ID. Logins for accounts not held yet are kept, and count once the
         * account registers.
         */
        Map<String, TakeoverScore> takeoverScores();

        /** Changes whenever the linking state, the transfers or the logins change. */
        long fingerprint();

        /** Discards the in-memory state and relinks everything from the database. */
        void rebuildFromDatabase();
    }

    /**
     * Runs {@code work} with exclusive access to the engine.
     *
     * @throws EngineUnavailableException if the state could not be loaded from the database
     */
    public <T> T withLock(Function<Session, T> work) {
        lock.lock();
        try {
            if (state == null) {
                rebuild();
            }
            return work.apply(new Session() {
                @Override
                public LinkState state() {
                    return state;
                }

                @Override
                public StreamLinker streamLinker() {
                    return streamLinker;
                }

                @Override
                public List<Transfer> transfers() {
                    return Collections.unmodifiableList(transfers);
                }

                @Override
                public void addTransfer(Transfer transfer) {
                    transfers.add(transfer);
                }

                @Override
                public Map<String, MuleScore> muleScores() {
                    return scores();
                }

                @Override
                public void addLogin(Login login) {
                    logins.add(login);
                }

                @Override
                public Map<String, TakeoverScore> takeoverScores() {
                    return LinkerEngine.this.takeoverScores();
                }

                @Override
                public long fingerprint() {
                    return LinkerEngine.this.fingerprint();
                }

                @Override
                public void rebuildFromDatabase() {
                    rebuild();
                }
            });
        } finally {
            lock.unlock();
        }
    }

    /** True when the state is loaded; used by the readiness health check. */
    public boolean isAvailable() {
        lock.lock();
        try {
            return state != null;
        } finally {
            lock.unlock();
        }
    }

    /** Caller must hold the lock. */
    private long fingerprint() {
        return state.store().size() * 1_000_003L + state.constraints().pairCount()
                + state.clusterCount() * 1_000_000_007L + transfers.size() * 998_244_353L + logins.size() * 754_974_721L;
    }

    /** Caller must hold the lock. */
    private Map<String, TakeoverScore> takeoverScores() {
        long now = fingerprint();
        if (takeoverScores == null || takeoverFingerprint != now) {
            Map<String, TakeoverScore> held = new LinkedHashMap<>();
            takeoverScorer.scoreAll(logins, MuleScorer.sameCluster(state)).forEach((accountId, score) -> {
                if (state.store().indexOf(accountId) >= 0) {
                    held.put(accountId, score);
                }
            });
            takeoverScores = Collections.unmodifiableMap(held);
            takeoverFingerprint = now;
        }
        return takeoverScores;
    }

    /** Caller must hold the lock. */
    private Map<String, MuleScore> scores() {
        long now = fingerprint();
        if (muleScores == null || scoresFingerprint != now) {
            Map<String, MuleScore> held = new LinkedHashMap<>();
            muleScorer.scoreAll(transfers, MuleScorer.sameCluster(state)).forEach((accountId, score) -> {
                if (state.store().indexOf(accountId) >= 0) {
                    held.put(accountId, score);
                }
            });
            muleScores = Collections.unmodifiableMap(held);
            scoresFingerprint = now;
        }
        return muleScores;
    }

    /** Caller must hold the lock. */
    private void rebuild() {
        long start = System.nanoTime();
        try {
            List<Account> stored = accounts.findAllInArrivalOrder();
            List<Constraint> pairs = constraints.findAll();
            LinkState rebuilt = BatchLinker.build(stored, Constraints.of(pairs), ClusteringConfig.defaults());
            List<MergeProposal> accepted = mergeProposals.findAcceptedInDecisionOrder();
            for (MergeProposal proposal : accepted) {
                List<String> accountIds = new ArrayList<>(proposal.accountsA());
                accountIds.addAll(proposal.accountsB());
                if (ClusterMerger.mergeAccounts(rebuilt, accountIds, proposal.score())
                        == MergeOutcome.CONFLICTS_WITH_CONSTRAINT) {
                    // Cannot happen through the API: a constraint inside one cluster is refused.
                    log.warn("Accepted merge proposal {} now conflicts with a constraint; not replayed",
                            proposal.proposalId());
                }
            }
            List<Transfer> storedTransfers = new ArrayList<>(transferRepository.findAll());
            List<Login> storedLogins = new ArrayList<>(loginRepository.findAll());
            state = rebuilt;
            streamLinker = new StreamLinker(rebuilt);
            transfers = storedTransfers;
            muleScores = null;
            logins = storedLogins;
            takeoverScores = null;
            log.info("Linked {} accounts with {} verified-distinct pairs and {} accepted merges, and loaded {} "
                            + "transfers and {} logins, in {} ms", stored.size(), pairs.size(), accepted.size(),
                    storedTransfers.size(), storedLogins.size(), (System.nanoTime() - start) / 1_000_000);
        } catch (RuntimeException e) {
            state = null;
            streamLinker = null;
            transfers = new ArrayList<>();
            muleScores = null;
            logins = new ArrayList<>();
            takeoverScores = null;
            log.error("Could not load linking state from the database; requests will fail until it can", e);
            throw new EngineUnavailableException(e);
        }
    }
}
