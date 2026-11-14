package io.github.rainyinsaigon.fraudlinker.service.risk;

/** An action plus a plain-language reason an analyst or support agent can read. */
public record RiskAssessment(RiskAction action, String reason) {
}
