package io.github.rainyinsaigon.fraudlinker.service.risk;

/** What the business should do with an account right now. */
public enum RiskAction {
    /** No reason to act. */
    ALLOW,
    /** Linked to other accounts, or flagged; a person should look before anyone is restricted. */
    REVIEW,
    /**
     * An analyst confirmed someone else got into this account: ask for strong
     * re-verification before letting the next login or payment through. Applies
     * to that account only, never to the rest of its cluster.
     */
    STEP_UP,
    /** Part of a cluster an analyst confirmed as fraud. */
    RESTRICT
}
