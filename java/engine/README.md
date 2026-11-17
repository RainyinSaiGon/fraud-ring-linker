# fraud-ring-linker engine

Java 21 port of the Go linker, with no framework dependencies. For the same
input, both produce byte-identical output (checked by
[`tools/parity`](../../tools/parity/)). The REST service in
[`../service`](../service/) is built on this module.

## Build

```bash
./gradlew :engine:build   # from java/: compile (warnings are errors), test, build jars
```

The runnable CLI jar is `engine/build/libs/fraud-ring-linker.jar`, with Jackson
bundled.

## Batch mode

```bash
java -jar engine/build/libs/fraud-ring-linker.jar link \
  --accounts accounts.jsonl --constraints constraints.jsonl --output clusters.json
```

Every input account appears in exactly one cluster. Cluster IDs (`c1`, `c2`, ...)
follow each cluster's smallest account ID, so the same input always gives the
same output.

## Stream mode

```bash
java -jar engine/build/libs/fraud-ring-linker.jar stream \
  --accounts accounts.jsonl --constraints constraints.jsonl < new-accounts.jsonl
```

After linking the initial accounts, it reads one account per stdin line and
writes (and flushes) one line per account:

```json
{"account_id":"acc_new01","cluster_id":"c12","confidence":0.9134}
```

Errors go to stderr only, and the exit code is 1.

### Merge candidates

Streaming never merges two existing clusters, so a ring whose members arrive in
an unlucky order stays split. For callers that can ask a person (the REST
service), `StreamLinker.assignWithCandidates` returns the same assignment plus
**merge candidates**: the other existing clusters the account would also have
joined, best first, each with its score and rule.

- A candidate is listed only if merging it with the assigned cluster keeps every
  verified-distinct pair apart.
- Nothing is merged automatically. `LinkState.mergeClusters(a, b, confidence)`
  merges two clusters after a review, keeps the older cluster ID, and refuses
  (changing nothing) if a verified-distinct pair would end up together.
- The CLI output is unchanged, so it stays byte-identical with the Go version.

## Cost mode

Scores a labelled dataset (for example one exported by `tools/simulator`) in
batch mode, stream mode, and stream mode with every merge proposal accepted, and
prices the mistakes:

```bash
java -jar engine/build/libs/fraud-ring-linker.jar cost \
  --accounts accounts.jsonl --constraints constraints.jsonl --truth truth.json \
  [--wrong-link-cost 50] [--missed-ring-cost 2000] [--format text|json] [--write-clusters DIR]
```

Part of the output for simulator seed 3:

```text
Costs: $50 per wrongly linked account, $2,000 per missed ring

                                          batch       stream     stream + proposals
Fraud rings fully linked                  8 / 8        7 / 8                  8 / 8
Multi-account actors split                    0            1                      0
Wrongly linked accounts                       0            0                      0
Cost                                         $0       $2,000                     $0

Merge proposals opened: 1 (1 merged when accepted)
```

- **Batch** links everything at once. **Stream** starts empty and adds accounts
  one by one in file order, the way the REST service does.
- **Stream + proposals** is the same stream, but each merge proposal is accepted
  the moment it opens. It is the best case for analyst-reviewed merges, and the
  proposal count is the review workload.
- Metrics and costs use the same definitions as the Go evaluator
  (`go/internal/evaluation`); `Cost` is its general proxy, `Cost, fraud-list view` its literal proxy.
- With a versioned `truth.json`, wrongly linked accounts are also broken down by
  population (customer, household member, office worker).
- `--write-clusters DIR` saves all three clusterings, for example to score them with the Go evaluator.

## Sweep mode

Finds the cheapest merge score floor and stream threshold over several labelled
datasets (each a directory with `accounts.jsonl`, `constraints.jsonl` and
`truth.json`):

```bash
java -jar engine/build/libs/fraud-ring-linker.jar sweep --datasets out/seed-1,out/seed-2   [--merge-scores 0.55,0.60,...] [--stream-thresholds 0.70,0.74,...]   [--costs 50:2000,50:500,50:50] [--format text|json]
```

- It prints a grid of mistakes (wrongly linked accounts / multi-account actors
  split) for every setting, a second grid for stream + proposals (adding the
  number of proposals opened), then the cheapest setting at each price pair.
- The current setting is always in the grid, and wins ties, so a tie never
  suggests a change.
- `tools/cost-sweep/sweep.sh [seedCount]` exports simulator seeds and runs the
  sweep in one command.

## Mules mode

Measures mule detection against the simulator's planted mule chains, then
sweeps its main thresholds. Each dataset directory also needs `transfers.jsonl`
and a version 2 `truth.json`, as `tools/simulator export` writes them:

```bash
java -jar engine/build/libs/fraud-ring-linker.jar mules --datasets out/seed-1,out/seed-2 \
  [--fan-in-payers 3,4,5,6,8] [--pass-through-shares 0.6,0.7,0.8,0.9] \
  [--flag-thresholds 0.7,0.75,0.8,0.85,0.9] [--costs 50:2000,50:500,50:50] [--format text|json]
```

- It prints mules found and missed at today's setting, legitimate accounts
  flagged by hard case and by the signals that fired, a grid of mules flagged /
  legitimate accounts flagged, and the cheapest setting at each price pair
  (`falseFlag:missedMule`).
- Only accounts in `accounts.jsonl` count as held, as in the service.
- `tools/mule-sweep/sweep.sh [seedCount]` exports simulator seeds and runs it in
  one command. Results are in Stage 6 of `docs/ROADMAP.md`.

## Takeovers mode

Measures takeover detection against the simulator's planted takeovers, then
sweeps its main thresholds. Each dataset directory also needs `logins.jsonl` and
a version 3 `truth.json`, as `tools/simulator export` writes them:

```bash
java -jar engine/build/libs/fraud-ring-linker.jar takeovers --datasets out/seed-1,out/seed-2 \
  [--min-history 3,5,8] [--max-speeds 500,900,1500] [--burst-failures 3,5,8] \
  [--shared-device-accounts 3,4,6] [--flag-thresholds 0.5,0.6,0.7,0.8,0.9] \
  [--costs 50:5000,50:1000,50:200] [--format text|json]
```

- It prints takeovers found and missed at today's setting (by kind), legitimate
  accounts flagged by hard case and by the signals that fired, the review count,
  and how soon each takeover was flagged: the attacker's successful logins up to
  the flag (1 means the flag came with the first one).
- The sweep changes one threshold at a time, with the others at today's value,
  and tries each at every flag threshold. Then it names the cheapest setting at
  each price pair (`falseFlag:missedTakeover`).
- Only accounts in `accounts.jsonl` count as held, as in the service.
- `tools/takeover-sweep/sweep.sh [seedCount]` exports simulator seeds and runs it
  in one command. Results are in Stage 7 of `docs/ROADMAP.md`.

## Mule scoring

`MuleScorer` (package `mules`, Java only) scores how mule-like each account's
money movement looks. It's a library for the service; no CLI command yet.

```java
Map<String, MuleScore> scores = new MuleScorer(MuleConfig.defaults())
        .scoreAll(transfers, MuleScorer.sameCluster(linkState));
```

| Reason | Fires when | Weight |
|---|---|---|
| `fan_in` | 5+ distinct unrelated payers within 24 h | 0.6 |
| `pass_through` | 80%+ of the money received leaves again within 60 min, to unrelated accounts | 0.7 |
| `chain` | money links the account to another pass-through account | 0.5 |

- Weights combine with a noisy-OR, and a score of 0.8 or more flags the account.
  No single reason reaches 0.8, so a busy small business (fan-in only) or a
  payday bill payer (pass-through only) is not flagged. Two reasons together are.
- **Unrelated** means not in the same cluster. A household, or one person moving
  money between their own accounts, is never fan-in or pass-through.
- Money in is matched against money out oldest first. Money that leaves after
  the window counts as the account's own balance.
- A transfer may name accounts the bank doesn't hold, such as an external victim.
- Scoring only reads cluster lookups; transfers never change a cluster.
- The defaults are starting points, to be tuned against the simulator (#49).

## Takeover scoring

`TakeoverScorer` (package `takeover`, Java only) scores how much each account's
logins look like a takeover of a real customer's account. It's a library for
the service; no CLI command yet.

```java
Map<String, TakeoverScore> scores = new TakeoverScorer(TakeoverConfig.defaults())
        .scoreAll(logins, MuleScorer.sameCluster(linkState));
```

| Reason | Fires when | Weight |
|---|---|---|
| `new_device` | a successful login from a device the account never used, after 5+ earlier logins | 0.4 |
| `impossible_travel` | two located successful logins 300+ km apart, faster than 900 km/h | 0.7 |
| `failed_burst_then_success` | 5+ failed logins within 15 min, then a success | 0.7 |
| `failed_burst` | 5+ failed logins within 15 min, no success | 0.3 |
| `shared_device` | the login's device logged in to 4+ unrelated accounts within 24 h | 0.7 |

- **Signals combine only within one incident** (24 h by default). A new phone in
  March and a forgotten password in May are two ordinary events. An account's
  score is its worst incident's noisy-OR, one weight per kind of signal.
- 0.8 or more flags the account. No single signal reaches it, so a new phone, a
  holiday flight, a forgotten password or a shared family tablet alone isn't
  flagged. A new device plus impossible travel, a guessed password or a
  stuffing device (0.82) is.
- `reachedAt` says when the worst incident reached its score, so an evaluation
  can measure how soon a takeover is caught.
- Logins carry their location from upstream, when known; logins without one
  skip the travel check. A device shared inside one cluster isn't "shared".
- Scoring only reads cluster lookups; logins never change a cluster.
- The defaults are starting points, to be tuned against the simulator (#60).

## Input format

`accounts.jsonl`, one object per line:

```json
{"account_id":"acc_1","email":"john.doe@gmail.com","device_id":null,"ip":"203.0.113.7","payment_fingerprint":"pf_a91x","created_at":"2026-03-14T09:22:11Z"}
```

`constraints.jsonl`, one object per line:

```json
{"account_a":"acc_1","account_b":"acc_2","relation":"verified_distinct"}
```

- Blank lines are skipped. Unknown fields are ignored.
- `account_id` must be present and unique. `created_at` must be RFC 3339.
- `device_id` and `payment_fingerprint` may be null. A malformed email or IP is
  treated as a missing signal, not an error.
- Constraints may name accounts that only arrive later in stream mode.

## Code map

| Package | Responsibility |
|---|---|
| `domain` | Input and output records |
| `normalize` | Email canonicalization (Gmail dots and `+tags`), offline IP parsing, edit distance |
| `index` | `SignalStore`: signal frequencies and inverted indexes for candidate lookup |
| `scoring` | `Scorer`: rarity-weighted evidence and the named merge rules |
| `clustering` | `BatchLinker`, constraint-aware union-find, `LinkState` lookups and runtime constraints, `ClusterExplainer` |
| `streaming` | `StreamLinker`: one-event assignment over the live state |
| `mules` | `MuleScorer`: fan-in, pass-through and chain detection from transfers |
| `takeover` | `TakeoverScorer`: new device, impossible travel, failed bursts and shared devices from logins |
| `io` | JSONL reading and fixed-layout JSON writing |
| `cli` | `LinkerCli` command-line entry point |

## Notes on the port

- **Text and IPs:** a few JDK defaults would silently change results, so the
  port avoids them. `Text` uses the Unicode white-space definition (a
  non-breaking space counts). `IpAddresses` parses IP literals itself, because
  `InetAddress.getByName` can fall back to a DNS lookup on bad input.
- **Output:** JSON is written by hand in a fixed layout, so outputs can be
  compared byte for byte with the Go version.
- **Threading:** the engine is single-threaded by design, like the Go version.
  Even lookups compress union-find paths, so callers must serialize all access;
  the service does this with one lock.
- **Explanations:** cluster explanations re-score pairs with *current* signal
  frequencies, so an old link can look weaker after its signal became common.
