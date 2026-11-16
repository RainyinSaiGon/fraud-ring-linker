// Types for logins.mjs, for TypeScript callers (the analyst page's end-to-end test).

import type { Scenario } from "./scenario.mjs";

export interface SimLogin {
  login_id: string;
  account_id: string;
  device_id: string;
  ip: string;
  success: boolean;
  occurred_at: string;
  /** Where IP geolocation puts the address; both null when it has no known place. */
  latitude: number | null;
  longitude: number | null;
}

export interface PlantedTakeover {
  account_id: string;
  kind: "credential_stuffing" | "phishing" | "password_guessing";
  attacker_device: string;
  /** The attacker's first successful login on the account. */
  first_bad_login_id: string;
  first_bad_at: string;
  /** Every attacker login on the account, failures included, in time order. */
  attacker_login_ids: string[];
}

export interface LoginCase {
  case: "new_phone" | "holiday" | "forgotten_password" | "family_tablet";
  account_id: string;
}

export interface LoginOptions {
  loginDays?: number;
  stuffingCampaigns?: number;
  stuffingMinVictims?: number;
  stuffingMaxVictims?: number;
  stuffingTargets?: number;
  phishing?: number;
  passwordGuessing?: number;
  newPhone?: number;
  holiday?: number;
  forgottenPassword?: number;
  familyTablets?: number;
}

export const LOGIN_DEFAULTS: Readonly<Required<LoginOptions>>;

export function buildLogins(
  scenario: Scenario,
  options?: LoginOptions,
): { logins: SimLogin[]; takeovers: PlantedTakeover[]; cases: LoginCase[] };

/** Great-circle distance between two located places, in km. */
export function distanceKm(
  a: { latitude: number; longitude: number },
  b: { latitude: number; longitude: number },
): number;
