package io.github.rainyinsaigon.fraudlinker.evaluation;

import io.github.rainyinsaigon.fraudlinker.LinkerException;

/**
 * Prices for takeover detection mistakes, in dollars.
 *
 * @param falseFlagCost      an analyst reviewing a legitimate login, and the customer's friction
 * @param missedTakeoverCost the customer's balance the attacker can move, plus the customer's trust
 */
public record TakeoverCostModel(long falseFlagCost, long missedTakeoverCost) {

    public TakeoverCostModel {
        if (falseFlagCost < 0 || missedTakeoverCost < 0) {
            throw new LinkerException("costs must not be negative");
        }
    }
}
