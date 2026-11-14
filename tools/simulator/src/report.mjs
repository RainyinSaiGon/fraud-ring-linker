// Compares where the service put each account with the truth the simulator planted.
//
// Cluster IDs come from each account's registration response. A streaming
// account joins at most one existing cluster and clusters never merge later,
// so the cluster an account was assigned on arrival is still its cluster.

/**
 * @param results  output of registerAll
 * @param truth    scenario.truth
 * @returns summary with ring recovery and false links
 */
export function summarize(results, truth) {
  const clusterOf = new Map();
  const failures = [];
  for (const result of results) {
    if (result.error) {
      failures.push({ accountId: result.account.account_id, message: result.error.message });
    } else {
      clusterOf.set(result.response.account_id, result.response.cluster_id);
    }
  }

  const ringMembers = new Set(truth.rings.flatMap((ring) => ring.accountIds));
  const membersByCluster = new Map();
  for (const [accountId, clusterId] of clusterOf) {
    if (!membersByCluster.has(clusterId)) membersByCluster.set(clusterId, []);
    membersByCluster.get(clusterId).push(accountId);
  }

  const rings = truth.rings.map((ring) => {
    const clusters = new Set(ring.accountIds.map((id) => clusterOf.get(id)).filter(Boolean));
    return {
      ringId: ring.ringId,
      size: ring.accountIds.length,
      clusters: clusters.size,
      fullyLinked: clusters.size === 1 && ring.accountIds.every((id) => clusterOf.has(id)),
    };
  });

  // A legitimate account is wrongly linked if its cluster holds anyone else.
  const wronglyLinked = [];
  // Clusters holding members of more than one planted ring.
  const mergedRings = [];
  for (const [clusterId, members] of membersByCluster) {
    if (members.length < 2) continue;
    const ringIds = new Set(members.filter((id) => ringMembers.has(id)).map((id) => ringOf(truth, id)));
    if (ringIds.size > 1) mergedRings.push({ clusterId, ringIds: [...ringIds].sort() });
    for (const id of members) {
      if (!ringMembers.has(id)) wronglyLinked.push({ accountId: id, clusterId });
    }
  }

  return {
    mergedRings,
    accountsSent: results.length,
    failures,
    ringsPlanted: rings.length,
    ringsFullyLinked: rings.filter((ring) => ring.fullyLinked).length,
    ringsSplit: rings.filter((ring) => ring.clusters > 1).length,
    rings,
    legitimateAccountsWronglyLinked: wronglyLinked.length,
    wronglyLinked,
  };
}

function ringOf(truth, accountId) {
  return truth.rings.find((ring) => ring.accountIds.includes(accountId))?.ringId;
}

/** Plain-text report for the terminal. */
export function formatSummary(summary) {
  const lines = [
    `Accounts sent:           ${summary.accountsSent}`,
    `Failed requests:         ${summary.failures.length}`,
    `Rings planted:           ${summary.ringsPlanted}`,
    `Rings fully linked:      ${summary.ringsFullyLinked}`,
    `Rings split apart:       ${summary.ringsSplit}`,
    `Legit accounts linked:   ${summary.legitimateAccountsWronglyLinked}`,
    `Clusters mixing rings:   ${summary.mergedRings.length}`,
    "",
    "ring       size  clusters  linked",
    ...summary.rings.map((ring) =>
      `${ring.ringId.padEnd(10)} ${String(ring.size).padStart(4)}  ${String(ring.clusters).padStart(8)}  ${ring.fullyLinked ? "yes" : "NO"}`),
  ];
  for (const failure of summary.failures.slice(0, 5)) {
    lines.push(`failed: ${failure.accountId}: ${failure.message}`);
  }
  return lines.join("\n");
}
