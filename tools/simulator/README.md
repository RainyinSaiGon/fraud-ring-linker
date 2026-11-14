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
```

This writes three files, all byte-identical for the same seed:

| File | Contents |
|---|---|
| `accounts.jsonl` | Every sign-up, in the order `run` sends them |
| `constraints.jsonl` | Verified-distinct pairs (empty: the simulator does not model identity checks yet) |
| `truth.json` | Who really owns each account |

`truth.json` (schema version 1) lists every real-world actor:

```json
{
  "schema_version": 1,
  "seed": 3,
  "run_id": "s3",
  "actors": [
    {"actor_id": "ring_0", "kind": "fraud_ring", "account_ids": ["s3_00231", "s3_00232", "s3_00233"],
     "shared": {"card": "card_...", "device": "dev_..."}},
    {"actor_id": "person_s3_00007", "kind": "household_member", "group": "household_3",
     "account_ids": ["s3_00007"]}
  ],
  "account_to_actor": {"s3_00007": "person_s3_00007"},
  "fraud_actor_ids": ["ring_0"]
}
```

- `kind` is `customer`, `household_member`, `office_worker` or `fraud_ring`, so a
  cost report can break mistakes down by population.
- `group` names the shared network (`household_N` or `office`).
- `account_to_actor` and `fraud_actor_ids` are derived from `actors`, and keep
  the file readable by the Go evaluator (`go run ./cmd/evaluate` in `go/`).

## What it sends

| Population | Shape | A correct linker should |
|---|---|---|
| Customers | Own email, phone, IP and (usually) card | leave them alone |
| Households | 2-4 people behind one home IP, own phones and cards | **not** link them: a shared IP is not enough |
| Office | Many unrelated people behind one busy IP | **not** link them |
| Fraud rings | 3-6 accounts; each reuses the ring's card, or its phone plus a near-identical email | put each ring in one cluster |

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
