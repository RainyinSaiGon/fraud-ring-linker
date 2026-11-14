import { defineConfig, devices } from "@playwright/test";

// Starts the real Java service (in-memory H2 database) and the page, then drives
// a browser through them. Build the service jar first: `./gradlew :service:bootJar` in java/.
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
      command: `java -jar ../java/service/build/libs/fraud-ring-linker-service.jar --spring.profiles.active=h2 --server.port=${servicePort}`,
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
