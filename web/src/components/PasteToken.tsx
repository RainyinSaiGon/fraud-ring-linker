import { useState, type FormEvent } from "react";
import { signIn } from "../auth";

/**
 * Paste-a-token sign-in, for development builds only: SignIn renders it behind
 * `import.meta.env.DEV`, so production builds leave it out (scripts/check-build.mjs checks).
 */
export function PasteToken() {
  const [token, setToken] = useState("");
  const [error, setError] = useState<string | null>(null);

  function submit(event: FormEvent) {
    event.preventDefault();
    const result = signIn(token);
    if ("error" in result) setError(result.error);
  }

  return (
    <form onSubmit={submit} className="paste-token">
      <h2>Development: paste a token</h2>
      <p className="muted small">
        Paste a token from <code>tools/dev-auth</code>. It's kept for this browser tab only.
      </p>
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
      <button type="submit" className="button secondary" disabled={token.trim() === ""}>
        Sign in
      </button>
    </form>
  );
}
