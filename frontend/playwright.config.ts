import { defineConfig, devices } from '@playwright/test'

// End-to-end tests run the real backend (offline AI model, its own database) and the production
// build of the app. Prerequisite: an empty PostgreSQL database, by default karname_e2e on localhost
// with user/password karname (KARNAME_E2E_DB_URL, KARNAME_E2E_DB_USER, KARNAME_E2E_DB_PASSWORD).
const BACKEND_PORT = 8091
const WEB_PORT = 4180
const MOCK_PORT = 8099

export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: true,
  workers: process.env.CI ? 2 : 3,
  retries: process.env.CI ? 1 : 0,
  forbidOnly: !!process.env.CI,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : [['list']],
  use: {
    baseURL: `http://127.0.0.1:${WEB_PORT}`,
    locale: 'fa-IR',
    timezoneId: 'Asia/Tehran',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'setup', testMatch: /global\.setup\.ts/ },
    {
      name: 'desktop',
      dependencies: ['setup'],
      testIgnore: /mobile\.spec\.ts/,
      use: { ...devices['Desktop Chrome'], viewport: { width: 1280, height: 860 } },
    },
    {
      name: 'mobile',
      dependencies: ['setup'],
      testMatch: /mobile\.spec\.ts/,
      use: { ...devices['Pixel 7'] },
    },
  ],
  webServer: [
    {
      command: 'cd ../backend && ./gradlew bootRun --console=plain --quiet',
      url: `http://127.0.0.1:${BACKEND_PORT}/actuator/health`,
      timeout: 300_000,
      reuseExistingServer: !process.env.CI,
      stdout: 'ignore',
      stderr: 'pipe',
      env: {
        KARNAME_PORT: String(BACKEND_PORT),
        KARNAME_DB_URL: process.env.KARNAME_E2E_DB_URL ?? 'jdbc:postgresql://localhost:5432/karname_e2e',
        KARNAME_DB_USER: process.env.KARNAME_E2E_DB_USER ?? 'karname',
        KARNAME_DB_PASSWORD: process.env.KARNAME_E2E_DB_PASSWORD ?? 'karname',
        KARNAME_AI_FAKE: 'true',
        KARNAME_REGISTRATIONS_PER_HOUR: '10000',
        KARNAME_SECRET_KEY: 'ZTJlLW9ubHktc2VjcmV0LWtleS1mb3ItdGVzdHMtMTI=',
        // no background jobs racing the tests (prices, recurring postings)
        KARNAME_SCHEDULING_ENABLED: 'false',
        ANTHROPIC_API_KEY: '',
      },
    },
    {
      command: `pnpm build && pnpm preview --host 127.0.0.1 --port ${WEB_PORT} --strictPort`,
      url: `http://127.0.0.1:${WEB_PORT}`,
      timeout: 300_000,
      reuseExistingServer: !process.env.CI,
      stdout: 'ignore',
      env: { KARNAME_BACKEND_URL: `http://127.0.0.1:${BACKEND_PORT}` },
    },
    {
      command: `node e2e/mock-price-server.mjs ${MOCK_PORT}`,
      url: `http://127.0.0.1:${MOCK_PORT}/health`,
      reuseExistingServer: !process.env.CI,
    },
  ],
})
