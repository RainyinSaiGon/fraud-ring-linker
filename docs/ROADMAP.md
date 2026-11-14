# Roadmap

What gets built next, in order, and why. Stages 1 and 2 (the Java engine and
the REST service) are done.

## Why these features

Research done in November 2026. Dates and vendor claims should be re-checked
before quoting them.

- **Scam rules are becoming law for banks.** Australia's Scams Prevention
  Framework requires banks to prevent, detect, disrupt, report and respond to
  scams. Draft rules and a banking code were released in May 2026; most law-firm
  sources put the bulk of bank obligations at 31 March 2027, though some sources
  give earlier dates. Either way, banks need evidence of *what was detected, what
  was done, and who decided*. That is what the audit log and analyst decisions
  record.
- **Mule accounts are the main target.** The big four banks have closed
  thousands of suspected mule accounts. The industry shares mule accounts and
  malicious IPs through the Australian Financial Crimes Exchange. AUSTRAC's
  April 2026 review called out high mule exposure at several banks.
- **Detection is moving to the receiving side.** Banks now score the account
  *receiving* money, not just the sender: funds arriving from many unrelated
  payers and leaving again quickly. That is a graph problem, which is what
  this project already solves for sign-ups.
- **More signals are being combined.** Telco data and behavioural signals are
  being joined with bank data to flag mules earlier.

Sources:
[Mallesons on the SPF timeline](https://www.mallesons.com/au/en/insights/latest-thinking/countdown-to-the-spf-recent-developments-in-the-scams-regulatory-landscape.html),
[Clayton Utz on the draft banking code](https://www.claytonutz.com/insights/2026/june/navigating-the-draft-spf-code-implications-and-responsibilities-for-banks),
[Baker McKenzie on the draft rules](https://www.bakermckenzie.com/en/insight/publications/2026/06/australia-scams-prevention-draft-code-and-rules-released),
[iTnews on mule account exits](https://www.itnews.com.au/news/australias-big-4-banks-exit-thousands-of-suspected-mule-accounts-614079),
[ATO on AFCX data sharing](https://www.ato.gov.au/media-centre/new-data-tackles-fraud-faster),
[IT Brief on the AUSTRAC review](https://itbrief.com.au/story/austrac-flags-foreign-banks-over-weak-money-laundering),
[Telstra on telco signals for mule detection](https://www.telstra.com.au/exchange/telstra-joins-forces-with-major-banks-to-tackle-money-laundering),
[Payment Expert on beneficiary checks](https://paymentexpert.com/2025/11/28/australian-banks-test-beneficiary-checks-in-real-time-scam-crackdown/).

## Stages

| Stage | Feature | Status |
|---|---|---|
| 3 | Quality gates: coverage and mutation testing in CI, manual test plan | in progress |
| 4 | Traffic simulator (Node) and analyst page (React) | in progress |
| 5 | Business cost report | planned |
| 6 | Mule-network detection from transfers | planned |
| 7 | Account-takeover signals at login | planned |
| 8 | Login and data protection | planned |

### Stage 3: quality gates

- **JaCoCo** measures test coverage. The build fails if line coverage drops
  below a floor set just under today's number, so coverage can only go up.
- **PIT mutation testing** changes the code in small ways (flips a condition,
  removes a call) and checks that a test fails. It runs on the two packages
  where a silent bug costs most: `scoring` and `clustering`.
- **Manual test plan** in `docs/TEST_PLAN.md`: exploratory checks a person
  runs against the analyst page before a release.

Done when: CI fails on a coverage drop or a mutation-score drop.

### Stage 4: simulator and analyst page

- **Simulator** (`tools/simulator`, Node): sends sign-ups to the REST API. It
  mixes ordinary customers, households on one IP, a busy office IP, and fraud
  rings that share cards or devices. It records which accounts it made part of
  which ring, so results can be scored later (Stage 5).
- **Analyst page** (`web/`, React + TypeScript): a review queue, the cluster
  view (members, which signals link them, the recommended action), buttons to
  confirm fraud or clear the cluster, and the audit trail.
- **Service additions:** a `GET /api/v1/clusters` review queue endpoint.
- **Tests:** Node's test runner for the simulator, Vitest and Testing Library
  for components, Playwright for one end-to-end flow.

Done when: one command starts the service, the simulator fills it, and an
analyst can clear the queue in the browser.

### Stage 5: business cost report

Run simulated traffic through the engine, compare clusters with the rings the
simulator planted, and price the mistakes: about $50 per legitimate customer
wrongly linked, about $2,000 per missed ring. Sweep the merge threshold and
print the cost at each setting.

Done when: a table shows the cheapest threshold and how the answer changes if
the two costs change.

**First finding, from the Stage 4 simulator:** on three seeded runs (290
sign-ups each, 8 rings), batch linking recovered 24 of 24 rings and streaming
recovered 22 of 24. Neither wrongly linked a single legitimate account. Both
streaming misses were caused only by arrival order. A ring member that arrives
before anyone it can link to starts its own cluster. Streaming never merges two
existing clusters, so a later account that links both cannot join them.

Fix to evaluate here: when a new account links strongly to two existing clusters,
open a **merge proposal** for an analyst rather than merging silently. Also
consider a nightly batch relink whose differences go to the same review queue.
Each missed ring costs about 40 times a wrong link, so this is likely the
biggest saving available.

### Stage 6: mule-network detection

A new event type: transfers between accounts.

- **Fan-in:** many unrelated payers sending to one account in a short time.
- **Pass-through:** money leaving within minutes of arriving.
- **Chains:** how many hops money travels inside or between clusters.
- A mule score per account, shown on the analyst page, and a new risk reason.

Transfer data is its own evidence family. It never merges clusters by itself,
for the same reason a shared IP does not.

Done when: the simulator plants a mule chain and the service flags it, with
tests for each pattern.

### Stage 7: account-takeover signals

Login events with device and IP: a first-time device, a location change no
person could travel, a burst of failed logins. A takeover flag makes an
account `REVIEW` even when it is in no cluster.

### Stage 8: login and data protection

- The analyst's name comes from an OAuth2 token, not the request body.
- Roles: analysts decide, services register accounts, auditors read.
- Emails and IPs are masked in responses unless the role allows them.

This closes the two biggest gaps listed under "Not built yet" in the service
README.

## Order

3 and 4 first: they are the team's listed requirements (JavaScript, quality
testing). 5 next because it is small and needs the simulator. 6 is the main
fraud feature. 8 should land before any real data is ever used.
