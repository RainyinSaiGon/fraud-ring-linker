import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { buildScenario, extraRingMember } from "../src/scenario.mjs";

const small = { seed: 7, customers: 30, households: 4, officeWorkers: 6, rings: 3 };

describe("buildScenario", () => {
  it("gives the same traffic for the same seed", () => {
    assert.deepEqual(buildScenario(small), buildScenario(small));
  });

  it("gives different traffic for a different seed", () => {
    assert.notDeepEqual(buildScenario(small).accounts, buildScenario({ ...small, seed: 8 }).accounts);
  });

  it("creates every population with unique account IDs and emails", () => {
    const { accounts, truth } = buildScenario(small);
    const ids = accounts.map((a) => a.account_id);
    const ringCount = truth.rings.reduce((n, ring) => n + ring.accountIds.length, 0);
    const householdCount = truth.households.reduce((n, h) => n + h.accountIds.length, 0);

    assert.equal(new Set(ids).size, ids.length);
    assert.equal(new Set(accounts.map((a) => a.email)).size, accounts.length);
    assert.equal(accounts.length, 30 + householdCount + 6 + ringCount);
    assert.equal(truth.customers.length, 30);
    assert.equal(truth.households.length, 4);
    assert.equal(truth.office.accountIds.length, 6);
    assert.equal(truth.rings.length, 3);
  });

  it("prefixes IDs with the run ID so repeated runs never collide", () => {
    const { accounts } = buildScenario({ ...small, runId: "demo42" });
    assert.ok(accounts.every((a) => a.account_id.startsWith("demo42_")));
  });

  it("makes every ring member reuse the ring card, or the ring phone with a near-identical email", () => {
    const { accounts, truth } = buildScenario({ ...small, rings: 10 });
    const byId = new Map(accounts.map((a) => [a.account_id, a]));
    for (const ring of truth.rings) {
      assert.ok(ring.accountIds.length >= 3 && ring.accountIds.length <= 6);
      const [baseLocal, domain] = ring.emailBase.split("@");
      for (const id of ring.accountIds) {
        const account = byId.get(id);
        const [local, accountDomain] = account.email.split("@");
        const sharesCard = account.payment_fingerprint === ring.card;
        const sharesPhoneAndEmail = account.device_id === ring.device && accountDomain === domain
          && local.startsWith(baseLocal) && local.length - baseLocal.length <= 1;
        assert.ok(sharesCard || sharesPhoneAndEmail, `${id} has no strong link to ${ring.ringId}`);
      }
      assert.ok(ring.accountIds.some((id) => byId.get(id).payment_fingerprint === ring.card),
        "the first member always carries the card");
    }
  });

  it("only uses signals another ring member shares, so every ring is linkable", () => {
    // A signal nobody else in the ring has links nothing; this caught a ring the engine could never join.
    const { accounts, truth } = buildScenario({ ...small, rings: 20 });
    const byId = new Map(accounts.map((a) => [a.account_id, a]));
    for (const ring of truth.rings) {
      const members = ring.accountIds.map((id) => byId.get(id));
      const cardHolders = members.filter((m) => m.payment_fingerprint === ring.card).length;
      const phoneHolders = members.filter((m) => m.device_id === ring.device).length;
      assert.ok(cardHolders >= 1 && phoneHolders >= 1);
      for (const member of members) {
        const viaCard = member.payment_fingerprint === ring.card && cardHolders >= 2;
        const viaPhone = member.device_id === ring.device && phoneHolders >= 2;
        assert.ok(viaCard || viaPhone, `${member.account_id} shares no signal with the rest of ${ring.ringId}`);
      }
    }
  });

  it("puts each household behind one IP but gives everyone their own phone and card", () => {
    const { accounts, truth } = buildScenario(small);
    const byId = new Map(accounts.map((a) => [a.account_id, a]));
    for (const household of truth.households) {
      const members = household.accountIds.map((id) => byId.get(id));
      assert.ok(members.every((m) => m.ip === household.ip));
      assert.equal(new Set(members.map((m) => m.device_id)).size, members.length);
      const cards = members.map((m) => m.payment_fingerprint).filter(Boolean);
      assert.equal(new Set(cards).size, cards.length);
    }
  });

  it("never lets unrelated people share a subnet by accident", () => {
    const { accounts, truth } = buildScenario({ ...small, customers: 300 });
    const shared = new Set([...truth.households.map((h) => h.ip), truth.office.ip]);
    const subnetOf = (ip) => ip.split(".").slice(0, 3).join(".");
    const customerIps = accounts.filter((a) => truth.customers.includes(a.account_id)).map((a) => a.ip);
    assert.equal(new Set(customerIps.map(subnetOf)).size, customerIps.length);
    assert.ok(customerIps.every((ip) => !shared.has(ip)));
  });

  it("uses valid RFC 3339 timestamps inside the scenario day", () => {
    const { accounts } = buildScenario(small);
    const start = Date.parse("2026-11-01T08:00:00Z");
    for (const account of accounts) {
      assert.match(account.created_at, /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/);
      const t = Date.parse(account.created_at);
      assert.ok(t >= start && t <= start + 25 * 3600_000);
    }
  });

  it("rejects settings that would make the scenario meaningless", () => {
    assert.throws(() => buildScenario({ customers: -1 }), /customers/);
    assert.throws(() => buildScenario({ ringMinSize: 1 }), /ring sizes/);
    assert.throws(() => buildScenario({ ringMaxSize: 9 }), /too common/);
    assert.throws(() => buildScenario({ start: "yesterday" }), /start/);
  });
});

describe("extraRingMember", () => {
  it("reuses the ring card and phone with a new ID", () => {
    const { truth } = buildScenario(small);
    const ring = truth.rings[0];
    const extra = extraRingMember(ring, "late_1", "2026-11-02T00:00:00Z");
    assert.equal(extra.payment_fingerprint, ring.card);
    assert.equal(extra.device_id, ring.device);
    assert.equal(extra.account_id, "late_1");
  });
});
