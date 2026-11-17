import { type APIRequestContext } from "@playwright/test";
import { expect, test } from "./fixtures";
import { buildScenario, type SimAccount } from "../../tools/simulator/src/scenario.mjs";
import { buildTransfers } from "../../tools/simulator/src/transfers.mjs";

const service = "http://localhost:18081";

async function register(request: APIRequestContext, account: SimAccount) {
  const response = await request.post(`${service}/api/v1/accounts`, { data: account });
  expect(response.status()).toBe(201);
}

/**
 * The simulator's own traffic: one two-hop mule chain and one small business,
 * planted exactly as `npm run simulate` would. Only the chain's mules and the
 * business are registered; everyone else is treated as outside the bank.
 */
async function seedMuleChain(request: APIRequestContext, runId: string) {
  const scenario = buildScenario({ seed: 3, runId, customers: 20, households: 0, officeWorkers: 0, rings: 0 });
  const money = buildTransfers(scenario, {
    muleChains: 1,
    muleChainMinLength: 2,
    muleChainMaxLength: 2,
    smallBusinesses: 1,
    ownMoney: 0,
    paydayBills: 0,
  });
  const [chain] = money.muleChains;
  const shop = money.cases.find((c) => c.case === "small_business")!.account_id;
  const held = new Set([...chain!.account_ids, shop]);
  for (const account of scenario.accounts.filter((a) => held.has(a.account_id))) {
    await register(request, account);
  }
  const transfers = money.transfers.filter((t) => held.has(t.from_account) || held.has(t.to_account));
  const response = await request.post(`${service}/api/v1/transfers`, { data: { transfers } });
  expect(response.status()).toBe(201);
  const body = (await response.json()) as { flagged_now: string[] };
  return { mules: chain!.account_ids, shop, flaggedNow: body.flagged_now };
}

test("an analyst sees both mules in the simulator's chain, with their signals, and confirms fraud", async ({
  page,
  request,
}) => {
  const runId = `e2em${Date.now().toString(36)}`;
  const { mules, shop, flaggedNow } = await seedMuleChain(request, runId);
  const [first, second] = mules as [string, string];
  expect([...flaggedNow].sort()).toEqual([...mules].sort());

  await page.goto("/");
  const list = page.getByRole("table", { name: "Possible mules waiting for review" });
  await expect(list).toBeVisible();
  await expect(list.getByRole("row", { name: new RegExp(first) })).toContainText("unrelated payers in");
  await expect(list.getByRole("row", { name: new RegExp(second) })).toContainText("sent on within");
  await expect(list.getByRole("row", { name: new RegExp(shop) })).toHaveCount(0);

  await list.getByRole("link", { name: new RegExp(`^Review ${first} in cluster`) }).click();
  await expect(page.getByRole("heading", { name: /^Cluster c\d+$/ })).toBeVisible();
  await expect(page.getByTestId("reason")).toContainText(`Account ${first} looks like a money mule`);
  const member = page.getByRole("listitem", { name: `Money movement of ${first}` });
  await expect(member.getByText("Likely mule")).toBeVisible();
  await expect(member.getByText(/unrelated payers in/)).toBeVisible();
  await expect(member.getByText(/sent on within/)).toBeVisible();
  await expect(member.getByText("on a money chain of 2 accounts")).toBeVisible();

  await member.getByRole("button", { name: "Show recent transfers" }).click();
  const transfers = page.getByRole("table", { name: `Recent transfers of ${first}` });
  await expect(transfers.getByRole("row", { name: new RegExp(second) }).first()).toContainText("Out");
  await expect(transfers.getByRole("row", { name: /ext_victim_/ }).first()).toContainText("In");

  await page.getByRole("button", { name: "Confirm fraud" }).click();
  await expect(page.getByText("Restrict", { exact: true })).toBeVisible();
  await expect(page.getByText(new RegExp(`${first} flagged as a likely mule`))).toBeVisible();

  // The decided mule leaves the list; the other hop is still waiting.
  await page.getByRole("link", { name: "← Review queue" }).click();
  await expect(list).toBeVisible();
  await expect(list.getByRole("row", { name: new RegExp(first) })).toHaveCount(0);
  await expect(list.getByRole("row", { name: new RegExp(second) })).toHaveCount(1);
});
