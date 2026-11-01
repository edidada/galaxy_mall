import { readFile, writeFile } from 'node:fs/promises';
import { resolve, dirname, join } from 'node:path';
import { pathToFileURL } from 'node:url';

export function parseOrders(log, expected) {
  if (!Number.isInteger(expected) || expected < 1 || expected > 100) {
    throw new Error('Expected count must be an integer from 1 to 100');
  }
  const orders = [];
  for (const line of log.split(/\r?\n/).filter(Boolean)) {
    const match = /\bmsg=("(?:[^"\\]|\\.)*")\s*$/.exec(line);
    if (!match) throw new Error('Unexpected k6 console line; refusing partial evidence');
    const record = JSON.parse(JSON.parse(match[1]));
    if (!record || !/^ORD-[0-9]+-[0-9]+$/.test(record.orderId || '') ||
        record.completed !== true || record.stage !== 'verify-paid' ||
        !Number.isFinite(record.elapsedMs) || record.elapsedMs < 0 ||
        !Number.isFinite(Date.parse(record.utc)) || typeof record.productId !== 'string') {
      throw new Error('Incomplete or malformed checkout iteration');
    }
    orders.push({ orderId: record.orderId, utc: record.utc, productId: record.productId,
      elapsedMs: record.elapsedMs, completed: true });
  }
  if (orders.length !== expected) throw new Error(`Expected ${expected} orders, observed ${orders.length}`);
  if (new Set(orders.map(o => o.orderId)).size !== orders.length) throw new Error('Duplicate order ID in console log');
  if (new Set(orders.map(o => o.productId)).size !== 1) throw new Error('Mixed products in one checkout soak');
  return { expectedOrders: expected, observedOrders: orders.length, orders };
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const [logPath, count] = process.argv.slice(2);
  try {
    if (!logPath) throw new Error('Usage: node scripts/soak-manifest.mjs <orders.log> <expected-count>');
    const manifest = parseOrders(await readFile(logPath, 'utf8'), Number(count));
    await writeFile(join(dirname(logPath), 'orders-manifest.json'), JSON.stringify(manifest, null, 2) + '\n');
    // Stdout is deliberately machine-readable for the remote audit wrapper.
    process.stdout.write(manifest.orders.map(o => o.orderId).join(','));
  } catch (error) {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 1;
  }
}
