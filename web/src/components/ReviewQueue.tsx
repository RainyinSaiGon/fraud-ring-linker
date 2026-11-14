import { useCallback, useEffect, useState } from "react";
import { api, ApiError, type ClusterSummary } from "../api";
import { percent } from "../labels";
import { RiskBadge } from "./RiskBadge";

type State =
  | { status: "loading" }
  | { status: "error"; message: string }
  | { status: "ready"; clusters: ClusterSummary[] };

/** Clusters of linked accounts that still need an analyst's decision, largest first. */
export function ReviewQueue() {
  const [state, setState] = useState<State>({ status: "loading" });

  const load = useCallback(async () => {
    setState({ status: "loading" });
    try {
      setState({ status: "ready", clusters: await api.reviewQueue() });
    } catch (error) {
      setState({ status: "error", message: error instanceof ApiError ? error.message : "Something went wrong." });
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <section aria-labelledby="queue-heading">
      <div className="section-head">
        <h1 id="queue-heading">Review queue</h1>
        <button type="button" className="button secondary" onClick={() => void load()}>
          Refresh
        </button>
      </div>
      <p className="muted">
        Accounts the engine linked together. Nothing is restricted until an analyst confirms fraud.
      </p>

      {state.status === "loading" && <p role="status">Loading clusters…</p>}
      {state.status === "error" && (
        <p role="alert" className="error">
          {state.message}
        </p>
      )}
      {state.status === "ready" && state.clusters.length === 0 && (
        <p className="empty">Nothing to review. New linked clusters appear here.</p>
      )}
      {state.status === "ready" && state.clusters.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption className="visually-hidden">Clusters waiting for review</caption>
            <thead>
              <tr>
                <th scope="col">Cluster</th>
                <th scope="col" className="num">
                  Accounts
                </th>
                <th scope="col" className="num">
                  Confidence
                </th>
                <th scope="col">Why it is here</th>
                <th scope="col">Action</th>
              </tr>
            </thead>
            <tbody>
              {state.clusters.map((cluster) => (
                <tr key={cluster.cluster_id}>
                  <td>
                    <a href={`#/clusters/${cluster.cluster_id}`}>{cluster.cluster_id}</a>
                  </td>
                  <td className="num">{cluster.size}</td>
                  <td className="num">{percent(cluster.confidence)}</td>
                  <td>{cluster.reason}</td>
                  <td>
                    <RiskBadge action={cluster.risk_action} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
