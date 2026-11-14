import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { ApiError, createClient, registerAll } from "../src/client.mjs";
import { formatSummary, summarize } from "../src/report.mjs";

/** A fake fetch that records calls and answers from a handler. */
function fakeFetch(handler) {
  const calls = [];
  const fetchImpl = async (url, init) => {
    calls.push({ url, method: init.method, body: init.body ? JSON.parse(init.body) : undefined });
    const { status = 200, body } = await handler(url, init);
    return { ok: status < 400, status, text: async () => (body === undefined ? "" : JSON.stringify(body)) };
  };
  return { fetchImpl, calls };
}

describe("createClient", () => {
  it("posts accounts as JSON to the accounts endpoint", async () => {
    const { fetchImpl, calls } = fakeFetch(() => ({ status: 201, body: { cluster_id: "c1" } }));
    const client = createClient("http://svc:8080/", fetchImpl);

    const response = await client.registerAccount({ account_id: "a" });

    assert.deepEqual(response, { cluster_id: "c1" });
    assert.equal(calls[0].url, "http://svc:8080/api/v1/accounts");
    assert.equal(calls[0].method, "POST");
    assert.deepEqual(calls[0].body, { account_id: "a" });
  });

  it("turns problem responses into ApiError with the detail message", async () => {
    const { fetchImpl } = fakeFetch(() => ({ status: 409, body: { title: "Conflict", detail: "Account a already exists." } }));
    const client = createClient("http://svc", fetchImpl);

    await assert.rejects(client.registerAccount({ account_id: "a" }), (error) => {
      assert.ok(error instanceof ApiError);
      assert.equal(error.status, 409);
      assert.equal(error.message, "Account a already exists.");
      return true;
    });
  });

  it("encodes cluster IDs in paths", async () => {
    const { fetchImpl, calls } = fakeFetch(() => ({ status: 201, body: {} }));
    await createClient("http://svc", fetchImpl).decide("c 1", "NOT_FRAUD", "ana", "ok");
    assert.equal(calls[0].url, "http://svc/api/v1/clusters/c%201/decisions");
    assert.deepEqual(calls[0].body, { outcome: "NOT_FRAUD", analyst: "ana", note: "ok" });
  });
});

describe("registerAll", () => {
  it("keeps input order, limits requests in flight and records failures", async () => {
    let inFlight = 0;
    let maxInFlight = 0;
    const { fetchImpl } = fakeFetch(async (url, init) => {
      inFlight += 1;
      maxInFlight = Math.max(maxInFlight, inFlight);
      await new Promise((resolve) => setTimeout(resolve, 2));
      inFlight -= 1;
      const { account_id: id } = JSON.parse(init.body);
      return id === "bad" ? { status: 400, body: { detail: "nope" } } : { status: 201, body: { account_id: id, cluster_id: "c1" } };
    });
    const accounts = ["a", "b", "bad", "c", "d", "e"].map((id) => ({ account_id: id }));

    const results = await registerAll(createClient("http://svc", fetchImpl), accounts, { concurrency: 2 });

    assert.deepEqual(results.map((r) => r.account.account_id), ["a", "b", "bad", "c", "d", "e"]);
    assert.equal(results[2].error.message, "nope");
    assert.equal(results.filter((r) => r.response).length, 5);
    assert.ok(maxInFlight <= 2);
  });
});

describe("summarize", () => {
  const truth = {
    rings: [
      { ringId: "ring_0", accountIds: ["r1", "r2", "r3"] },
      { ringId: "ring_1", accountIds: ["s1", "s2"] },
    ],
    customers: ["x", "y"],
  };
  const ok = (id, cluster) => ({ account: { account_id: id }, response: { account_id: id, cluster_id: cluster } });

  it("counts fully linked and split rings and wrongly linked customers", () => {
    const results = [
      ok("r1", "c1"), ok("r2", "c1"), ok("r3", "c1"),
      ok("s1", "c2"), ok("s2", "c3"),
      ok("x", "c1"), ok("y", "c4"),
    ];

    const summary = summarize(results, truth);

    assert.equal(summary.ringsFullyLinked, 1);
    assert.equal(summary.ringsSplit, 1);
    assert.equal(summary.legitimateAccountsWronglyLinked, 1);
    assert.deepEqual(summary.wronglyLinked, [{ accountId: "x", clusterId: "c1" }]);
    assert.deepEqual(summary.mergedRings, []);
  });

  it("flags a cluster that mixes two rings", () => {
    const results = [ok("r1", "c1"), ok("r2", "c1"), ok("r3", "c1"), ok("s1", "c1"), ok("s2", "c1")];
    assert.deepEqual(summarize(results, truth).mergedRings, [{ clusterId: "c1", ringIds: ["ring_0", "ring_1"] }]);
  });

  it("does not count a ring as linked when a member failed to register", () => {
    const results = [ok("r1", "c1"), ok("r2", "c1"), { account: { account_id: "r3" }, error: new Error("boom") }];
    const summary = summarize(results, { rings: [truth.rings[0]], customers: [] });
    assert.equal(summary.ringsFullyLinked, 0);
    assert.equal(summary.failures.length, 1);
    assert.match(formatSummary(summary), /failed: r3: boom/);
  });
});
