import http from 'k6/http';
import { sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

if (__ENV.E2E_ENV !== 'uat' || __ENV.LOAD_ALLOW_ORDER_WRITES !== '1') {
  throw new Error('Set E2E_ENV=uat and LOAD_ALLOW_ORDER_WRITES=1 for real test orders');
}
if (__ENV.K6_DURATION || __ENV.K6_VUS) throw new Error('Unset native K6_DURATION/K6_VUS');
for (const name of ['E2E_USERNAME', 'E2E_PASSWORD', 'E2E_PRODUCT_ID']) {
  if (!__ENV[name]) throw new Error(`Missing ${name}`);
}
const origin = (__ENV.E2E_BASE_URL || 'http://192.168.31.77:30080').replace(/\/$/, '');
if (!/^https?:\/\/[^/?#@]+$/.test(origin)) throw new Error('Use an HTTP(S) origin');
const completed = new Rate('orders_completed');
const elapsed = new Trend('order_e2e_ms', true);
export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(95)', 'p(99)'],
  scenarios: { checkout: { executor: 'constant-arrival-rate', rate: 1, timeUnit: '1m',
    duration: __ENV.LOAD_DURATION || '30m', preAllocatedVUs: 1, maxVUs: 1, gracefulStop: '45s' } },
  thresholds: { orders_completed: ['rate==1'], dropped_iterations: ['count==0'],
    order_e2e_ms: ['p(95)<15000'], http_req_failed: ['rate<0.001'] },
};

function body(response) {
  if (response.status !== 200) return null;
  try { return response.json(); } catch { return null; }
}

export function setup() {
  const response = http.post(`${origin}/api/mall/login`, JSON.stringify({
    username: __ENV.E2E_USERNAME, password: __ENV.E2E_PASSWORD,
  }), { headers: { 'Content-Type': 'application/json' }, timeout: '5s' });
  const session = body(response);
  if (session?.success !== true || !session.data?.token || !session.data?.userId) throw new Error('Test login failed');
  return { token: session.data.token, userId: session.data.userId };
}

export default function (session) {
  const started = Date.now();
  const headers = { 'Content-Type': 'application/json', 'X-Auth-Token': session.token };
  const settings = { headers, timeout: '5s', tags: { name: 'checkout' } };
  let id = null;
  let ok = false;
  let stage = 'submit';
  try {
    const created = body(http.post(`${origin}/api/gateway/orders`, JSON.stringify({
      userId: session.userId, productId: __ENV.E2E_PRODUCT_ID, quantity: 1, clientIp: '10.250.0.70',
    }), settings));
    if (created?.success !== true || created.data?.status === 'QUEUED' || !created.data?.orderId) return;
    id = created.data.orderId;
    stage = 'await-ready';
    let ready = false;
    for (let i = 0; i < 25; i++) {
      const observed = body(http.get(`${origin}/api/gateway/orders/${id}`, settings));
      if (observed?.success && observed.data?.status === 'WAITING_PAYMENT') { ready = true; break; }
      if (observed?.data?.status === 'FAILED') break;
      sleep(.4);
    }
    if (!ready) return;
    stage = 'payment';
    const payment = body(http.post(`${origin}/api/gateway/payments/${id}`, null, settings));
    if (payment?.success !== true || payment.data?.status !== 'SUCCESS') return;
    stage = 'verify-paid';
    const final = body(http.get(`${origin}/api/gateway/orders/${id}`, settings));
    ok = final?.success === true && final.data?.status === 'PAID' && final.data?.userId === session.userId;
  } finally {
    completed.add(ok);
    elapsed.add(Date.now() - started);
    // Identifiers only. Never log requests, sessions or credentials.
    console.log(JSON.stringify({ utc: new Date().toISOString(), orderId: id,
      productId: __ENV.E2E_PRODUCT_ID, completed: ok, stage, elapsedMs: Date.now() - started }));
  }
}

export function teardown(session) {
  http.post(`${origin}/api/mall/logout`, null, { headers: { 'X-Auth-Token': session.token }, timeout: '5s' });
}

export function handleSummary(data) {
  const dir = __ENV.K6_OUTPUT_DIR || 'artifacts/checkout-soak';
  const metric = (name, key) => data.metrics[name]?.values?.[key] ?? 'N/A';
  const failed = Object.entries(data.metrics).flatMap(([name, item]) => Object.entries(item.thresholds || {})
    .filter(([, rule]) => !rule.ok).map(([rule]) => `${name}: ${rule}`));
  const report = ['# K3s Checkout Soak', '', `- UTC: ${new Date().toISOString()}`,
    `- Target: ${origin}`, '- Load: 1 real test order / minute',
    `- Duration: ${__ENV.LOAD_DURATION || '30m'}`, `- Completed iterations: ${metric('iterations', 'count')}`,
    `- Order success rate: ${metric('orders_completed', 'rate')}`,
    `- Dropped iterations: ${metric('dropped_iterations', 'count')}`,
    `- E2E p95 ms: ${metric('order_e2e_ms', 'p(95)')}`,
    `- E2E p99 ms: ${metric('order_e2e_ms', 'p(99)')}`,
    `- Verdict: ${failed.length ? 'FAILED' : 'PASSED'}`, '', ...failed.map(f => `- FAIL: ${f}`), '',
    'Sequential UAT checkout soak. This does not establish concurrency safety or settlement correctness.', ''].join('\n');
  return { [`${dir}/summary.json`]: JSON.stringify(data, null, 2), [`${dir}/report.md`]: report, stdout: report };
}
