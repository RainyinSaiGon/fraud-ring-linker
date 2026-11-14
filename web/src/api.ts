// Typed client for the fraud-ring-linker REST API. Field names match the
// service's snake_case JSON exactly.

export type RiskAction = "ALLOW" | "REVIEW" | "RESTRICT";
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
};
