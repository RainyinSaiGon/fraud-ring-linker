# Traffic simulator

Sends a realistic day of synthetic sign-ups to the fraud-ring-linker service,
then scores the result against the fraud rings it planted. No dependencies:
Node 20 or newer.

## Run it

Start the service first, for example without Docker:

```bash
cd java && ./gradlew :service:bootRun --args='--spring.profiles.active=h2'
```

Then, from this folder:

```bash
npm run simulate                                  # defaults: seed 1, 200 customers, 8 rings
npm run simulate -- --seed 3 --rings 12 --base-url http://localhost:8080
npm run extra-ring-member -- --ring 0             # one more sign-up using ring 0's card and phone
```

Example output:

```text
Accounts sent:           290
Failed requests:         0
Rings planted:           8
Rings fully linked:      7
Rings split apart:       1
Legit accounts linked:   0
Clusters mixing rings:   0
```

Ground truth (which accounts belong to which ring) is written to `out/truth.json`.

## Export a scenario without a service

```bash
npm run export -- --seed 3 --out-dir out/seed-3
npm run export -- --seed 3 --mule-chains 4 --out-dir out/seed-3-mules
```

This writes five files, all byte-identical for the same seed:

| File | Contents |
|---|---|
| `accounts.jsonl` | Every sign-up, in the order `run` sends them |
| `constraints.jsonl` | Verified-distinct pairs (empty: the simulator does not model identity checks yet) |
| `transfers.jsonl` | Money movement over the days after the sign-ups, in time order |
| `logins.jsonl` | Logins over the three weeks after the sign-ups, in time order |
| `truth.json` | Who really owns each account, who is a mule, and who was taken over |

Transfers and logins each come from their own random stream, so `accounts.jsonl`
is the same with or without them, `transfers.jsonl` is the same with or without
logins, and the results in `docs/ROADMAP.md` still hold. `run` sends the sign-ups
first, then the transfers, and reports how many planted mules the service flagged
and whether it flagged any hard legitimate case. It does not send logins yet,
because the service does not accept them.

`transfers.jsonl`, one transfer per line. Accounts starting with `ext_` are
outside the bank:

```json
{"transfer_id":"s3_t000412","from_account":"ext_victim_s3_0_2","to_account":"s3_00075","amount_cents":184233,"occurred_at":"2026-11-03T10:41:00Z"}
```

`logins.jsonl`, one login per line. The location is where IP geolocation puts
the address, so every login from one IP has the same place; a few addresses
have none, and both coordinates are then `null`:

```json
{"login_id":"s3_l0002419","account_id":"s3_00085","device_id":"dev_s3_86_643249","ip":"172.16.0.44","success":true,"occurred_at":"2026-11-03T07:00:26Z","latitude":-34.8714,"longitude":138.6616}
```

`truth.json` (schema version 3) lists every real-world actor:

```json
{
  "schema_version": 3,
  "seed": 3,
  "run_id": "s3",
  "actors": [
    {"actor_id": "ring_0", "kind": "fraud_ring", "account_ids": ["s3_00231", "s3_00232", "s3_00233"],
     "shared": {"card": "card_...", "device": "dev_..."}},
    {"actor_id": "person_s3_00007", "kind": "household_member", "group": "household_3",
     "account_ids": ["s3_00007"]}
  ],
  "account_to_actor": {"s3_00007": "person_s3_00007"},
  "fraud_actor_ids": ["ring_0"],
  "mule_chains": [{"chain_id": "mule_chain_0", "account_ids": ["s3_00075", "s3_00153"],
                   "victims": 8, "cash_out": "ext_cashout_s3_0"}],
  "mule_account_ids": ["s3_00075", "s3_00153"],
  "transfer_cases": [{"case": "small_business", "account_id": "s3_00012"}],
  "takeovers": [{"account_id": "s3_00011", "kind": "phishing", "attacker_device": "dev_s3_l35_517705",
                 "first_bad_login_id": "s3_l0007772", "first_bad_at": "2026-11-14T13:35:00Z",
                 "attacker_login_ids": ["s3_l0007772", "s3_l0007773", "s3_l0007774"]}],
  "takeover_account_ids": ["s3_00011"],
  "login_cases": [{"case": "family_tablet", "account_id": "s3_00203"}]
}
```

- `kind` is `customer`, `household_member`, `office_worker` or `fraud_ring`, so a
  cost report can break mistakes down by population.
- `group` names the shared network (`household_N` or `office`).
- `account_to_actor` and `fraud_actor_ids` are derived from `actors`, and keep
  the file readable by the Go evaluator (`go run ./cmd/evaluate` in `go/`).
- `mule_chains` lists each chain's accounts in hop order. Mules are recruited
  customers, so they keep their `customer` actor.
- `transfer_cases` names the hard legitimate accounts a mule detector must not
  flag: `small_business`, `own_money` and `payday_bills`.
- `takeovers` lists each planted takeover: the attacker's device, their first
  successful login on the account (the moment it was taken over), and every
  attacker login on it, failures included. A taken-over account still belongs
  to its customer's actor.
- `login_cases` names the hard legitimate accounts a takeover detector must not
  flag: `new_phone`, `holiday`, `forgotten_password` and `family_tablet`.
- Versions 2 and 3 only add fields. Readers of version 1 (the Go evaluator and
  the engine's `Truth`) and of version 2 (the engine's `MuleTruth`) read them
  unchanged.

## What it sends

| Population | Shape | A correct linker should |
|---|---|---|
| Customers | Own email, phone, IP and (usually) card | leave them alone |
| Households | 2-4 people behind one home IP, own phones and cards | **not** link them: a shared IP is not enough |
| Office | Many unrelated people behind one busy IP | **not** link them |
| Fraud rings | 3-6 accounts; each reuses the ring's card, or its phone plus a near-identical email | put each ring in one cluster |

Money movement (`transfers.jsonl`):

| Population | Shape | A correct mule detector should |
|---|---|---|
| Everyone | A salary from outside the bank, 1-3 bills, sometimes a payment to another customer; households pay each other rent shares | leave them alone |
| Mule chains (2 by default) | 2-3 recruited customers. 5-9 outside victims pay the first hop, each hop passes 85-98% of every payment on within 5-40 min, and the last hop pays an outside cash-out | flag every hop |
| Small businesses (2) | Paid by 15-30 payers in one day; pay a supplier 40-60% the next day | **not** flag them: fan-in alone |
| Own money (3) | Move 85-95% of the salary to their own savings at another bank within 20 min | **not** flag them: pass-through alone |
| Payday bills (5) | Pay 3-5 bills within 50 min of payday, 40-70% of the salary | **not** flag them |

Logins (`logins.jsonl`), over 21 days. Planted events start on day 8 or later,
after a login history:

| Population | Shape | A correct takeover detector should |
|---|---|---|
| Everyone | 1-2 logins on most days from their own device, at home or sometimes at work in the same city; now and then one mistyped password. Rings log in from the ring's devices | leave them alone |
| Credential stuffing (1 campaign) | One attacker device tries 30 accounts once each within minutes, and gets into 4-6 of them | flag the 4-6 it got into |
| Phishing (3) | 20-90 min after the customer's own login, a new device over 10,000 km away logs in, 1-3 times | flag them |
| Password guessing (3) | 6-12 failed logins from a new device, 20-90 s apart, then a success | flag them |
| New phone (4) | Switches to a new device for good, from the same places | **not** flag them: a new device alone |
| Holiday (3) | Flies over 300 km at a possible speed, logs in abroad for 3-5 days, then comes home | **not** flag them |
| Forgotten password (4) | 4-7 failed logins on their own device, a reset, then a success | **not** flag them: a burst on a known device |
| Family tablet (2 households) | The largest households share one tablet at home, as well as their own phones | **not** flag them. Households are not linked, so a tablet used by 4 or more members looks shared |

The same seed always produces the same traffic. IDs carry a per-run prefix,
so repeated runs against one service never collide. All data is fake.

## What it found

Three seeded runs gave these results:

| Mode | Rings recovered | Legitimate accounts wrongly linked |
|---|---|---|
| Batch linking | 24 of 24 | 0 |
| Streaming (the service) | 22 of 24 | 0 |

Both streaming misses were caused only by arrival order. A ring member that
arrives before anyone it can link to starts its own cluster, and streaming
never merges existing clusters. See Stage 5 in `docs/ROADMAP.md`.

Building the simulator also turned up a bug in the generator itself. Some
ring members carried a signal no other member shared, so those rings could
never be linked. A test now checks that every planted ring is linkable.

## Tests

```bash
npm test
```

The tests cover determinism, the shapes of sign-ups, transfers and logins, that
adding transfers or logins never changes the earlier files, that every ring is linkable,
the HTTP client (with a fake `fetch`), bounded concurrency, and the scoring
report.
