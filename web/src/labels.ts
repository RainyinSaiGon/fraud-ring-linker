// Plain-language names for the engine's rule and signal codes.

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

export function percent(value: number): string {
  return `${Math.round(value * 100)}%`;
}

export function dateTime(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime())
    ? iso
    : date.toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
}
