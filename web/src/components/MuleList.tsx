import { useEffect, useState } from "react";
import { api, ApiError, type MuleSummary } from "../api";
import { dateTime, percent } from "../labels";

type State =
  | { status: "loading" }
  | { status: "error"; message: string }
  | { status: "ready"; mules: MuleSummary[] };

/**
 * Accounts whose money movement looks like a mule's and that no analyst has
 * decided on since. Many are single accounts, so they would never appear in
 * the cluster queue, which lists clusters of two or more.
 *
 * Loaded on its own, so a failure here never hides the cluster queue.
 */
export function MuleList({ reloadToken }: { reloadToken: number }) {
  const [state, setState] = useState<State>({ status: "loading" });

  useEffect(() => {
    let current = true;
    setState({ status: "loading" });
    api.mules().then(
      (mules) => current && setState({ status: "ready", mules }),
      (error: unknown) =>
        current &&
        setState({ status: "error", message: error instanceof ApiError ? error.message : "Something went wrong." }),
    );
    return () => {
      current = false;
    };
  }, [reloadToken]);

  return (
    <section aria-labelledby="mules-heading" className="proposals">
      <h2 id="mules-heading">Possible mules</h2>
      <p className="muted">
        Accounts that receive from many unrelated payers or pass money straight on. A flag asks for review; it
        never restricts anyone by itself.
      </p>
      {state.status === "loading" && <p role="status">Loading possible mules…</p>}
      {state.status === "error" && (
        <p role="alert" className="error">
          Possible mules could not be loaded: {state.message}
        </p>
      )}
      {state.status === "ready" && state.mules.length === 0 && <p className="empty">No possible mules waiting.</p>}
      {state.status === "ready" && state.mules.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption className="visually-hidden">Possible mules waiting for review</caption>
            <thead>
              <tr>
                <th scope="col">Account</th>
                <th scope="col" className="num">
                  Mule score
                </th>
                <th scope="col">Signals</th>
                <th scope="col">Flagged</th>
                <th scope="col">Cluster</th>
              </tr>
            </thead>
            <tbody>
              {state.mules.map((mule) => (
                <tr key={mule.account_id}>
                  <td className="nowrap">
                    <code>{mule.account_id}</code>
                  </td>
                  <td className="num">{percent(mule.mule_score)}</td>
                  <td>{mule.mule_reasons.map((reason) => reason.description).join(", ")}</td>
                  <td>{mule.flagged_at ? dateTime(mule.flagged_at) : "Just now"}</td>
                  <td>
                    <a
                      href={`#/clusters/${mule.cluster_id}`}
                      aria-label={`Review ${mule.account_id} in cluster ${mule.cluster_id}`}
                    >
                      {mule.cluster_id}
                    </a>
                    {mule.cluster_size > 1 && <span className="muted small"> ({mule.cluster_size} accounts)</span>}
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
