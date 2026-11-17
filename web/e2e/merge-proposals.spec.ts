import { type APIRequestContext } from "@playwright/test";
import { expect, test } from "./fixtures";

const service = "http://localhost:18081";

/** A different /24 for every account, so no subnet rule links them by accident. */
let nextSubnet = Math.floor(Math.random() * 200);
function isolatedIp(): string {
  nextSubnet += 1;
  return `172.${16 + (nextSubnet % 16)}.${Math.floor(nextSubnet / 16) % 250}.${1 + Math.floor(Math.random() * 250)}`;
}

async function register(
  request: APIRequestContext,
  accountId: string,
  email: string,
  device: string | null,
  payment: string | null,
) {
  const response = await request.post(`${service}/api/v1/accounts`, {
    data: {
      account_id: accountId,
      email,
      device_id: device,
      ip: isolatedIp(),
      payment_fingerprint: payment,
      created_at: "2026-11-01T09:00:00Z",
    },
  });
  expect(response.status()).toBe(201);
  return (await response.json()) as { cluster_id: string; merge_proposal_ids: string[] };
}

/**
 * One ring whose members arrive in an unlucky order: r1 and r2 share nothing,
 * so they start apart. r3 shares a phone and a near-identical email with r1 and
 * a card with r2, so it joins one of them and proposes merging the other.
 */
async function seedSplitRing(request: APIRequestContext, run: string) {
  const r1 = await register(request, `e2e_${run}_r1`, `maria.lopez.${run}@x.com`, `phone_${run}`, null);
  const r2 = await register(request, `e2e_${run}_r2`, `zz.other.${run}@y.com`, null, `card_${run}`);
  expect(r1.cluster_id).not.toBe(r2.cluster_id);
  const r3 = await register(request, `e2e_${run}_r3`, `maria.lopes.${run}@x.com`, `phone_${run}`, `card_${run}`);
  expect(r3.merge_proposal_ids).toHaveLength(1);
  return { proposalId: r3.merge_proposal_ids[0]!, clusters: [r1.cluster_id, r2.cluster_id] };
}

test("an analyst merges a ring that arrived split, and it becomes one cluster", async ({ page, request }) => {
  const run = Date.now().toString(36);
  const { proposalId, clusters } = await seedSplitRing(request, run);

  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Possible split rings" })).toBeVisible();
  const review = page.locator(`a[href="#/proposals/${proposalId}"]`);
  await expect(review).toBeVisible();
  await review.click();

  await expect(page.getByRole("heading", { name: /^Merge c\d+ and c\d+\?$/ })).toBeVisible();
  await expect(page.getByTestId("evidence")).toContainText(`e2e_${run}_r3`);
  await expect(page.getByText(`e2e_${run}_r1`)).toBeVisible();
  await expect(page.getByText(`e2e_${run}_r2`)).toBeVisible();

  const merge = page.getByRole("button", { name: "Merge clusters" });
  await expect(merge).toBeDisabled();
  await page.getByLabel("Your name").fill("E2E Analyst");
  await page.getByLabel("Note (optional)").fill("Same phone and card");
  await merge.click();

  const result = page.getByTestId("merge-result");
  await expect(result).toContainText("Clusters merged");
  await expect(result).toContainText("now has 3 accounts");
  await expect(page.getByText(/Merged by E2E Analyst/)).toBeVisible();

  // The merged cluster holds the whole ring, under one of the two old IDs.
  await result.getByRole("link").click();
  const heading = page.getByRole("heading", { name: /^Cluster c\d+$/ });
  await expect(heading).toBeVisible();
  expect(clusters).toContain((await heading.textContent())!.replace("Cluster ", ""));
  const accounts = page.locator("ul.accounts li");
  await expect(accounts).toHaveCount(3);
  for (const member of ["r1", "r2", "r3"]) {
    await expect(accounts.filter({ hasText: `e2e_${run}_${member}` })).toHaveCount(1);
  }
  await expect(page.getByText("E2E Analyst merged clusters")).toBeVisible();

  // The decided proposal leaves the queue.
  await page.getByRole("link", { name: "← Review queue" }).click();
  await expect(page.getByRole("heading", { name: "Possible split rings" })).toBeVisible();
  await expect(page.locator(`a[href="#/proposals/${proposalId}"]`)).toHaveCount(0);
});

test("an analyst keeps a proposed pair apart", async ({ page, request }) => {
  const run = `k${Date.now().toString(36)}`;
  const { proposalId } = await seedSplitRing(request, run);

  await page.goto(`/#/proposals/${proposalId}`);
  await page.getByLabel("Your name").fill("E2E Analyst");
  await page.getByRole("button", { name: "Keep apart" }).click();

  await expect(page.getByText(/Kept apart by E2E Analyst/)).toBeVisible();
  await expect(page.getByText("This pair will not be proposed again.")).toBeVisible();
  await page.reload();
  await expect(page.getByText("Kept apart", { exact: true })).toBeVisible();
});
