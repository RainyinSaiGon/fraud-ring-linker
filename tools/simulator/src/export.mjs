// Writes a scenario to files so it can be scored offline (no running service):
//   accounts.jsonl     every sign-up, in the order `run` sends them
//   constraints.jsonl  verified-distinct pairs (none yet: the simulator does not model identity checks)
//   truth.json         who really owns each account (schema below)
//
// truth.json, schema_version 1:
//   actors            one entry per real-world actor:
//                       actor_id, kind (customer | household_member | office_worker | fraud_ring),
//                       group (household_N | office, when the actor shares a network with others),
//                       account_ids, and for rings the `shared` card and phone.
//   account_to_actor  derived from actors; kept so the Go evaluator (go/internal/evaluation) can read the file
//   fraud_actor_ids   derived from actors; same reason
//
// Output is deterministic: the same scenario always gives byte-identical files.

import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";

export const TRUTH_SCHEMA_VERSION = 1;

/** Builds the versioned ground truth for a scenario from buildScenario(). */
export function buildTruth(scenario) {
  const { truth } = scenario;
  const actors = [];

  for (const ring of truth.rings) {
    actors.push({
      actor_id: ring.ringId,
      kind: "fraud_ring",
      account_ids: [...ring.accountIds].sort(),
      shared: { card: ring.card, device: ring.device },
    });
  }
  for (const accountId of truth.customers) {
    actors.push({ actor_id: personId(accountId), kind: "customer", account_ids: [accountId] });
  }
  truth.households.forEach((household, index) => {
    for (const accountId of household.accountIds) {
      actors.push({
        actor_id: personId(accountId),
        kind: "household_member",
        group: `household_${index}`,
        account_ids: [accountId],
      });
    }
  });
  if (truth.office) {
    for (const accountId of truth.office.accountIds) {
      actors.push({ actor_id: personId(accountId), kind: "office_worker", group: "office", account_ids: [accountId] });
    }
  }

  actors.sort((a, b) => compare(a.actor_id, b.actor_id));
  const accountToActor = {};
  const pairs = actors.flatMap((actor) => actor.account_ids.map((accountId) => [accountId, actor.actor_id]));
  pairs.sort((a, b) => compare(a[0], b[0]));
  for (const [accountId, actorId] of pairs) {
    if (accountId in accountToActor) {
      throw new Error(`account ${accountId} belongs to two actors`);
    }
    accountToActor[accountId] = actorId;
  }

  return {
    schema_version: TRUTH_SCHEMA_VERSION,
    seed: truth.seed,
    run_id: truth.runId,
    actors,
    account_to_actor: accountToActor,
    fraud_actor_ids: actors.filter((actor) => actor.kind === "fraud_ring").map((actor) => actor.actor_id),
  };
}

/** Writes accounts.jsonl, constraints.jsonl and truth.json into `outDir`. Returns their paths. */
export function writeScenario(scenario, outDir) {
  const truth = buildTruth(scenario);
  const covered = Object.keys(truth.account_to_actor).length;
  if (covered !== scenario.accounts.length) {
    throw new Error(`truth covers ${covered} accounts but the scenario has ${scenario.accounts.length}`);
  }
  mkdirSync(outDir, { recursive: true });
  const paths = {
    accounts: join(outDir, "accounts.jsonl"),
    constraints: join(outDir, "constraints.jsonl"),
    truth: join(outDir, "truth.json"),
  };
  writeFileSync(paths.accounts, scenario.accounts.map((account) => JSON.stringify(account)).join("\n") + "\n");
  writeFileSync(paths.constraints, "");
  writeFileSync(paths.truth, JSON.stringify(truth, null, 2) + "\n");
  return paths;
}

function personId(accountId) {
  return `person_${accountId}`;
}

/** Plain code-unit comparison, so the order never depends on the machine's locale. */
function compare(a, b) {
  return a < b ? -1 : a > b ? 1 : 0;
}
