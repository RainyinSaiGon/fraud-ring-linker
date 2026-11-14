// Builds one synthetic day of sign-ups plus the ground truth behind it.
//
// Populations, chosen to test both kinds of mistake:
// - customers:  ordinary people. Must stay unlinked.
// - households: 2-4 people behind one home IP, with their own phones and cards.
//               Sharing an IP alone must NOT link them (false-positive trap).
// - office:     many unrelated people behind one busy office IP (false-positive trap).
// - rings:      one actor opening several accounts. Every member reuses the
//               ring's card, or the ring's phone plus a near-identical email,
//               so a correct linker should put each ring in one cluster.
//
// All data is fake. Account IDs carry a run prefix so repeated runs against one
// service never collide.

import { createRandom } from "./random.mjs";

const FIRST = ["liam", "olivia", "noah", "ava", "minh", "linh", "aarav", "priya", "jack", "chloe", "wei", "mei",
  "lucas", "zoe", "ethan", "isla", "khoa", "ngoc", "omar", "sofia"];
const LAST = ["smith", "nguyen", "tran", "patel", "brown", "wilson", "chen", "le", "taylor", "kumar", "pham",
  "jones", "williams", "singh", "martin", "lee"];
const DOMAINS = ["gmail.com", "outlook.com", "yahoo.com.au", "icloud.com", "bigpond.com"];

export const DEFAULTS = Object.freeze({
  seed: 1,
  customers: 200,
  households: 12,
  officeWorkers: 15,
  rings: 8,
  ringMinSize: 3,
  ringMaxSize: 6,
  start: "2026-11-01T08:00:00Z",
});

export function buildScenario(options = {}) {
  const config = { ...DEFAULTS, ...options };
  validate(config);
  const random = createRandom(config.seed);
  const prefix = options.runId ?? `s${config.seed}`;
  const start = Date.parse(config.start);
  let counter = 0;
  const usedEmails = new Set();

  const nextId = () => `${prefix}_${String(counter++).padStart(5, "0")}`;
  const timeAt = (minutesAfterStart) => new Date(start + minutesAfterStart * 60_000).toISOString().replace(".000Z", "Z");
  // Each person gets a fresh /24 in 10.0.0.0/8 so unrelated people never share a subnet by accident.
  let subnet = 0;
  const ownIp = () => {
    subnet += 1;
    return `10.${(subnet >> 8) & 255}.${subnet & 255}.${random.int(2, 250)}`;
  };
  const token = (kind) => `${kind}_${prefix}_${counter}_${random.int(100000, 999999)}`;
  const uniqueEmail = (base) => {
    let email = `${base}@${random.pick(DOMAINS)}`;
    while (usedEmails.has(email)) {
      email = `${base}${random.int(10, 9999)}@${random.pick(DOMAINS)}`;
    }
    usedEmails.add(email);
    return email;
  };
  const personName = () => `${random.pick(FIRST)}.${random.pick(LAST)}${random.int(1, 999)}`;

  const accounts = [];
  const truth = { seed: config.seed, runId: prefix, rings: [], households: [], office: null, customers: [] };

  const addAccount = (account) => {
    accounts.push(account);
    return account.account_id;
  };

  for (let i = 0; i < config.customers; i++) {
    const id = addAccount({
      account_id: nextId(),
      email: uniqueEmail(personName()),
      device_id: random.chance(0.85) ? token("dev") : null,
      ip: ownIp(),
      payment_fingerprint: random.chance(0.6) ? token("card") : null,
      created_at: timeAt(random.int(0, 24 * 60)),
    });
    truth.customers.push(id);
  }

  for (let h = 0; h < config.households; h++) {
    const homeIp = ownIp();
    const members = [];
    const size = random.int(2, 4);
    for (let m = 0; m < size; m++) {
      members.push(addAccount({
        account_id: nextId(),
        email: uniqueEmail(personName()),
        device_id: token("dev"),
        ip: homeIp,
        payment_fingerprint: random.chance(0.7) ? token("card") : null,
        created_at: timeAt(random.int(0, 24 * 60)),
      }));
    }
    truth.households.push({ ip: homeIp, accountIds: members });
  }

  if (config.officeWorkers > 0) {
    const officeIp = ownIp();
    const members = [];
    for (let w = 0; w < config.officeWorkers; w++) {
      members.push(addAccount({
        account_id: nextId(),
        email: uniqueEmail(personName()),
        device_id: token("dev"),
        ip: officeIp,
        payment_fingerprint: random.chance(0.5) ? token("card") : null,
        // Office sign-ups spread over the working day, not one burst.
        created_at: timeAt(random.int(60, 9 * 60)),
      }));
    }
    truth.office = { ip: officeIp, accountIds: members };
  }

  for (let r = 0; r < config.rings; r++) {
    const ring = makeRing(r);
    truth.rings.push(ring);
  }

  function makeRing(index) {
    const card = token("card");
    const device = token("dev");
    const base = `${random.pick(FIRST)}${random.pick(LAST)}${random.int(10, 99)}`;
    const domain = random.pick(DOMAINS);
    const startMinute = random.int(0, 23 * 60);
    const size = random.int(config.ringMinSize, config.ringMaxSize);
    const members = [];
    for (let m = 0; m < size; m++) {
      const usesCard = m === 0 || random.chance(0.75);
      members.push(addAccount({
        account_id: nextId(),
        // A one-character change keeps the email within one edit of the ring's base name.
        email: `${base}${m === 0 ? "" : String.fromCharCode(97 + ((m - 1) % 26))}@${domain}`,
        // The first member always carries both the card and the phone, so a phone-only
        // member always has someone to share the phone with.
        device_id: m === 0 || !usesCard || random.chance(0.5) ? device : token("dev"),
        ip: ownIp(),
        payment_fingerprint: usesCard ? card : null,
        created_at: timeAt(startMinute + random.int(0, 60)),
      }));
    }
    return { ringId: `ring_${index}`, card, device, emailBase: `${base}@${domain}`, accountIds: members };
  }

  return { accounts: random.shuffle(accounts), truth };
}

/** A new sign-up that reuses a ring's card and phone, to show a known ring cannot simply open another account. */
export function extraRingMember(ring, accountId, createdAt) {
  return {
    account_id: accountId,
    email: ring.emailBase.replace("@", "z@"),
    device_id: ring.device,
    ip: "10.250.250.9",
    payment_fingerprint: ring.card,
    created_at: createdAt,
  };
}

function validate(config) {
  for (const key of ["customers", "households", "officeWorkers", "rings"]) {
    if (!Number.isInteger(config[key]) || config[key] < 0) {
      throw new Error(`${key} must be a whole number of 0 or more`);
    }
  }
  if (config.ringMinSize < 2 || config.ringMaxSize < config.ringMinSize) {
    throw new Error("ring sizes need 2 <= ringMinSize <= ringMaxSize");
  }
  if (config.ringMaxSize > 8) {
    // Past 8 accounts a shared card stops counting as rare, by design.
    throw new Error("ringMaxSize above 8 makes the shared card too common to link on");
  }
  if (Number.isNaN(Date.parse(config.start))) {
    throw new Error("start must be an ISO date-time");
  }
}
