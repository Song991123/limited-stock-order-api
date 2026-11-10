import http from 'k6/http';
import encoding from 'k6/encoding';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';

// HTTP 전체 경로 측정. 준비 구간과 CSRF 발급은 별도 태그로 분리.
export const options = {
  vus: Number(__ENV.STOCK_LOAD_VUS), duration: '40s', noCookiesReset: true,
  thresholds: { unexpected_responses: ['count==0'] },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(95)', 'p(99)'],
};
const createdAll = new Counter('purchase_created_all');
const accepted = new Counter('measured_created');
const rejected = new Counter('measured_rejected');
const unavailable = new Counter('measured_unavailable');
const unexpected = new Counter('unexpected_responses');
const acceptedDuration = new Trend('measured_success_ms', true);
const rejectedDuration = new Trend('measured_rejection_ms', true);
const products = JSON.parse(__ENV.STOCK_LOAD_PRODUCTS);
let csrf;
let csrfHeader;

export default function () {
  const user = __VU % 2 === 0 ? 'alice' : 'bob';
  const password = user === 'alice' ? __ENV.STOCK_ALICE_PASSWORD : __ENV.STOCK_BOB_PASSWORD;
  const authorization = `Basic ${encoding.b64encode(`${user}:${password}`)}`;
  unexpected.add(0);
  if (!csrf) {
    const token = http.get(`${__ENV.STOCK_LOAD_URL}/csrf`, {
      headers: { Authorization: authorization }, tags: { operation: 'csrf', phase: 'setup' }, timeout: '15s',
    });
    if (token.status !== 200) { unexpected.add(1); return; }
    const body = token.json(); csrf = body.token; csrfHeader = body.headerName;
  }
  const measured = exec.instance.currentTestRunDuration >= 10000;
  const response = http.post(`${__ENV.STOCK_LOAD_URL}/orders`, JSON.stringify({
    productId: products[(__VU + __ITER) % products.length], quantity: 1,
  }), {
    headers: { Authorization: authorization, [csrfHeader]: csrf, 'Content-Type': 'application/json',
      'Idempotency-Key': `${__ENV.STOCK_LOAD_CASE}-${__VU}-${__ITER}` },
    tags: { operation: 'purchase', phase: measured ? 'measurement' : 'warmup' }, timeout: '15s',
  });
  if (response.status === 201) {
    createdAll.add(1);
    if (measured) { accepted.add(1); acceptedDuration.add(response.timings.duration); }
  } else if (response.status === 429 || response.status === 503) {
    let code;
    try { code = response.json('code'); } catch (_) { unexpected.add(1); return; }
    const expected = response.status === 429 ? 'TOO_MANY_REQUESTS' : 'DATABASE_UNAVAILABLE';
    if (code !== expected || !response.headers['Retry-After']) unexpected.add(1);
    if (measured) {
      rejected.add(1); rejectedDuration.add(response.timings.duration);
      if (response.status === 503) unavailable.add(1);
    }
  } else { unexpected.add(1); }
}

export function handleSummary(data) {
  return { [__ENV.STOCK_LOAD_SUMMARY]: JSON.stringify(data, null, 2) };
}
