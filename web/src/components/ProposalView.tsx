import { useCallback, useEffect, useState, type FormEvent } from "react";
import { api, ApiError, type MergeAcceptResult, type MergeProposal } from "../api";
import { dateTime, percent, ruleLabel } from "../labels";
import { clusterName } from "./ProposalList";
import { RiskBadge } from "./RiskBadge";

type State =
  | { status: "loading" }
  | { status: "error"; message: string }
  | { status: "ready"; proposal: MergeProposal };

type Choice = "accept" | "reject";

/** One merge proposal: both sides, the evidence joining them, and the analyst's decision. */
export function ProposalView({ proposalId }: { proposalId: string }) {
  const [state, setState] = useState<State>({ status: "loading" });
  const [merge, setMerge] = useState<MergeAcceptResult | null>(null);

  const load = useCallback(async () => {
    try {
      setState({ status: "ready", proposal: await api.proposal(proposalId) });
    } catch (error) {
      const message =
        error instanceof ApiError && error.status === 404
          ? "Merge proposal not found."
          : error instanceof ApiError
            ? error.message
            : "Something went wrong.";
      setState({ status: "error", message });
    }
  }, [proposalId]);

  useEffect(() => {
    setState({ status: "loading" });
    void load();
  }, [load]);

  if (state.status === "loading") return <p role="status">Loading merge proposal…</p>;
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

  const { proposal } = state;
  const a = clusterName(proposal.current_cluster_a);
  const b = clusterName(proposal.current_cluster_b);
  const alreadyOne = proposal.current_cluster_a !== null && proposal.current_cluster_a === proposal.current_cluster_b;

  return (
    <section aria-labelledby="proposal-heading">
      <a href="#/" className="back">
        ← Review queue
      </a>
      <div className="section-head">
        <h1 id="proposal-heading">
          Merge {a} and {b}?
        </h1>
        <span className={`badge badge-${proposal.status === "OPEN" ? "review" : "neutral"}`}>{statusText(proposal)}</span>
      </div>
      <p className="reason" data-testid="evidence">
        Account <code>{proposal.trigger_account_id}</code> joined {clusterName(proposal.cluster_a)} and also links
        to {clusterName(proposal.cluster_b)}: <strong>{ruleLabel(proposal.rule)}</strong> ({percent(proposal.score)}).
      </p>
      {alreadyOne && proposal.status === "OPEN" && (
        <p className="muted">Both sides are already in one cluster. Accepting only closes the proposal.</p>
      )}

      <div className="grid">
        <div className="pair">
          <Side title={`Side A: cluster ${a}`} clusterId={proposal.current_cluster_a} accounts={proposal.account_ids_a} trigger={proposal.trigger_account_id} />
          <Side title={`Side B: cluster ${b}`} clusterId={proposal.current_cluster_b} accounts={proposal.account_ids_b} trigger={proposal.trigger_account_id} />
        </div>
        <div className="side">
          {merge && (
            <div className="card" role="status" data-testid="merge-result">
              <h2>{merge.merged ? "Clusters merged" : "Already one cluster"}</h2>
              <p>
                Cluster <a href={`#/clusters/${merge.cluster_id}`}>{merge.cluster_id}</a> now has {merge.cluster_size}{" "}
                accounts. <RiskBadge action={merge.risk_action} />
              </p>
              <p className="muted">{merge.reason}</p>
            </div>
          )}
          {proposal.status === "OPEN" ? (
            <ProposalDecisionForm
              proposalId={proposal.proposal_id}
              onAccepted={(result) => {
                setMerge(result);
                setState({ status: "ready", proposal: result.proposal });
              }}
              onRejected={(rejected) => setState({ status: "ready", proposal: rejected })}
            />
          ) : (
            <div className="card">
              <h2>Decision</h2>
              <p>
                {proposal.status === "ACCEPTED" ? "Merged" : "Kept apart"} by {proposal.decided_by}
                {proposal.decided_at ? ` on ${dateTime(proposal.decided_at)}` : ""}.
              </p>
              {proposal.note && <p className="muted">“{proposal.note}”</p>}
              {proposal.status === "REJECTED" && <p className="muted">This pair will not be proposed again.</p>}
            </div>
          )}
        </div>
      </div>
    </section>
  );
}

function statusText(proposal: MergeProposal): string {
  switch (proposal.status) {
    case "OPEN":
      return "Waiting for a decision";
    case "ACCEPTED":
      return "Merged";
    case "REJECTED":
      return "Kept apart";
  }
}

function Side({ title, clusterId, accounts, trigger }: { title: string; clusterId: string | null; accounts: string[]; trigger: string }) {
  return (
    <div className="card">
      <h2>{title}</h2>
      <ul className="accounts" aria-label={`${title} accounts`}>
        {accounts.map((id) => (
          <li key={id}>
            <code>{id}</code>
            {id === trigger && <span className="muted small"> (new account)</span>}
          </li>
        ))}
      </ul>
      {clusterId && (
        <p className="small">
          <a href={`#/clusters/${clusterId}`}>See why cluster {clusterId} is linked</a>
        </p>
      )}
    </div>
  );
}

interface FormProps {
  proposalId: string;
  onAccepted: (result: MergeAcceptResult) => void;
  onRejected: (proposal: MergeProposal) => void;
}

/** Like the cluster decision form: both choices need a name, because the audit log must say who decided. */
function ProposalDecisionForm({ proposalId, onAccepted, onRejected }: FormProps) {
  const [analyst, setAnalyst] = useState("");
  const [note, setNote] = useState("");
  const [pending, setPending] = useState<Choice | null>(null);
  const [error, setError] = useState<string | null>(null);
  const ready = analyst.trim() !== "" && pending === null;

  async function decide(choice: Choice) {
    setPending(choice);
    setError(null);
    try {
      if (choice === "accept") {
        onAccepted(await api.acceptProposal(proposalId, analyst.trim(), note));
      } else {
        onRejected(await api.rejectProposal(proposalId, analyst.trim(), note));
      }
    } catch (failure) {
      setError(failure instanceof ApiError ? failure.message : "The decision was not saved.");
      setPending(null);
    }
  }

  function submit(event: FormEvent) {
    event.preventDefault();
  }

  return (
    <form className="card" onSubmit={submit} aria-labelledby="proposal-decision-heading">
      <h2 id="proposal-decision-heading">Decision</h2>
      <p className="muted">Merging puts every account on both sides in one cluster. It cannot be undone here.</p>
      <label htmlFor="proposal-analyst">Your name</label>
      <input
        id="proposal-analyst"
        autoComplete="name"
        value={analyst}
        maxLength={128}
        required
        onChange={(event) => setAnalyst(event.target.value)}
      />
      <label htmlFor="proposal-note">Note (optional)</label>
      <textarea id="proposal-note" rows={3} maxLength={2000} value={note} onChange={(event) => setNote(event.target.value)} />
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      <div className="actions">
        <button type="button" className="button primary" disabled={!ready} onClick={() => void decide("accept")}>
          {pending === "accept" ? "Merging…" : "Merge clusters"}
        </button>
        <button type="button" className="button secondary" disabled={!ready} onClick={() => void decide("reject")}>
          {pending === "reject" ? "Saving…" : "Keep apart"}
        </button>
      </div>
    </form>
  );
}
