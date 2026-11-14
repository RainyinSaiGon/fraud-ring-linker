import { expect, test } from "@playwright/test";

const service = "http://localhost:18081";

/** Registers accounts that share one card, so the engine links them into one cluster. */
async function seedRing(request: import("@playwright/test").APIRequestContext, run: string, size: number) {
  let clusterId = "";
  for (let i = 0; i < size; i++) {
    const response = await request.post(`${service}/api/v1/accounts`, {
      data: {
        account_id: `e2e_${run}_${i}`,
        email: `member${i}.${run}@example.com`,
        device_id: null,
        ip: `10.${(i * 37) % 250}.${run.length + i}.1`,
        payment_fingerprint: `card_${run}`,
        created_at: "2026-11-01T09:00:00Z",
      },
    });
    expect(response.status()).toBe(201);
    clusterId = (await response.json()).cluster_id;
  }
  return clusterId;
}

test("an analyst reviews a linked cluster and confirms fraud", async ({ page, request }) => {
  const run = Date.now().toString(36);
  const clusterId = await seedRing(request, run, 3);

  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Review queue" })).toBeVisible();
  const row = page.getByRole("row", { name: new RegExp(`\\b${clusterId}\\b`) });
  await expect(row).toContainText("Needs review");

  await row.getByRole("link", { name: clusterId }).click();
  await expect(page.getByRole("heading", { name: `Cluster ${clusterId}` })).toBeVisible();
  await expect(page.getByText("Same payment card, rarely shared").first()).toBeVisible();

  const confirm = page.getByRole("button", { name: "Confirm fraud" });
  await expect(confirm).toBeDisabled();
  await page.getByLabel("Your name").fill("E2E Analyst");
  await page.getByLabel("Note (optional)").fill("Three accounts on one card");
  await confirm.click();

  await expect(page.getByText("Restrict", { exact: true })).toBeVisible();
  await expect(page.getByTestId("reason")).toContainText("confirmed as fraud by E2E Analyst");
  await expect(page.getByText("E2E Analyst marked it fraud")).toBeVisible();

  // The decided cluster leaves the queue.
  await page.getByRole("link", { name: "← Review queue" }).click();
  await expect(page.getByRole("heading", { name: "Review queue" })).toBeVisible();
  await expect(page.getByRole("link", { name: clusterId, exact: true })).toHaveCount(0);
});

test("a cluster that does not exist shows a clear message", async ({ page }) => {
  await page.goto("/#/clusters/c999999999");
  await expect(page.getByRole("alert")).toHaveText("Cluster c999999999 not found.");
});
