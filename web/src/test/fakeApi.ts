import { vi } from "vitest";
import type {
  AccountDetail,
  ClusterDetail,
  ClusterSummary,
  DecisionResult,
  MergeAcceptResult,
  MergeProposal,
  MuleScore,
  Login,
  MuleSummary,
  TakeoverDecisionResult,
  TakeoverOutcome,
  TakeoverReason,
  TakeoverSummary,
  Transfer,
} from "../api";

const b64url = (value: unknown) =>
  btoa(String.fromCharCode(...new TextEncoder().encode(JSON.stringify(value))))
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");

/** An unsigned token with these claims; the page only reads claims, and fetch is mocked. */
export function testToken(claims: Record<string, unknown> = {}): string {
  const payload = { sub: "ana", roles: ["analyst"], exp: Math.floor(Date.now() / 1000) + 3600, ...claims };
  return `${b64url({ alg: "RS256" })}.${b64url(payload)}.c2ln`;
}

/** Replaces global fetch with a router of canned responses, and records every call. */
export function mockFetch(routes: Record<string, (init?: RequestInit) => { status?: number; body?: unknown }>) {
  const calls: { url: string; method: string; body?: unknown; authorization?: string }[] = [];
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const headers = (init?.headers ?? {}) as Record<string, string>;
    calls.push({
      url,
      method: init?.method ?? "GET",
      body: init?.body ? JSON.parse(String(init.body)) : undefined,
      authorization: headers.Authorization,
    });
    const key = Object.keys(routes).find((pattern) => url.startsWith(pattern) && (routes[pattern] !== undefined));
    const route = key === undefined ? undefined : routes[key];
    if (!route) return new Response(JSON.stringify({ title: "Not found", detail: "Not found." }), { status: 404 });
    const { status = 200, body } = route(init);
    return new Response(body === undefined ? null : JSON.stringify(body), {
      status,
      headers: { "Content-Type": "application/json" },
    });
  });
  vi.stubGlobal("fetch", fetchMock);
  return { calls, fetchMock };
}

export const queueItem = (overrides: Partial<ClusterSummary> = {}): ClusterSummary => ({
  cluster_id: "c7",
  size: 3,
  confidence: 0.8855,
  risk_action: "REVIEW",
  reason: "Linked to 2 other account(s); not reviewed yet.",
  ...overrides,
});

export const clusterDetail = (overrides: Partial<ClusterDetail> = {}): ClusterDetail => ({
  cluster_id: "c7",
  size: 2,
  account_ids: ["acc_1", "acc_2"],
  account_ids_truncated: false,
  confidence: 0.8855,
  risk_action: "REVIEW",
  reason: "Linked to 1 other account(s); not reviewed yet.",
  links: [
    { account_a: "acc_1", account_b: "acc_2", rule: "rare_payment", score: 0.8855, shared_signals: ["same_payment_method"] },
  ],
  links_truncated: false,
  latest_decision: null,
  mule_scores: [],
  ...overrides,
});

export const decisionResult = (analyst: string): DecisionResult => ({
  decision: {
    decision_id: "d1",
    cluster_id: "c7",
    outcome: "CONFIRMED_FRAUD",
    analyst,
    note: null,
    decided_at: "2026-11-14T03:00:00Z",
    account_ids: ["acc_1", "acc_2"],
  },
  risk_action: "RESTRICT",
  reason: `Linked to accounts confirmed as fraud by ${analyst}.`,
});

export const PROPOSAL_ID = "3f2b8c1e-7a4d-4e5f-9b6a-0c1d2e3f4a5b";

export const proposal = (overrides: Partial<MergeProposal> = {}): MergeProposal => ({
  proposal_id: PROPOSAL_ID,
  status: "OPEN",
  trigger_account_id: "acc_3",
  score: 0.9,
  rule: "device_and_email",
  proposed_at: "2026-11-14T02:00:00Z",
  cluster_a: "c4",
  cluster_b: "c5",
  current_cluster_a: "c4",
  current_cluster_b: "c5",
  account_ids_a: ["acc_1", "acc_3"],
  account_ids_b: ["acc_2"],
  decided_by: null,
  note: null,
  decided_at: null,
  ...overrides,
});

export const mergeAcceptResult = (analyst: string): MergeAcceptResult => ({
  proposal: proposal({
    status: "ACCEPTED",
    current_cluster_b: "c4",
    decided_by: analyst,
    decided_at: "2026-11-14T03:00:00Z",
  }),
  merged: true,
  cluster_id: "c4",
  cluster_size: 3,
  risk_action: "REVIEW",
  reason: "Linked to 2 other account(s); not reviewed yet.",
});

export const muleScore = (overrides: Partial<MuleScore> = {}): MuleScore => ({
  account_id: "acc_1",
  mule_score: 0.94,
  mule_flagged: true,
  mule_reasons: [
    { code: "fan_in", description: "6 unrelated payers in 25 min" },
    { code: "pass_through", description: "90% sent on within 30 min" },
    { code: "chain", description: "on a money chain of 2 accounts" },
  ],
  ...overrides,
});

export const muleSummary = (overrides: Partial<MuleSummary> = {}): MuleSummary => ({
  account_id: "acc_1",
  cluster_id: "c7",
  cluster_size: 1,
  mule_score: 0.94,
  mule_reasons: muleScore().mule_reasons,
  flagged_at: "2026-11-14T04:00:00Z",
  risk_action: "REVIEW",
  reason: "Account acc_1 looks like a money mule: 6 unrelated payers in 25 min.",
  ...overrides,
});

export const transferItem = (overrides: Partial<Transfer> = {}): Transfer => ({
  transfer_id: "t1",
  from_account: "ext_victim_1",
  to_account: "acc_1",
  amount_cents: 184_233,
  occurred_at: "2026-11-14T03:10:00Z",
  direction: "IN",
  ...overrides,
});

export const NEW_DEVICE_AT = "2026-11-14T10:45:00Z";

export const takeoverReasons = (): TakeoverReason[] => [
  { code: "new_device", description: "first login from a new device after 7 logins", at: NEW_DEVICE_AT },
  { code: "impossible_travel", description: "16,990 km in 45 min (22,653 km/h)", at: NEW_DEVICE_AT },
];

export const accountDetail = (overrides: Partial<AccountDetail> = {}): AccountDetail => ({
  account_id: "acc_1",
  email: "acc.1@example.com",
  device_id: "dev_own",
  ip: "10.0.0.1",
  payment_fingerprint: "card_1",
  created_at: "2026-11-01T09:00:00Z",
  cluster_id: "c7",
  cluster_size: 2,
  risk_action: "REVIEW",
  reason: "Account acc_1 may have been taken over: first login from a new device after 7 logins.",
  mule_score: 0,
  mule_flagged: false,
  mule_reasons: [],
  takeover_score: 0.82,
  takeover_flagged: true,
  takeover_reasons: takeoverReasons(),
  ...overrides,
});

export const takeoverSummary = (overrides: Partial<TakeoverSummary> = {}): TakeoverSummary => ({
  account_id: "acc_1",
  cluster_id: "c7",
  cluster_size: 2,
  takeover_score: 0.82,
  takeover_reasons: takeoverReasons(),
  flagged_at: "2026-11-14T10:46:00Z",
  reached_at: NEW_DEVICE_AT,
  risk_action: "REVIEW",
  reason: "Account acc_1 may have been taken over: first login from a new device after 7 logins.",
  ...overrides,
});

export const loginItem = (overrides: Partial<Login> = {}): Login => ({
  login_id: "l1",
  account_id: "acc_1",
  device_id: "dev_own",
  ip: "10.0.0.1",
  success: true,
  occurred_at: "2026-11-14T10:00:00Z",
  latitude: -33.87,
  longitude: 151.21,
  ...overrides,
});

export const takeoverDecisionResult = (analyst: string, outcome: TakeoverOutcome): TakeoverDecisionResult => ({
  decision: {
    decision_id: "td1",
    account_id: "acc_1",
    outcome,
    analyst,
    note: null,
    decided_at: "2026-11-14T11:00:00Z",
  },
  risk_action: outcome === "CONFIRMED_TAKEOVER" ? "STEP_UP" : "ALLOW",
  reason: "",
});
