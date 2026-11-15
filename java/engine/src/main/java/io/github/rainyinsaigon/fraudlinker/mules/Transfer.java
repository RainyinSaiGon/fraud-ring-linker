package io.github.rainyinsaigon.fraudlinker.mules;

import io.github.rainyinsaigon.fraudlinker.LinkerException;

import java.time.Instant;

/**
 * Money sent from one account to another. Either side may be an account the
 * bank does not hold (for example a scam victim paying in from another bank),
 * so neither ID has to be a known account.
 *
 * @param amountCents whole cents, always positive
 */
public record Transfer(String transferId, String fromAccount, String toAccount, long amountCents,
                       Instant occurredAt) {

    public Transfer {
        if (isBlank(transferId)) {
            throw new LinkerException("a transfer needs a transfer_id");
        }
        if (isBlank(fromAccount) || isBlank(toAccount)) {
            throw new LinkerException("transfer " + transferId + " needs both a from and a to account");
        }
        if (fromAccount.equals(toAccount)) {
            throw new LinkerException("transfer " + transferId + " sends money from an account to itself");
        }
        if (amountCents <= 0) {
            throw new LinkerException("transfer " + transferId + " must move a positive amount");
        }
        if (occurredAt == null) {
            throw new LinkerException("transfer " + transferId + " needs a time");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
