import { defineConfig, devices } from "@playwright/test";
export default defineConfig({
  testDir: "./e2e",
  fullyParallel: true,
  use: { baseURL: "http://127.0.0.1:4173", trace: "retain-on-failure" },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: {
    command: "npm run dev -- --host 127.0.0.1 --port 4173 --strictPort",
    url: "http://127.0.0.1:4173",
    reuseExistingServer: false,
    env: { VITE_PLATFORM_API_URL: "http://127.0.0.1:4173", VITE_OIDC_ISSUER_URL: "https://identity.example.test",
      VITE_OIDC_CLIENT_ID: "dashboard-test", VITE_PLATFORM_WS_URL: "ws://127.0.0.1:4173" },
  },
});
