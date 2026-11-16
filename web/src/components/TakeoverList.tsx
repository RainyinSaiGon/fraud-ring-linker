import { useEffect, useState } from "react";
import { api, ApiError, type TakeoverSummary } from "../api";
import { dateTime, percent } from "../labels";

type State =
  | { status: "loading" }
  | { status: "error"; message: string }
  | { status: "ready"; takeovers: TakeoverSummary[] };

/**
 * Accounts whose logins look like someone else got in, and that no analyst has
 * decided on since. A takeover is about one customer, so each row links to the
 * account, not its cluster.
 *
 * Loaded on its own, so a failure here never hides the cluster queue.
 */
export function TakeoverList({ reloadToken }: { reloadToken: number }) {
  const [state, setState] = useState<State>({ status: "loading" });

  useEffect(() => {
    let current = true;
    setState({ status: "loading" });
    api.takeovers().then(
      (takeovers) => current && setState({ status: "ready", takeovers }),
      (error: unknown) =>
        current &&
        setState({ status: "error", message: error instanceof ApiError ? error.message : "Something went wrong." }),
    );
    return () => {
      current = false;
    };
  }, [reloadToken]);

  return (
    <section aria-labelledby="takeovers-heading" className="proposals">
      <h2 id="takeovers-heading">Possible takeovers</h2>
      <p className="muted">
        Accounts whose logins look like someone other than the customer got in: a new device, a jump no one could
        travel, a burst of failed logins. A flag asks for review; it never blocks anyone by itself.
      </p>
      {state.status === "loading" && <p role="status">Loading possible takeovers…</p>}
      {state.status === "error" && (
        <p role="alert" className="error">
          Possible takeovers could not be loaded: {state.message}
        </p>
      )}
      {state.status === "ready" && state.takeovers.length === 0 && (
        <p className="empty">No possible takeovers waiting.</p>
      )}
      {state.status === "ready" && state.takeovers.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption className="visually-hidden">Possible takeovers waiting for review</caption>
            <thead>
              <tr>
                <th scope="col">Account</th>
                <th scope="col" className="num">
                  Takeover score
                </th>
                <th scope="col">Signals</th>
                <th scope="col">Flagged</th>
              </tr>
            </thead>
            <tbody>
              {state.takeovers.map((takeover) => (
                <tr key={takeover.account_id}>
                  <td className="nowrap">
                    <a href={`#/accounts/${encodeURIComponent(takeover.account_id)}`}
                       aria-label={`Review account ${takeover.account_id}`}>
                      <code>{takeover.account_id}</code>
                    </a>
                  </td>
                  <td className="num">{percent(takeover.takeover_score)}</td>
                  <td>{takeover.takeover_reasons.map((reason) => reason.description).join(", ")}</td>
                  <td>{takeover.flagged_at ? dateTime(takeover.flagged_at) : "Just now"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
