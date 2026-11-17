import { useEffect, useState } from "react";
import { currentSession, onSessionChange, signOut, signOutReason, type Session } from "./auth";
import { AccountView } from "./components/AccountView";
import { ClusterView } from "./components/ClusterView";
import { ProposalView } from "./components/ProposalView";
import { ReviewQueue } from "./components/ReviewQueue";
import { SignIn } from "./components/SignIn";

export type Route =
  | { page: "queue" }
  | { page: "cluster"; clusterId: string }
  | { page: "account"; accountId: string }
  | { page: "proposal"; proposalId: string };

/** Routes are hash-based (#/clusters/c12) so links and refreshes work with no server routing. */
export function parseRoute(hash: string): Route {
  const cluster = /^#\/clusters\/([A-Za-z0-9]+)$/.exec(hash);
  if (cluster?.[1]) return { page: "cluster", clusterId: cluster[1] };
  // The service's account ID characters; ":" may arrive percent-encoded.
  const account = /^#\/accounts\/((?:[A-Za-z0-9._:-]|%3[Aa]){1,128})$/.exec(hash);
  if (account?.[1]) return { page: "account", accountId: decodeURIComponent(account[1]) };
  const proposal = /^#\/proposals\/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$/.exec(hash);
  if (proposal?.[1]) return { page: "proposal", proposalId: proposal[1] };
  return { page: "queue" };
}

const ROLE_NAMES: Record<string, string> = { analyst: "Analyst", auditor: "Auditor", ingest: "Ingest" };

export function App() {
  const [hash, setHash] = useState(window.location.hash);
  const [session, setSession] = useState<Session | null>(() => currentSession());

  useEffect(() => onSessionChange(() => setSession(currentSession())), []);

  // Sign out when the token expires, rather than on the next refused call.
  useEffect(() => {
    if (!session) return;
    const timer = setTimeout(
      () => signOut("Your session has ended. Sign in again."),
      Math.max(0, Math.min(session.expiresAt - Date.now(), 2 ** 31 - 1)),
    );
    return () => clearTimeout(timer);
  }, [session]);

  useEffect(() => {
    const onChange = () => setHash(window.location.hash);
    window.addEventListener("hashchange", onChange);
    return () => window.removeEventListener("hashchange", onChange);
  }, []);

  const route = parseRoute(hash);
  return (
    <>
      <header className="topbar">
        <a href="#/" className="brand">
          Fraud ring review
        </a>
        <span className="muted small">Synthetic data only</span>
        {session && (
          <span className="session small">
            Signed in as <strong>{session.name}</strong>
            {session.roles.length > 0 && (
              <span className="muted"> ({session.roles.map((r) => ROLE_NAMES[r] ?? r).join(", ")})</span>
            )}
            <button type="button" className="link-button" onClick={() => signOut()}>
              Sign out
            </button>
          </span>
        )}
      </header>
      <main>
        {!session && <SignIn notice={signOutReason() ?? undefined} />}
        {session && route.page === "cluster" && <ClusterView key={route.clusterId} clusterId={route.clusterId} />}
        {session && route.page === "account" && <AccountView key={route.accountId} accountId={route.accountId} />}
        {session && route.page === "proposal" && <ProposalView key={route.proposalId} proposalId={route.proposalId} />}
        {session && route.page === "queue" && <ReviewQueue />}
      </main>
    </>
  );
}
