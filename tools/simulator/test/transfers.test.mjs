import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { buildScenario } from "../src/scenario.mjs";
import { buildTransfers, TRANSFER_DEFAULTS } from "../src/transfers.mjs";

const options = { seed: 11, runId: "s11", customers: 60, households: 6, officeWorkers: 8, rings: 3 };
const MINUTE = 60_000;

const scenario = buildScenario(options);
const money = buildTransfers(scenario);
const at = (transfer) => Date.parse(transfer.occurred_at);
const into = (account) => money.transfers.filter((t) => t.to_account === account);
const outOf = (account) => money.transfers.filter((t) => t.from_account === account);
const accountsOf = (kind) => money.cases.filter((c) => c.case === kind).map((c) => c.account_id);

describe("buildTransfers", () => {
  it("gives the same transfers for the same seed, and different ones for another seed", () => {
    assert.deepEqual(buildTransfers(buildScenario(options)), money);
    assert.notDeepEqual(buildTransfers(buildScenario({ ...options, seed: 12 })).transfers, money.transfers);
  });

  it("never changes the sign-ups", () => {
    const before = JSON.stringify(buildScenario(options));
    buildTransfers(scenario, { muleChains: 5, smallBusinesses: 4 });
    assert.equal(JSON.stringify(scenario), before);
  });

  it("writes well-formed transfers in time order, all after the last sign-up", () => {
    const ids = money.transfers.map((t) => t.transfer_id);
    assert.equal(new Set(ids).size, ids.length);
    const lastSignUp = Math.max(...scenario.accounts.map((a) => Date.parse(a.created_at)));
    let previous = 0;
    for (const t of money.transfers) {
      assert.deepEqual(Object.keys(t), ["transfer_id", "from_account", "to_account", "amount_cents", "occurred_at"]);
      assert.ok(t.transfer_id.startsWith("s11_t"));
      assert.notEqual(t.from_account, t.to_account);
      assert.ok(Number.isInteger(t.amount_cents) && t.amount_cents > 0, t.transfer_id);
      assert.match(t.occurred_at, /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ$/);
      assert.ok(at(t) >= previous && at(t) > lastSignUp, t.transfer_id);
      previous = at(t);
    }
  });

  it("gives every special role to a different customer", () => {
    const special = [...money.muleChains.flatMap((chain) => chain.account_ids), ...money.cases.map((c) => c.account_id)];
    assert.equal(new Set(special).size, special.length);
    const customers = new Set(scenario.truth.customers);
    assert.ok(special.every((id) => customers.has(id)));
    assert.equal(money.muleChains.length, TRANSFER_DEFAULTS.muleChains);
    assert.equal(accountsOf("small_business").length, TRANSFER_DEFAULTS.smallBusinesses);
    assert.equal(accountsOf("own_money").length, TRANSFER_DEFAULTS.ownMoney);
    assert.equal(accountsOf("payday_bills").length, TRANSFER_DEFAULTS.paydayBills);
  });

  it("pays every ordinary person a salary from outside the bank", () => {
    const special = new Set([...money.muleChains.flatMap((c) => c.account_ids), ...money.cases.map((c) => c.account_id)]);
    const people = [...scenario.truth.customers, ...scenario.truth.households.flatMap((h) => h.accountIds),
      ...scenario.truth.office.accountIds].filter((id) => !special.has(id));
    for (const person of people) {
      assert.equal(into(person).filter((t) => t.from_account.startsWith("ext_employer_")).length, 1, person);
    }
  });
});

describe("mule chains", () => {
  it("are 2-3 hops fed by 5-9 outside victims and ending at an outside cash-out", () => {
    for (const chain of money.muleChains) {
      assert.ok(chain.account_ids.length >= 2 && chain.account_ids.length <= 3, chain.chain_id);
      const victims = new Set(into(chain.account_ids[0]).map((t) => t.from_account));
      assert.equal(victims.size, chain.victims);
      assert.ok(chain.victims >= 5 && chain.victims <= 9);
      assert.ok([...victims].every((v) => v.startsWith("ext_victim_")));
      const last = chain.account_ids.at(-1);
      assert.ok(outOf(last).every((t) => t.to_account === chain.cash_out));
      assert.ok(chain.cash_out.startsWith("ext_cashout_"));
    }
  });

  it("pass 85-98% of every payment on to the next hop within 5-40 minutes", () => {
    for (const chain of money.muleChains) {
      const hops = [...chain.account_ids, chain.cash_out];
      for (let h = 0; h < chain.account_ids.length; h++) {
        const received = into(hops[h]);
        const sent = outOf(hops[h]);
        assert.equal(sent.length, received.length, `${chain.chain_id} hop ${h}`);
        assert.ok(sent.every((t) => t.to_account === hops[h + 1]));
        // Each payment out passes on one payment in. Forwards can overtake each other, so match them
        // to any unused payment in that fits, rather than by position.
        const unused = new Set(received);
        for (const forward of sent) {
          const source = [...unused].find((payment) => {
            const share = forward.amount_cents / payment.amount_cents;
            const delay = (at(forward) - at(payment)) / MINUTE;
            return share >= 0.84 && share <= 0.98 && delay >= 5 && delay <= 40;
          });
          assert.ok(source, `${chain.chain_id} hop ${h}: ${forward.transfer_id} passes on no payment`);
          unused.delete(source);
        }
      }
    }
  });

  it("can be switched off or made longer", () => {
    assert.deepEqual(buildTransfers(scenario, { muleChains: 0 }).muleChains, []);
    const long = buildTransfers(scenario, { muleChainMinLength: 3, muleChainMaxLength: 3 });
    assert.ok(long.muleChains.every((chain) => chain.account_ids.length === 3));
  });
});

describe("hard legitimate cases", () => {
  it("a small business is paid by 15-30 payers in one day and keeps at least 40% of the takings", () => {
    for (const shop of accountsOf("small_business")) {
      const payments = into(shop);
      assert.ok(payments.length >= 15 && payments.length <= 30, shop);
      const day = payments[0].occurred_at.slice(0, 10);
      assert.ok(payments.every((t) => t.occurred_at.startsWith(day)));
      const takings = payments.reduce((sum, t) => sum + t.amount_cents, 0);
      const [supplier] = outOf(shop);
      assert.equal(outOf(shop).length, 1);
      assert.ok(supplier.amount_cents <= takings * 0.6);
      assert.ok(at(supplier) - Math.max(...payments.map(at)) > 60 * MINUTE);
    }
  });

  it("an own-money saver moves 85-95% of the salary to their own outside savings within 20 minutes", () => {
    for (const saver of accountsOf("own_money")) {
      const [salary] = into(saver);
      const savings = outOf(saver).find((t) => t.to_account === `ext_own_savings_${saver}`);
      assert.equal(into(saver).length, 1);
      const share = savings.amount_cents / salary.amount_cents;
      assert.ok(share >= 0.84 && share <= 0.95, `${saver}: ${share}`);
      assert.ok(at(savings) - at(salary) <= 20 * MINUTE);
    }
  });

  it("a payday bill payer sends less than 80% of the salary on within the hour", () => {
    for (const payer of accountsOf("payday_bills")) {
      const [salary] = into(payer);
      const bills = outOf(payer);
      assert.ok(bills.length >= 3 && bills.length <= 5);
      const share = bills.reduce((sum, t) => sum + t.amount_cents, 0) / salary.amount_cents;
      assert.ok(share >= 0.39 && share < 0.8, `${payer}: ${share}`);
      assert.ok(bills.every((t) => at(t) - at(salary) <= 50 * MINUTE));
    }
  });
});

describe("validation", () => {
  it("rejects bad options with a clear message", () => {
    assert.throws(() => buildTransfers(scenario, { muleChains: -1 }), /muleChains must be a whole number/);
    assert.throws(() => buildTransfers(scenario, { ownMoney: 1.5 }), /ownMoney must be a whole number/);
    assert.throws(() => buildTransfers(scenario, { muleChainMinLength: 1 }), /2 <= muleChainMinLength/);
    assert.throws(() => buildTransfers(scenario, { muleChainMinLength: 3, muleChainMaxLength: 2 }),
      /muleChainMinLength <= muleChainMaxLength/);
    assert.throws(() => buildTransfers(scenario, { transferDays: 1 }), /transferDays must be at least 2/);
    assert.throws(() => buildTransfers(buildScenario({ ...options, customers: 10 })),
      /the transfer roles need up to 16 customers, but the scenario has 10/);
  });

  it("works with no ordinary customers left over", () => {
    const tight = buildScenario({ ...options, customers: 16, households: 0, officeWorkers: 0 });
    const result = buildTransfers(tight, { muleChainMinLength: 3 });
    for (const shop of result.cases.filter((c) => c.case === "small_business")) {
      const payers = result.transfers.filter((t) => t.to_account === shop.account_id).map((t) => t.from_account);
      assert.ok(payers.every((p) => p.startsWith("ext_buyer_")));
    }
  });
});
