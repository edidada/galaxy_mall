import { test, expect } from '../support/fixtures.js';
import { regularProduct } from '../support/api.js';

test('home renders real product cards and working images', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('[data-track="header-logo"]')).toBeVisible();
  const card = page.locator('[data-track="product-card"]').first();
  await expect(card).toBeVisible();
  const image = card.locator('img');
  await expect.poll(() => image.evaluate((img: HTMLImageElement) => img.complete && img.naturalWidth > 0)).toBe(true);
});

test('search, empty result and reset work against live catalog', async ({ page, request }) => {
  const item = await regularProduct(request);
  await page.goto('/products');
  const search = page.getByRole('textbox', { name: '页内搜索', exact: true });
  await search.fill(item.name);
  await expect(page.locator('[data-track="product-card-title"]')).toHaveCount(1);
  await expect(page.locator('[data-track="product-card-title"]')).toHaveText(item.name);
  await search.fill('e2e-no-such-product-7d43');
  await expect(page.getByText('没有符合条件的商品', { exact: true })).toBeVisible();
  await search.fill('');
  await expect(page.locator('[data-track="product-card"]').first()).toBeVisible();
});

test('detail cart quantity persists across reload and can be removed', async ({ page, request }) => {
  const item = await regularProduct(request);
  await page.goto(`/products/${encodeURIComponent(item.productId)}`);
  await expect(page.locator('[data-track="detail-add-cart"]')).toBeEnabled();
  await page.locator('[data-track="detail-add-cart"]').click();
  await page.locator('[data-track="detail-go-cart"]').click();
  const quantity = page.getByRole('textbox', { name: '购买数量', exact: true });
  await expect(quantity).toHaveValue('1');
  await page.locator('[data-track="qty-plus"]').click();
  await expect(quantity).toHaveValue('2');
  await page.reload();
  await expect(quantity).toHaveValue('2');
  await page.locator('[data-track="cart-remove"]').click();
  await expect(page.getByText('购物车空空如也', { exact: true })).toBeVisible();
});

test('anonymous checkout redirects to login without submitting orders', async ({ page, request }) => {
  const item = await regularProduct(request);
  await page.goto(`/products/${encodeURIComponent(item.productId)}`);
  await page.locator('[data-track="detail-add-cart"]').click();
  await page.locator('[data-track="detail-go-cart"]').click();
  await page.locator('[data-track="cart-checkout"]').click();
  await expect(page).toHaveURL(/\/login/);
  await expect(page.locator('[data-track="login-submit"]')).toBeVisible();
});
