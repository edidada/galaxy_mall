import { test, expect } from '../support/fixtures.js';
import { login } from '../support/login.js';

test('login survives page reload and logout revokes the session', async ({ page }) => {
  await login(page);
  const restored = page.waitForResponse(r => new URL(r.url()).pathname === '/api/mall/me');
  await page.reload();
  expect((await (await restored).json()).success).toBe(true);
  const cached = await page.evaluate(() => JSON.parse(localStorage.getItem('gm_session') || 'null'));
  expect(typeof cached?.token === 'string').toBe(true);
  const logout = page.waitForResponse(r => new URL(r.url()).pathname === '/api/mall/logout');
  await page.getByText('退出', { exact: true }).click();
  expect((await (await logout).json()).success).toBe(true);
  const response = await page.request.get('/api/mall/me', { headers: { 'X-Auth-Token': cached.token } });
  expect((await response.json()).message).toBe('session-not-found');
  await expect(page.getByText('你好，请登录', { exact: true })).toBeVisible();
});
