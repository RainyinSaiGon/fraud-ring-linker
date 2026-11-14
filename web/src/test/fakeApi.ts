import { vi } from "vitest";
import type { ClusterDetail, ClusterSummary, DecisionResult } from "../api";

/** Replaces global fetch with a router of canned responses, and records every call. */
export function mockFetch(routes: Record<string, (init?: RequestInit) => { status?: number; body?: unknown }>) {
  const calls: { url: string; method: string; body?: unknown }[] = [];
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    calls.push({ url, method: init?.method ?? "GET", body: init?.body ? JSON.parse(String(init.body)) : undefined });
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
