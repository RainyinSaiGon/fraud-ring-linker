# Multi-Account Linker (Go reference implementation)

Run every command below from this `go/` folder.

A standard-library-only Go implementation of constrained, rarity-aware account
linking. It supports deterministic batch clustering and low-latency monotonic
stream assignments.

> Sample datasets are not included in this repository. The `datasets/` paths
> below are examples; supply your own accounts and constraints JSONL files.

## Requirements and build

- Go 1.24 or newer
- No network access or third-party dependencies

```bash
go build -mod=mod -o bin/linker ./cmd/linker
```

There is no `vendor/` directory because the module uses only the Go standard
library.

## Batch mode

```bash
bin/linker link \
  --accounts datasets/sample_accounts.jsonl \
  --constraints datasets/sample_constraints.jsonl \
  --output clusters.json
```

Every input account appears exactly once in `clusters.json`. Cluster IDs and
member order are deterministic, and every `verified_distinct` constraint is
enforced before a union is accepted.

## Stream mode

```bash
bin/linker stream \
  --accounts datasets/sample_accounts.jsonl \
  --constraints datasets/sample_constraints.jsonl
```

Write one account JSON object per line to stdin. The linker writes and flushes
one assignment per event:

```json
{"account_id":"acc_new01","cluster_id":"c12","confidence":0.9134}
```

Diagnostics go to stderr, so stdout remains a clean JSONL protocol.

## Architecture

The linker models accounts as a sparse weighted identity graph. It normalizes
signals, builds frequency tables and bounded inverted indexes, and generates
only plausible pairs. Pair scoring combines independent evidence families and
down-weights shared values. IP, time, or device alone cannot create an edge.
Batch linking processes eligible edges strongest-first with a component-aware
union-find that refuses any hard constraint conflict. Stream mode reuses the
same state, queries only local postings, and joins the best feasible cluster or
creates a singleton without rerunning batch.

In addition to exact identity rules, two bounded fraud-cohort patterns reflect
the stated false-negative cost:

- a diversified IPv4 `/24` (IPv6 `/64`) burst of 3–8 accounts within one hour;
- a diversified 3–8-account prefix containing an independently linked
  identity seed.

Arbitrary prefix or IP equality is never merge-eligible. Hot keys are suppressed
for candidate generation.

### Confidence

Confidence is linkage strength, not fraud probability.

- Batch singleton: `1.0`, because no uncertain positive-link claim was made.
- Batch non-singleton: the weakest accepted edge in its union tree.
- Stream join: the selected assignment edge or group score.
- Stream singleton: `1.0`.

## Input handling

- Blank JSONL lines are ignored.
- `account_id` must be non-empty and unique.
- `created_at` must be valid RFC 3339 input for Go's `time.Time`.
- Null/empty device and payment values are unavailable evidence.
- Malformed email or IP values are treated as unavailable/opaque signals rather
  than fatal structural errors.
- Unknown constraint endpoints are retained, which protects accounts that
  arrive later in stream mode.
- Unsupported constraint relations and self-constraints fail clearly.

## Offline evaluation

`sample_truth.json` is used only by the separate evaluator and is never imported
or read by the linker:

```bash
go run ./cmd/evaluate \
  --clusters clusters.json \
  --truth datasets/sample_truth.json \
  --constraints datasets/sample_constraints.jsonl
```

The evaluator reports hard invariant checks, pair metrics, impurity,
fragmentation, fraud-ring recovery, and two explicitly labeled cost proxies.

## Verification

```bash
gofmt -w ./cmd ./internal
go test ./...
go test -race ./...
go vet ./...
go build -mod=mod -o bin/linker ./cmd/linker
```

The tests cover normalization, fuzzy blocking, scoring composition, hot-key
suppression, direct and transitive constraints, deterministic output, fraud
cohorts, future-account constraints, monotonic streaming, duplicate rejection,
and immediate stream flushing.

## Repository layout

```text
cmd/linker/          CLI contract
cmd/evaluate/        offline truth-based evaluator
internal/domain/     input/output and evidence types
internal/normalize/  signal normalization and bounded edit matching
internal/index/      frequencies and inverted indexes
internal/scoring/    rarity-aware evidence policy
internal/clustering/ constraint-aware batch union-find
internal/streaming/  local monotonic event assignment
internal/jsonl/      bounded JSONL input
internal/evaluation/ local metrics only
```
