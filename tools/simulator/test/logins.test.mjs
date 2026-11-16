import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { buildScenario } from "../src/scenario.mjs";
import { buildLogins, distanceKm, LOGIN_DEFAULTS } from "../src/logins.mjs";
import { buildTransfers } from "../src/transfers.mjs";

const options = { seed: 13, runId: "s13", customers: 60, households: 6, officeWorkers: 8, rings: 3 };
const MINUTE = 60_000;
const HOUR = 60 * MINUTE;

const scenario = buildScenario(options);
const result = buildLogins(scenario);
const at = (login) => Date.parse(login.occurred_at);
const of = (account) => result.logins.filter((l) => l.account_id === account);
const ok = (account) => of(account).filter((l) => l.success);
const located = (login) => login.latitude !== null;
const accountsOf = (kind) => result.cases.filter((c) => c.case === kind).map((c) => c.account_id);
const takeoversOf = (kind) => result.takeovers.filter((t) => t.kind === kind);
const kmh = (a, b) => distanceKm(a, b) / Math.max((at(b) - at(a)) / HOUR, 1 / 60);
/** The account's own logins: everything except attackers', including stuffing tries that failed. */
const attackerDevices = new Set(result.takeovers.map((t) => t.attacker_device));
const own = (account) => of(account).filter((l) => !attackerDevices.has(l.device_id));
/** The fastest hop between successful located logins over 300 km apart, as the impossible-travel check sees them. */
const fastestHop = (logins) => {
  const path = logins.filter((l) => l.success && located(l));
  let fastest = 0;
  for (let i = 1; i < path.length; i++) {
    if (distanceKm(path[i - 1], path[i]) >= 300) fastest = Math.max(fastest, kmh(path[i - 1], path[i]));
  }
  return fastest;
};

describe("buildLogins", () => {
  it("gives the same logins for the same seed, and different ones for another seed", () => {
    assert.deepEqual(buildLogins(buildScenario(options)), result);
    assert.notDeepEqual(buildLogins(buildScenario({ ...options, seed: 14 })).logins, result.logins);
  });

  it("never changes the sign-ups or the transfers", () => {
    const before = JSON.stringify(buildScenario(options));
    const transfers = JSON.stringify(buildTransfers(scenario));
    buildLogins(scenario, { phishing: 6, stuffingTargets: 40 });
    assert.equal(JSON.stringify(scenario), before);
    assert.equal(JSON.stringify(buildTransfers(scenario)), transfers);
  });

  it("writes well-formed logins in time order, all after the last sign-up", () => {
    const ids = result.logins.map((l) => l.login_id);
    assert.equal(new Set(ids).size, ids.length);
    const accounts = new Set(scenario.accounts.map((a) => a.account_id));
    const lastSignUp = Math.max(...scenario.accounts.map((a) => Date.parse(a.created_at)));
    let previous = 0;
    for (const l of result.logins) {
      assert.deepEqual(Object.keys(l),
        ["login_id", "account_id", "device_id", "ip", "success", "occurred_at", "latitude", "longitude"]);
      assert.match(l.login_id, /^s13_l\d{7}$/);
      assert.ok(accounts.has(l.account_id), l.login_id);
      assert.ok(l.device_id.startsWith("dev_"), l.login_id);
      assert.match(l.ip, /^\d+\.\d+\.\d+\.\d+$/);
      assert.equal(typeof l.success, "boolean");
      assert.match(l.occurred_at, /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ$/);
      // A location is both coordinates or neither.
      assert.equal(l.latitude === null, l.longitude === null, l.login_id);
      if (located(l)) assert.ok(Math.abs(l.latitude) <= 90 && Math.abs(l.longitude) <= 180, l.login_id);
      assert.ok(at(l) >= previous && at(l) > lastSignUp, l.login_id);
      previous = at(l);
    }
  });

  it("gives every account a login history, and every special role to a different customer", () => {
    for (const account of scenario.accounts) {
      if (!scenario.truth.rings.some((r) => r.accountIds.includes(account.account_id))) {
        assert.ok(ok(account.account_id).length >= 10, account.account_id);
      }
    }
    const special = [...result.takeovers.map((t) => t.account_id),
      ...result.cases.filter((c) => c.case !== "family_tablet").map((c) => c.account_id)];
    assert.equal(new Set(special).size, special.length);
    const customers = new Set(scenario.truth.customers);
    assert.ok(special.every((id) => customers.has(id)));
  });

  it("maps each IP address to one fixed place, and leaves a few unlocated", () => {
    const places = new Map();
    for (const l of result.logins) {
      const where = `${l.latitude},${l.longitude}`;
      assert.equal(places.get(l.ip) ?? where, where, l.ip);
      places.set(l.ip, where);
    }
    const unlocated = result.logins.filter((l) => !located(l)).length / result.logins.length;
    assert.ok(unlocated < 0.1, `${unlocated}`);
    // About 1 address in 20 has no known place; in a full-size scenario some always do.
    const full = buildLogins(buildScenario({ seed: 13 })).logins;
    const share = full.filter((l) => !located(l)).length / full.length;
    assert.ok(share > 0.01 && share < 0.1, `${share}`);
  });

  it("keeps ordinary customers on one device and within reach of home", () => {
    const special = new Set([...result.takeovers.map((t) => t.account_id), ...result.cases.map((c) => c.account_id)]);
    for (const account of scenario.truth.customers.filter((id) => !special.has(id))) {
      const failures = own(account).filter((l) => !l.success);
      assert.equal(new Set(own(account).map((l) => l.device_id)).size, 1, account);
      assert.ok(fastestHop(own(account)) < 900, account);
      // A mistyped password is a single failure, never a burst of 5 within 15 minutes.
      for (const f of failures) {
        assert.ok(failures.filter((g) => at(g) >= at(f) && at(g) - at(f) <= 15 * MINUTE).length < 5, account);
      }
    }
  });
});

describe("planted takeovers", () => {
  it("are recorded with the attacker's device and first successful login", () => {
    assert.equal(takeoversOf("phishing").length, LOGIN_DEFAULTS.phishing);
    assert.equal(takeoversOf("password_guessing").length, LOGIN_DEFAULTS.passwordGuessing);
    for (const t of result.takeovers) {
      const attempts = t.attacker_login_ids.map((id) => result.logins.find((l) => l.login_id === id));
      assert.ok(attempts.every((l) => l.account_id === t.account_id && l.device_id === t.attacker_device));
      const first = attempts.find((l) => l.success);
      assert.equal(t.first_bad_login_id, first.login_id);
      assert.equal(t.first_bad_at, first.occurred_at);
      // Every takeover follows a history on the customer's own device, which the attacker never had.
      const history = own(t.account_id).filter((l) => l.success && at(l) < at(first));
      assert.ok(history.length >= 5, t.account_id);
      assert.ok(history.every((l) => l.device_id !== t.attacker_device));
    }
  });

  it("credential stuffing tries many accounts once each from one device, and gets into a few", () => {
    const victims = takeoversOf("credential_stuffing");
    assert.ok(victims.length >= LOGIN_DEFAULTS.stuffingMinVictims && victims.length <= LOGIN_DEFAULTS.stuffingMaxVictims);
    const [device] = new Set(victims.map((t) => t.attacker_device));
    const tries = result.logins.filter((l) => l.device_id === device);
    assert.equal(tries.length, LOGIN_DEFAULTS.stuffingTargets);
    assert.equal(new Set(tries.map((l) => l.account_id)).size, tries.length);
    assert.deepEqual(tries.filter((l) => l.success).map((l) => l.account_id).sort(),
      victims.map((t) => t.account_id).sort());
    assert.ok(at(tries.at(-1)) - at(tries[0]) < HOUR);
    // It only fails on accounts that are not part of a planted case.
    const special = new Set(result.cases.map((c) => c.account_id));
    assert.ok(tries.filter((l) => !l.success).every((l) => !special.has(l.account_id)));
  });

  it("phishing logs in from far away within 20-90 minutes of the customer's own login", () => {
    for (const t of takeoversOf("phishing")) {
      const first = result.logins.find((l) => l.login_id === t.first_bad_login_id);
      const before = own(t.account_id).filter((l) => l.success && located(l) && at(l) < at(first));
      const gap = (login) => (at(first) - at(login)) / MINUTE;
      assert.ok(before.some((l) => gap(l) >= 20 && gap(l) <= 90), t.account_id);
      // An ordinary session can fall in between; either way the last place was half a world away.
      assert.ok(distanceKm(before.at(-1), first) > 10_000);
      assert.ok(kmh(before.at(-1), first) > 900);
    }
  });

  it("password guessing is 6-12 failures a minute or so apart, then a success", () => {
    for (const t of takeoversOf("password_guessing")) {
      const attempts = t.attacker_login_ids.map((id) => result.logins.find((l) => l.login_id === id));
      const failures = attempts.slice(0, -1);
      assert.ok(failures.length >= 6 && failures.length <= 12);
      assert.ok(failures.every((l) => !l.success) && attempts.at(-1).success);
      assert.ok(at(attempts.at(-1)) - at(failures.at(-5)) < 15 * MINUTE);
    }
  });
});

describe("hard legitimate cases", () => {
  it("a new phone takes over from the old one for good, from the same places", () => {
    assert.equal(accountsOf("new_phone").length, LOGIN_DEFAULTS.newPhone);
    for (const account of accountsOf("new_phone")) {
      const logins = own(account);
      const devices = [...new Set(logins.map((l) => l.device_id))];
      assert.equal(devices.length, 2, account);
      const switched = logins.findIndex((l) => l.device_id === devices[1]);
      assert.ok(logins.slice(0, switched).filter((l) => l.success).length >= 5, account);
      assert.ok(logins.slice(switched).every((l) => l.device_id === devices[1]), account);
      assert.ok(logins.slice(switched).length >= 3, account);
      assert.ok(fastestHop(logins) < 900, account);
    }
  });

  it("a holiday flies over 300 km at a possible speed, logs in abroad, then comes home", () => {
    assert.equal(accountsOf("holiday").length, LOGIN_DEFAULTS.holiday);
    for (const account of accountsOf("holiday")) {
      const logins = own(account).filter((l) => l.success);
      const home = logins[0];
      const away = logins.filter((l) => distanceKm(home, l) > 300);
      assert.ok(away.length >= 3, account);
      assert.ok(distanceKm(home, logins.at(-1)) < 300, `${account} comes home`);
      assert.equal(new Set(logins.map((l) => l.device_id)).size, 1);
      assert.ok(fastestHop(logins) < 900, `${account}: ${fastestHop(logins)} km/h`);
    }
  });

  it("a forgotten password is 4-7 failures within 15 minutes, then a success on the own device", () => {
    assert.equal(accountsOf("forgotten_password").length, LOGIN_DEFAULTS.forgottenPassword);
    for (const account of accountsOf("forgotten_password")) {
      const logins = own(account);
      assert.equal(new Set(logins.map((l) => l.device_id)).size, 1);
      // The longest run of failures in a row.
      let run = [];
      let longest = [];
      for (const l of logins) {
        run = l.success ? [] : [...run, l];
        if (run.length > longest.length) longest = run;
      }
      assert.ok(longest.length >= 4 && longest.length <= 7, `${account}: ${longest.length}`);
      assert.ok(at(longest.at(-1)) - at(longest[0]) < 15 * MINUTE);
    }
  });

  it("a family tablet is shared by everyone in the largest households, at home", () => {
    const members = accountsOf("family_tablet");
    const largest = Math.max(...scenario.truth.households.map((h) => h.accountIds.length));
    const households = scenario.truth.households.filter((h) => h.accountIds.some((id) => members.includes(id)));
    assert.equal(households.length, LOGIN_DEFAULTS.familyTablets);
    assert.equal(households[0].accountIds.length === largest || households[1].accountIds.length === largest, true);
    for (const household of households) {
      assert.ok(household.accountIds.every((id) => members.includes(id)));
      const own = new Set(household.accountIds.map((id) => scenario.accounts.find((a) => a.account_id === id).device_id));
      const shared = result.logins.filter((l) => household.accountIds.includes(l.account_id) && !own.has(l.device_id));
      const [tablet] = new Set(shared.map((l) => l.device_id));
      assert.ok(shared.every((l) => l.device_id === tablet && l.ip === household.ip));
      assert.equal(new Set(shared.map((l) => l.account_id)).size, household.accountIds.length);
    }
  });
});

describe("validation", () => {
  it("rejects bad options with a clear message", () => {
    assert.throws(() => buildLogins(scenario, { phishing: -1 }), /phishing must be a whole number/);
    assert.throws(() => buildLogins(scenario, { holiday: 1.5 }), /holiday must be a whole number/);
    assert.throws(() => buildLogins(scenario, { loginDays: 13 }), /loginDays must be at least 14/);
    assert.throws(() => buildLogins(scenario, { stuffingMinVictims: 0 }), /1 <= stuffingMinVictims/);
    assert.throws(() => buildLogins(scenario, { stuffingMinVictims: 5, stuffingMaxVictims: 4 }),
      /stuffingMinVictims <= stuffingMaxVictims/);
    assert.throws(() => buildLogins(scenario, { stuffingTargets: 5 }), /stuffingTargets must be at least/);
    assert.throws(() => buildLogins(buildScenario({ ...options, customers: 20 })),
      /the login roles need up to 23 customers, but the scenario has 20/);
    assert.throws(() => buildLogins(scenario, { familyTablets: 7 }), /familyTablets is 7, but the scenario has 6/);
  });

  it("works with the shortest login period, and with nothing planted", () => {
    const short = buildLogins(scenario, { loginDays: 14 });
    assert.equal(short.takeovers.length, result.takeovers.length);
    const quiet = buildLogins(scenario, { stuffingCampaigns: 0, phishing: 0, passwordGuessing: 0, newPhone: 0,
      holiday: 0, forgottenPassword: 0, familyTablets: 0 });
    assert.deepEqual(quiet.takeovers, []);
    assert.deepEqual(quiet.cases, []);
    assert.ok(quiet.logins.length > 1000);
  });
});
