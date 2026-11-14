# fraud-ring-linker (Java)

Two Gradle modules:

| Module | What it is |
|---|---|
| [`engine/`](engine/) | The linking engine and `link` / `stream` CLI. Plain Java 21, no framework. Byte-identical to the Go version (checked by [`tools/parity`](../tools/parity/)). |
| [`service/`](service/) | Spring Boot 4 REST service: live account linking, cluster explanations, analyst decisions, and an append-only audit log in PostgreSQL. |

```bash
./gradlew build          # compile, run every test, enforce coverage floors, build both jars
./gradlew :engine:pitest # mutation testing (slower; CI runs it as its own job)
```

Use `gradlew.bat` on Windows Command Prompt or PowerShell. No Gradle install is
needed: the wrapper downloads the pinned version. Jars land in
`engine/build/libs/fraud-ring-linker.jar` (CLI) and
`service/build/libs/fraud-ring-linker-service.jar` (service).

Shared settings (Java 21 target, warnings as errors, JUnit, coverage floors) live
in the `build-logic` convention plugin. Every dependency version is in
`gradle/libs.versions.toml`.

PostgreSQL tests use Testcontainers and need Docker. Without Docker they are
skipped, and everything else still runs. CI always runs them.

---

## Service

### Run it

Without Docker or PostgreSQL (in-memory database; data is lost on stop):

```bash
./gradlew :service:bootRun --args='--spring.profiles.active=h2'
# or build the jar and run it:
./gradlew :service:bootJar
java -jar service/build/libs/fraud-ring-linker-service.jar --spring.profiles.active=h2
```

With PostgreSQL (start one with `docker compose up -d` from this folder, or use your own):

```bash
DATABASE_URL=jdbc:postgresql://localhost:5432/fraudlinker DATABASE_USERNAME=fraudlinker DATABASE_PASSWORD=fraudlinker \
  java -jar service/build/libs/fraud-ring-linker-service.jar
```

Health: `GET /actuator/health` (includes `linkingEngine`, DOWN until the state is loaded).

### API

JSON field names are snake_case, the same as the engine's JSONL format. Errors
are RFC 9457 problem responses (`title`, `status`, `detail`, plus `errors` for
validation).

| Method and path | What it does |
|---|---|
| `POST /api/v1/accounts` | Register a new account. Returns its cluster, confidence and **risk action**. 201, or 409 if the ID exists. |
| `GET /api/v1/accounts/{id}` | The account, its cluster and current risk action. |
| `GET /api/v1/clusters?limit=` | Review queue: clusters with 2+ accounts still marked REVIEW, largest first. |
| `GET /api/v1/clusters/{id}` | Members, risk action, latest decision, and which pairs are linked by which rule and shared signals. |
| `POST /api/v1/clusters/{id}/decisions` | Analyst decision: `{"outcome":"CONFIRMED_FRAUD"\|"NOT_FRAUD","analyst":"...","note":"..."}`. Covers every current member. |
| `GET /api/v1/clusters/{id}/decisions` | Decision history for the cluster's current members. |
| `POST /api/v1/constraints` | `{"account_a","account_b","actor"}`: two accounts are different people (for example, both passed identity checks). 201 new, 200 already known, 409 if they are already linked. |
| `GET /api/v1/audit-events?account_id=&cluster_id=&limit=` | Audit trail, newest first. |

Example:

```bash
curl -s -XPOST localhost:8080/api/v1/accounts -H 'Content-Type: application/json' -d '{
  "account_id":"acc_1","email":"alice@example.com","device_id":null,
  "ip":"203.0.113.7","payment_fingerprint":"pf_x","created_at":"2026-03-14T09:22:11Z"}'
# {"account_id":"acc_1","cluster_id":"c1","confidence":1.0,"cluster_size":1,"risk_action":"ALLOW",...}
```

### Risk actions

The engine only links accounts; it never restricts anyone on its own. A wrong
restriction hurts a real customer, so restriction needs an analyst. First match wins:

| Situation | Action |
|---|---|
| Latest decision on any member is `CONFIRMED_FRAUD` | `RESTRICT`, including accounts that join later |
| Latest decision is `NOT_FRAUD` and covered every current member | `ALLOW` |
| Latest decision is `NOT_FRAUD` but accounts joined since | `REVIEW` |
| No decision, 2+ members | `REVIEW` |
| No decision, 1 member | `ALLOW` |

The payoff is the first row: once a ring is confirmed, a fresh account using the
ring's card or device is restricted the moment it signs up.

### Design decisions

- **Database is the source of truth, memory is a cache.** Linking state lives in
  memory for speed and is rebuilt from the `accounts` and `identity_constraints`
  tables on startup.
- **One write path.** `WriteThrough` holds the engine lock and one database
  transaction around every change:
  - If anything fails after the engine changed, the transaction rolls back and
    the engine is rebuilt from the database. `WriteFailureTest` proves this.
  - Failures before any change (duplicates, unknown IDs) skip the rebuild, so bad
    requests can't force expensive relinks.
  - If the rebuild itself fails, for example because the database is down, the
    service returns 503 and reports DOWN until it recovers.
- **One lock.** The engine is single-threaded by design: even lookups mutate
  union-find internals. Engine work is microseconds; the database write inside
  the lock is the real limit, roughly one write per database round trip.
  Sharding by tenant or a single-writer queue would be the next step.
- **Decisions store their accounts, not just a cluster ID.** Cluster numbers can
  change after a rebuild, so each decision records exactly which accounts it
  covered. The rebuild test checks that decisions still apply after renumbering.
- **Append-only audit.** Every automated link and every human decision is
  recorded. On PostgreSQL, a trigger rejects `UPDATE`, `DELETE` and `TRUNCATE`
  on `audit_events`.
- **A late constraint cannot split a cluster.** Two accounts already linked
  return 409. Splitting needs a full relink plus a person, not an API call.

### Not built yet

- Authentication. `analyst` and `actor` come from the request body; they must
  come from the caller's identity, for example an OAuth2 resource server.
- PII handling. Responses include raw emails and IPs; mask them per role.
- Pagination beyond `limit`.
- Horizontal scaling. State is per instance, so run a single writer.
