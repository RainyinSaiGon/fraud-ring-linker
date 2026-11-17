import type { APIRequestContext } from "@playwright/test";
import { expect, signInAs, test, token } from "./fixtures";

const service = "http://localhost:18081";

/** Two accounts on one card, so they form a cluster waiting for review. */
async function seedPair(request: APIRequestContext, run: string): Promise<string> {
  let clusterId = "";
  for (const n of [1, 2]) {
    const response = await request.post(`${service}/api/v1/accounts`, {
      data: {
        account_id: `e2e_${run}_s${n}`,
        email: `s${n}.${run}@example.com`,
        device_id: null,
        ip: `10.253.${Math.floor(Math.random() * 250)}.${n}`,
        payment_fingerprint: `card_${run}`,
        created_at: "2026-11-01T09:00:00Z",
      },
    });
    expect(response.status()).toBe(201);
    clusterId = ((await response.json()) as { cluster_id: string }).cluster_id;
  }
  return clusterId;
}

test("the page asks for a token first, and signs in with one", async ({ browser }) => {
  // A fresh context, without the fixture's analyst sign-in.
  const context = await browser.newContext();
  const page = await context.newPage();
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Sign in" })).toBeVisible();

  await page.getByLabel("Token").fill(token("pasted-analyst", ["analyst"]));
  await page.getByRole("button", { name: "Sign in" }).click();

  await expect(page.getByRole("heading", { name: "Review queue" })).toBeVisible();
  await expect(page.getByText("Signed in as")).toContainText("pasted-analyst");
  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(page.getByRole("heading", { name: "Sign in" })).toBeVisible();
  await context.close();
});

test("an auditor can read a cluster but the service refuses their decision", async ({ page, request }) => {
  const run = Date.now().toString(36);
  const clusterId = await seedPair(request, run);
  await signInAs(page, "e2e-auditor", ["auditor"]);

  await page.goto(`/#/clusters/${clusterId}`);
  await expect(page.getByRole("heading", { name: `Cluster ${clusterId}` })).toBeVisible();
  await page.getByRole("button", { name: "Confirm fraud" }).click();

  await expect(page.getByRole("alert")).toHaveText("Your role can't do this.");
  await expect(page.getByText("Needs review", { exact: true })).toBeVisible();
});

test("the service refuses calls with no token or the wrong role", async ({ playwright }) => {
  const anonymous = await playwright.request.newContext();
  expect((await anonymous.get(`${service}/api/v1/clusters`)).status()).toBe(401);
  expect((await anonymous.get(`${service}/actuator/health`)).status()).toBe(200);
  const auditor = await playwright.request.newContext({
    extraHTTPHeaders: { Authorization: `Bearer ${token("e2e-auditor", ["auditor"])}` },
  });
  expect((await auditor.post(`${service}/api/v1/accounts`, { data: {} })).status()).toBe(403);
  await anonymous.dispose();
  await auditor.dispose();
});

test("contact details are masked; an analyst reveals one account and the history records it", async ({
  browser,
  page,
  request,
}) => {
  const run = Date.now().toString(36);
  const accountId = `e2e_${run}_pii`;
  const email = `jane.${run}@example.com`;
  const ip = `10.254.${Math.floor(Math.random() * 250)}.77`;
  const response = await request.post(`${service}/api/v1/accounts`, {
    data: { account_id: accountId, email, device_id: null, ip, payment_fingerprint: null, created_at: "2026-11-01T09:00:00Z" },
  });
  expect(response.status()).toBe(201);

  // An auditor sees the masked values and no way to reveal them.
  const auditorContext = await browser.newContext();
  const auditor = await auditorContext.newPage();
  await signInAs(auditor, "e2e-auditor", ["auditor"]);
  await auditor.goto(`/#/accounts/${accountId}`);
  await expect(auditor.getByTestId("contact-email")).toHaveText("j***@example.com");
  await expect(auditor.getByTestId("contact-ip")).toHaveText(ip.replace(/\.\d+$/, ".x"));
  await expect(auditor.getByRole("button", { name: "Show details" })).toHaveCount(0);
  await expect(auditor.getByText(email)).toHaveCount(0);
  await auditorContext.close();

  // The analyst reveals them, with a reason, and the history says so.
  await page.goto(`/#/accounts/${accountId}`);
  await expect(page.getByTestId("contact-email")).toHaveText("j***@example.com");
  await page.getByLabel("Reason (optional)").fill("Customer called to confirm");
  await page.getByRole("button", { name: "Show details" }).click();
  await expect(page.getByTestId("contact-email")).toHaveText(email);
  await expect(page.getByTestId("contact-ip")).toHaveText(ip);
  await expect(page.getByText(`e2e-analyst showed ${accountId}'s contact details: Customer called to confirm`)).toBeVisible();

  // Reloading masks them again.
  await page.reload();
  await expect(page.getByTestId("contact-email")).toHaveText("j***@example.com");
});
