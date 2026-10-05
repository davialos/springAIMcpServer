import { defineConfig } from '@playwright/test';

/**
 * End-to-end tests run against a LIVE stack (the Docker stack, or the same services started natively): real services, real
 * PostgreSQL, real protobuf. E2E_BASE_URL is the UI's origin (default: the nginx of the stack on :8080).
 * PLAYWRIGHT_CHROMIUM_PATH points at a browser binary when the one Playwright expects is not installed.
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:8080',
    trace: 'retain-on-failure',
    launchOptions: {
      executablePath: process.env.PLAYWRIGHT_CHROMIUM_PATH || undefined,
      args: ['--no-sandbox'],
    },
  },
});
