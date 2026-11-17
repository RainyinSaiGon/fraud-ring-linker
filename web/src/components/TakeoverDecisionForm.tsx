import { useState, type FormEvent } from "react";
import { api, ApiError, type TakeoverDecisionResult, type TakeoverOutcome } from "../api";
import { DecidingAs } from "./DecidingAs";

interface Props {
  accountId: string;
  clusterSize: number;
  onDecided: (result: TakeoverDecisionResult) => void;
}

/**
 * Records whether someone else got into one account, as the signed-in analyst.
 * Unlike a fraud decision, it never covers the rest of the cluster.
 */
export function TakeoverDecisionForm({ accountId, clusterSize, onDecided }: Props) {
  const [note, setNote] = useState("");
  const [pending, setPending] = useState<TakeoverOutcome | null>(null);
  const [error, setError] = useState<string | null>(null);
  const ready = pending === null;

  async function decide(outcome: TakeoverOutcome) {
    setPending(outcome);
    setError(null);
    try {
      onDecided(await api.decideTakeover(accountId, outcome, note));
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

  const others = clusterSize - 1;
  return (
    <form className="card" onSubmit={submit} aria-labelledby="takeover-decision-heading">
      <h2 id="takeover-decision-heading">Takeover decision</h2>
      <p className="muted">
        Confirming steps up this account only: the customer must re-verify before their next login or payment.
        {others > 0 && ` The ${others} other account(s) in its cluster are not affected.`} Choose “Not a takeover”
        once the customer has re-verified.
      </p>
      <DecidingAs />
      <label htmlFor="takeover-note">Note (optional)</label>
      <textarea
        id="takeover-note"
        rows={3}
        maxLength={2000}
        value={note}
        onChange={(event) => setNote(event.target.value)}
      />
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      <div className="actions">
        <button
          type="button"
          className="button danger"
          disabled={!ready}
          onClick={() => void decide("CONFIRMED_TAKEOVER")}
        >
          {pending === "CONFIRMED_TAKEOVER" ? "Saving…" : "Confirm takeover"}
        </button>
        <button
          type="button"
          className="button secondary"
          disabled={!ready}
          onClick={() => void decide("NOT_TAKEOVER")}
        >
          {pending === "NOT_TAKEOVER" ? "Saving…" : "Not a takeover"}
        </button>
      </div>
    </form>
  );
}
