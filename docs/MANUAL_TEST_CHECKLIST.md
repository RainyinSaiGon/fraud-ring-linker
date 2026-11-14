# Manual test checklist

Run before a release, against the service started with
`--spring.profiles.active=h2` and filled by the simulator
(`npm run simulate` in `tools/simulator`). Record the date, the commit, and
pass or fail for each line.

The automated tests cover one main flow end to end (Playwright) and each
component's behaviour (Vitest). This list covers what they don't: real
browsers, layout, keyboard use, and behaviour over time.

## Analyst page

| # | Check | Expected |
|---|---|---|
| M1 | Open the review queue | Only clusters with 2+ accounts and no current decision are listed, largest first |
| M2 | Open a cluster from the queue | Members, confidence, recommended action and each link's rule and shared signals are shown |
| M3 | Try to decide with an empty name, or only spaces | Both buttons stay disabled; nothing is sent |
| M4 | Confirm fraud with a name and a note | Action changes to Restrict; the history shows the decision with your name; the cluster leaves the queue |
| M5 | Mark a different cluster as not fraud | Action changes to Allow; the cluster leaves the queue |
| M6 | Run `npm run extra-ring-member -- --ring N` for a ring you confirmed | The new account arrives as RESTRICT without any analyst action |
| M7 | Send a new sign-up into a cluster cleared as not fraud | The cluster returns to the queue as REVIEW, with "joined since" in the reason |
| M8 | Stop the service with the page open, then click Refresh | A clear "can't reach the service" message, no blank page |
| M9 | Open `#/clusters/c999999` directly | "Cluster c999999 not found." and a link back to the queue |
| M10 | Use the page with the keyboard only | Every link and button can be reached and used; focus is always visible |
| M11 | Shrink the window to phone width | Nothing scrolls sideways; the decision form stays usable |
| M12 | Switch the system to dark mode | Text and badges stay readable |

## Exploratory charters (30 minutes each)

- **Edge-case sign-ups:** odd emails (spaces, capitals, `+tags`, very long local
  parts), IPv6 and malformed IPs, a missing phone or card. Look for links a
  person would call wrong.
- **Busy shared networks:** many unrelated accounts on one office IP. Confirm
  they stay separate unless another signal links them.
- **Decision history:** confirm a cluster, clear it, then confirm it again.
  Check the history tells the story in order and the newest decision wins.
