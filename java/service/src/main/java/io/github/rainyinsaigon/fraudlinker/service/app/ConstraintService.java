package io.github.rainyinsaigon.fraudlinker.service.app;

import io.github.rainyinsaigon.fraudlinker.clustering.LinkState.ConstraintOutcome;
import io.github.rainyinsaigon.fraudlinker.service.persistence.AuditRepository;
import io.github.rainyinsaigon.fraudlinker.service.persistence.ConstraintRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/**
 * Records that two accounts belong to different people, typically because both
 * passed independent identity verification.
 */
@Service
public class ConstraintService {
    private final WriteThrough writeThrough;
    private final ConstraintRepository constraints;
    private final AuditRepository audit;
    private final Clock clock;

    public ConstraintService(WriteThrough writeThrough, ConstraintRepository constraints, AuditRepository audit,
                             Clock clock) {
        this.writeThrough = writeThrough;
        this.constraints = constraints;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * @return ADDED or ALREADY_PRESENT
     * @throws ConflictException when both accounts are already in one cluster;
     *                           splitting a cluster needs a full re-link and an analyst, not an API call
     */
    public ConstraintOutcome addVerifiedDistinct(String accountA, String accountB, String actor) {
        if (accountA.equals(accountB)) {
            throw new IllegalArgumentException("An account cannot be distinct from itself.");
        }
        return writeThrough.execute(session -> {
            ConstraintOutcome outcome = session.state().addConstraint(accountA, accountB);
            switch (outcome) {
                case CONFLICTS_WITH_EXISTING_CLUSTER -> throw new ConflictException("Accounts " + accountA + " and "
                        + accountB + " are already linked in one cluster. Review that cluster instead.");
                case ALREADY_PRESENT -> {
                    // Nothing to store.
                }
                case ADDED -> {
                    Instant now = clock.instant();
                    constraints.insert(accountA, accountB, now);
                    audit.record(now, AuditRepository.CONSTRAINT_ADDED, accountA, null, actor,
                            Map.of("other_account_id", accountB));
                }
            }
            return outcome;
        });
    }
}
