import { useState, type FormEvent } from "react";
import { api, ApiError, type RevealResult } from "../api";
import { currentSession } from "../auth";

interface Props {
  accountId: string;
  /** Masked by the service. */
  email: string;
  ip: string;
  onRevealed: () => void;
}

/**
 * The account's email and sign-up IP, masked. An analyst can show the full
 * details; the service records every reveal in the history. The full values
 * stay on this page only until it's left or reloaded.
 */
export function ContactDetails({ accountId, email, ip, onRevealed }: Props) {
  const canReveal = currentSession()?.roles.includes("analyst") ?? false;
  const [reason, setReason] = useState("");
  const [revealed, setRevealed] = useState<RevealResult | null>(null);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function reveal(event: FormEvent) {
    event.preventDefault();
    setPending(true);
    setError(null);
    try {
      setRevealed(await api.reveal(accountId, reason));
      onRevealed();
    } catch (failure) {
      setError(failure instanceof ApiError ? failure.message : "The details could not be shown.");
    } finally {
      setPending(false);
    }
  }

  return (
    <div className="card" aria-labelledby="contact-heading">
      <h2 id="contact-heading">Contact details</h2>
      <dl className="facts compact">
        <div>
          <dt>Email</dt>
          <dd data-testid="contact-email">{revealed ? revealed.email : email || "None"}</dd>
        </div>
        <div>
          <dt>Sign-up IP</dt>
          <dd data-testid="contact-ip">{revealed ? revealed.ip || "None" : ip || "None"}</dd>
        </div>
      </dl>
      {revealed ? (
        <p className="muted small" role="status">
          Shown to you and recorded in the history.
        </p>
      ) : canReveal ? (
        <form onSubmit={reveal}>
          <p className="muted small">Masked. Showing the full details is recorded in the history.</p>
          <label htmlFor="reveal-reason">Reason (optional)</label>
          <input
            id="reveal-reason"
            value={reason}
            maxLength={500}
            onChange={(event) => setReason(event.target.value)}
          />
          {error && (
            <p role="alert" className="error">
              {error}
            </p>
          )}
          <button type="submit" className="button secondary" disabled={pending}>
            {pending ? "Showing…" : "Show details"}
          </button>
        </form>
      ) : (
        <p className="muted small">Masked. Only analysts can show the full details.</p>
      )}
    </div>
  );
}
