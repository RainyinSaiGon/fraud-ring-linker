import { defineConfig, devices } from "@playwright/test";
import { serviceArgs } from "../tools/dev-auth/dev-auth.mjs";
import { keys } from "./e2e/keys";

// Starts the real Java service (in-memory H2 database) and the page, then drives
// a browser through them. The service checks tokens signed with a key pair made
// for the run (see e2e/keys.ts). Build the service jar first: `./gradlew :service:bootJar` in java/.
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
      command: `java -jar ../java/service/build/libs/fraud-ring-linker-service.jar --spring.profiles.active=h2 --server.port=${servicePort} ${serviceArgs(keys.publicKey).join(" ")}`,
      url: `http://localhost:${servicePort}/actuator/health`,
      timeout: 120_000,
      reuseExistingServer: !process.env.CI,
    },
    {
      command: `npx vite --port ${webPort}`,
      url: `http://localhost:${webPort}`,
      env: { API_TARGET: `http://localhost:${servicePort}`, PORT: String(webPort) },
      timeout: 60_000,
      reuseExistingServer: !process.env.CI,
    },
  ],
});
