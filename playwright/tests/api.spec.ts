import { test, expect } from '@playwright/test';
import { business, catalog, type Product } from '../support/api.js';

test('catalog has unique IDs, valid prices and usable images', async ({ request }) => {
  const items = await catalog(request);
  expect(new Set(items.map(p => p.productId)).size).toBe(items.length);
  for (const item of items) {
    expect(item.productId).toBeTruthy(); expect(item.name).toBeTruthy();
    expect(Number.isInteger(item.priceCent) && item.priceCent > 0).toBe(true);
    expect(Array.isArray(item.skus)).toBe(true);
  }
  const image = await request.get(items[0].mainImage);
  expect(image.ok()).toBe(true);
  expect(image.headers()['content-type']).toMatch(/^image\//);
});

test('catalog item and detail agree', async ({ request }) => {
  const [item] = await catalog(request);
  const detail = await business<{ item: Product }>(await request.get(`/api/mall/products/${encodeURIComponent(item.productId)}`));
  expect(detail.item.productId).toBe(item.productId);
  expect(detail.item.priceCent).toBe(item.priceCent);
});

test('missing product is a business rejection', async ({ request }) => {
  const response = await request.get('/api/mall/products/e2e-nonexistent-product-000');
  expect(response.ok()).toBe(true);
  const body = await response.json();
  expect(body.success).toBe(false);
  expect(body.data).toBeNull();
  expect(body.message).not.toContain('downstream-unavailable');
});

test('invalid session cannot access current user', async ({ request }) => {
  const response = await request.get('/api/mall/me', { headers: { 'X-Auth-Token': 'e2e-invalid-session' } });
  expect(response.ok()).toBe(true);
  const body = await response.json();
  expect(body.success).toBe(false);
  expect(body.message).toBe('session-not-found');
});
