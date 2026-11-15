// Money movement for a scenario: a few days of transfers after the sign-ups,
// plus the ground truth about who is a mule.
//
// Populations, chosen to test both kinds of mistake:
// - everyone:       a salary from an employer outside the bank, a few bills, and
//                   now and then a payment to another customer. Households also
//                   pay each other (rent shares).
// - mule chains:    2-3 recruited customers. Outside "victims" pay the first hop;
//                   each hop passes 85-98% of every payment on within minutes;
//                   the last hop sends it to a cash-out account outside the bank.
//                   A correct detector flags every hop.
// - small business: paid by many customers in one day, keeps the takings, pays a
//                   supplier the next day. Fan-in only: must NOT be flagged.
// - own money:      moves most of the salary to their own savings at another bank
//                   within minutes. Pass-through only: must NOT be flagged.
// - payday bills:   pays several bills within the hour after payday, under 80% of
//                   the salary. Must NOT be flagged.
//
// Uses its own random stream, so adding transfers never changes the sign-ups:
// accounts.jsonl stays byte-identical for a seed.

import { createRandom } from "./random.mjs";

export const TRANSFER_DEFAULTS = Object.freeze({
  muleChains: 2,
  muleChainMinLength: 2,
  muleChainMaxLength: 3,
  smallBusinesses: 2,
  ownMoney: 3,
  paydayBills: 5,
  transferDays: 3,
});

/** Mixed into the seed so the transfer stream is independent of the sign-up stream. */
const STREAM_SALT = 0x5eed7a11;
const DOLLAR = 100;
const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

/**
 * @param scenario the result of buildScenario(): sign-ups and their truth
 * @returns {{transfers: object[], muleChains: object[], cases: object[]}}
 *   transfers sorted by time then ID; muleChains with account_ids in hop order;
 *   cases naming the hard legitimate accounts
 */
export function buildTransfers(scenario, options = {}) {
  const config = { ...TRANSFER_DEFAULTS, ...options };
  validate(config, scenario.truth.customers.length);
  const random = createRandom((scenario.truth.seed ^ STREAM_SALT) >>> 0);
  const prefix = scenario.truth.runId;
  const firstDay = Date.parse(scenario.accounts.reduce((latest, a) => (a.created_at > latest ? a.created_at : latest),
    scenario.accounts[0]?.created_at ?? "2026-11-01T00:00:00Z")) + DAY;
  const dayStart = (day) => Math.floor(firstDay / DAY) * DAY + day * DAY;

  const transfers = [];
  let counter = 0;
  const send = (from, to, cents, at) => {
    transfers.push({
      transfer_id: `${prefix}_t${String(counter++).padStart(6, "0")}`,
      from_account: from,
      to_account: to,
      amount_cents: cents,
      occurred_at: new Date(at).toISOString().replace(".000Z", "Z"),
    });
  };
  const dollars = (min, max) => random.int(min, max) * DOLLAR + random.int(0, 99);
  const anyTime = () => dayStart(random.int(0, config.transferDays - 1)) + random.int(7 * 60, 21 * 60) * MINUTE;
  const salaryTime = () => dayStart(random.int(0, config.transferDays - 1)) + random.int(6 * 60, 10 * 60) * MINUTE;
  const employer = () => `ext_employer_${random.int(1, 20)}`;
  const biller = () => `ext_biller_${random.int(1, 15)}`;

  // Pick distinct customers for every special role; everyone else is ordinary.
  const pool = random.shuffle(scenario.truth.customers);
  const take = (n) => pool.splice(0, n);
  const chains = [];
  for (let c = 0; c < config.muleChains; c++) {
    chains.push(take(random.int(config.muleChainMinLength, config.muleChainMaxLength)));
  }
  const businesses = take(config.smallBusinesses);
  const ownMoney = take(config.ownMoney);
  const payday = take(config.paydayBills);
  const ordinaryCustomers = pool;
  const householdMembers = scenario.truth.households.flatMap((h) => h.accountIds);
  const officeWorkers = scenario.truth.office?.accountIds ?? [];

  // Ordinary money movement.
  for (const person of [...ordinaryCustomers, ...householdMembers, ...officeWorkers]) {
    send(employer(), person, dollars(1500, 6000), salaryTime());
    for (let b = random.int(1, 3); b > 0; b--) {
      send(person, biller(), dollars(50, 400), anyTime());
    }
    if (ordinaryCustomers.length > 1 && random.chance(0.3)) {
      for (let p = random.int(1, 2); p > 0; p--) {
        const friend = random.pick(ordinaryCustomers);
        if (friend !== person) send(person, friend, dollars(10, 200), anyTime());
      }
    }
  }
  for (const household of scenario.truth.households) {
    const [payee, ...payers] = household.accountIds;
    for (const payer of payers) {
      if (random.chance(0.6)) send(payer, payee, dollars(200, 800), anyTime());
    }
  }

  // Mule chains.
  const muleChains = chains.map((hops, index) => {
    const cashOut = `ext_cashout_${prefix}_${index}`;
    const victims = random.int(5, 9);
    let at = dayStart(random.int(0, config.transferDays - 1)) + random.int(8 * 60, 14 * 60) * MINUTE;
    for (let v = 0; v < victims; v++) {
      at += random.int(10, 50) * MINUTE;
      let amount = dollars(200, 2500);
      let when = at;
      send(`ext_victim_${prefix}_${index}_${v}`, hops[0], amount, when);
      for (let h = 0; h < hops.length; h++) {
        const next = h + 1 < hops.length ? hops[h + 1] : cashOut;
        amount = Math.floor((amount * random.int(85, 98)) / 100);
        when += random.int(5, 40) * MINUTE;
        send(hops[h], next, amount, when);
      }
    }
    return { chain_id: `mule_chain_${index}`, account_ids: hops, victims, cash_out: cashOut };
  });

  // Hard legitimate cases.
  const cases = [];
  for (const shop of businesses) {
    const day = dayStart(random.int(0, config.transferDays - 2));
    const buyers = random.int(15, 30);
    let takings = 0;
    for (let b = 0; b < buyers; b++) {
      const amount = dollars(10, 80);
      takings += amount;
      const buyer = ordinaryCustomers.length > 0 && random.chance(0.5)
        ? random.pick(ordinaryCustomers)
        : `ext_buyer_${prefix}_${random.int(1, 500)}`;
      send(buyer, shop, amount, day + random.int(8 * 60, 18 * 60) * MINUTE);
    }
    send(shop, `ext_supplier_${random.int(1, 10)}`, Math.floor((takings * random.int(40, 60)) / 100),
      day + DAY + random.int(9 * 60, 11 * 60) * MINUTE);
    cases.push({ case: "small_business", account_id: shop });
  }
  for (const saver of ownMoney) {
    const salary = dollars(2000, 6000);
    const paid = salaryTime();
    send(employer(), saver, salary, paid);
    send(saver, `ext_own_savings_${saver}`, Math.floor((salary * random.int(85, 95)) / 100),
      paid + random.int(2, 20) * MINUTE);
    send(saver, biller(), dollars(20, 80), paid + random.int(6, 30) * HOUR);
    cases.push({ case: "own_money", account_id: saver });
  }
  for (const household of payday) {
    const salary = dollars(2000, 6000);
    const paid = salaryTime();
    send(employer(), household, salary, paid);
    const bills = random.int(3, 5);
    const share = random.int(40, 70) / 100;
    for (let b = 0; b < bills; b++) {
      send(household, biller(), Math.floor((salary * share) / bills), paid + random.int(10, 50) * MINUTE);
    }
    cases.push({ case: "payday_bills", account_id: household });
  }

  transfers.sort((a, b) => compare(a.occurred_at, b.occurred_at) || compare(a.transfer_id, b.transfer_id));
  cases.sort((a, b) => compare(a.case, b.case) || compare(a.account_id, b.account_id));
  return { transfers, muleChains, cases };
}

function validate(config, customerCount) {
  for (const key of Object.keys(TRANSFER_DEFAULTS)) {
    if (!Number.isInteger(config[key]) || config[key] < 0) {
      throw new Error(`${key} must be a whole number of 0 or more`);
    }
  }
  if (config.muleChainMinLength < 2 || config.muleChainMaxLength < config.muleChainMinLength) {
    throw new Error("mule chain lengths need 2 <= muleChainMinLength <= muleChainMaxLength");
  }
  if (config.transferDays < 2) {
    throw new Error("transferDays must be at least 2, so a business can pay its supplier the next day");
  }
  const needed = config.muleChains * config.muleChainMaxLength + config.smallBusinesses + config.ownMoney
    + config.paydayBills;
  if (needed > customerCount) {
    throw new Error(`the transfer roles need up to ${needed} customers, but the scenario has ${customerCount}`);
  }
}

/** Plain code-unit comparison, so the order never depends on the machine's locale. */
function compare(a, b) {
  return a < b ? -1 : a > b ? 1 : 0;
}
