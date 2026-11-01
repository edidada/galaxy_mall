import { expect, type Page } from '@playwright/test';
import { required } from './environment.js';

export async function login(page: Page): Promise<void> {
  await page.goto('/login');
  await page.locator('[data-track-input="login-username"]').fill(required('E2E_USERNAME'));
  await page.locator('[data-track-input="login-password"]').fill(required('E2E_PASSWORD'));
  const response = page.waitForResponse(r => new URL(r.url()).pathname === '/api/mall/login' && r.request().method() === 'POST');
  await page.locator('[data-track="login-submit"]').click();
  expect((await (await response).json()).success, 'Login must succeed').toBe(true);
  await expect(page).not.toHaveURL(/\/login/);
  await expect(page.getByText('退出', { exact: true })).toBeVisible();
}
