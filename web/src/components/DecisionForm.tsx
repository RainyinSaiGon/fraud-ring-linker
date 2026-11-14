import { useState, type FormEvent } from "react";
import { api, ApiError, type DecisionResult, type Outcome } from "../api";

interface Props {
  clusterId: string;
  accountCount: number;
  onDecided: (result: DecisionResult) => void;
}

/**
 * Records an analyst's decision. Both outcomes need a name, because the audit
 * log must say who decided. (Once login exists, the name will come from it.)
 */
export function DecisionForm({ clusterId, accountCount, onDecided }: Props) {
  const [analyst, setAnalyst] = useState("");
  const [note, setNote] = useState("");
  const [pending, setPending] = useState<Outcome | null>(null);
  const [error, setError] = useState<string | null>(null);
  const ready = analyst.trim() !== "" && pending === null;

  async function decide(outcome: Outcome) {
    setPending(outcome);
    setError(null);
    try {
      onDecided(await api.decide(clusterId, outcome, analyst.trim(), note));
      setNote("");
    } catch (failure) {
      setError(failure instanceof ApiError ? failure.message : "The decision was not saved.");
    } finally {
      setPending(null);
    }
  }

  function submit(event: FormEvent) {
    event.preventDefault();
  }

  return (
    <form className="card" onSubmit={submit} aria-labelledby="decision-heading">
      <h2 id="decision-heading">Decision</h2>
      <p className="muted">Applies to all {accountCount} accounts in this cluster.</p>
      <label htmlFor="analyst">Your name</label>
      <input
        id="analyst"
        autoComplete="name"
        value={analyst}
        maxLength={128}
        required
        onChange={(event) => setAnalyst(event.target.value)}
      />
      <label htmlFor="note">Note (optional)</label>
      <textarea id="note" rows={3} maxLength={2000} value={note} onChange={(event) => setNote(event.target.value)} />
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      <div className="actions">
        <button type="button" className="button danger" disabled={!ready} onClick={() => void decide("CONFIRMED_FRAUD")}>
          {pending === "CONFIRMED_FRAUD" ? "Saving…" : "Confirm fraud"}
        </button>
        <button type="button" className="button secondary" disabled={!ready} onClick={() => void decide("NOT_FRAUD")}>
          {pending === "NOT_FRAUD" ? "Saving…" : "Not fraud"}
        </button>
      </div>
    </form>
  );
}
