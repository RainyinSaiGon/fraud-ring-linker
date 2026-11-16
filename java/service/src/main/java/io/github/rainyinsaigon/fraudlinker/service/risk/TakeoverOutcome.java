package io.github.rainyinsaigon.fraudlinker.service.risk;

/** What an analyst concluded about one account that may have been taken over. */
public enum TakeoverOutcome {
    /** Someone other than the customer got into the account. */
    CONFIRMED_TAKEOVER,
    /** The customer was the one logging in (a new phone, a trip), or the customer has re-verified. */
    NOT_TAKEOVER
}
