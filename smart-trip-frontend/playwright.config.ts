import { defineConfig } from "@playwright/test";
const baseURL = process.env.SMARTTRIP_BASE_URL || "http://127.0.0.1:5173";
const port = new URL(baseURL).port || "5173";
export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  use: {
    baseURL,
    channel: "chrome",
    headless: true,
    trace: "retain-on-failure",
  },
  reporter: "list",
  webServer: {
    command: "npm run dev -- --port " + port,
    url: baseURL,
    reuseExistingServer: true,
  },
});
