package io.github.rainyinsaigon.fraudlinker.service.app;

import io.github.rainyinsaigon.fraudlinker.service.engine.LinkerEngine;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Function;

/**
 * Runs a change against both the engine and the database as one unit.
 *
 * <p>The work runs while holding the engine lock and inside one database
 * transaction. If it fails after the engine changed (including in the commit
 * itself), the transaction rolls back and the engine is rebuilt from the
 * database, so memory never holds a change the database does not.
 *
 * <p>A failure before the engine changed (a duplicate account, an unknown
 * cluster) does not trigger a rebuild. Otherwise anyone could force an
 * expensive full relink just by sending bad requests.
 */
@Component
public class WriteThrough {
    private final LinkerEngine engine;
    private final TransactionTemplate transactions;

    public WriteThrough(LinkerEngine engine, TransactionTemplate transactions) {
        this.engine = engine;
        this.transactions = transactions;
    }

    public <T> T execute(Function<LinkerEngine.Session, T> work) {
        return engine.withLock(session -> {
            long before = session.fingerprint();
            try {
                return transactions.execute(status -> work.apply(session));
            } catch (RuntimeException e) {
                if (session.fingerprint() != before) {
                    session.rebuildFromDatabase();
                }
                throw e;
            }
        });
    }
}
