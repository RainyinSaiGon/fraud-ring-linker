import { useState, type FormEvent } from "react";
import { signIn } from "../auth";

/** Paste-a-token sign-in. */
export function SignIn({ notice }: { notice?: string }) {
  const [token, setToken] = useState("");
  const [error, setError] = useState<string | null>(null);

  function submit(event: FormEvent) {
    event.preventDefault();
    const result = signIn(token);
    if ("error" in result) setError(result.error);
  }

  return (
    <section className="card sign-in" aria-labelledby="sign-in-title">
      <h1 id="sign-in-title">Sign in</h1>
      {notice && <p role="status">{notice}</p>}
      <p className="muted">
        Paste a token from your identity provider. For a local service, <code>tools/dev-auth</code> makes one. The
        token is kept for this browser tab only.
      </p>
      <form onSubmit={submit}>
        <label htmlFor="sign-in-token">Token</label>
        <textarea
          id="sign-in-token"
          rows={4}
          value={token}
          autoComplete="off"
          spellCheck={false}
          onChange={(e) => {
            setToken(e.target.value);
            setError(null);
          }}
        />
        {error && (
          <p role="alert" className="error">
            {error}
          </p>
        )}
        <button type="submit" className="button primary" disabled={token.trim() === ""}>
          Sign in
        </button>
      </form>
    </section>
  );
}
