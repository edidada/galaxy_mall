import { test, expect } from '@playwright/test';
import { required } from '../support/environment.js';
import { business } from '../support/api.js';
import { recordOrder } from '../support/order-evidence.js';

test('two concurrent payments produce at most one successful payment', async ({ request }, info) => {
  test.skip(process.env.E2E_ALLOW_WRITES !== '1', 'Creates ONE pending test order');
  const session = await business<{ token: string; userId: string }>(await request.post('/api/mall/login', {
    data: { username: required('E2E_USERNAME'), password: required('E2E_PASSWORD') },
  }));
  const headers = { 'X-Auth-Token': session.token };
  let id: string | undefined;
  try {
    const order = await business<{ orderId: string; status: string }>(await request.post('/api/gateway/orders', {
      headers, data: { userId: session.userId, productId: required('E2E_PRODUCT_ID'), quantity: 1, clientIp: '10.250.0.62' },
    }));
    expect(order.status).not.toBe('QUEUED');
    id = order.orderId;
    recordOrder({ ...order, source: 'concurrent-payment', userId: session.userId });
    await info.attach('test-order-id', { body: id, contentType: 'text/plain' });
    await expect.poll(async () => (await (await request.get(`/api/gateway/orders/${id}`, { headers })).json()).data?.status,
      { timeout: 30_000 }).toBe('WAITING_PAYMENT');
    const results = await Promise.all([request.post(`/api/gateway/payments/${id}`, { headers }),
      request.post(`/api/gateway/payments/${id}`, { headers })]);
    const bodies = await Promise.all(results.map(r => r.json()));
    const successes = bodies.filter(b => b.success === true && b.data?.status === 'SUCCESS');
    const final = await business<{ orderId: string; status: string }>(await request.get(`/api/gateway/orders/${id}`, { headers }));
    recordOrder({ ...final, source: 'concurrent-payment', userId: session.userId,
      successfulPayments: successes.length, paymentIds: successes.map(b => b.data.paymentId) });
    expect(final.status).toBe('PAID');
    expect(successes.length, 'Concurrent payment success must be exactly one').toBe(1);
  } finally {
    if (id) {
      const current = await request.get(`/api/gateway/orders/${id}`, { headers });
      if ((await current.json()).data?.status === 'WAITING_PAYMENT') await request.post(`/api/gateway/payments/${id}`, { headers });
    }
    await request.post('/api/mall/logout', { headers });
  }
});
