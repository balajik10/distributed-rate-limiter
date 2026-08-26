import http from 'k6/http';
import {check} from 'k6';
import {Counter, Rate} from 'k6/metrics';

const allowed = new Counter('allowed_responses');
const denied = new Counter('denied_responses');
const degraded = new Counter('degraded_responses');
const unavailable = new Counter('unavailable_responses');
const unexpected = new Counter('unexpected_responses');
const protocolOk = new Rate('protocol_ok');

const baseUrl = __ENV.BASE_URL || 'http://app:8080';
const policy = __ENV.POLICY_ID || 'benchmark-token-strict';
const cardinality = Number(__ENV.KEY_CARDINALITY || 1);
const rate = Number(__ENV.RPS || 100);
const vus = Number(__ENV.VUS || 20);
const duration = __ENV.DURATION || '15s';
const scenario = __ENV.SCENARIO || 'strict-hot-key';
const runId = __ENV.RUN_ID || `${Date.now()}`;
const configuredBatch = Number(__ENV.CONFIGURED_BATCH || 1);
const effectiveCardinality = scenario === 'strict-hot-key' || scenario === 'leased-hot-key' ? 1 : cardinality;

export const options = {
  scenarios: {
    benchmark: {
      executor: 'constant-arrival-rate',
      rate,
      timeUnit: '1s',
      duration,
      preAllocatedVUs: vus,
      maxVUs: Math.max(vus, Math.min(vus * 4, 10000)),
    },
  },
  thresholds: {
    checks: ['rate==1'],
    protocol_ok: ['rate==1'],
    unexpected_responses: ['count==0'],
  },
  summaryTrendStats: ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

function choosePolicy() {
  if (scenario !== 'algorithms') return policy;
  const choices = ['benchmark-token-strict', 'benchmark-log-strict', 'benchmark-counter-strict'];
  return choices[__ITER % choices.length];
}

function chooseKey() {
  if (scenario === 'strict-hot-key' || scenario === 'leased-hot-key') return `benchmark:${runId}:hot`;
  if (scenario === 'mixed-80-20') {
    if ((__ITER % 10) < 8) return `benchmark:${runId}:hot`;
    const coldCardinality = cardinality - 1;
    const coldOrdinal = Math.floor(__ITER / 10) * 2 + ((__ITER % 10) - 8);
    return `benchmark:${runId}:key:${coldOrdinal % coldCardinality}`;
  }
  return `benchmark:${runId}:key:${__ITER % cardinality}`;
}

export default function () {
  const headers = {'Content-Type': 'application/json', 'X-Request-Id': `k6:${__VU}:${__ITER}`};
  if (__ENV.API_KEY) headers['X-API-Key'] = __ENV.API_KEY;
  const response = http.post(`${baseUrl}/api/v1/rate-limits/check`, JSON.stringify({
    policyId: choosePolicy(),
    key: chooseKey(),
    permits: 1,
  }), {headers, responseCallback: http.expectedStatuses(200, 429, 503)});

  let body = null;
  try { body = response.json(); } catch (_) { body = null; }
  const valid = check(response, {
    'expected status': (r) => r.status === 200 || r.status === 429 || r.status === 503,
    'stable response fields': () => body && typeof body.allowed === 'boolean' && body.policyId && body.algorithm && body.source && body.reason && body.requestId,
    'request id echoed': (r) => Boolean(r.headers['X-Request-Id']),
  });
  protocolOk.add(valid);

  if (response.status === 200 && body && body.degraded) degraded.add(1);
  else if (response.status === 200) allowed.add(1);
  else if (response.status === 429) denied.add(1);
  else if (response.status === 503) unavailable.add(1);
  else unexpected.add(1);
}

export function handleSummary(data) {
  const output = {};
  output[__ENV.SUMMARY_PATH || '/results/summary.json'] = JSON.stringify(data, null, 2);
  const count = (name) => data.metrics[name] ? data.metrics[name].values.count : 0;
  const latency = data.metrics.http_req_duration ? data.metrics.http_req_duration.values : {};
  output[__ENV.SUMMARY_MARKDOWN_PATH || '/results/summary.md'] = [
    '# Distributed Rate Limiter benchmark',
    '',
    `- Run ID: \`${runId}\``,
    `- Scenario: \`${scenario}\``,
    `- Policy: \`${policy}\``,
    `- Offered rate: ${rate} requests/second`,
    `- VUs: ${vus}`,
    `- Duration: \`${duration}\``,
    `- Effective key cardinality: ${effectiveCardinality}`,
    `- Configured maximum batch: ${configuredBatch}`,
    `- Completed requests: ${count('http_reqs')}`,
    `- Completed request rate: ${data.metrics.http_reqs ? data.metrics.http_reqs.values.rate : 'n/a'} requests/second`,
    `- Allowed / denied / degraded / unavailable / unexpected: ${count('allowed_responses')} / ${count('denied_responses')} / ${count('degraded_responses')} / ${count('unavailable_responses')} / ${count('unexpected_responses')}`,
    `- Latency ms (p50 / p95 / p99 / max): ${latency.med ?? 'n/a'} / ${latency['p(95)'] ?? 'n/a'} / ${latency['p(99)'] ?? 'n/a'} / ${latency.max ?? 'n/a'}`,
    '',
    'Generated from this run’s k6 summary; no values are estimated.',
    '',
  ].join('\n');
  output.stdout = JSON.stringify({
    scenario,
    policy,
    rate,
    vus,
    duration,
    cardinality: effectiveCardinality,
    configuredBatch,
    metrics: data.metrics,
  }, null, 2);
  return output;
}
