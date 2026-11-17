import type { APIRequestContext, Page } from "@playwright/test";
import { expect, test } from "./fixtures";

const service = "http://localhost:18081";

async function seedPair(request: APIRequestContext, run: string): Promise<string> {
  let clusterId = "";
  for (const n of [1, 2]) {
    const response = await request.post(`${service}/api/v1/accounts`, {
      data: {
        account_id: `e2e_${run}_o${n}`,
        email: `o${n}.${run}@example.com`,
        device_id: null,
        ip: `10.249.${Math.floor(Math.random() * 250)}.${n}`,
        payment_fingerprint: `card_o_${run}`,
        created_at: "2026-11-01T09:00:00Z",
      },
    });
    expect(response.status()).toBe(201);
    clusterId = ((await response.json()) as { cluster_id: string }).cluster_id;
  }
  return clusterId;
}

/** Goes through the local issuer's sign-in form as `name` with `roles`. */
async function signInThroughIssuer(page: Page, name: string, roles: string[]) {
  await page.getByRole("button", { name: "Sign in through your identity provider" }).click();
  await expect(page.getByRole("heading", { name: "Local sign-in" })).toBeVisible();
  await page.getByLabel("Name").fill(name);
  for (const role of ["ingest", "analyst", "auditor"]) {
    await page.getByLabel(role).setChecked(roles.includes(role));
  }
  await page.getByRole("button", { name: "Sign in" }).click();
}

test("an analyst signs in through the identity provider, lands back on the same page, and decides", async ({
  browser,
  request,
}) => {
  const run = Date.now().toString(36);
  const clusterId = await seedPair(request, run);
  const context = await browser.newContext();
  const page = await context.newPage();

  await page.goto(`/#/clusters/${clusterId}`);
  await expect(page.getByRole("heading", { name: "Sign in" })).toBeVisible();
  await signInThroughIssuer(page, `oidc-${run}`, ["analyst"]);

  await expect(page.getByRole("heading", { name: `Cluster ${clusterId}` })).toBeVisible();
  expect(new URL(page.url()).search).toBe("");
  await expect(page.getByText("Signed in as")).toContainText(`oidc-${run}`);
  await expect(page.getByText("Deciding as")).toContainText(`oidc-${run}`);
  // The token is kept in memory, never in storage.
  expect(await page.evaluate(() => JSON.stringify({ ...sessionStorage, ...localStorage }))).not.toContain("eyJ");

  await page.getByRole("button", { name: "Confirm fraud" }).click();
  await expect(page.getByText(`oidc-${run} marked it fraud`)).toBeVisible();

  // Signing out ends the provider's session too, and comes back to the sign-in screen.
  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(page.getByRole("heading", { name: "Sign in" })).toBeVisible();
  await context.close();
});

test("an auditor signs in through the identity provider and can read but not decide", async ({ browser, request }) => {
  const run = Date.now().toString(36);
  const clusterId = await seedPair(request, run);
  const context = await browser.newContext();
  const page = await context.newPage();

  await page.goto(`/#/clusters/${clusterId}`);
  await signInThroughIssuer(page, `oidc-auditor-${run}`, ["auditor"]);

  await expect(page.getByRole("heading", { name: `Cluster ${clusterId}` })).toBeVisible();
  await expect(page.getByText("Signed in as")).toContainText("(Auditor)");
  await expect(page.getByText("Only analysts can decide. You can read everything here.")).toBeVisible();
  await context.close();
});

test("a reload forgets the token, which was only ever in memory", async ({ browser }) => {
  const context = await browser.newContext();
  const page = await context.newPage();
  await page.goto("/");
  await signInThroughIssuer(page, "oidc-reload", ["analyst"]);
  await expect(page.getByRole("heading", { name: "Review queue" })).toBeVisible();

  await page.reload();

  await expect(page.getByRole("heading", { name: "Sign in" })).toBeVisible();
  await context.close();
});
