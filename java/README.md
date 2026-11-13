# fraud-ring-linker (Java)

Java 21 port of the Go linker. Behaviour is identical: for the same input both
produce byte-identical output (checked by [`tools/parity`](../tools/parity/)).

## Build and test

```bash
mvn verify            # compile (warnings are errors), run tests, build the jar
```

The runnable jar is `target/fraud-ring-linker.jar` (Jackson is bundled; no other
runtime dependencies).

## Batch mode

```bash
java -jar target/fraud-ring-linker.jar link \
  --accounts accounts.jsonl --constraints constraints.jsonl --output clusters.json
```

Every input account appears in exactly one cluster. Cluster IDs (`c1`, `c2`, ...)
follow each cluster's smallest account ID, so the same input always gives the
same output.

## Stream mode

```bash
java -jar target/fraud-ring-linker.jar stream \
  --accounts accounts.jsonl --constraints constraints.jsonl < new-accounts.jsonl
```

After linking the initial accounts, it reads one account per stdin line and
writes (and flushes) one line per account:

```json
{"account_id":"acc_new01","cluster_id":"c12","confidence":0.9134}
```

Errors go to stderr only, and the exit code is 1.

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
| `clustering` | `BatchLinker`, the constraint-aware union-find, `LinkState` |
| `streaming` | `StreamLinker`: one-event assignment over the live state |
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
  It needs a lock or a single-writer queue before it is shared between threads.
