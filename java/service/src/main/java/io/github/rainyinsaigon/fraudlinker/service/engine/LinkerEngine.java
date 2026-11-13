package io.github.rainyinsaigon.fraudlinker.service.engine;

import io.github.rainyinsaigon.fraudlinker.clustering.BatchLinker;
import io.github.rainyinsaigon.fraudlinker.clustering.ClusteringConfig;
import io.github.rainyinsaigon.fraudlinker.clustering.Constraints;
import io.github.rainyinsaigon.fraudlinker.clustering.LinkState;
import io.github.rainyinsaigon.fraudlinker.domain.Account;
import io.github.rainyinsaigon.fraudlinker.domain.Constraint;
import io.github.rainyinsaigon.fraudlinker.service.persistence.AccountRepository;
import io.github.rainyinsaigon.fraudlinker.service.persistence.ConstraintRepository;
import io.github.rainyinsaigon.fraudlinker.streaming.StreamLinker;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
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
    private final ReentrantLock lock = new ReentrantLock();

    /** Guarded by {@link #lock}. Null while unavailable. */
    private LinkState state;
    /** Guarded by {@link #lock}. Null while unavailable. */
    private StreamLinker streamLinker;

    public LinkerEngine(AccountRepository accounts, ConstraintRepository constraints) {
        this.accounts = accounts;
        this.constraints = constraints;
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
    private void rebuild() {
        long start = System.nanoTime();
        try {
            List<Account> stored = accounts.findAllInArrivalOrder();
            List<Constraint> pairs = constraints.findAll();
            LinkState rebuilt = BatchLinker.build(stored, Constraints.of(pairs), ClusteringConfig.defaults());
            state = rebuilt;
            streamLinker = new StreamLinker(rebuilt);
            log.info("Linked {} accounts with {} verified-distinct pairs in {} ms",
                    stored.size(), pairs.size(), (System.nanoTime() - start) / 1_000_000);
        } catch (RuntimeException e) {
            state = null;
            streamLinker = null;
            log.error("Could not load linking state from the database; requests will fail until it can", e);
            throw new EngineUnavailableException(e);
        }
    }
}
