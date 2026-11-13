# Design Decisions

## Assumptions

### Data and validation

- `account_id` is a case-sensitive opaque identifier. I trim surrounding
  whitespace, require a non-empty value, and reject duplicates in both the
  initial file and the stream.
- `created_at` is required and must decode as a Go `time.Time`. A missing or
  invalid timestamp is a structural error because time is part of the supplied
  schema and silently replacing it would change behavioral evidence.
- Missing, null, empty, unusual, or malformed fraud signals are data quality
  conditions rather than structural errors. Empty device/payment values are not
  indexed. Invalid IPs provide no network evidence. An unusual email remains
  usable as an opaque exact value, but receives no domain-aware fuzzy behavior.
- Blank JSONL lines are ignored and unknown JSON fields are tolerated for
  forward compatibility. Records up to 4 MiB are supported.
- Initial and streamed accounts are unique. A duplicate event fails rather than
  silently changing an earlier assignment or indexing the event twice.

### Constraints

- The only supported relation is `verified_distinct`. Unsupported relations
  fail instead of being silently weakened.
- Constraints are symmetric and deduplicated. A self-constraint is
  unsatisfiable and fails early.
- A constraint may name an account absent from the initial dataset. It remains
  in the adjacency index so it becomes effective if that ID arrives later.
- Constraint correctness is a harder requirement than any similarity score. A
  merge that would put any verified-distinct pair in one component is rejected.

### Online behavior and output

- Stream events are processed sequentially in input order.
- A streamed account can join one existing cluster or create a singleton; it
  never merges two old clusters. Cluster IDs therefore remain stable, but batch
  and stream-over-the-same-records are not guaranteed to produce identical
  partitions.
- Rarity for a stream event uses the projected count including that event.
  Frequencies and indexes are committed after the decision. Earlier decisions
  are not retroactively rescored when a value later becomes common.
- Confidence is linkage strength, not a calibrated probability of shared
  identity or fraud. A singleton confidence of `1.0` means no uncertain
  positive-link claim was needed.
- The hidden grader's exact conversion from a clustering to “affected
  customers” and “missed rings” is unspecified. The included evaluator reports
  multiple transparent metrics and labels both cost calculations as proxies.

## Algorithm choice

### Options considered

I considered:

1. all-pairs fuzzy comparison followed by connected components;
2. a learned pair classifier;
3. unconstrained connected components with constraint repair afterward;
4. a sparse, heuristic identity graph with constraints enforced during union.

All-pairs work is quadratic and cannot satisfy the scale or streaming contract.
A learned model is not defensible from 100 labeled sample records and adds
training, calibration, dependency, and interview-explainability risk.
Post-processing constraints can cut the wrong part of a component and makes the
hard invariant easy to violate. I chose option 4 because it is sparse,
deterministic, testable, low-latency, and every decision has an inspectable rule.

### Normalization

All paths share one normalizer:

- email: trim and lowercase; retain exact local/domain parts;
- Gmail/Googlemail: unify the domains, remove dots, and remove `+` tags;
- Outlook/Hotmail/Live and Proton: remove `+` tags but retain punctuation;
- comparison-only email skeleton: remove `.`, `_`, `-`, and whitespace;
- device/payment: trim but preserve opaque identifier case;
- IP: parse with `net/netip`, unmap IPv4-mapped addresses, and derive IPv4
  `/24` or IPv6 `/64`;
- fuzzy email: rune-safe Levenshtein distance at most one, same domain, with
  original plus one-deletion signatures used for blocking.

The skeleton and fuzzy forms are evidence, not global canonical identities.

### Frequencies, blocking, and hot keys

The engine builds frequencies and inverted indexes over canonical email,
device, payment, exact IP, network prefix, and domain-scoped email deletion
signatures. A signal can contribute during scoring even when it is too common
to generate pairs.

Default posting limits are:

| Blocking key | Maximum posting |
|---|---:|
| canonical email | 64 |
| device | 24 |
| payment | 32 |
| exact IP | 10 |
| network prefix | 32 |
| one-deletion email signature | 32 |

Email locals are capped at 64 runes for signature generation, and one account
retrieves at most 4,096 distinct candidates. These bounds prevent a common
office IP, carrier NAT, shared device, or popular signature from creating
quadratic work.

### Evidence and merge policy

I combine evidence with a noisy-OR formula, but use the maximum email variant
rather than summing exact, canonical, fuzzy, local, and skeleton matches. This
avoids double-counting one identity family. For a signal with frequency `f`,
the base weight is multiplied by:

```text
floor + (1 - floor) / sqrt(f)
```

The main base weights are exact email `0.98`, canonical email `0.95`, payment
`0.94`, device `0.76`, fuzzy email `0.62`, comparison-only local/skeleton
`0.60/0.56`, exact IP `0.12`, prefix `0.035`, and close time at most `0.08`.

A batch edge needs a score of at least `0.70` and one eligible composition:

- rare exact/canonical email (frequency at most 24);
- rare payment (frequency at most 8);
- bounded device plus independent email or payment evidence;
- bounded device plus uncommon exact IP plus close time;
- fuzzy/local/skeleton email plus payment;
- common email plus device/payment corroboration;
- a qualified diversified network cohort described below.

Device alone, exact IP alone, prefix alone, time alone, and IP plus time alone
are never eligible. Stream uses a higher irreversible threshold of `0.82`.

### Cost-aware network cohorts

The sample and business cost expose a gap in a strictly pairwise exact design:
a coordinated ring can rotate every identifier while retaining a local network
and behavioral pattern. I added two deliberately narrow group rules:

1. **Velocity cohort:** 3–8 accounts on distinct host addresses (at least 75%
   distinct) in one IPv4 `/24` or IPv6 `/64`, within one hour.
2. **Strong-seeded cohort:** a diversified prefix of 3–8 accounts with exactly
   one component containing at least two accounts already linked by
   non-network identity evidence. Remaining cohort members receive a lower
   `0.82` group edge.

The second rule cannot be bootstrapped by the first: a velocity-only cluster is
not an identity seed. Multiple established identities on one prefix make the
cohort ambiguous and disable expansion. Identity-seed status is retained
separately from the highest-scoring diagnostic rule, so a pair with both
velocity and strong payment/email evidence does not lose the latter fact.
These rules are intentionally recall-oriented under the stated roughly 40:1
missed-ring/customer cost ratio. They are not claims that a shared subnet
proves identity.

### Constraint-aware clustering

Eligible edges are sorted by descending score, then lexicographically by their
endpoint IDs. Before each union, the DSU scans the smaller component and checks
every member's constraint neighbors. A merge is rejected if any neighbor is in
the other component.

For a conflict such as:

```text
A --0.96-- X --0.81-- B, with A verified distinct from B
```

the stronger `A-X` relation is retained and `X-B` is rejected. This greedy
choice is not a globally optimal constrained graph cut, but it is deterministic,
fast, explainable, and makes violations impossible during normal operation.

Cluster IDs are assigned by sorting members, sorting components by smallest
account ID, and numbering `c1`, `c2`, and so on. Non-singleton batch confidence
is the minimum accepted union score in the component's spanning tree.

## Engineering design

### Shared batch and streaming state

Batch performs:

```text
read -> normalize -> frequencies/indexes -> sparse candidates
     -> evidence/eligibility -> descending constrained DSU -> stable IDs
```

The resulting account features, frequencies, postings, constraint graph, DSU
memberships, confidence, and cluster IDs remain live. Stream does not rebuild
them. For one event it:

```text
normalize -> bounded posting lookup -> pair/cluster scores
          -> discard conflicting clusters -> choose best feasible cluster
          -> commit indexes/DSU -> encode and flush
```

Candidates collapse to their current cluster using the maximum feasible pair
score. Ties use stable cluster number. If the highest-scoring cluster conflicts,
the linker considers the next feasible cluster rather than immediately creating
a singleton. Cohort diversity and velocity are inspected only after an O(1)
frequency check confirms the prefix is within the configured eight-account
maximum; a hot carrier or office prefix is never scanned on the stream hot
path.

The CLI uses a 4 MiB scanner buffer and a single buffered writer. Each
assignment calls `Flush` before reading the next event. No goroutines are used
on the hot path, preserving input order and making race behavior simple.

### Complexity and growth

With selective postings, normalization and index construction are `O(N)`;
candidate scoring is `O(E)` for sparse candidate count `E`; sorting is
`O(E log E)`; DSU operations are nearly constant amortized. A stream event is
primarily proportional to its bounded candidate postings and relevant
constraint neighbors, not historical account count.

Around 1 million accounts, the first pressure points are the in-memory
candidate-pair set, edge slice/sort, repeated strings, and component member
lists—not DSU parent lookup. I would compact identifiers, process blocking
partitions independently, and externally/chunk-sort edges.

Around 10 million accounts, a single process is the wrong persistence and
availability boundary. I would use partitioned/persistent query indexes,
distributed candidate workers, external edge ordering, frequency sketches for
hot keys, durable cluster state, and an idempotent stream processor.

### Deliberate omissions

Within the assignment budget I did not build:

- an ML model or probability calibration;
- generalized Unicode confusable folding or name parsing;
- edit distance greater than one;
- a global optimum constrained correlation-clustering solver;
- online retroactive reassignment or old-cluster merging;
- persistent/distributed indexes;
- goroutine parallelism without profiling evidence;
- full CQRS/repository abstractions for a single-process CLI.

These omissions reduce overfitting, dependency/build risk, and operational
complexity while keeping the submitted behavior explainable.

## Operating point

The stated costs imply a roughly `2000 / 50 = 40` ratio, but a single bad graph
edge can pull many legitimate customers into a contaminated component.
Therefore my operating point is:

> Recall-oriented for rare identity evidence and tightly bounded coordinated
> cohorts; precision-oriented for shared infrastructure and irreversible
> streaming decisions.

Rare canonical email and payment can link alone. Device and fuzzy email need
corroboration. IP values are weak context, hot values cannot block, and an
arbitrary shared IP or prefix never links accounts. The two small-cohort rules
accept more risk specifically where network diversity plus velocity or a strong
identity seed indicates coordinated account creation.

The offline evaluator reports pair precision/recall/F1, cluster impurity, true
actor fragmentation, fully recovered rings, largest predicted component,
constraint violations, and two cost views:

```text
general proxy = 50 * minority accounts in mixed clusters
              + 2000 * fragmented multi-account actors

fraud-list proxy = 50 * non-fraud actors sharing a fraud cluster
                 + 2000 * fragmented listed fraud actors
```

Neither is represented as the hidden grader's known formula. I tune only
general rules, never account IDs or truth labels in runtime code.

On the supplied sample, the committed default configuration produces:

| Metric | Result |
|---|---:|
| constraint violations | 0 |
| pair precision | 1.0000 |
| pair recall | 0.8125 |
| pair F1 | 0.8966 |
| mixed-cluster impurity | 0 |
| fully recovered multi-account actors | 12 / 18 |
| fully recovered labeled fraud rings | 2 / 2 |
| largest predicted cluster | 5 |
| general proxy cost | $12,000 |
| fraud-list proxy cost | $0 |

The six remaining fragmented actors have weak alias-only evidence. I chose not
to introduce broad cross-provider name/handle rules solely to make the small
sample perfect; the hidden data is messier and those rules need more negative
examples. The two network cohort rules are retained because they express
bounded, general fraud patterns and materially affect the stated missed-ring
cost.

If the FP:FN ratio flipped, I would change evidence composition rather than only
raise one threshold: no single email/payment signal would link alone;
canonical email plus device/payment or payment plus device would be required;
the velocity and seeded-prefix expansion rules would be disabled by default;
and stream would use a still higher threshold.

## Next steps

With one more day I would:

1. Add larger synthetic generators and benchmark 1M-account selective and
   adversarial hot-key workloads, tracking p50/p95/p99 and allocations.
2. Run sensitivity analysis over blocking limits, evidence weights, cohort
   size/window, and several plausible business-cost definitions.
3. Add a rare, cross-provider email-handle feature only after measuring its
   false-positive behavior on more labels; the current same-domain fuzzy rule is
   intentionally conservative.
4. Replace the greedy conflict choice with a bounded local constrained graph
   optimization around each conflict and compare cost, runtime, and
   explainability.
5. Add durable snapshots/versioned configuration so batch state can restart
   stream processing reproducibly.
6. Add structured decision traces behind a debug flag without polluting the
   required stdout protocol.
7. Profile memory, then consider compact integer/string interning and
   partitioned candidate generation before adding concurrency.
