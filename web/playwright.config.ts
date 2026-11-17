import { defineConfig, devices } from "@playwright/test";
import { serviceArgs } from "../tools/dev-auth/dev-auth.mjs";
import { clientId, issuer, issuerPort, keys, keysDir } from "./e2e/keys";

// Starts a local test issuer, the real Java service (in-memory H2 database) and the
// page, then drives a browser through them. The issuer and the tests sign tokens with
// a key pair made for the run, and the service trusts it (see e2e/keys.ts). Build the service jar first: `./gradlew :service:bootJar` in java/.
const servicePort = 18081;
const webPort = 5174;

export default defineConfig({
  testDir: "./e2e",
  timeout: 30_000,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
  use: {
    baseURL: `http://localhost:${webPort}`,
    trace: "retain-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: [
    {
      command: `node ../tools/dev-auth/dev-auth.mjs issuer --keys "${keysDir}" --port ${issuerPort}`,
      url: `${issuer}/.well-known/openid-configuration`,
      timeout: 30_000,
      reuseExistingServer: !process.env.CI,
    },
    {
      command: `java -jar ../java/service/build/libs/fraud-ring-linker-service.jar --spring.profiles.active=h2 --server.port=${servicePort} ${serviceArgs(keys.publicKey, issuer).join(" ")}`,
      url: `http://localhost:${servicePort}/actuator/health`,
      timeout: 120_000,
      reuseExistingServer: !process.env.CI,
    },
    {
      command: `npx vite --port ${webPort}`,
      url: `http://localhost:${webPort}`,
      env: {
        API_TARGET: `http://localhost:${servicePort}`,
        PORT: String(webPort),
        VITE_OIDC_ISSUER: issuer,
        VITE_OIDC_CLIENT_ID: clientId,
      },
      timeout: 60_000,
      reuseExistingServer: !process.env.CI,
    },
  ],
});
