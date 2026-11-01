import { mkdirSync, writeFileSync } from 'node:fs';

export function recordOrder(order: { orderId: string; [key: string]: unknown }): void {
  if (!/^ORD-[A-Za-z0-9_-]+$/.test(order.orderId)) throw new Error('Invalid evidence order ID');
  mkdirSync('artifacts/orders', { recursive: true });
  // Callers provide business identifiers/states only, never sessions or HTTP headers.
  const allowed = ['orderId', 'userId', 'productId', 'quantity', 'amountCent', 'status', 'source',
    'paymentId', 'attackerUserId', 'unauthorizedPaymentSucceeded', 'acceptedEpochMillis', 'successfulPayments', 'paymentIds'];
  const data = Object.fromEntries(allowed.filter(k => k in order).map(k => [k, order[k]]));
  writeFileSync(`artifacts/orders/${order.orderId}.json`, JSON.stringify({ utc: new Date().toISOString(), ...data }, null, 2));
}
