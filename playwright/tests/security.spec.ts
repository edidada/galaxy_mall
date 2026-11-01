import { test, expect, type APIRequestContext, type APIResponse } from '@playwright/test';
import { required } from '../support/environment.js';
import { business } from '../support/api.js';
import { recordOrder } from '../support/order-evidence.js';

type Session = { token: string; userId: string };
type Order = { orderId: string; userId: string; productId: string; status: string };
const sampleOrder = required('E2E_ORDER_ID');
if (!/^ORD-[A-Za-z0-9_-]+$/.test(sampleOrder)) throw new Error('Use an order created by this test run');

async function actor(request: APIRequestContext, other = false): Promise<Session> {
  return business<Session>(await request.post('/api/mall/login', { data: {
    username: required(other ? 'E2E_OTHER_USERNAME' : 'E2E_USERNAME'),
    password: required(other ? 'E2E_OTHER_PASSWORD' : 'E2E_PASSWORD'),
  } }));
}

async function rejected(response: APIResponse): Promise<void> {
  if ([401, 403, 404].includes(response.status())) return;
  const data = await response.json();
  expect(data.success, 'Unauthorized caller must not receive order data or perform payment').toBe(false);
  expect(data.message, 'Business failure must be authorization rejection').toMatch(/unauthorized|forbidden|access-denied|not-found/);
}

test('anonymous caller cannot read a real test order', async ({ request }) => {
  await rejected(await request.get(`/api/gateway/orders/${sampleOrder}`));
});

test('another user cannot read the test order', async ({ request }) => {
  const ownerSession = await actor(request);
  const session = await actor(request, true);
  try {
    const owner = await business<Order>(await request.get(`/api/gateway/orders/${sampleOrder}`, { headers: { 'X-Auth-Token': ownerSession.token } }));
    expect(session.userId === owner.userId, 'Use two different users').toBe(false);
    await rejected(await request.get(`/api/gateway/orders/${sampleOrder}`, { headers: { 'X-Auth-Token': session.token } }));
  } finally {
    await request.post('/api/mall/logout', { headers: { 'X-Auth-Token': session.token } });
    await request.post('/api/mall/logout', { headers: { 'X-Auth-Token': ownerSession.token } });
  }
});

test('edge alias cannot bypass order ownership', async ({ request }) => {
  const session = await actor(request, true);
  try {
    await rejected(await request.get(`/edge/orders/${sampleOrder}`, { headers: { 'X-Auth-Token': session.token } }));
  } finally {
    await request.post('/api/mall/logout', { headers: { 'X-Auth-Token': session.token } });
  }
});

test('another user cannot pay an owned pending test order', async ({ request }, info) => {
  test.skip(process.env.E2E_ALLOW_WRITES !== '1', 'Set E2E_ALLOW_WRITES=1 to create ONE owned test order');
  const owner = await actor(request);
  const other = await actor(request, true);
  expect(owner.userId === other.userId).toBe(false);
  let id: string | undefined;
  try {
    const order = await business<Order>(await request.post('/api/gateway/orders', {
      headers: { 'X-Auth-Token': owner.token }, data: { userId: owner.userId,
        productId: required('E2E_PRODUCT_ID'), quantity: 1, clientIp: '10.250.0.61' },
    }));
    expect(order.status).not.toBe('QUEUED');
    id = order.orderId;
    recordOrder({ ...order, source: 'cross-user-payment' });
    await info.attach('test-order-id', { body: id, contentType: 'text/plain' });
    await expect.poll(async () => {
      const result = await request.get(`/api/gateway/orders/${id}`, { headers: { 'X-Auth-Token': owner.token } });
      const body = await result.json(); return body.data?.status;
    }, { timeout: 30_000 }).toBe('WAITING_PAYMENT');
    const attempt = await request.post(`/api/gateway/payments/${id}`, { headers: { 'X-Auth-Token': other.token } });
    const observation = await request.get(`/api/gateway/orders/${id}`, { headers: { 'X-Auth-Token': owner.token } });
    const current = (await observation.json()).data;
    recordOrder({ ...order, status: current?.status, source: 'cross-user-payment',
      attackerUserId: other.userId, unauthorizedPaymentSucceeded: current?.status === 'PAID' });
    await rejected(attempt);
  } finally {
    // If rejection worked, complete only our own test order as its owner.
    if (id) {
      const current = await request.get(`/api/gateway/orders/${id}`, { headers: { 'X-Auth-Token': owner.token } });
      if ((await current.json()).data?.status === 'WAITING_PAYMENT') {
        await request.post(`/api/gateway/payments/${id}`, { headers: { 'X-Auth-Token': owner.token } });
      }
    }
    await request.post('/api/mall/logout', { headers: { 'X-Auth-Token': owner.token } });
    await request.post('/api/mall/logout', { headers: { 'X-Auth-Token': other.token } });
  }
});

test('a paid test order cannot produce a second successful payment', async ({ request }) => {
  const session = await actor(request);
  try {
    const current = await business<Order>(await request.get(`/api/gateway/orders/${sampleOrder}`, { headers: { 'X-Auth-Token': session.token } }));
    expect(current.status).toBe('PAID'); expect(current.userId).toBe(session.userId);
    const duplicate = await request.post(`/api/gateway/payments/${sampleOrder}`, { headers: { 'X-Auth-Token': session.token } });
    const body = await duplicate.json();
    expect(body.success).toBe(false); expect(body.message).toBe('order-not-payable');
    // This validates sequential duplicate rejection, not concurrent payment idempotency.
  } finally {
    await request.post('/api/mall/logout', { headers: { 'X-Auth-Token': session.token } });
  }
});
