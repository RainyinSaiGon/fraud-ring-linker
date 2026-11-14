# Test plan

How this project is tested and which checks block a merge.

## Test levels

| Level | Tool | What it proves | Where |
|---|---|---|---|
| Unit | JUnit 5, AssertJ | Each rule, limit and weight behaves as documented | `java/engine/src/test` |
| Boundary | JUnit parameterized tests | Every rarity limit, time window and score floor, on both sides of its boundary | `ScorerBoundaryTest` |
| Mutation | PIT | Tests catch at least 85% of bugs planted in `scoring` and `clustering` | CI job "Mutation testing" |
| Coverage | JaCoCo | Line and branch coverage never drop below the floors in `java/pom.xml` | `mvn verify` |
| Parity | `tools/parity` | Java and Go give byte-identical output on messy synthetic data | CI job "Go and Java parity" |
| Web contract | MockMvc | JSON shape, validation and error codes of the REST API | `AccountControllerWebTest` |
| Integration | REST Assured, Testcontainers | End-to-end flows on H2 and on real PostgreSQL | `ApiScenarios` |
| Failure | Spring test with a spy | A failed database write rolls back both the database and the engine | `WriteFailureTest` |
| Concurrency | Parallel HTTP calls | Simultaneous sign-ups from one ring land in one cluster | `ApiScenarios` |

## Quality gates in CI

A pull request is green only if all of these pass:

1. Java tests with coverage floors: line 90%, branch 70% (engine branch 80%).
2. Mutation score of at least 85% on `scoring` and `clustering`.
3. Go tests with the race detector.
4. Go and Java parity on 12 synthetic datasets.

Floors are set just under the measured values and only ever go up.

| Measure | When the gate was added |
|---|---|
| Engine coverage | 93% lines, 83% branches |
| Service coverage | 94% lines, 74% branches |
| Mutation score | 88% (was 68% before the boundary tests) |

## Running the checks locally

```bash
cd java
mvn verify                          # tests and coverage floors; reports in */target/site/jacoco
mvn -Pmutation verify -pl engine    # mutation testing; report in engine/target/pit-reports
```

## What mutation testing found

The first PIT run caught only 68% of planted bugs, even with 91% line coverage
in the mutated classes. The tests ran the scoring code but rarely checked
exact limits. For example, changing "a card shared by at most 8 accounts is
rare" to "fewer than 8" went unnoticed. `ScorerBoundaryTest` now tests every
limit on both sides, and checks scores worked out by hand.

## Known limits

- PostgreSQL tests need Docker, so they run in CI but are skipped on machines
  without it.
- The stream latency test uses a generous bound to avoid flaky CI, so it only
  catches large regressions.
- The remaining surviving mutants are mostly equivalent: for example, removing
  a sort on a list that is already sorted changes nothing observable.
