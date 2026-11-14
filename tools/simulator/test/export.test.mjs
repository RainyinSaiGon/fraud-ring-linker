import assert from "node:assert/strict";
import { mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, it } from "node:test";
import { buildTruth, TRUTH_SCHEMA_VERSION, writeScenario } from "../src/export.mjs";
import { buildScenario } from "../src/scenario.mjs";

const options = { seed: 5, runId: "s5", customers: 40, households: 5, officeWorkers: 7, rings: 4 };
const tempDir = () => mkdtempSync(join(tmpdir(), "sim-export-"));

describe("buildTruth", () => {
  const scenario = buildScenario(options);
  const truth = buildTruth(scenario);

  it("is versioned and records the seed and run", () => {
    assert.equal(truth.schema_version, TRUTH_SCHEMA_VERSION);
    assert.equal(truth.seed, 5);
    assert.equal(truth.run_id, "s5");
  });

  it("gives every account exactly one actor", () => {
    const ids = scenario.accounts.map((account) => account.account_id).sort();
    assert.deepEqual(Object.keys(truth.account_to_actor).sort(), ids);
    const fromActors = truth.actors.flatMap((actor) => actor.account_ids).sort();
    assert.deepEqual(fromActors, ids);
  });

  it("lists every ring, and only rings, as fraud actors", () => {
    const rings = truth.actors.filter((actor) => actor.kind === "fraud_ring");
    assert.equal(rings.length, 4);
    assert.deepEqual([...truth.fraud_actor_ids].sort(), rings.map((ring) => ring.actor_id).sort());
    for (const ring of rings) {
      assert.ok(ring.account_ids.length >= 3);
      assert.ok(ring.shared.card.startsWith("card_") && ring.shared.device.startsWith("dev_"));
      for (const accountId of ring.account_ids) assert.equal(truth.account_to_actor[accountId], ring.actor_id);
    }
  });

  it("makes each household member and office worker their own actor, grouped by network", () => {
    const members = truth.actors.filter((actor) => actor.kind === "household_member");
    const workers = truth.actors.filter((actor) => actor.kind === "office_worker");
    assert.equal(members.length, scenario.truth.households.reduce((n, h) => n + h.accountIds.length, 0));
    assert.equal(workers.length, 7);
    for (const actor of [...members, ...workers]) assert.equal(actor.account_ids.length, 1);
    assert.equal(new Set(members.map((actor) => actor.group)).size, 5);
    assert.ok(workers.every((actor) => actor.group === "office"));
  });

  it("counts customers as single-account actors with no group", () => {
    const customers = truth.actors.filter((actor) => actor.kind === "customer");
    assert.equal(customers.length, 40);
    assert.ok(customers.every((actor) => actor.account_ids.length === 1 && actor.group === undefined));
  });

  it("only uses the four known kinds", () => {
    const kinds = new Set(truth.actors.map((actor) => actor.kind));
    assert.deepEqual([...kinds].sort(), ["customer", "fraud_ring", "household_member", "office_worker"]);
  });
});

describe("writeScenario", () => {
  it("writes byte-identical files for the same seed", () => {
    const first = writeScenario(buildScenario(options), tempDir());
    const second = writeScenario(buildScenario(options), tempDir());
    for (const name of ["accounts", "constraints", "truth"]) {
      assert.equal(readFileSync(first[name], "utf8"), readFileSync(second[name], "utf8"), name);
    }
  });

  it("keeps the arrival order the run command uses", () => {
    const scenario = buildScenario(options);
    const paths = writeScenario(scenario, tempDir());
    const lines = readFileSync(paths.accounts, "utf8").trimEnd().split("\n").map((line) => JSON.parse(line));
    assert.deepEqual(lines.map((account) => account.account_id), scenario.accounts.map((account) => account.account_id));
    assert.deepEqual(lines[0], scenario.accounts[0]);
  });

  it("writes an empty constraints file and valid JSON truth", () => {
    const paths = writeScenario(buildScenario(options), tempDir());
    assert.equal(readFileSync(paths.constraints, "utf8"), "");
    const truth = JSON.parse(readFileSync(paths.truth, "utf8"));
    assert.equal(truth.schema_version, 1);
  });
});
