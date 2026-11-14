import { useEffect, useState } from "react";
import { ClusterView } from "./components/ClusterView";
import { ReviewQueue } from "./components/ReviewQueue";

/** Routes are hash-based (#/clusters/c12) so links and refreshes work with no server routing. */
export function parseRoute(hash: string): { page: "queue" } | { page: "cluster"; clusterId: string } {
  const match = /^#\/clusters\/([A-Za-z0-9]+)$/.exec(hash);
  return match?.[1] ? { page: "cluster", clusterId: match[1] } : { page: "queue" };
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
      <main>{route.page === "cluster" ? <ClusterView key={route.clusterId} clusterId={route.clusterId} /> : <ReviewQueue />}</main>
    </>
  );
}
