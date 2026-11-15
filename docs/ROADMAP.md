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
| 5 | Business cost report | done |
| 6 | Mule-network detection from transfers | done |
| 7 | Account-takeover signals at login | planned |
| 8 | Login and data protection | planned |

### Stage 3: quality gates

- **JaCoCo** measures test coverage. The build fails if line coverage drops
  below a floor set just under today's number, so coverage can only go up.
- **PIT mutation testing** changes the code in small ways (flips a condition,
  removes a call) and checks that a test fails. It runs on the three packages
  where a silent bug costs most: `scoring`, `clustering` and `mules`.
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

**Threshold sweep (#32):** `tools/cost-sweep/sweep.sh` exports simulator seeds
1 to 10 (about 290 sign-ups and 8 rings each) and links every one at 7 merge
score floors (0.55 to 0.85) and 7 stream thresholds (0.70 to 0.94). Each cell is
wrongly linked accounts / multi-account actors split, summed over the 10 seeds:

| Merge score | Batch | Stream at 0.70 to 0.86 | Stream at 0.90 | Stream at 0.94 |
|---|---|---|---|---|
| 0.55 to 0.85 (0.70 today) | 0 / 0 | 0 / 12 | 0 / 47 | 0 / 55 |

Every row is the same, and every split actor is a fraud ring: batch links 80 of
80 rings, streaming 68 of 80.

| Prices (wrong link : missed ring) | Cheapest stream setting | Stream cost | Batch cost |
|---|---|---|---|
| $50 : $2,000 (40:1, today's estimate) | today's (0.70, 0.82) | $24,000 | $0 |
| $50 : $500 (10:1) | today's | $6,000 | $0 |
| $50 : $50 (1:1) | today's | $600 | $0 |

The cost curve is flat from 0.30 to 0.80 for the merge score and from 0.50 to
0.86 for the stream threshold. Prices only change how much each mistake costs,
not which setting is cheapest. Run locally on the original take-home sample, the
answer is the same: today's setting is cheapest at all three ratios ($12,000
batch, $16,000 stream at 40:1), and the curve is flat over the same ranges.

What this means:

- **Keep the defaults.** Both sit inside the flat region, with room on each
  side. Raising either threshold only loses rings; lowering them adds nothing.
- **Thresholds are not the lever.** A pair merges only when a named rule fires
  (shared card, same device with a matching email, and so on), and those pairs
  score well above the floor. Lowering the floor admits no new pairs, so no
  wrong links appear even at a floor of 0.20 on the simulator.
- **The saving is elsewhere.** All 12 stream misses come from arrival order, so
  the merge proposals in #29 target the whole $24,000. On the take-home sample,
  batch still misses 6 actors that no rule links; new rules would help there,
  not lower thresholds.
- **The simulator is too easy on false positives.** Nothing was wrongly linked
  at any setting, so this sweep cannot show the other side of the trade-off.
  Harder legitimate cases (shared family cards, shared office devices) would
  make it meaningful.

**Merge proposals (#29):** when a new account links strongly to two clusters,
the service now opens a proposal and an analyst accepts or rejects it. The cost
report has a third mode, "stream + proposals", which accepts every proposal the
moment it opens. That is the best case: an analyst who agrees with every
proposal and answers at once. Same 10 seeds, today's setting (0.70, 0.82), $50
per wrong link and $2,000 per missed ring:

| Mode | Rings fully linked | Actors split | Wrongly linked | Proposals to review | Cost |
|---|---|---|---|---|---|
| Batch | 80 / 80 | 0 | 0 | none | $0 |
| Stream | 68 / 80 | 12 | 0 | none | $24,000 |
| Stream + proposals | 80 / 80 | 0 | 0 | 12 | $0 |

- **Proposals close the whole gap on the simulator.** Each of the 12 splits
  opened exactly one proposal, and accepting it recovered the ring. That is 12
  reviews, $2,000 saved per review, and none wasted on a pair that should stay
  apart.
- **The stream threshold matters for proposals.** A proposal needs a link that
  is strong enough to join, so at stream thresholds of 0.90 and 0.94 no
  proposals open and the splits stay (47 and 55). This is one more reason to keep
  the threshold at 0.82.
- **On the take-home sample** (run locally only): stream $16,000, stream +
  proposals $14,000, batch $12,000. One proposal recovers one of the two rings
  that streaming splits. The other ring has a member whose only link to the rest
  is a shared IP subnet. Batch links it with a subnet rule that needs the whole
  sign-up cohort in hindsight, so in streaming it never joins anything and no
  proposal can open. A nightly batch relink that sends its differences to the
  review queue would cover that case.
- **Still to test:** analysts don't accept everything, and they don't answer at
  once. Until a proposal is accepted, ring members who arrive meanwhile can land
  on either side. The best case here is an upper bound on the saving.

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

**Built (#44):** the engine scores fan-in, pass-through and chains, and no
single signal can flag an account (#45). The simulator plants mule chains and
hard legitimate cases (#46). The service flags accounts it holds and asks for
review (#47). The analyst page lists possible mules and shows each member's
money movement (#48).

**Measured (#49):** `tools/mule-sweep/sweep.sh` exports simulator seeds 1 to 10
(about 960 transfers, 2 mule chains of 2-3 hops, and 10 hard legitimate
accounts each) and scores every setting. Clusters come from batch linking.

| At today's setting (5 payers, 0.80 share, flag at 0.80) | Result |
|---|---|
| Mules flagged | 50 of 50 (recall 100%) |
| Small businesses flagged | 0 of 20 |
| Own-money savers flagged | 0 of 30 |
| Payday bill payers flagged | 0 of 50 |
| Any other held account flagged | 0 (precision 100%) |

Mules flagged / legitimate accounts flagged, summed over the 10 seeds:

| Fan-in payers, pass-through share | Flag at 0.70 | 0.75 | 0.80 (today) | 0.85 | 0.90 |
|---|---|---|---|---|---|
| 3 to 8 payers, 0.70 or 0.80 share | 50 / 30 | 50 / 0 | 50 / 0 | 50 / 0 | 5 to 20 / 0 |
| 3 to 8 payers, 0.60 share | 50 / 46 | 50 / 0 | 50 / 0 | 50 / 0 | 5 to 20 / 0 |
| 3 to 6 payers, 0.90 share | 44 or 45 / 15 | 38 or 39 / 0 | 38 or 39 / 0 | 33 / 0 | 10 to 12 / 0 |

What this means:

- **Keep the defaults.** Today's setting is cheapest at every price tried
  ($50 per false flag against $2,000, $500 or $50 per missed mule), and it sits in
  the middle of a flat region: 3 to 8 payers, a 0.60 to 0.80 share, and a flag
  threshold of 0.75 to 0.85 all give the same 50 / 0.
- **The flag threshold is the lever, and the weights explain why.** At 0.70,
  pass-through alone (weight 0.7) flags an account: all 30 own-money savers are
  flagged, on pass-through only. With a 0.60 share, 16 payday bill payers (those
  paying out 60 to 70% of their salary) join them. At 0.90, only first hops with
  all three signals (0.94) are flagged: 20 of 50, and every second hop (0.85) is
  missed.
- **A 0.90 share misses real mules.** Mules pass on 85 to 98% of each payment, so
  a 0.90 share loses the ones that keep a bigger cut.
- **Fan-in barely matters at today's threshold.** Pass-through plus chain (0.85)
  already flags every hop, so raising the payer count from 5 to 8 changes nothing
  at 0.80. It only shows at 0.90, where first hops need all three signals: there
  8 payers flags 5 mules instead of 20.
- **Only held accounts are flagged.** Without that rule, 30 outside billers
  reached exactly 0.80 (fan-in plus a chain through own-money savers); see #46.
- **Still to test:** mules that also get a salary, so their pass-through share
  drops; two mules linked into one cluster, whose transfers then count as the
  person's own money (a known gap noted in #45); and slower mules that wait
  longer than an hour. As in Stage 5, the simulator is easier than real traffic,
  so these numbers are an upper bound.

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
