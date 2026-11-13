#!/usr/bin/env node
// Generates a messy synthetic dataset for parity testing (Go vs Java).
//
// Usage: node generate.mjs <seed> <accountCount> <outDir>
// Writes <outDir>/accounts.jsonl (80% of accounts, plus a blank line),
//        <outDir>/events.jsonl   (the other 20%, for stream mode),
//        <outDir>/constraints.jsonl.
//
// The data is deliberately awkward: rings that share devices, payments and
// /24 prefixes; email variations (dots, +tags, typos, case, odd spaces);
// shared office IPs; IPv6 and IPv4-mapped addresses; malformed IPs; and
// mixed timestamp offsets. It is fake data only.

import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const [seedArg, countArg, outDir] = process.argv.slice(2);
if (!seedArg || !countArg || !outDir) {
  console.error("usage: node generate.mjs <seed> <accountCount> <outDir>");
  process.exit(2);
}

// mulberry32: tiny deterministic PRNG so a seed always gives the same dataset.
function prng(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const random = prng(Number(seedArg));
const int = (min, max) => min + Math.floor(random() * (max - min + 1));
const pick = (items) => items[Math.floor(random() * items.length)];

const names = ["john.doe", "maria", "ng.van.an", "li_wei", "sara-k", "tom", "anna.b", "kevin99", "jj", "x"];
const domains = ["gmail.com", "googlemail.com", "outlook.com", "yahoo.com", "proton.me", "corp.com.au"];

function mutate(local) {
  switch (pick(["dot", "plus", "digit", "swap", "del", "upper", "none", "space"])) {
    case "dot": return local.replaceAll(".", "") + ".";
    case "plus": return `${local}+promo${int(1, 9)}`;
    case "digit": return local + int(0, 9);
    case "swap": {
      if (local.length <= 2) return local;
      const i = int(0, local.length - 2);
      return local.slice(0, i) + local[i + 1] + local[i] + local.slice(i + 2);
    }
    case "del": {
      if (local.length <= 1) return local;
      const i = int(0, local.length - 1);
      return local.slice(0, i) + local.slice(i + 1);
    }
    case "upper": return local.toUpperCase();
    case "space": return ` ${local} `;
    default: return local;
  }
}

function randomIp() {
  const k = random();
  if (k < 0.15) return `203.0.113.${int(1, 3)}`; // busy shared office
  if (k < 0.25) return `2001:db8:${int(0, 2).toString(16)}:${int(0, 3).toString(16)}::${int(1, 5).toString(16)}`;
  if (k < 0.3) return `::ffff:198.51.100.${int(1, 9)}`;
  if (k < 0.33) return pick(["bad-ip", "01.2.3.4", "", "300.1.1.1", "fe80::1%eth0", "::"]);
  return `10.${int(0, 3)}.${int(0, 5)}.${int(1, 250)}`;
}

function timestamp(date) {
  const iso = date.toISOString().slice(0, 19);
  return iso + pick(["Z", "+07:00", ".5Z"]);
}

const total = Number(countArg);
const base = Date.UTC(2026, 2, 1);
const accounts = [];
let id = 0;
while (accounts.length < total) {
  const ring = random() < 0.35;
  const size = ring ? int(2, 6) : 1;
  const local = pick(names) + (random() < 0.5 ? String(int(1, 99)) : "");
  const domain = pick(domains);
  const device = `dev_${int(1, 1_000_000)}`;
  const payment = `pf_${int(1, 1_000_000)}`;
  const start = base + int(0, 60 * 24 * 30) * 60_000;
  const prefix = `172.16.${int(0, 40)}.`;
  for (let k = 0; k < size; k++) {
    accounts.push({
      account_id: `acc_${String(id++).padStart(5, "0")}`,
      email: `${mutate(local)}@${domain}`,
      device_id: ring && random() < 0.6 ? device : random() < 0.3 ? `dev_${int(1, 50)}` : null,
      ip: ring && random() < 0.5 ? prefix + int(1, 250) : randomIp(),
      payment_fingerprint: ring && random() < 0.5 ? payment : random() < 0.05 ? "pf_shared" : null,
      created_at: timestamp(new Date(start + (ring ? int(0, 90) * 60_000 : 0))),
    });
  }
}

// Fisher-Yates shuffle so rings are not contiguous in the file.
for (let i = accounts.length - 1; i > 0; i--) {
  const j = Math.floor(random() * (i + 1));
  [accounts[i], accounts[j]] = [accounts[j], accounts[i]];
}

const cut = Math.floor(accounts.length * 0.8);
const lines = (records) => records.map((r) => JSON.stringify(r)).join("\n") + "\n";
mkdirSync(outDir, { recursive: true });
writeFileSync(join(outDir, "accounts.jsonl"), lines(accounts.slice(0, cut)) + "\n");
writeFileSync(join(outDir, "events.jsonl"), lines(accounts.slice(cut)));

const ids = accounts.map((a) => a.account_id);
const constraints = [];
for (let i = 0; i < Math.max(1, Math.floor(total / 15)); i++) {
  const a = pick(ids);
  let b = pick(ids);
  while (b === a) b = pick(ids);
  constraints.push({ account_a: a, account_b: b, relation: "verified_distinct" });
}
writeFileSync(join(outDir, "constraints.jsonl"), lines(constraints));
