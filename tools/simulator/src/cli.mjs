#!/usr/bin/env node
// Usage:
//   node src/cli.mjs run   [--base-url URL] [--seed N] [--customers N] [--households N]
//                          [--office-workers N] [--rings N] [--truth FILE]
//   node src/cli.mjs extra --truth FILE [--ring INDEX] [--base-url URL]
//   node src/cli.mjs export [--seed N] [--customers N] [--households N]
//                           [--office-workers N] [--rings N] [--out-dir DIR]
//
// `run` sends a scenario to the service and prints how well rings were recovered.
// `extra` sends one more sign-up reusing a planted ring's card and phone.
// `export` writes a scenario to files (accounts, constraints, ground truth) with no service.

import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";
import { parseArgs } from "node:util";
import { createClient, registerAll } from "./client.mjs";
import { formatSummary, summarize } from "./report.mjs";
import { writeScenario } from "./export.mjs";
import { buildScenario, DEFAULTS, extraRingMember } from "./scenario.mjs";

const [command, ...rest] = process.argv.slice(2);
const { values } = parseArgs({
  args: rest,
  options: {
    "base-url": { type: "string", default: "http://localhost:8080" },
    seed: { type: "string", default: String(DEFAULTS.seed) },
    customers: { type: "string", default: String(DEFAULTS.customers) },
    households: { type: "string", default: String(DEFAULTS.households) },
    "office-workers": { type: "string", default: String(DEFAULTS.officeWorkers) },
    rings: { type: "string", default: String(DEFAULTS.rings) },
    truth: { type: "string", default: "out/truth.json" },
    ring: { type: "string", default: "0" },
    "run-id": { type: "string" },
    "out-dir": { type: "string" },
  },
});

const client = createClient(values["base-url"]);

try {
  if (command === "run") {
    await run();
  } else if (command === "extra") {
    await extra();
  } else if (command === "export") {
    exportScenario();
  } else {
    console.error("usage: cli.mjs run|extra|export [options]  (see the header of src/cli.mjs)");
    process.exit(2);
  }
} catch (error) {
  console.error(`simulator: ${error.message}`);
  process.exit(1);
}

async function run() {
  await client.health().catch(() => {
    throw new Error(`no service at ${values["base-url"]}; start it first (see java/README.md)`);
  });
  const seed = Number(values.seed);
  // A time-based run ID keeps repeated runs against one service from colliding.
  const runId = values["run-id"] ?? `s${seed}t${Date.now().toString(36)}`;
  const { accounts, truth } = buildScenario({
    seed,
    runId,
    customers: Number(values.customers),
    households: Number(values.households),
    officeWorkers: Number(values["office-workers"]),
    rings: Number(values.rings),
  });
  console.log(`Sending ${accounts.length} sign-ups to ${values["base-url"]} (run ${runId})...`);
  const started = Date.now();
  const results = await registerAll(client, accounts, {
    concurrency: 8,
    onProgress: (done, total) => {
      if (done % 50 === 0 || done === total) process.stdout.write(`  ${done}/${total}\r`);
    },
  });
  const seconds = (Date.now() - started) / 1000;
  console.log(`\nDone in ${seconds.toFixed(1)} s (${Math.round(accounts.length / seconds)} sign-ups/s)\n`);
  console.log(formatSummary(summarize(results, truth)));

  mkdirSync(dirname(values.truth), { recursive: true });
  writeFileSync(values.truth, JSON.stringify(truth, null, 2));
  console.log(`\nGround truth written to ${values.truth}`);
}

async function extra() {
  const truth = JSON.parse(readFileSync(values.truth, "utf8"));
  const ring = truth.rings[Number(values.ring)];
  if (!ring) {
    throw new Error(`no ring ${values.ring} in ${values.truth}`);
  }
  const account = extraRingMember(ring, `${truth.runId}_extra_${Date.now().toString(36)}`,
    new Date().toISOString().replace(/\.\d+Z$/, "Z"));
  const response = await client.registerAccount(account);
  console.log(`${response.account_id} -> ${response.cluster_id}: ${response.risk_action} (${response.reason})`);
}

function exportScenario() {
  const seed = Number(values.seed);
  // A fixed run ID (no timestamp) keeps exports byte-identical for the same seed.
  const runId = values["run-id"] ?? `s${seed}`;
  const outDir = values["out-dir"] ?? `out/seed-${seed}`;
  const scenario = buildScenario({
    seed,
    runId,
    customers: Number(values.customers),
    households: Number(values.households),
    officeWorkers: Number(values["office-workers"]),
    rings: Number(values.rings),
  });
  const paths = writeScenario(scenario, outDir);
  console.log(`Wrote ${scenario.accounts.length} accounts and ${scenario.truth.rings.length} rings:`);
  for (const path of Object.values(paths)) console.log(`  ${path}`);
}
