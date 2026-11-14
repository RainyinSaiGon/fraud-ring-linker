import { useCallback, useEffect, useState } from "react";
import { api, ApiError, type AuditEvent, type ClusterDetail, type Decision } from "../api";
import { dateTime, percent, ruleLabel, signalLabel } from "../labels";
import { DecisionForm } from "./DecisionForm";
import { RiskBadge } from "./RiskBadge";

interface Loaded {
  cluster: ClusterDetail;
  decisions: Decision[];
  audit: AuditEvent[];
}

type State = { status: "loading" } | { status: "error"; message: string } | { status: "ready"; data: Loaded };

/** One cluster: who is in it, what links them, the recommended action, and its history. */
export function ClusterView({ clusterId }: { clusterId: string }) {
  const [state, setState] = useState<State>({ status: "loading" });

  const load = useCallback(async () => {
    try {
      const [cluster, decisions, audit] = await Promise.all([
        api.cluster(clusterId),
        api.decisions(clusterId),
        api.audit(clusterId),
      ]);
      setState({ status: "ready", data: { cluster, decisions, audit } });
    } catch (error) {
      const message =
        error instanceof ApiError && error.status === 404
          ? `Cluster ${clusterId} not found.`
          : error instanceof ApiError
            ? error.message
            : "Something went wrong.";
      setState({ status: "error", message });
    }
  }, [clusterId]);

  useEffect(() => {
    setState({ status: "loading" });
    void load();
  }, [load]);

  if (state.status === "loading") return <p role="status">Loading cluster {clusterId}…</p>;
  if (state.status === "error") {
    return (
      <section>
        <p role="alert" className="error">
          {state.message}
        </p>
        <a href="#/">Back to the review queue</a>
      </section>
    );
  }

  const { cluster, decisions, audit } = state.data;
  return (
    <section aria-labelledby="cluster-heading">
      <a href="#/" className="back">
        ← Review queue
      </a>
      <div className="section-head">
        <h1 id="cluster-heading">Cluster {cluster.cluster_id}</h1>
        <RiskBadge action={cluster.risk_action} />
      </div>
      <p className="reason" data-testid="reason">
        {cluster.reason}
      </p>
      <dl className="facts">
        <div>
          <dt>Accounts</dt>
          <dd>{cluster.size}</dd>
        </div>
        <div>
          <dt>Confidence</dt>
          <dd>{percent(cluster.confidence)}</dd>
        </div>
        <div>
          <dt>Last decision</dt>
          <dd>
            {cluster.latest_decision
              ? `${cluster.latest_decision.outcome === "CONFIRMED_FRAUD" ? "Fraud" : "Not fraud"} by ${cluster.latest_decision.analyst}`
              : "None yet"}
          </dd>
        </div>
      </dl>

      <div className="grid">
        <div className="card">
          <h2>Why these accounts are linked</h2>
          {cluster.links.length === 0 ? (
            <p className="muted">This account is not linked to any other.</p>
          ) : (
            <ul className="links">
              {cluster.links.map((link) => (
                <li key={`${link.account_a}-${link.account_b}`}>
                  <div className="link-accounts">
                    <code>{link.account_a}</code> ↔ <code>{link.account_b}</code>
                    <span className="score">{percent(link.score)}</span>
                  </div>
                  <div className="link-rule">{ruleLabel(link.rule)}</div>
                  <ul className="chips" aria-label="Shared signals">
                    {link.shared_signals.map((signal) => (
                      <li key={signal} className="chip">
                        {signalLabel(signal)}
                      </li>
                    ))}
                  </ul>
                </li>
              ))}
            </ul>
          )}
          {cluster.links_truncated && (
            <p className="muted">Large cluster: only links between the first members are shown.</p>
          )}
          <h3>Accounts</h3>
          <ul className="accounts">
            {cluster.account_ids.map((id) => (
              <li key={id}>
                <code>{id}</code>
              </li>
            ))}
          </ul>
          {cluster.account_ids_truncated && <p className="muted">List shortened; {cluster.size} accounts in total.</p>}
        </div>

        <div className="side">
          <DecisionForm
            clusterId={cluster.cluster_id}
            accountCount={cluster.size}
            onDecided={() => void load()}
          />
          <div className="card">
            <h2>History</h2>
            {decisions.length === 0 && audit.length === 0 ? (
              <p className="muted">No activity yet.</p>
            ) : (
              <ol className="history">
                {audit.map((event) => (
                  <li key={event.event_id}>
                    <span className="when">{dateTime(event.occurred_at)}</span>
                    <span>{describe(event)}</span>
                  </li>
                ))}
              </ol>
            )}
          </div>
        </div>
      </div>
    </section>
  );
}

function describe(event: AuditEvent): string {
  switch (event.event_type) {
    case "account.linked":
      return `${event.account_id} joined (${String(event.details.risk_action ?? "")})`;
    case "decision.recorded":
      return `${event.actor} marked it ${event.details.outcome === "CONFIRMED_FRAUD" ? "fraud" : "not fraud"}`;
    default:
      return `${event.event_type} by ${event.actor}`;
  }
}
