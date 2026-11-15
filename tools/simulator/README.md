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

This writes four files, all byte-identical for the same seed:

| File | Contents |
|---|---|
| `accounts.jsonl` | Every sign-up, in the order `run` sends them |
| `constraints.jsonl` | Verified-distinct pairs (empty: the simulator does not model identity checks yet) |
| `transfers.jsonl` | Money movement over the days after the sign-ups, in time order |
| `truth.json` | Who really owns each account, and who is a mule |

Transfers come from their own random stream, so `accounts.jsonl` is the same with
or without them, and the linking results in `docs/ROADMAP.md` still hold. `run`
does not send transfers yet; the service has no endpoint for them (#47).

`transfers.jsonl`, one transfer per line. Accounts starting with `ext_` are
outside the bank:

```json
{"transfer_id":"s3_t000412","from_account":"ext_victim_s3_0_2","to_account":"s3_00075","amount_cents":184233,"occurred_at":"2026-11-03T10:41:00Z"}
```

`truth.json` (schema version 2) lists every real-world actor:

```json
{
  "schema_version": 2,
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
  "transfer_cases": [{"case": "small_business", "account_id": "s3_00012"}]
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
- Version 2 only adds fields. Readers of version 1 (the Go evaluator and the
  engine's `Truth`) read it unchanged.

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

The tests cover determinism, population shapes, that every ring is linkable,
the HTTP client (with a fake `fetch`), bounded concurrency, and the scoring
report.
