// Plain-language names for the engine's rule and signal codes.

import type { AuditEvent } from "./api";

const RULES: Record<string, string> = {
  rare_payment: "Same payment card, rarely shared",
  rare_exact_or_canonical_email: "Same email address",
  device_and_payment: "Same phone and card",
  device_and_email: "Same phone, similar email",
  device_network_and_time: "Same phone and IP within a day",
  email_variation_and_payment: "Similar email and same card",
  common_email_with_corroboration: "Shared email backed by another signal",
  diversified_prefix_velocity: "Sign-up burst on one subnet",
  strong_seeded_prefix_cohort: "Small subnet around a confirmed link",
  supporting_evidence_only: "Weak evidence only",
};

const SIGNALS: Record<string, string> = {
  same_email: "same email",
  same_email_after_normalization: "same email (ignoring dots and +tags)",
  email_one_edit_apart: "email one letter apart",
  same_email_name_different_domain: "same email name, other domain",
  similar_email_ignoring_separators: "similar email",
  same_device: "same phone",
  same_payment_method: "same card",
  same_ip: "same IP",
  same_ip_subnet: "same subnet",
  signup_burst_on_subnet: "sign-up burst",
};

export function ruleLabel(rule: string): string {
  return RULES[rule] ?? rule.replaceAll("_", " ");
}

export function signalLabel(signal: string): string {
  return SIGNALS[signal] ?? signal.replaceAll("_", " ");
}

/** Whole dollars and cents from integer cents, for example 184233 -> "$1,842.33". */
export function money(cents: number): string {
  return (cents / 100).toLocaleString("en-AU", { style: "currency", currency: "AUD", currencyDisplay: "narrowSymbol" });
}

/** Where geolocation put a login, for example "-33.87, 151.21", or "Unknown". */
export function place(latitude: number | null, longitude: number | null): string {
  return latitude === null || longitude === null ? "Unknown" : `${latitude.toFixed(2)}, ${longitude.toFixed(2)}`;
}

const TAKEOVER_MARKS: Record<string, string> = {
  new_device: "New device",
  impossible_travel: "Impossible jump",
  failed_burst_then_success: "Success after failed logins",
  failed_burst: "Burst of failed logins",
  shared_device: "Device shared with other accounts",
};

/** A short name for a takeover signal, shown next to the login that showed it. */
export function takeoverMark(code: string): string {
  return TAKEOVER_MARKS[code] ?? code.replaceAll("_", " ");
}

/** One audit event in plain words, for a history list. */
export function describeEvent(event: AuditEvent): string {
  switch (event.event_type) {
    case "account.linked":
      return `${event.account_id} joined (${String(event.details.risk_action ?? "")})`;
    case "decision.recorded":
      return `${event.actor} marked it ${event.details.outcome === "CONFIRMED_FRAUD" ? "fraud" : "not fraud"}`;
    case "merge.proposed":
      return `${event.account_id} also links to cluster ${String(event.details.other_cluster_id ?? "")}; merge proposed`;
    case "mule.flagged":
      return `${event.account_id} flagged as a likely mule: ${String(event.details.description ?? "")}`;
    case "merge.accepted":
      return `${event.actor} merged clusters (${String(event.details.cluster_size ?? "")} accounts)`;
    case "takeover.flagged":
      return `${event.account_id} flagged as a possible takeover: ${String(event.details.description ?? "")}`;
    case "takeover.decided":
      return `${event.actor} marked ${event.account_id} ${
        event.details.outcome === "CONFIRMED_TAKEOVER" ? "taken over" : "not a takeover"
      }`;
    default:
      return `${event.event_type} by ${event.actor}`;
  }
}

export function percent(value: number): string {
  return `${Math.round(value * 100)}%`;
}

export function dateTime(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime())
    ? iso
    : date.toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
}
