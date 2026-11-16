// Login traffic for a scenario: a few weeks of logins after the sign-ups, plus
// the ground truth about which accounts were taken over.
//
// Populations, chosen to test both kinds of mistake:
// - everyone:          logs in from their own device, mostly from home and
//                      sometimes from work, near where they live. Now and then
//                      they mistype the password once. Rings log in too, from
//                      the ring's devices.
// - credential stuffing: one attacker device tries a leaked password on many
//                      accounts, once each, in minutes, and gets into a few.
// - phishing:          right after the customer's own login, a new device far
//                      away logs in with the stolen password.
// - password guessing: a burst of failed logins from a new device, then a success.
// - new phone:         the customer switches to a new device nearby and uses it
//                      every day after. Must NOT be flagged.
// - holiday:           a long but possible flight, logins abroad for a few days,
//                      then home. Must NOT be flagged.
// - forgotten password: several failed logins, a reset, then a success on the
//                      customer's own device. Must NOT be flagged.
// - family tablet:     one tablet shared by everyone in a household, as well as
//                      their own phones. Must NOT be flagged.
//
// IP addresses map to a fixed location, as an IP geolocation lookup would. A few
// addresses have no known location. Logins use their own random stream, so
// adding logins never changes the sign-ups or the transfers.

import { createRandom } from "./random.mjs";

export const LOGIN_DEFAULTS = Object.freeze({
  loginDays: 21,
  stuffingCampaigns: 1,
  stuffingMinVictims: 4,
  stuffingMaxVictims: 6,
  stuffingTargets: 30,
  phishing: 3,
  passwordGuessing: 3,
  newPhone: 4,
  holiday: 3,
  forgottenPassword: 4,
  familyTablets: 2,
});

/** Mixed into the seed so the login stream is independent of the sign-up and transfer streams. */
const STREAM_SALT = 0x1061a5ed;
const SECOND = 1000;
const MINUTE = 60 * SECOND;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;
/** Planted takeovers and changes of habit start on this day or later, after a login history. */
const FIRST_EVENT_DAY = 8;
/** A holiday flight covers at most this many km a day, including the airport, well under any speed limit. */
const FLIGHT_KM_PER_DAY = 550 * 24;

// [latitude, longitude]
const CITIES = [[-33.87, 151.21], [-37.81, 144.96], [-27.47, 153.03], [-31.95, 115.86], [-34.93, 138.6],
  [-35.28, 149.13], [-42.88, 147.33], [-12.46, 130.84]];
const HOLIDAYS = [[-8.65, 115.22], [-36.85, 174.76], [35.68, 139.69], [1.35, 103.82], [-17.76, 177.44]];
/** Far from every city above: over 10,000 km. */
const REMOTE = [[51.51, -0.13], [40.71, -74.01], [52.37, 4.9], [-23.55, -46.63], [43.65, -79.38]];

/**
 * @param scenario the result of buildScenario(): sign-ups and their truth
 * @returns {{logins: object[], takeovers: object[], cases: object[]}}
 *   logins sorted by time then ID; takeovers and cases sorted by account ID
 */
export function buildLogins(scenario, options = {}) {
  const config = { ...LOGIN_DEFAULTS, ...options };
  validate(config, scenario.truth);
  const random = createRandom((scenario.truth.seed ^ STREAM_SALT) >>> 0);
  const prefix = scenario.truth.runId;
  const accountsById = new Map(scenario.accounts.map((account) => [account.account_id, account]));
  const lastSignUp = Date.parse(scenario.accounts.reduce((latest, a) => (a.created_at > latest ? a.created_at : latest),
    scenario.accounts[0]?.created_at ?? "2026-11-01T00:00:00Z"));
  const firstDay = Math.floor((lastSignUp + DAY) / DAY) * DAY;
  const dayStart = (day) => firstDay + day * DAY;
  /** A moment in the waking day, to the second. */
  const during = (day) => dayStart(day) + random.int(7 * 60, 22 * 60) * MINUTE + random.int(0, 59) * SECOND;

  let tokens = 0;
  const token = (kind) => `${kind}_${prefix}_l${tokens++}_${random.int(100000, 999999)}`;
  let addresses = 0;
  const newIp = () => {
    addresses += 1;
    return `172.${16 + ((addresses >> 16) & 15)}.${(addresses >> 8) & 255}.${addresses & 255}`;
  };
  const near = ([latitude, longitude]) => [round(latitude + (random.next() - 0.5) * 0.2),
    round(longitude + (random.next() - 0.5) * 0.2)];
  /** An IP address and where geolocation puts it; `located: false` for one of the few with no known place. */
  const place = (ip, city, located = random.chance(0.95)) => {
    const [latitude, longitude] = near(city);
    return located ? { ip, latitude, longitude } : { ip, latitude: null, longitude: null };
  };

  const logins = [];
  let counter = 0;
  const record = (accountId, deviceId, where, success, at) => {
    const login = {
      login_id: `${prefix}_l${String(counter++).padStart(7, "0")}`,
      account_id: accountId,
      device_id: deviceId,
      ip: where.ip,
      success,
      occurred_at: new Date(at).toISOString().replace(".000Z", "Z"),
      latitude: where.latitude,
      longitude: where.longitude,
    };
    logins.push(login);
    return login;
  };

  // Special roles go to distinct customers, picked before anything else is drawn.
  const pool = random.shuffle(scenario.truth.customers);
  const take = (n) => pool.splice(0, n);
  const stuffing = [];
  for (let c = 0; c < config.stuffingCampaigns; c++) {
    stuffing.push(take(random.int(config.stuffingMinVictims, config.stuffingMaxVictims)));
  }
  const phished = take(config.phishing);
  const guessed = take(config.passwordGuessing);
  const newPhones = take(config.newPhone);
  const holidays = take(config.holiday);
  const forgotten = take(config.forgottenPassword);
  // The largest households, so a shared tablet has as many users as possible.
  const tabletHouseholds = scenario.truth.households
    .map((household, index) => ({ household, index }))
    .sort((a, b) => b.household.accountIds.length - a.household.accountIds.length || a.index - b.index)
    .slice(0, config.familyTablets);
  const special = new Set([...stuffing.flat(), ...phished, ...guessed, ...newPhones, ...holidays, ...forgotten,
    ...tabletHouseholds.flatMap(({ household }) => household.accountIds)]);

  // Everyone who logs in: where they live and work, and their own device.
  const people = [];
  const person = (accountId, home, work, dailyChance) => {
    const account = accountsById.get(accountId);
    const entry = { accountId, device: account.device_id ?? token("dev"), home, work, dailyChance };
    people.push(entry);
    return entry;
  };
  for (const accountId of scenario.truth.customers) {
    const city = random.pick(CITIES);
    // Accounts with a planted event always have a located home, so the event really tests the detector.
    const located = special.has(accountId) || undefined;
    const home = place(accountsById.get(accountId).ip, city, located);
    const work = random.chance(0.5) ? place(newIp(), city, located) : null;
    person(accountId, home, work, special.has(accountId) ? 1 : 0.85);
  }
  const householdPeople = scenario.truth.households.map((household) => {
    const city = random.pick(CITIES);
    const home = place(household.ip, city, true);
    return household.accountIds.map((accountId) =>
      person(accountId, home, random.chance(0.5) ? place(newIp(), city) : null, 0.85));
  });
  if (scenario.truth.office) {
    const city = random.pick(CITIES);
    const office = place(scenario.truth.office.ip, city, true);
    for (const accountId of scenario.truth.office.accountIds) {
      person(accountId, place(newIp(), city), office, 0.85);
    }
  }
  for (const ring of scenario.truth.rings) {
    const city = random.pick(CITIES);
    for (const accountId of ring.accountIds) {
      person(accountId, place(accountsById.get(accountId).ip, city), null, 0.5);
    }
  }
  const byAccount = new Map(people.map((p) => [p.accountId, p]));

  // Changes of habit, set before the daily traffic is drawn.
  const cases = [];
  for (const accountId of newPhones) {
    const p = byAccount.get(accountId);
    p.newPhone = { from: random.int(FIRST_EVENT_DAY, config.loginDays - 4), device: token("dev") };
    cases.push({ case: "new_phone", account_id: accountId });
  }
  for (const accountId of holidays) {
    const p = byAccount.get(accountId);
    const destination = random.pick(HOLIDAYS);
    const travelDays = Math.ceil(distanceKm(p.home, { latitude: destination[0], longitude: destination[1] })
      / FLIGHT_KM_PER_DAY) + 1;
    const stay = random.int(3, 5);
    const lastStart = config.loginDays - (2 * travelDays + stay) - 1;
    const depart = random.int(Math.min(FIRST_EVENT_DAY, lastStart), lastStart);
    p.holiday = {
      depart,
      arrive: depart + travelDays,
      leave: depart + travelDays + stay,
      back: depart + 2 * travelDays + stay,
      where: place(newIp(), destination, true),
    };
    cases.push({ case: "holiday", account_id: accountId });
  }
  for (const { household, index } of tabletHouseholds) {
    const tablet = token("dev");
    for (const p of householdPeople[index]) {
      p.tablet = tablet;
      cases.push({ case: "family_tablet", account_id: p.accountId });
    }
  }

  // Ordinary daily traffic.
  const ownLogin = (p, where, at, device) => {
    if (random.chance(0.04)) {
      record(p.accountId, device, where, false, at - random.int(10, 60) * SECOND);
    }
    return record(p.accountId, device, where, true, at);
  };
  for (const p of people) {
    for (let day = 0; day < config.loginDays; day++) {
      const h = p.holiday;
      if (h && ((day >= h.depart && day < h.arrive) || (day >= h.leave && day < h.back))) continue;
      if (!random.chance(p.dailyChance)) continue;
      const abroad = h && day >= h.arrive && day < h.leave;
      for (let s = random.int(1, 2); s > 0; s--) {
        const where = abroad ? h.where : p.work && random.chance(0.4) ? p.work : p.home;
        let device = p.newPhone && day >= p.newPhone.from ? p.newPhone.device : p.device;
        if (p.tablet && where === p.home && random.chance(0.35)) device = p.tablet;
        ownLogin(p, where, during(day), device);
      }
    }
  }

  // A forgotten password: failures a few seconds to two minutes apart, a reset, then a success.
  for (const accountId of forgotten) {
    const p = byAccount.get(accountId);
    let at = during(random.int(FIRST_EVENT_DAY, config.loginDays - 1));
    for (let f = random.int(4, 7); f > 0; f--) {
      record(accountId, p.device, p.home, false, at);
      at += random.int(10, 120) * SECOND;
    }
    record(accountId, p.device, p.home, true, at + random.int(1, 4) * MINUTE);
    cases.push({ case: "forgotten_password", account_id: accountId });
  }

  // Planted takeovers.
  const takeovers = [];
  const attackDay = () => random.int(FIRST_EVENT_DAY, config.loginDays - 3);
  const attackerPlace = () => {
    addresses += 1;
    return place(`203.0.113.${addresses % 250 + 2}`, random.pick(REMOTE), true);
  };
  const planted = (kind, accountId, device, attempts) => {
    const first = attempts.find((login) => login.success);
    takeovers.push({
      account_id: accountId,
      kind,
      attacker_device: device,
      first_bad_login_id: first.login_id,
      first_bad_at: first.occurred_at,
      attacker_login_ids: attempts.map((login) => login.login_id),
    });
  };

  // Stuffing also tries ordinary accounts, which it fails to get into. Rings use their own passwords.
  const ringAccounts = new Set(scenario.truth.rings.flatMap((ring) => ring.accountIds));
  const others = people.filter((p) => !special.has(p.accountId) && !ringAccounts.has(p.accountId));
  stuffing.forEach((victims) => {
    const device = token("dev");
    const where = attackerPlace();
    const misses = random.shuffle(others).slice(0, Math.max(0, config.stuffingTargets - victims.length));
    const targets = random.shuffle([...victims.map((id) => ({ id, success: true })),
      ...misses.map((p) => ({ id: p.accountId, success: false }))]);
    let at = during(attackDay());
    for (const target of targets) {
      const login = record(target.id, device, where, target.success, at);
      if (target.success) planted("credential_stuffing", target.id, device, [login]);
      at += random.int(5, 40) * SECOND;
    }
  });
  for (const accountId of phished) {
    const p = byAccount.get(accountId);
    const device = token("dev");
    const where = attackerPlace();
    const day = attackDay();
    const own = ownLogin(p, p.home, dayStart(day) + random.int(8 * 60, 16 * 60) * MINUTE, p.device);
    let at = Date.parse(own.occurred_at) + random.int(20, 90) * MINUTE;
    const attempts = [];
    for (let n = random.int(1, 3); n > 0; n--) {
      attempts.push(record(accountId, device, where, true, at));
      at += random.int(10, 120) * MINUTE;
    }
    planted("phishing", accountId, device, attempts);
  }
  for (const accountId of guessed) {
    const device = token("dev");
    // Half the guessers hide behind an address with no known location.
    const where = random.chance(0.5) ? attackerPlace() : place(`198.51.100.${random.int(2, 250)}`, REMOTE[0], false);
    let at = during(attackDay());
    const attempts = [];
    for (let f = random.int(6, 12); f > 0; f--) {
      attempts.push(record(accountId, device, where, false, at));
      at += random.int(20, 90) * SECOND;
    }
    attempts.push(record(accountId, device, where, true, at + random.int(10, 60) * SECOND));
    planted("password_guessing", accountId, device, attempts);
  }

  logins.sort((a, b) => compare(a.occurred_at, b.occurred_at) || compare(a.login_id, b.login_id));
  takeovers.sort((a, b) => compare(a.account_id, b.account_id));
  cases.sort((a, b) => compare(a.case, b.case) || compare(a.account_id, b.account_id));
  return { logins, takeovers, cases };
}

/** Great-circle distance between two located places, in km. */
export function distanceKm(a, b) {
  const toRad = Math.PI / 180;
  const dLat = (b.latitude - a.latitude) * toRad;
  const dLon = (b.longitude - a.longitude) * toRad;
  const h = Math.sin(dLat / 2) ** 2
    + Math.cos(a.latitude * toRad) * Math.cos(b.latitude * toRad) * Math.sin(dLon / 2) ** 2;
  return 2 * 6371 * Math.asin(Math.min(1, Math.sqrt(h)));
}

function validate(config, truth) {
  for (const key of Object.keys(LOGIN_DEFAULTS)) {
    if (!Number.isInteger(config[key]) || config[key] < 0) {
      throw new Error(`${key} must be a whole number of 0 or more`);
    }
  }
  if (config.loginDays < 14) {
    throw new Error("loginDays must be at least 14, so every planted event follows a login history");
  }
  if (config.stuffingMinVictims < 1 || config.stuffingMaxVictims < config.stuffingMinVictims) {
    throw new Error("stuffing victims need 1 <= stuffingMinVictims <= stuffingMaxVictims");
  }
  if (config.stuffingTargets < config.stuffingMaxVictims) {
    throw new Error("stuffingTargets must be at least stuffingMaxVictims: every victim is a target");
  }
  const needed = config.stuffingCampaigns * config.stuffingMaxVictims + config.phishing + config.passwordGuessing
    + config.newPhone + config.holiday + config.forgottenPassword;
  if (needed > truth.customers.length) {
    throw new Error(`the login roles need up to ${needed} customers, but the scenario has ${truth.customers.length}`);
  }
  if (config.familyTablets > truth.households.length) {
    throw new Error(`familyTablets is ${config.familyTablets}, but the scenario has ${truth.households.length} households`);
  }
}

function round(value) {
  return Math.round(value * 10_000) / 10_000;
}

/** Plain code-unit comparison, so the order never depends on the machine's locale. */
function compare(a, b) {
  return a < b ? -1 : a > b ? 1 : 0;
}
