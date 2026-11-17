import { test as base, type Page } from "@playwright/test";
import { readFileSync } from "node:fs";
import { mintToken, type Role } from "../../tools/dev-auth/dev-auth.mjs";
import { keys } from "./keys";

export function token(sub: string, roles: Role[]): string {
  return mintToken(readFileSync(keys.privateKey, "utf8"), { sub, roles, minutes: 30 });
}

/** Signs the page in as `sub` before any of its scripts run, as if a token had been pasted. */
export async function signInAs(page: Page, sub: string, roles: Role[]) {
  await page.addInitScript((value) => sessionStorage.setItem("linker.token", value), token(sub, roles));
}

export const ANALYST = "e2e-analyst";

/**
 * `request` talks to the service as an ingest client, as the bank's systems would.
 * `page` is signed in as an analyst; a test can sign in as someone else with
 * `signInAs` before its first `goto`, because the later init script wins.
 */
export const test = base.extend({
  request: async ({ playwright }, use) => {
    const context = await playwright.request.newContext({
      extraHTTPHeaders: { Authorization: `Bearer ${token("e2e-ingest", ["ingest"])}` },
    });
    await use(context);
    await context.dispose();
  },
  page: async ({ page }, use) => {
    await signInAs(page, ANALYST, ["analyst"]);
    await use(page);
  },
});

export { expect } from "@playwright/test";
