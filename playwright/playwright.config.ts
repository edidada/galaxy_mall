import { defineConfig, devices } from '@playwright/test';
import { targetURL, writeEnabled, required } from './support/environment.js';

const writes = writeEnabled();
const auth = process.env.E2E_AUTH === '1';
if (auth) { required('E2E_USERNAME'); required('E2E_PASSWORD'); }
const diagnostics = process.env.E2E_DIAGNOSTICS === '1';
const reportDir = process.env.E2E_REPORT_DIR || './artifacts';
if (diagnostics && process.env.E2E_ENV !== 'uat') {
  throw new Error('Sensitive diagnostics are restricted to E2E_ENV=uat');
}

export default defineConfig({
  testDir: './tests',
  outputDir: `${reportDir}/results`,
  timeout: 45_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  forbidOnly: !!process.env.CI,
  reporter: [
    ['list'],
    ['html', { outputFolder: `${reportDir}/html`, open: 'never' }],
    ['junit', { outputFile: `${reportDir}/junit.xml` }],
    ['./support/markdown-reporter.ts'],
  ],
  use: {
    baseURL: targetURL(),
    actionTimeout: 10_000,
    navigationTimeout: 20_000,
    ignoreHTTPSErrors: false,
    screenshot: 'only-on-failure',
    trace: diagnostics ? 'retain-on-failure' : 'off',
    video: diagnostics ? 'retain-on-failure' : 'off',
    locale: 'zh-CN',
  },
  projects: [
    { name: 'tooling', testMatch: /tooling\.spec\.ts/ },
    { name: 'api', testMatch: /api\.spec\.ts/ },
    { name: 'resilience', testMatch: /resilience\.spec\.ts/, use: { ...devices['Desktop Chrome'] } },
    { name: 'chromium', testMatch: /storefront\.spec\.ts/, use: { ...devices['Desktop Chrome'] } },
    { name: 'mobile', testMatch: /storefront\.spec\.ts/, use: { ...devices['Pixel 7'] } },
    ...(process.env.E2E_BROWSERS === 'all' ? [
      { name: 'firefox', testMatch: /storefront\.spec\.ts/, use: { ...devices['Desktop Firefox'] } },
      { name: 'webkit', testMatch: /storefront\.spec\.ts/, use: { ...devices['Desktop Safari'] } },
    ] : []),
    ...(auth ? [{ name: 'auth', testMatch: /auth\.spec\.ts/, use: { ...devices['Desktop Chrome'] } }] : []),
    ...(writes ? [{ name: 'transaction', testMatch: /transaction\.spec\.ts/,
      retries: 0, use: { ...devices['Desktop Chrome'] } }] : []),
    ...(process.env.E2E_SECURITY === '1' ? [{ name: 'security', testMatch: /(security|payment-race)\.spec\.ts/, retries: 0 }] : []),
  ],
});
