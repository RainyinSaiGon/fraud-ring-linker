#!/usr/bin/env bash
# Checks that the Go and Java linkers produce byte-identical output.
#
# For each seed it generates a synthetic dataset, then compares:
#   - batch clusters.json
#   - stream responses for the held-back events
#   - stderr (both must succeed silently)
#
# Usage: tools/parity/parity.sh [seedCount]     (default 12)
# Needs: go, java 21+, maven, node.
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
seeds="${1:-12}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

echo "Building Go linker..."
(cd "$root/go" && go build -o "$work/go-linker" ./cmd/linker)
echo "Building Java linker..."
(cd "$root/java" && mvn -q -B -DskipTests package)
java_jar="$root/java/target/fraud-ring-linker.jar"

failures=0
for seed in $(seq 1 "$seeds"); do
  count=$((200 + seed * 150))
  data="$work/seed-$seed"
  node "$root/tools/parity/generate.mjs" "$seed" "$count" "$data"

  for impl in go java; do
    if [ "$impl" = go ]; then run=("$work/go-linker"); else run=(java -jar "$java_jar"); fi
    "${run[@]}" link --accounts "$data/accounts.jsonl" --constraints "$data/constraints.jsonl" \
      --output "$data/$impl-clusters.json" 2> "$data/$impl-stderr" || true
    "${run[@]}" stream --accounts "$data/accounts.jsonl" --constraints "$data/constraints.jsonl" \
      < "$data/events.jsonl" > "$data/$impl-stream.jsonl" 2>> "$data/$impl-stderr" || true
  done

  if cmp -s "$data/go-clusters.json" "$data/java-clusters.json" \
    && cmp -s "$data/go-stream.jsonl" "$data/java-stream.jsonl" \
    && [ ! -s "$data/go-stderr" ] && [ ! -s "$data/java-stderr" ]; then
    echo "seed $seed ($count accounts): identical"
  else
    echo "seed $seed ($count accounts): MISMATCH"
    diff "$data/go-clusters.json" "$data/java-clusters.json" | head -20 || true
    diff "$data/go-stream.jsonl" "$data/java-stream.jsonl" | head -20 || true
    cat "$data/go-stderr" "$data/java-stderr"
    failures=$((failures + 1))
  fi
done

if [ "$failures" -ne 0 ]; then
  echo "$failures of $seeds seeds differ"
  exit 1
fi
echo "All $seeds seeds identical."
