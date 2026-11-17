import { useState } from "react";
import { oidcConfigured, startSignIn } from "../oidc";
import { PasteToken } from "./PasteToken";

/** Sign-in: through the identity provider, plus a paste-a-token form in development builds. */
export function SignIn({ notice }: { notice?: string }) {
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  async function go() {
    setPending(true);
    setError(null);
    try {
      await startSignIn();
    } catch {
      setError("Can't reach the identity provider. Try again.");
      setPending(false);
    }
  }

  return (
    <section className="card sign-in" aria-labelledby="sign-in-title">
      <h1 id="sign-in-title">Sign in</h1>
      {notice && <p role="status">{notice}</p>}
      {oidcConfigured ? (
        <>
          <p className="muted">You'll sign in with your organisation's account, then come back here.</p>
          {error && (
            <p role="alert" className="error">
              {error}
            </p>
          )}
          <button type="button" className="button primary" disabled={pending} onClick={() => void go()}>
            {pending ? "Opening sign-in…" : "Sign in through your identity provider"}
          </button>
        </>
      ) : (
        !import.meta.env.DEV && (
          <p role="alert" className="error">
            Sign-in isn't set up for this build. Build it with VITE_OIDC_ISSUER and VITE_OIDC_CLIENT_ID.
          </p>
        )
      )}
      {import.meta.env.DEV && <PasteToken />}
    </section>
  );
}
