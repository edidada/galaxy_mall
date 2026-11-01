import { readFileSync, writeFileSync } from 'node:fs';

const [file, id] = process.argv.slice(2);
if (!file || !/^ORD-[A-Za-z0-9_-]+$/.test(id || '')) throw new Error('Usage: node scripts/verify-audit.mjs <audit.json> <test-order-id>');
const data = JSON.parse(readFileSync(file, 'utf8').replace(/^\uFEFF/, ''));
const rows = data.orders.flatMap(table => typeof table.rows === 'string' && table.rows
  ? table.rows.split('\n').map(row => row.split('\t')) : []);
const found = rows.filter(row => row[0] === id);
const entries = typeof data.redisReservation === 'string' ? data.redisReservation.split('\n') : [];
const reservation = Object.fromEntries(entries.reduce((pairs, value, i) => i % 2 === 0 ? [...pairs, [value, entries[i + 1]]] : pairs, []));
const checks = {
  oneRowAcrossEightOrderTables: data.dbTables.split('\n').length === 8 && found.length === 1,
  persistedPaid: found[0]?.[5] === 'PAID',
  expectedTestOwner: found[0]?.[1] === 'u100',
  expectedProductQuantityAmount: found[0]?.[2] === 'sku-coffee' && found[0]?.[3] === '1' && found[0]?.[4] === '12800',
  redisReservationConfirmed: reservation.status === 'CONFIRMED' && reservation.productId === 'sku-coffee' && reservation.quantity === '1',
  matchedRocketMqBody: data.mqMatchedMessage?.bodyMatchesOrderId === true,
  consumerPassedMessageOffset: data.mqMatchedMessage?.consumerPassedOffset === true,
  noCurrentConsumerLag: /Consume Diff Total:\s*0\b/.test(data.mqConsumers || ''),
};
const verdict = { utc: new Date().toISOString(), orderId: id, checks, passed: Object.values(checks).every(Boolean),
  limitation: 'Bounded recent-message lookup; no assertion of global exactly-once delivery or persistent stock ledger.' };
writeFileSync(`${file}.verdict.json`, JSON.stringify(verdict, null, 2));
console.log(JSON.stringify(verdict, null, 2));
process.exitCode = verdict.passed ? 0 : 1;
