# fraud-ring-linker

Links accounts that are probably controlled by the same person: the pattern
behind mule networks, synthetic identities and sign-up bonus abuse.

Given account sign-ups (email, device, IP, payment fingerprint, time) and a list
of account pairs known to be different people (for example, both passed
identity verification), it groups accounts into clusters. It works in two modes:

- **Batch:** link a whole dataset at once.
- **Stream:** assign each new account to a cluster as it arrives, in well under
  a millisecond per event, without re-running the batch.

## How it decides

- **Rarity matters.** A device or payment card shared by 2 accounts is strong
  evidence. One shared by 500 accounts (a café's IP, a popular phone model) is
  not, so every signal's weight shrinks as more accounts share it.
- **No single weak signal links accounts.** Same IP, same device or close
  sign-up times alone never link two accounts. A named rule must fire, such as
  "rare payment card" or "same device and similar email".
- **Verified-distinct pairs are absolute.** Two accounts known to be different
  people never end up in one cluster, even through a chain of other accounts.
  The strongest links are kept first, so the weakest link in a chain is the one
  that is cut.
- **Streaming never rewrites history.** A new account joins at most one existing
  cluster, so earlier answers are never contradicted.

The reasoning, trade-offs and operating point are in
[go/DECISIONS.md](go/DECISIONS.md).

## Layout

| Folder | What it is |
|---|---|
| [`java/`](java/) | Java 21 implementation (Maven). This is where new work happens. |
| [`go/`](go/) | The original Go implementation, kept as the reference. |
| [`tools/parity/`](tools/parity/) | Synthetic data generator (Node) and a script that checks Go and Java give byte-identical output. |

## Quick start (Java)

```bash
cd java
mvn verify
java -jar target/fraud-ring-linker.jar link \
  --accounts accounts.jsonl --constraints constraints.jsonl --output clusters.json
```

See [java/README.md](java/README.md) for input formats and stream mode.

## Testing

- **Unit and contract tests:** `mvn verify` in `java/`, `go test -race ./...` in `go/`.
- **Parity:** `tools/parity/parity.sh` generates messy synthetic datasets and
  checks both implementations agree byte for byte, in batch and stream mode.
- **CI:** GitHub Actions runs all three on every push and pull request.

All data in this repository is synthetic. Never commit real customer data.
