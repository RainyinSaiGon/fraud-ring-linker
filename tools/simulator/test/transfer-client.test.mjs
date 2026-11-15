import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { createClient, sendTransfers, TRANSFER_BATCH } from "../src/client.mjs";
import { formatMuleSummary, summarizeMules } from "../src/report.mjs";

function fakeFetch(handler) {
  const calls = [];
  const fetchImpl = async (url, init) => {
    calls.push({ url, method: init.method, body: init.body ? JSON.parse(init.body) : undefined });
    const { status = 200, body } = await handler(url, init);
    return { ok: status < 400, status, text: async () => (body === undefined ? "" : JSON.stringify(body)) };
  };
  return { fetchImpl, calls };
}

const transfer = (i) => ({ transfer_id: `t${i}`, from_account: "a", to_account: "b", amount_cents: 1,
  occurred_at: "2026-11-03T10:00:00Z" });

describe("sendTransfers", () => {
  it("posts transfers in batches the service accepts, in order, and collects the flagged accounts", async () => {
    let batch = 0;
    const { fetchImpl, calls } = fakeFetch(() => ({ status: 201, body: { flagged_now: [`m${batch++}`] } }));
    const client = createClient("http://svc", fetchImpl);
    const transfers = Array.from({ length: TRANSFER_BATCH * 2 + 1 }, (_, i) => transfer(i));
    const progress = [];

    const result = await sendTransfers(client, transfers, { onProgress: (done) => progress.push(done) });

    assert.equal(calls.length, 3);
    assert.ok(calls.every((call) => call.url === "http://svc/api/v1/transfers" && call.method === "POST"));
    assert.deepEqual(calls.map((call) => call.body.transfers.length), [500, 500, 1]);
    assert.equal(calls[1].body.transfers[0].transfer_id, "t500");
    assert.deepEqual(result, { sent: 1001, flagged: ["m0", "m1", "m2"] });
    assert.deepEqual(progress, [500, 1000, 1001]);
  });

  it("sends nothing for no transfers", async () => {
    const { fetchImpl, calls } = fakeFetch(() => ({ status: 201, body: { flagged_now: [] } }));
    assert.deepEqual(await sendTransfers(createClient("http://svc", fetchImpl), []), { sent: 0, flagged: [] });
    assert.equal(calls.length, 0);
  });

  it("stops at the first refused batch", async () => {
    const { fetchImpl, calls } = fakeFetch(() => ({ status: 409, body: { detail: "Transfer t0 already exists." } }));
    await assert.rejects(sendTransfers(createClient("http://svc", fetchImpl), [transfer(0)]),
      /Transfer t0 already exists/);
    assert.equal(calls.length, 1);
  });
});

describe("summarizeMules", () => {
  const money = {
    muleChains: [{ chain_id: "mule_chain_0", account_ids: ["m1", "m2"] }, { chain_id: "mule_chain_1", account_ids: ["m3"] }],
    cases: [{ case: "small_business", account_id: "shop" }, { case: "own_money", account_id: "saver" }],
  };

  it("counts mules found and missed, hard cases flagged by kind, and anyone else flagged", () => {
    const summary = summarizeMules(["m2", "m1", "shop", "stranger", "m1"], money);

    assert.deepEqual(summary, {
      mulesPlanted: 3,
      mulesFlagged: 2,
      missedMules: ["m3"],
      hardCases: 2,
      hardCasesFlagged: 1,
      legitFlaggedByCase: { small_business: 1 },
      otherFlagged: ["stranger"],
    });
    assert.equal(formatMuleSummary(summary), [
      "Mules planted:           3",
      "Mules flagged:           2",
      "Hard cases flagged:      1 of 2 (small_business 1)",
      "Other accounts flagged:  1",
      "missed mule: m3",
    ].join("\n"));
  });

  it("reports a clean run without a breakdown", () => {
    const text = formatMuleSummary(summarizeMules(["m1", "m2", "m3"], money));
    assert.match(text, /Mules flagged: {11}3/);
    assert.match(text, /Hard cases flagged: {6}0 of 2$/m);
  });
});
