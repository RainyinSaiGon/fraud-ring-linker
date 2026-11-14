package io.github.rainyinsaigon.fraudlinker.service.risk;

/** What an analyst concluded after reviewing a cluster. */
public enum Outcome {
    /** The linked accounts are a fraud ring. */
    CONFIRMED_FRAUD,
    /** The link is real but harmless (a family, a shared office) or wrong: leave the accounts alone. */
    NOT_FRAUD
}
