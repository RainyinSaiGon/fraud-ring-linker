import { useEffect, useState } from "react";
import { api, ApiError, type MergeProposal } from "../api";
import { dateTime, percent, ruleLabel } from "../labels";

type State =
  | { status: "loading" }
  | { status: "error"; message: string }
  | { status: "ready"; proposals: MergeProposal[] };

/**
 * Open merge proposals: pairs of clusters that one new account linked strongly
 * to. Streaming never merges clusters by itself, so a ring whose members arrived
 * in an unlucky order waits here for an analyst.
 *
 * Loaded on its own, so a failure here never hides the cluster queue.
 */
export function ProposalList({ reloadToken }: { reloadToken: number }) {
  const [state, setState] = useState<State>({ status: "loading" });

  useEffect(() => {
    let current = true;
    setState({ status: "loading" });
    api.proposals("OPEN").then(
      (proposals) => current && setState({ status: "ready", proposals }),
      (error: unknown) =>
        current &&
        setState({ status: "error", message: error instanceof ApiError ? error.message : "Something went wrong." }),
    );
    return () => {
      current = false;
    };
  }, [reloadToken]);

  return (
    <section aria-labelledby="proposals-heading" className="proposals">
      <h2 id="proposals-heading">Possible split rings</h2>
      <p className="muted">
        A new account linked strongly to two clusters. They stay apart until an analyst merges them.
      </p>
      {state.status === "loading" && <p role="status">Loading merge proposals…</p>}
      {state.status === "error" && (
        <p role="alert" className="error">
          Merge proposals could not be loaded: {state.message}
        </p>
      )}
      {state.status === "ready" && state.proposals.length === 0 && (
        <p className="empty">No merge proposals waiting.</p>
      )}
      {state.status === "ready" && state.proposals.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption className="visually-hidden">Merge proposals waiting for a decision</caption>
            <thead>
              <tr>
                <th scope="col">Clusters</th>
                <th scope="col" className="num">
                  Accounts
                </th>
                <th scope="col">Evidence</th>
                <th scope="col">Opened</th>
                <th scope="col">
                  <span className="visually-hidden">Open</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {state.proposals.map((proposal) => (
                <tr key={proposal.proposal_id}>
                  <td>
                    {clusterName(proposal.current_cluster_a)} ↔ {clusterName(proposal.current_cluster_b)}
                  </td>
                  <td className="num">{proposal.account_ids_a.length + proposal.account_ids_b.length}</td>
                  <td>
                    <code>{proposal.trigger_account_id}</code>: {ruleLabel(proposal.rule)} ({percent(proposal.score)})
                  </td>
                  <td>{dateTime(proposal.proposed_at)}</td>
                  <td>
                    <a
                      href={`#/proposals/${proposal.proposal_id}`}
                      aria-label={`Review merge of ${clusterName(proposal.current_cluster_a)} and ${clusterName(proposal.current_cluster_b)}`}
                    >
                      Review
                    </a>
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

export function clusterName(clusterId: string | null): string {
  return clusterId ?? "unknown cluster";
}
