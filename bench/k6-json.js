import http from 'k6/http';
import { check } from 'k6';
import { Trend, Counter, Rate } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://127.0.0.1:8080';
const KEY = __ENV.API_KEY || 'sk-demo';
const ENGINE = __ENV.ENGINE || 'unknown';
const GOV = __ENV.GOVERNANCE || 'on';
const RUNTIME = __ENV.RUNTIME || 'jvm';
// PAYLOAD_BYTES=4096 turns the wire profile into a realistic RAG-sized prompt.
// Default 0 keeps the payload byte-for-byte identical to every historical run.
const PAD = Number(__ENV.PAYLOAD_BYTES || 0) > 0
  ? 'x'.repeat(Number(__ENV.PAYLOAD_BYTES)) : '';

// Unique per VU AND per k6 process. A bare vu/iter pair repeats across warmup
// and rounds, letting the exact-hash semantic cache turn a governance-cost
// measurement into a cache-hit measurement. Init context runs once per VU.
const RUN_TAG = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;

const latency = new Trend('gateway_latency_ms', true);
const requests = new Counter('gateway_requests');
const failures = new Rate('gateway_failure_rate');

export const options = {
  scenarios: {
    load: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 50),
      duration: __ENV.DURATION || '30s',
    },
  },
  thresholds: {
    gateway_failure_rate: ['rate<0.01'],
  },
  tags: { engine: ENGINE, governance: GOV, runtime: RUNTIME, mode: 'json' },
};

export default function () {
  // Unique payload per iteration: avoids semantic-cache hits skewing the
  // "governance on" measurement into a cache benchmark.
  const payload = JSON.stringify({
    model: 'qwen-plus',
    messages: [{ role: 'user', content: `benchmark non-stream run=${RUN_TAG} vu=${__VU} iter=${__ITER}${PAD}` }],
    stream: false,
  });
  const res = http.post(`${BASE}/v1/chat/completions`, payload, {
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${KEY}` },
    tags: { engine: ENGINE, governance: GOV, runtime: RUNTIME, mode: 'json' },
    timeout: '60s',
  });
  const ok = check(res, { 'status is 200': (r) => r.status === 200 });
  latency.add(res.timings.duration);
  requests.add(1);
  failures.add(ok ? 0 : 1);
}
