#!/usr/bin/env bash
# Finds the cheapest merge score and stream threshold on simulated traffic.
#
# Exports one labelled scenario per simulator seed, then runs the engine's
# `sweep` command over all of them. Output is the same for the same seeds.
#
# Usage: tools/cost-sweep/sweep.sh [seedCount] [extra sweep flags...]   (default 10 seeds)
#   e.g. tools/cost-sweep/sweep.sh 10 --costs 50:2000,50:200 --format json
# Needs: java 21+, node 20+. (Gradle comes from the wrapper.)
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
seeds="${1:-10}"
shift || true
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# Java on Windows needs Windows paths; cygpath exists only in Git Bash / Cygwin.
native() { if command -v cygpath > /dev/null; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

(cd "$root/java" && ./gradlew -q :engine:cliJar)

datasets=()
for seed in $(seq 1 "$seeds"); do
  node "$root/tools/simulator/src/cli.mjs" export --seed "$seed" --out-dir "$work/seed-$seed" > /dev/null
  datasets+=("$(native "$work/seed-$seed")")
done

java -jar "$(native "$root/java/engine/build/libs/fraud-ring-linker.jar")" sweep \
  --datasets "$(IFS=,; echo "${datasets[*]}")" "$@"
