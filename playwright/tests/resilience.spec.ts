import { test, expect } from '@playwright/test';

for (const fault of ['503', 'timeout'] as const) {
  test(`catalog ${fault} is visible to the user and retry recovers`, async ({ page }) => {
    const uncaught: string[] = [];
    page.on('pageerror', error => uncaught.push(error.name));
    await page.route('**/api/mall/products', route => fault === '503'
      ? route.fulfill({ status: 503, contentType: 'application/json',
        body: JSON.stringify({ success: false, message: 'downstream-unavailable:product', data: null }) })
      : route.abort('timedout'));
    await page.goto('/products');
    await expect(page.locator('.gm-alert.error')).toBeVisible();
    await expect(page.locator('[data-track="product-card"]')).toHaveCount(0);
    await page.unroute('**/api/mall/products');
    await page.getByRole('button', { name: '重试', exact: true }).click();
    await expect(page.locator('[data-track="product-card"]').first()).toBeVisible();
    await expect(page.locator('.gm-alert.error')).toHaveCount(0);
    expect(uncaught).toEqual([]);
  });
}
