# Analyst page

A browser page for fraud analysts: see which linked clusters need review, why
their accounts are linked, and record a decision. React 19, TypeScript and
Vite. It talks only to the service's REST API.

## Run it

```bash
# 1. The service (no Docker needed with the h2 profile), checking tokens signed by a local key
(cd ../java && ./gradlew :service:bootJar && java -jar service/build/libs/fraud-ring-linker-service.jar \
  --spring.profiles.active=h2 $(node ../tools/dev-auth/dev-auth.mjs keys /tmp/linker-keys))
# 2. Some traffic to review
(cd ../tools/simulator && LINKER_TOKEN=$(node ../dev-auth/dev-auth.mjs token --keys /tmp/linker-keys \
  --sub simulator --roles ingest,analyst) npm run simulate)
# 3. A token to sign in with, then the page at http://localhost:5173
node ../tools/dev-auth/dev-auth.mjs token --keys /tmp/linker-keys --sub alice --roles analyst
npm install
npm run dev
```

## Signing in

The page asks for a token first and sends it with every call. For now you paste
one: from your identity provider, or from `tools/dev-auth` for a local service.
It's kept in `sessionStorage`, so it lasts for this tab only. The top bar shows
who is signed in and their roles, with a "Sign out" button.

- When the token expires, or the service refuses it (401), the page goes back to
  sign-in and says the session has ended.
- When the role can't do something (403), the page stays signed in and says
  "Your role can't do this." The service is what enforces roles.
- Real sign-in through the identity provider (OpenID Connect) replaces the paste
  screen in #70.

In development, Vite forwards `/api` to `http://localhost:8080`, so the service
needs no CORS setup. Set `API_TARGET` to point it elsewhere.

## Pages

| URL | What it shows |
|---|---|
| `#/` | **Review queue:** linked clusters still marked REVIEW, largest first; then accounts that may have been taken over ("Possible takeovers"); then likely mules waiting for a decision ("Possible mules"); then open merge proposals ("Possible split rings") |
| `#/clusters/c12` | **Cluster:** members, each link's rule and shared signals in plain words, the recommended action and why, the decision form, the history, and each member's money movement: mule score, signals in plain words, and recent transfers on request |
| `#/accounts/<id>` | **Account:** its own action and why, its takeover score and signals in plain words, recent logins (time, device, place, result) with each signal named next to the login that showed it, its contact details (masked; an analyst can show them, and the history records it), the takeover decision form, and the account's history. Account IDs on the cluster page link here |
| `#/proposals/<id>` | **Merge proposal:** the accounts on each side, the new account and rule that link them, and Merge clusters / Keep apart |

Design choices:
- **Customer details stay masked.** The service masks every email and IP. On the
  account page an analyst can press "Show details" (with an optional reason) to
  see one account's full email and sign-up IP; the service records it, and the
  history shows it. Leaving or reloading the page masks them again. Auditors
  see no button.
- **Decisions record the signed-in analyst.** There's no name to type: each form
  says "Deciding as …", and the service takes the name from the token, so the
  audit log can't be told someone else decided.
- **Mule flags ask, never act.** A likely mule shows its signals ("6 unrelated payers in
  25 min, 90% sent on within 30 min") so the analyst can judge them, and only a
  decision restricts it. Possible mules get their own list because most are single
  accounts, which the cluster queue (two or more accounts) never shows.
- **A takeover is about one customer.** Possible takeovers link to the account,
  not its cluster, and the takeover decision covers that account only. The form
  says so: confirming shows **Step up** (the customer must re-verify) on this
  account, and the other accounts in its cluster keep their own action. "Not a
  takeover" is also how a re-verified customer is recorded.
- **Signals are named on the login that showed them,** for example "New device"
  and "Impossible jump: 13,361 km in 1 h", in words as well as a highlighted row.
- **Split rings wait for a person.** Streaming never merges two clusters by itself.
  When a new account links strongly to two, the page lists the pair, and an
  analyst merges them or keeps them apart. Merging is recorded as the signed-in analyst
  like any decision, and a refusal (for example, a verified-distinct pair on the two sides) is shown
  with the service's reason.
- **Actions are shown in colour and words,** so the page never relies on colour alone.
- **Errors use the service's own message,** for example "Cluster c9 not found."
  or "Linking is temporarily unavailable."
- **Hash URLs** (`#/clusters/c12`) work on refresh with no server routing.

## Tests

```bash
npm test            # Vitest + Testing Library: component behaviour with a mocked API
npm run typecheck
npm run e2e         # Playwright: starts the real service and the page, then clicks through a review
```

The end-to-end test needs the service jar (`./gradlew :service:bootJar` in
`java/`) and a Chromium download the first time
(`npx playwright install chromium`). It makes a key pair in the system temporary
folder (`fraud-ring-linker-e2e-keys`), starts the service with its public half,
and signs its own tokens: the browser signs in as an analyst or an auditor, and
the test's API calls use the `ingest` role (`e2e/fixtures.ts`).

Manual release checks are in [`docs/MANUAL_TEST_CHECKLIST.md`](../docs/MANUAL_TEST_CHECKLIST.md).
