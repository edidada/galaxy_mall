import { test, expect } from '../support/fixtures.js';
import { login } from '../support/login.js';
import { required } from '../support/environment.js';
import { catalog } from '../support/api.js';
import { recordOrder } from '../support/order-evidence.js';

test('UI checkout reaches WAITING_PAYMENT and payment reaches PAID', async ({ page, request }, info) => {
  test.setTimeout(90_000);
  const product = (await catalog(request)).find(p => p.productId === required('E2E_PRODUCT_ID'));
  expect(product, 'Dedicated test product must exist').toBeDefined();
  expect(product!.seckillProduct, 'Use an ordinary test SKU').toBe(false);
  expect(product!.skus.some(s => s.stock > 0), 'Test product must have stock').toBe(true);
  await login(page);
  await page.goto(`/products/${encodeURIComponent(required('E2E_PRODUCT_ID'))}`);
  await page.locator('[data-track="detail-buy-now"]').click();
  await expect(page).toHaveURL(/\/checkout/);
  const created = page.waitForResponse(r => new URL(r.url()).pathname === '/api/gateway/orders' && r.request().method() === 'POST');
  await page.locator('[data-track="checkout-submit"]').click();
  const body = await (await created).json();
  expect(body.success).toBe(true);
  expect(body.data.status, 'QUEUED is not a persisted order').not.toBe('QUEUED');
  const orderId: string = body.data.orderId;
  recordOrder({ ...body.data, source: 'ui-transaction' });
  await info.attach('test-order-id', { body: orderId, contentType: 'text/plain' });
  await expect(page).toHaveURL(new RegExp(`/pay/${orderId}$`));
  const pay = page.locator('[data-track="pay-confirm"]');
  await expect(pay).toBeEnabled({ timeout: 30_000 });
  const payment = page.waitForResponse(r => new URL(r.url()).pathname === `/api/gateway/payments/${orderId}`);
  await pay.click();
  const paid = await (await payment).json();
  expect(paid.success).toBe(true); expect(paid.data.status).toBe('SUCCESS');
  await expect(page.getByText('✓ 支付成功，订单已进入 PAID', { exact: true })).toBeVisible();
  await expect.poll(async () => {
    const response = await page.request.get(`/api/gateway/orders/${orderId}`);
    const data = await response.json();
    return data.success ? data.data.status : 'FAILED';
  }, { timeout: 30_000 }).toBe('PAID');
  recordOrder({ ...body.data, source: 'ui-transaction', status: 'PAID', paymentId: paid.data.paymentId });
});
