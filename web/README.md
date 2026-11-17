# Analyst page

A browser page for fraud analysts: see which linked clusters need review, why
their accounts are linked, and record a decision. React 19, TypeScript and
Vite. It talks only to the service's REST API.

## Run it

```bash
# 1. A local identity provider (keep it running in its own terminal)
node ../tools/dev-auth/dev-auth.mjs issuer --keys /tmp/linker-keys
# 2. The service (no Docker needed with the h2 profile), trusting that provider
(cd ../java && ./gradlew :service:bootJar && java -jar service/build/libs/fraud-ring-linker-service.jar \
  --spring.profiles.active=h2 $(node ../tools/dev-auth/dev-auth.mjs keys /tmp/linker-keys --issuer http://localhost:18090))
# 3. Some traffic to review
(cd ../tools/simulator && LINKER_TOKEN=$(node ../dev-auth/dev-auth.mjs token --keys /tmp/linker-keys \
  --issuer http://localhost:18090 --sub simulator --roles ingest,analyst) npm run simulate)
# 4. The page at http://localhost:5173, signing in through the local provider
npm install
VITE_OIDC_ISSUER=http://localhost:18090 VITE_OIDC_CLIENT_ID=analyst-page npm run dev
```

## Signing in

The page signs in through the bank's identity provider with OpenID Connect: the
authorization code flow with PKCE (`src/oidc.ts`, using `oidc-client-ts`). The
page is a public client, so there's no client secret in the browser; PKCE
protects the code. It sends the access token with every call.

| Build setting | |
|---|---|
| `VITE_OIDC_ISSUER` | The identity provider's issuer URL (its `/.well-known/openid-configuration` is read from there) |
| `VITE_OIDC_CLIENT_ID` | This page's client ID at the provider. Register the page's own URL as its redirect and post-logout URL. |
| `VITE_OIDC_SCOPE` | Optional; `openid profile` by default. The access token must carry the `roles` the service checks. |

- **Tokens stay in memory**, never in storage, so a reload or a new tab signs in
  again (quickly, while the provider's own session lasts). Only the sign-in round
  trip (state and the PKCE verifier) uses `sessionStorage`.
- **Refresh:** the token is refreshed shortly before it expires. If that fails,
  or the service refuses the token (401), the page goes back to sign-in and says
  the session has ended.
- **Sign in, then carry on:** after sign-in the page returns to the route it
  started on, for example a cluster link someone sent.
- **Sign out** ends the provider's session too.
- **Roles on screen:** decision forms and "Show details" show only for analysts;
  an auditor sees "Only analysts can decide." The service is what enforces roles;
  a 403 says "Your role can't do this."
- **Development builds only** also offer "paste a token" (from `tools/dev-auth`),
  kept in `sessionStorage` for the tab. Production builds leave it out entirely:
  `npm run check:build` fails if the built files contain it, and CI runs it.
  A production build without the OIDC settings says sign-in isn't set up.

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
npm run build && npm run check:build   # the production build has no paste-a-token sign-in
npm run e2e         # Playwright: starts the real service and the page, then clicks through a review
```

The end-to-end test needs the service jar (`./gradlew :service:bootJar` in
`java/`) and a Chromium download the first time
(`npx playwright install chromium`). It makes a key pair in the system temporary
folder (`fraud-ring-linker-e2e-keys`) and starts the local identity provider
(`tools/dev-auth issuer`) on port 18090, the service trusting it, and the page
built with its settings. `e2e/sign-in.spec.ts` signs in through the provider as
an analyst and as an auditor. The other specs sign in with a pasted token to stay
fast, and the test's API calls use the `ingest` role (`e2e/fixtures.ts`).

Manual release checks are in [`docs/MANUAL_TEST_CHECKLIST.md`](../docs/MANUAL_TEST_CHECKLIST.md).
