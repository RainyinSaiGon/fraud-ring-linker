# Analyst page

A browser page for fraud analysts: see which linked clusters need review, why
their accounts are linked, and record a decision. React 19, TypeScript and
Vite. It talks only to the service's REST API.

## Run it

```bash
# 1. The service (no Docker needed with the h2 profile)
(cd ../java && ./gradlew :service:bootRun --args='--spring.profiles.active=h2')
# 2. Some traffic to review
(cd ../tools/simulator && npm run simulate)
# 3. The page, at http://localhost:5173
npm install
npm run dev
```

In development, Vite forwards `/api` to `http://localhost:8080`, so the service
needs no CORS setup. Set `API_TARGET` to point it elsewhere.

## Pages

| URL | What it shows |
|---|---|
| `#/` | **Review queue:** linked clusters still marked REVIEW, largest first |
| `#/clusters/c12` | **Cluster:** members, each link's rule and shared signals in plain words, the recommended action and why, the decision form, and the history |

Design choices:
- **Decisions need a name.** Both buttons stay disabled until the analyst types a
  name, because the audit log must say who decided. Once login exists (roadmap
  Stage 8), the name will come from the signed-in user instead.
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
(`npx playwright install chromium`).

Manual release checks are in [`docs/MANUAL_TEST_CHECKLIST.md`](../docs/MANUAL_TEST_CHECKLIST.md).
