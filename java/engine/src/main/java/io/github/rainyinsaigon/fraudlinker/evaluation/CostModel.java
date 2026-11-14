package io.github.rainyinsaigon.fraudlinker.evaluation;

import io.github.rainyinsaigon.fraudlinker.LinkerException;

/**
 * What each kind of mistake costs the business, in whole dollars.
 *
 * @param wrongLinkCost  a legitimate customer wrongly linked to someone else (support load, churn risk)
 * @param missedRingCost a multi-account actor (a ring) split across clusters, so not caught as a whole
 */
public record CostModel(long wrongLinkCost, long missedRingCost) {
    /** The original estimates: about $50 per wrong link, about $2,000 per missed ring. */
    public static final CostModel DEFAULT = new CostModel(50, 2000);

    public CostModel {
        if (wrongLinkCost < 0 || missedRingCost < 0) {
            throw new LinkerException("costs must not be negative");
        }
    }
}
