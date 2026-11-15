import { useState } from "react";
import { api, ApiError, type MuleScore, type Transfer } from "../api";
import { dateTime, money, percent } from "../labels";

/**
 * Mule signals for the cluster's members: each member that moved money, its
 * score, and the reasons in plain words. A flag asks for review; it never
 * restricts anyone by itself.
 */
export function MoneyMovement({ scores }: { scores: MuleScore[] }) {
  if (scores.length === 0) return null;
  const ordered = [...scores].sort((a, b) => b.mule_score - a.mule_score || compare(a.account_id, b.account_id));
  return (
    <div className="card" aria-labelledby="money-heading">
      <h2 id="money-heading">Money movement</h2>
      <ul className="mules">
        {ordered.map((score) => (
          <MemberScore key={score.account_id} score={score} />
        ))}
      </ul>
    </div>
  );
}

function MemberScore({ score }: { score: MuleScore }) {
  return (
    <li aria-label={`Money movement of ${score.account_id}`}>
      <div className="link-accounts">
        <code>{score.account_id}</code>
        {score.mule_flagged ? (
          <span className="badge badge-review">Likely mule</span>
        ) : (
          <span className="badge badge-neutral">Not flagged</span>
        )}
        <span className="score">Mule score {percent(score.mule_score)}</span>
      </div>
      {score.mule_reasons.length > 0 ? (
        <ul className="chips" aria-label="Mule signals">
          {score.mule_reasons.map((reason) => (
            <li key={reason.code} className="chip">
              {reason.description}
            </li>
          ))}
        </ul>
      ) : (
        <p className="muted small">No mule signals.</p>
      )}
      <Transfers accountId={score.account_id} />
    </li>
  );
}

type TransferState =
  | { status: "hidden" }
  | { status: "loading" }
  | { status: "error"; message: string }
  | { status: "ready"; transfers: Transfer[] };

/** Recent transfers, loaded only when asked for. */
function Transfers({ accountId }: { accountId: string }) {
  const [state, setState] = useState<TransferState>({ status: "hidden" });

  async function show() {
    setState({ status: "loading" });
    try {
      setState({ status: "ready", transfers: await api.transfers(accountId) });
    } catch (error) {
      setState({ status: "error", message: error instanceof ApiError ? error.message : "Something went wrong." });
    }
  }

  if (state.status === "hidden") {
    return (
      <button type="button" className="link-button" onClick={() => void show()}>
        Show recent transfers
      </button>
    );
  }
  if (state.status === "loading") return <p role="status">Loading transfers…</p>;
  if (state.status === "error") {
    return (
      <p role="alert" className="error">
        Transfers could not be loaded: {state.message}
      </p>
    );
  }
  return (
    <div className="table-wrap transfers">
      <table>
        <caption className="visually-hidden">Recent transfers of {accountId}</caption>
        <thead>
          <tr>
            <th scope="col">When</th>
            <th scope="col">Direction</th>
            <th scope="col">Other account</th>
            <th scope="col" className="num">
              Amount
            </th>
          </tr>
        </thead>
        <tbody>
          {state.transfers.map((t) => (
            <tr key={t.transfer_id}>
              <td>{dateTime(t.occurred_at)}</td>
              <td>{t.direction === "IN" ? "In" : "Out"}</td>
              <td>
                <code>{t.direction === "IN" ? t.from_account : t.to_account}</code>
              </td>
              <td className="num">{money(t.amount_cents)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function compare(a: string, b: string) {
  return a < b ? -1 : a > b ? 1 : 0;
}
