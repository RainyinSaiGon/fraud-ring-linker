// Writes a scenario to files so it can be scored offline (no running service):
//   accounts.jsonl     every sign-up, in the order `run` sends them
//   constraints.jsonl  verified-distinct pairs (none yet: the simulator does not model identity checks)
//   transfers.jsonl    money movement after the sign-ups, in time order (see transfers.mjs)
//   logins.jsonl       logins over the weeks after the sign-ups, in time order (see logins.mjs)
//   truth.json         who really owns each account, who is a mule, and who was taken over (schema below)
//
// truth.json, schema_version 3 (version 1 had no mule fields, version 2 no takeover fields):
//   actors            one entry per real-world actor:
//                       actor_id, kind (customer | household_member | office_worker | fraud_ring),
//                       group (household_N | office, when the actor shares a network with others),
//                       account_ids, and for rings the `shared` card and phone.
//   account_to_actor  derived from actors; kept so the Go evaluator (go/internal/evaluation) can read the file
//   fraud_actor_ids   derived from actors; same reason
//   mule_chains       chain_id, account_ids in hop order, victims (outside payers), cash_out
//   mule_account_ids  every account in any mule chain, sorted
//   transfer_cases    hard legitimate accounts that must not be flagged:
//                       case (small_business | own_money | payday_bills), account_id
//   takeovers         planted account takeovers, by account_id:
//                       kind (credential_stuffing | phishing | password_guessing), attacker_device,
//                       first_bad_login_id and first_bad_at (the attacker's first successful login),
//                       attacker_login_ids (every attacker login on the account, failures included)
//   takeover_account_ids  every account in takeovers, sorted
//   login_cases       hard legitimate accounts a takeover detector must not flag:
//                       case (new_phone | holiday | forgotten_password | family_tablet), account_id
//
// Output is deterministic: the same scenario always gives byte-identical files.

import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { buildLogins } from "./logins.mjs";
import { buildTransfers } from "./transfers.mjs";

export const TRUTH_SCHEMA_VERSION = 3;

/**
 * Builds the versioned ground truth for a scenario from buildScenario().
 *
 * @param money  the result of buildTransfers(scenario); built with the defaults if not given
 * @param access the result of buildLogins(scenario); built with the defaults if not given
 */
export function buildTruth(scenario, money = buildTransfers(scenario), access = buildLogins(scenario)) {
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
    mule_chains: money.muleChains,
    mule_account_ids: money.muleChains.flatMap((chain) => chain.account_ids).sort(compare),
    transfer_cases: money.cases,
    takeovers: access.takeovers,
    takeover_account_ids: access.takeovers.map((takeover) => takeover.account_id),
    login_cases: access.cases,
  };
}

/**
 * Writes accounts.jsonl, constraints.jsonl, transfers.jsonl, logins.jsonl and truth.json into `outDir`.
 *
 * @param transferOptions overrides for TRANSFER_DEFAULTS, for example {muleChains: 4}
 * @param loginOptions    overrides for LOGIN_DEFAULTS, for example {phishing: 5}
 * @returns the five paths
 */
export function writeScenario(scenario, outDir, transferOptions = {}, loginOptions = {}) {
  const money = buildTransfers(scenario, transferOptions);
  const access = buildLogins(scenario, loginOptions);
  const truth = buildTruth(scenario, money, access);
  const covered = Object.keys(truth.account_to_actor).length;
  if (covered !== scenario.accounts.length) {
    throw new Error(`truth covers ${covered} accounts but the scenario has ${scenario.accounts.length}`);
  }
  mkdirSync(outDir, { recursive: true });
  const paths = {
    accounts: join(outDir, "accounts.jsonl"),
    constraints: join(outDir, "constraints.jsonl"),
    transfers: join(outDir, "transfers.jsonl"),
    logins: join(outDir, "logins.jsonl"),
    truth: join(outDir, "truth.json"),
  };
  writeFileSync(paths.accounts, scenario.accounts.map((account) => JSON.stringify(account)).join("\n") + "\n");
  writeFileSync(paths.constraints, "");
  writeFileSync(paths.transfers, jsonLines(money.transfers));
  writeFileSync(paths.logins, jsonLines(access.logins));
  writeFileSync(paths.truth, JSON.stringify(truth, null, 2) + "\n");
  return paths;
}

function jsonLines(records) {
  return records.length === 0 ? "" : records.map((record) => JSON.stringify(record)).join("\n") + "\n";
}

function personId(accountId) {
  return `person_${accountId}`;
}

/** Plain code-unit comparison, so the order never depends on the machine's locale. */
function compare(a, b) {
  return a < b ? -1 : a > b ? 1 : 0;
}
