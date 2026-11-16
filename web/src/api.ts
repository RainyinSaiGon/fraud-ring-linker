// Typed client for the fraud-ring-linker REST API. Field names match the
// service's snake_case JSON exactly.

export type RiskAction = "ALLOW" | "REVIEW" | "STEP_UP" | "RESTRICT";
export type Outcome = "CONFIRMED_FRAUD" | "NOT_FRAUD";

export interface ClusterSummary {
  cluster_id: string;
  size: number;
  confidence: number;
  risk_action: RiskAction;
  reason: string;
}

export interface Link {
  account_a: string;
  account_b: string;
  rule: string;
  score: number;
  shared_signals: string[];
}

export interface Decision {
  decision_id: string;
  cluster_id: string;
  outcome: Outcome;
  analyst: string;
  note: string | null;
  decided_at: string;
  account_ids: string[];
}

/** One mule detector that fired, for example {code: "fan_in", description: "6 unrelated payers in 25 min"}. */
export interface MuleReason {
  code: string;
  description: string;
}

/** How mule-like an account's money movement looks. A flag asks for review; it never restricts. */
export interface MuleScore {
  account_id: string;
  mule_score: number;
  mule_flagged: boolean;
  mule_reasons: MuleReason[];
}

/** A likely mule no analyst has decided on since it was flagged. */
export interface MuleSummary {
  account_id: string;
  cluster_id: string;
  cluster_size: number;
  mule_score: number;
  mule_reasons: MuleReason[];
  flagged_at: string | null;
  risk_action: RiskAction;
  reason: string;
}

export interface Transfer {
  transfer_id: string;
  from_account: string;
  to_account: string;
  amount_cents: number;
  occurred_at: string;
  direction: "IN" | "OUT";
}

export interface ClusterDetail {
  cluster_id: string;
  size: number;
  account_ids: string[];
  account_ids_truncated: boolean;
  confidence: number;
  risk_action: RiskAction;
  reason: string;
  links: Link[];
  links_truncated: boolean;
  latest_decision: Decision | null;
  /** Members that have sent or received money; the others have no score. */
  mule_scores: MuleScore[];
}

export interface DecisionResult {
  decision: Decision;
  risk_action: RiskAction;
  reason: string;
}

export interface AuditEvent {
  event_id: number;
  occurred_at: string;
  event_type: string;
  account_id: string | null;
  cluster_id: string | null;
  actor: string;
  details: Record<string, unknown>;
}

export type ProposalStatus = "OPEN" | "ACCEPTED" | "REJECTED";

/**
 * A new account linked strongly to two clusters: it joined side A, and side B
 * is proposed for merging. Accounts are stored per side because cluster IDs can
 * change; current_cluster_a/b are where those accounts are now.
 */
export interface MergeProposal {
  proposal_id: string;
  status: ProposalStatus;
  trigger_account_id: string;
  score: number;
  rule: string;
  proposed_at: string;
  cluster_a: string;
  cluster_b: string;
  current_cluster_a: string | null;
  current_cluster_b: string | null;
  account_ids_a: string[];
  account_ids_b: string[];
  decided_by: string | null;
  note: string | null;
  decided_at: string | null;
}

export interface MergeAcceptResult {
  proposal: MergeProposal;
  merged: boolean;
  cluster_id: string;
  cluster_size: number;
  risk_action: RiskAction;
  reason: string;
}

/** An error the page can show as-is: the service's own message, or a plain explanation. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response;
  try {
    response = await fetch(path, {
      ...init,
      headers: { Accept: "application/json", ...(init?.body ? { "Content-Type": "application/json" } : {}) },
    });
  } catch {
    throw new ApiError(0, "Can't reach the linking service. Check that it is running.");
  }
  if (!response.ok) {
    throw new ApiError(response.status, await problemMessage(response));
  }
  return (await response.json()) as T;
}

async function problemMessage(response: Response): Promise<string> {
  try {
    const problem = (await response.json()) as { detail?: string; title?: string };
    if (problem.detail) return problem.detail;
    if (problem.title) return problem.title;
  } catch {
    // Not a problem response; fall through.
  }
  if (response.status === 503) return "Linking is temporarily unavailable. Retry later.";
  if (response.status === 404) return "Not found.";
  return `The service answered with status ${response.status}.`;
}

function decisionBody(analyst: string, note: string): string {
  return JSON.stringify({ analyst, note: note.trim() === "" ? null : note.trim() });
}

const proposalPath = (proposalId: string) => `/api/v1/merge-proposals/${encodeURIComponent(proposalId)}`;

export const api = {
  reviewQueue: (limit = 100) => request<ClusterSummary[]>(`/api/v1/clusters?limit=${limit}`),
  cluster: (clusterId: string) => request<ClusterDetail>(`/api/v1/clusters/${encodeURIComponent(clusterId)}`),
  decisions: (clusterId: string) =>
    request<Decision[]>(`/api/v1/clusters/${encodeURIComponent(clusterId)}/decisions`),
  audit: (clusterId: string, limit = 20) =>
    request<AuditEvent[]>(`/api/v1/audit-events?cluster_id=${encodeURIComponent(clusterId)}&limit=${limit}`),
  decide: (clusterId: string, outcome: Outcome, analyst: string, note: string) =>
    request<DecisionResult>(`/api/v1/clusters/${encodeURIComponent(clusterId)}/decisions`, {
      method: "POST",
      body: JSON.stringify({ outcome, analyst, note: note.trim() === "" ? null : note.trim() }),
    }),
  mules: (limit = 100) => request<MuleSummary[]>(`/api/v1/mules?limit=${limit}`),
  transfers: (accountId: string, limit = 20) =>
    request<Transfer[]>(`/api/v1/accounts/${encodeURIComponent(accountId)}/transfers?limit=${limit}`),
  proposals: (status: ProposalStatus = "OPEN", limit = 100) =>
    request<MergeProposal[]>(`/api/v1/merge-proposals?status=${status}&limit=${limit}`),
  proposal: (proposalId: string) => request<MergeProposal>(proposalPath(proposalId)),
  acceptProposal: (proposalId: string, analyst: string, note: string) =>
    request<MergeAcceptResult>(`${proposalPath(proposalId)}/accept`, { method: "POST", body: decisionBody(analyst, note) }),
  rejectProposal: (proposalId: string, analyst: string, note: string) =>
    request<MergeProposal>(`${proposalPath(proposalId)}/reject`, { method: "POST", body: decisionBody(analyst, note) }),
};
