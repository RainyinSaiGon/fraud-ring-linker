import { type APIRequestContext } from "@playwright/test";
import { expect, test } from "./fixtures";
import { buildLogins } from "../../tools/simulator/src/logins.mjs";
import { buildScenario, type SimAccount } from "../../tools/simulator/src/scenario.mjs";

const service = "http://localhost:18081";

async function register(request: APIRequestContext, account: SimAccount) {
  const response = await request.post(`${service}/api/v1/accounts`, { data: account });
  expect(response.status()).toBe(201);
}

/**
 * The simulator's own traffic: one phished customer, planted exactly as
 * `npm run export` would, with three weeks of their logins. A second account
 * shares the customer's card, so the customer is in a cluster with someone
 * else, and confirming the takeover must leave that account alone.
 */
async function seedPhishedCustomer(request: APIRequestContext, runId: string) {
  // Seed 5 is one where the phished customer has a card to share, and no other spec uses it.
  const scenario = buildScenario({ seed: 5, runId, customers: 25, households: 0, officeWorkers: 0, rings: 0 });
  const access = buildLogins(scenario, {
    phishing: 1,
    stuffingCampaigns: 0,
    passwordGuessing: 0,
    newPhone: 0,
    holiday: 0,
    forgottenPassword: 0,
    familyTablets: 0,
  });
  const [takeover] = access.takeovers;
  const planted = scenario.accounts.find((a) => a.account_id === takeover!.account_id)!;
  expect(planted.payment_fingerprint).not.toBeNull();
  // A run-specific email and IP, so repeated runs against one service never link to each other.
  // Logins do not depend on either.
  const subnet = Math.floor(Math.random() * 250);
  const victim: SimAccount = { ...planted, email: `victim.${runId}@example.com`, ip: `10.251.${subnet}.7` };
  const partner: SimAccount = {
    ...planted,
    account_id: `${planted.account_id}_partner`,
    email: `partner.${runId}@example.com`,
    device_id: null,
    ip: `10.252.${subnet}.9`,
  };
  await register(request, victim);
  await register(request, partner);

  const logins = access.logins.filter((l) => l.account_id === victim.account_id);
  const response = await request.post(`${service}/api/v1/logins`, { data: { logins } });
  expect(response.status()).toBe(201);
  const body = (await response.json()) as { flagged_now: string[] };
  return { victim: victim.account_id, partner: partner.account_id, flaggedNow: body.flagged_now };
}

test("an analyst sees the simulator's phished customer, confirms the takeover, and only that account steps up", async ({
  page,
  request,
}) => {
  const runId = `e2et${Date.now().toString(36)}`;
  const { victim, partner, flaggedNow } = await seedPhishedCustomer(request, runId);
  expect(flaggedNow).toEqual([victim]);

  await page.goto("/");
  const list = page.getByRole("table", { name: "Possible takeovers waiting for review" });
  const row = list.getByRole("row", { name: new RegExp(victim) });
  await expect(row).toContainText("first login from a new device after");
  await expect(row).toContainText("km/h");

  await row.getByRole("link", { name: `Review account ${victim}` }).click();
  await expect(page.getByRole("heading", { name: `Account ${victim}` })).toBeVisible();
  await expect(page.getByTestId("reason")).toContainText(`Account ${victim} may have been taken over`);
  await expect(page.getByText("Possible takeover", { exact: true })).toBeVisible();
  const logins = page.getByRole("table", { name: `Recent logins of ${victim}` });
  await expect(logins.getByText("New device")).toBeVisible();
  await expect(logins.getByText(/^Impossible jump: /)).toBeVisible();
  await expect(page.getByText("The 1 other account(s) in its cluster are not affected.", { exact: false })).toBeVisible();

  await page.getByRole("button", { name: "Confirm takeover" }).click();
  await expect(page.locator(".section-head").getByText("Step up", { exact: true })).toBeVisible();
  await expect(page.getByTestId("reason")).toContainText("re-verify the customer");
  await expect(page.getByText(`e2e-analyst marked ${victim} taken over`)).toBeVisible();

  // The other account in the cluster keeps its own action.
  await page.getByRole("link", { name: /^c\d+$/ }).click();
  await expect(page.getByRole("heading", { name: /^Cluster c\d+$/ })).toBeVisible();
  await expect(page.locator(".section-head").getByText("Step up", { exact: true })).toHaveCount(0);
  await page.getByRole("link", { name: `Open account ${partner}` }).click();
  await expect(page.getByRole("heading", { name: `Account ${partner}` })).toBeVisible();
  await expect(page.locator(".section-head").getByText("Needs review", { exact: true })).toBeVisible();
  await expect(page.getByText("Step up", { exact: true })).toHaveCount(0);

  // Decided, so it leaves the list (which may now be empty).
  await page.getByRole("link", { name: "← Review queue" }).click();
  await expect(page.getByRole("heading", { name: "Possible takeovers" })).toBeVisible();
  await expect(page.getByText("Loading possible takeovers…")).toHaveCount(0);
  await expect(page.getByRole("link", { name: `Review account ${victim}` })).toHaveCount(0);
});
