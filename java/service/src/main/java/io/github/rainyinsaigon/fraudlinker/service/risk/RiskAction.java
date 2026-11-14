package io.github.rainyinsaigon.fraudlinker.service.risk;

/** What the business should do with an account right now. */
public enum RiskAction {
    /** No reason to act. */
    ALLOW,
    /** Linked to other accounts; a person should look before anyone is restricted. */
    REVIEW,
    /** Part of a cluster an analyst confirmed as fraud. */
    RESTRICT
}
