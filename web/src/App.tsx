import { useEffect, useState } from "react";
import { AccountView } from "./components/AccountView";
import { ClusterView } from "./components/ClusterView";
import { ProposalView } from "./components/ProposalView";
import { ReviewQueue } from "./components/ReviewQueue";

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

export function App() {
  const [hash, setHash] = useState(window.location.hash);

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
      </header>
      <main>
        {route.page === "cluster" && <ClusterView key={route.clusterId} clusterId={route.clusterId} />}
        {route.page === "account" && <AccountView key={route.accountId} accountId={route.accountId} />}
        {route.page === "proposal" && <ProposalView key={route.proposalId} proposalId={route.proposalId} />}
        {route.page === "queue" && <ReviewQueue />}
      </main>
    </>
  );
}
