import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseOrders } from './soak-manifest.mjs';

const row = { utc: '2026-11-01T01:03:35.470Z', orderId: 'ORD-1793495015095-1002',
  productId: 'sku-coffee', completed: true, stage: 'verify-paid', elapsedMs: 613 };
const line = (record = row) => `time="2026-11-01T09:03:35+08:00" level=info msg=${JSON.stringify(JSON.stringify(record))}`;

test('parses k6 escaped JSON without retaining unknown fields', () => {
  const parsed = parseOrders(line({ ...row, token: 'must-not-retain' }) + '\r\n', 1);
  assert.equal(parsed.orders[0].orderId, row.orderId);
  assert.equal(JSON.stringify(parsed).includes('must-not-retain'), false);
});
test('rejects incomplete, missing and duplicated iterations', () => {
  assert.throws(() => parseOrders(line({ ...row, completed: false }), 1), /Incomplete/);
  assert.throws(() => parseOrders(line(), 2), /Expected 2/);
  assert.throws(() => parseOrders(`${line()}\n${line()}`, 2), /Duplicate/);
});
test('does not silently ignore malformed or unrelated console lines', () => {
  assert.throws(() => parseOrders(`${line()}\nerror without structured result`, 1), /Unexpected/);
  assert.throws(() => parseOrders(line({ ...row, orderId: null }), 1), /Incomplete/);
  assert.throws(() => parseOrders(line({ ...row, elapsedMs: -1 }), 1), /Incomplete/);
  assert.throws(() => parseOrders(line(), 0), /Expected count/);
});
