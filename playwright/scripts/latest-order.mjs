import { readdirSync, readFileSync } from 'node:fs';
const orders = readdirSync('artifacts/orders').filter(f => /^ORD-[A-Za-z0-9_-]+\.json$/.test(f))
  .map(f => JSON.parse(readFileSync(`artifacts/orders/${f}`, 'utf8')))
  .filter(o => o.source === 'ui-transaction' && o.status === 'PAID' && Date.now() - Date.parse(o.utc) < 10 * 60_000)
  .sort((a, b) => Date.parse(b.utc) - Date.parse(a.utc));
if (!orders.length) throw new Error('No recently PAID UI test order from this run');
console.log(orders[0].orderId);
