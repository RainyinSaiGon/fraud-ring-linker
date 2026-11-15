package io.github.rainyinsaigon.fraudlinker.evaluation;

import io.github.rainyinsaigon.fraudlinker.LinkerException;

/**
 * Prices for mule detection mistakes, in dollars.
 *
 * @param falseFlagCost  an analyst reviewing a legitimate account, and the customer's friction
 * @param missedMuleCost scam money that keeps moving through a mule nobody looked at
 */
public record MuleCostModel(long falseFlagCost, long missedMuleCost) {

    public MuleCostModel {
        if (falseFlagCost < 0 || missedMuleCost < 0) {
            throw new LinkerException("costs must not be negative");
        }
    }
}
