import { expect, type APIRequestContext, type APIResponse } from '@playwright/test';

export type Product = {
  productId: string; name: string; priceCent: number; mainImage: string;
  seckillProduct: boolean; skus: { skuId: string; stock: number }[];
};

export async function business<T>(response: Pick<APIResponse, 'ok' | 'headers' | 'json'>): Promise<T> {
  expect(response.ok(), 'HTTP response must succeed').toBe(true);
  expect(response.headers()['content-type']).toContain('application/json');
  const body = await response.json();
  expect(body.success, 'Business response must succeed even when HTTP is 200').toBe(true);
  expect(body.data, 'Business response must have data').not.toBeNull();
  return body.data as T;
}

export async function catalog(request: APIRequestContext): Promise<Product[]> {
  const items = await business<Product[]>(await request.get('/api/mall/products'));
  expect(Array.isArray(items)).toBe(true);
  expect(items.length).toBeGreaterThan(0);
  return items;
}

export async function regularProduct(request: APIRequestContext): Promise<Product> {
  const item = (await catalog(request)).find(p => !p.seckillProduct && p.skus.some(s => s.stock > 0));
  expect(item, 'A non-seckill SKU with stock is required').toBeDefined();
  return item!;
}
