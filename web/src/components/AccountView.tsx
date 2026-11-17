import { useCallback, useEffect, useState } from "react";
import { api, ApiError, type AccountDetail, type AuditEvent, type Login, type TakeoverReason } from "../api";
import { dateTime, describeEvent, percent, place, takeoverMark } from "../labels";
import { ContactDetails } from "./ContactDetails";
import { RiskBadge } from "./RiskBadge";
import { TakeoverDecisionForm } from "./TakeoverDecisionForm";

interface Loaded {
  account: AccountDetail;
  logins: Login[];
  audit: AuditEvent[];
}

type State = { status: "loading" } | { status: "error"; message: string } | { status: "ready"; data: Loaded };

/**
 * One account: its own action, its login signals and recent logins, and the
 * takeover decision. A takeover is about one customer, so this page acts on
 * this account only.
 */
export function AccountView({ accountId }: { accountId: string }) {
  const [state, setState] = useState<State>({ status: "loading" });

  const load = useCallback(async () => {
    try {
      const [account, logins, audit] = await Promise.all([
        api.account(accountId),
        api.logins(accountId),
        api.accountAudit(accountId),
      ]);
      setState({ status: "ready", data: { account, logins, audit } });
    } catch (error) {
      const message =
        error instanceof ApiError && error.status === 404
          ? `Account ${accountId} not found.`
          : error instanceof ApiError
            ? error.message
            : "Something went wrong.";
      setState({ status: "error", message });
    }
  }, [accountId]);

  useEffect(() => {
    setState({ status: "loading" });
    void load();
  }, [load]);

  if (state.status === "loading") return <p role="status">Loading account {accountId}…</p>;
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

  const { account, logins, audit } = state.data;
  return (
    <section aria-labelledby="account-heading">
      <a href="#/" className="back">
        ← Review queue
      </a>
      <div className="section-head">
        <h1 id="account-heading">Account {account.account_id}</h1>
        <RiskBadge action={account.risk_action} />
      </div>
      <p className="reason" data-testid="reason">
        {account.reason}
      </p>
      <dl className="facts">
        <div>
          <dt>Cluster</dt>
          <dd>
            <a href={`#/clusters/${account.cluster_id}`}>{account.cluster_id}</a>
            <span className="muted small"> ({account.cluster_size} account{account.cluster_size === 1 ? "" : "s"})</span>
          </dd>
        </div>
        <div>
          <dt>Signed up</dt>
          <dd>{dateTime(account.created_at)}</dd>
        </div>
        <div>
          <dt>Takeover score</dt>
          <dd>{percent(account.takeover_score)}</dd>
        </div>
      </dl>

      <div className="grid">
        <div className="stack">
          <div className="card" aria-labelledby="signals-heading">
            <div className="link-accounts">
              <h2 id="signals-heading">Login signals</h2>
              {account.takeover_flagged ? (
                <span className="badge badge-review">Possible takeover</span>
              ) : (
                <span className="badge badge-neutral">Not flagged</span>
              )}
            </div>
            {account.takeover_reasons.length > 0 ? (
              <ul className="chips" aria-label="Takeover signals">
                {account.takeover_reasons.map((reason) => (
                  <li key={reason.code} className="chip">
                    {reason.description}
                  </li>
                ))}
              </ul>
            ) : (
              <p className="muted small">No takeover signals.</p>
            )}
            <h3>Recent logins</h3>
            <Logins accountId={account.account_id} logins={logins} reasons={account.takeover_reasons} />
          </div>
        </div>

        <div className="side">
          <ContactDetails
            accountId={account.account_id}
            email={account.email}
            ip={account.ip}
            onRevealed={() => void api.accountAudit(accountId).then((audit) =>
              setState((s) => (s.status === "ready" ? { status: "ready", data: { ...s.data, audit } } : s)),
            )}
          />
          <TakeoverDecisionForm
            accountId={account.account_id}
            clusterSize={account.cluster_size}
            onDecided={() => void load()}
          />
          <div className="card">
            <h2>History</h2>
            {audit.length === 0 ? (
              <p className="muted">No activity yet.</p>
            ) : (
              <ol className="history">
                {audit.map((event) => (
                  <li key={event.event_id}>
                    <span className="when">{dateTime(event.occurred_at)}</span>
                    <span>{describeEvent(event)}</span>
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

/**
 * Logins newest first. Each signal is shown in words next to the login that
 * showed it, so nothing depends on colour.
 */
function Logins({ accountId, logins, reasons }: { accountId: string; logins: Login[]; reasons: TakeoverReason[] }) {
  if (logins.length === 0) return <p className="muted">No logins yet.</p>;
  const marksAt = new Map<number, TakeoverReason[]>();
  for (const reason of reasons) {
    const at = Date.parse(reason.at);
    marksAt.set(at, [...(marksAt.get(at) ?? []), reason]);
  }
  return (
    <div className="table-wrap transfers">
      <table>
        <caption className="visually-hidden">Recent logins of {accountId}</caption>
        <thead>
          <tr>
            <th scope="col">When</th>
            <th scope="col">Device</th>
            <th scope="col">Place</th>
            <th scope="col">Result</th>
            <th scope="col">Signals</th>
          </tr>
        </thead>
        <tbody>
          {logins.map((login) => {
            const marks = marksAt.get(Date.parse(login.occurred_at)) ?? [];
            return (
              <tr key={login.login_id} className={marks.length > 0 ? "flagged-row" : undefined}>
                <td>{dateTime(login.occurred_at)}</td>
                <td>{login.device_id ? <code>{login.device_id}</code> : <span className="muted">Unknown</span>}</td>
                <td>{place(login.latitude, login.longitude)}</td>
                <td>{login.success ? "Success" : "Failed"}</td>
                <td>
                  {marks.map((reason) => (
                    <span key={reason.code} className="mark">
                      {takeoverMark(reason.code)}
                      {reason.code === "impossible_travel" && `: ${reason.description}`}
                    </span>
                  ))}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
