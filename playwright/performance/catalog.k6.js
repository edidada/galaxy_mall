import http from 'k6/http';
import { check } from 'k6';
import { Rate } from 'k6/metrics';

if (__ENV.K6_ALLOW_LOAD !== '1') throw new Error('Set K6_ALLOW_LOAD=1 to send load');
// K6_DURATION/K6_VUS are native CLI settings that replace scenarios, not custom inputs.
if (__ENV.K6_DURATION || __ENV.K6_VUS) throw new Error('Unset K6_DURATION/K6_VUS; use LOAD_DURATION/LOAD_VUS');
const baseURL = (__ENV.E2E_BASE_URL || 'http://192.168.31.77:30080').replace(/\/$/, '');
if (!/^https?:\/\/[^/?#@]+$/.test(baseURL)) throw new Error('E2E_BASE_URL must be an HTTP(S) origin');
const rate = Number(__ENV.K6_RATE || '5');
const vus = Number(__ENV.LOAD_VUS || '10');
if (!Number.isInteger(rate) || rate < 1 || rate > 10000 || !Number.isInteger(vus) || vus < 1 || vus > 1000) {
  throw new Error('K6_RATE must be 1..10000 and LOAD_VUS must be 1..1000');
}
const businessSuccess = new Rate('business_success');

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(95)', 'p(99)'],
  scenarios: {
    catalog: { executor: 'constant-arrival-rate', rate, timeUnit: '1s',
      duration: __ENV.LOAD_DURATION || '30s', preAllocatedVUs: vus, maxVUs: vus,
      gracefulStop: '10s' },
  },
  thresholds: {
    http_req_failed: ['rate<0.001'],
    business_success: ['rate>0.999'],
    dropped_iterations: ['count==0'],
    http_req_duration: ['p(95)<800', 'p(99)<2000'],
  },
};

function valid(response) {
  try {
    const body = response.json();
    return response.status === 200 && body.success === true && Array.isArray(body.data) &&
      body.data.length > 0 && body.data.every(p => typeof p.productId === 'string' && p.priceCent > 0);
  } catch { return false; }
}

export function setup() {
  // Business samples start later; aggregate HTTP timings also include these warmups.
  for (let i = 0; i < 5; i++) {
    if (!valid(http.get(`${baseURL}/api/mall/products`, { timeout: '5s' }))) {
      throw new Error('Catalog warmup did not return valid business data');
    }
  }
}

export default function () {
  const response = http.get(`${baseURL}/api/mall/products`, { timeout: '5s', tags: { name: 'mall-catalog' } });
  const ok = valid(response);
  businessSuccess.add(ok);
  check(response, { 'HTTP and catalog business contract succeed': () => ok });
}

export function handleSummary(data) {
  const output = __ENV.K6_OUTPUT_DIR || 'artifacts';
  const value = (name, field) => data.metrics[name]?.values?.[field] ?? 'N/A';
  const failed = Object.entries(data.metrics).flatMap(([name, metric]) =>
    Object.entries(metric.thresholds || {}).filter(([, result]) => !result.ok).map(([rule]) => `${name}: ${rule}`));
  const report = [
    '# K3s Catalog Performance Report', '', `- UTC: ${new Date().toISOString()}`,
    `- Target: ${baseURL}`, `- Offered rate: ${rate} iterations/s; one HTTP request per iteration`,
    `- Duration: ${__ENV.LOAD_DURATION || '30s'}; allocated VUs: ${vus}`,
    `- Thresholds: ${failed.length ? 'FAILED' : 'PASSED'}`,
    `- Completed iterations: ${value('iterations', 'count')}`,
    `- Business success rate: ${value('business_success', 'rate')}`,
    `- Dropped iterations: ${value('dropped_iterations', 'count')}`,
    `- HTTP p95 (ms): ${value('http_req_duration', 'p(95)')}`,
    `- HTTP p99 (ms): ${value('http_req_duration', 'p(99)')}`,
    '', ...failed.map(f => `- FAIL: ${f}`), '',
    'This measures the storefront catalog read path. It does not exercise Redis stock, MySQL writes,',
    'RocketMQ delivery, checkout or payment. A low-rate pass does not establish saturation capacity.', '',
  ].join('\n');
  return { [`${output}/k6-summary.json`]: JSON.stringify(data, null, 2),
    [`${output}/performance-report.md`]: report, stdout: report };
}
