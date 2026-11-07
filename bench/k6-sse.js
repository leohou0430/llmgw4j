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

// Unique per VU AND per k6 process, so warmup / round payloads never repeat
// into the exact-hash semantic cache.
const RUN_TAG = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;

const latency = new Trend('gateway_latency_ms', true);
const requests = new Counter('gateway_requests');
const failures = new Rate('gateway_failure_rate');

export const options = {
  scenarios: {
    sse: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 50),
      duration: __ENV.DURATION || '30s',
    },
  },
  thresholds: {
    gateway_failure_rate: ['rate<0.01'],
  },
  tags: { engine: ENGINE, governance: GOV, runtime: RUNTIME, mode: 'sse' },
};

export default function () {
  const payload = JSON.stringify({
    model: 'qwen-plus',
    messages: [{ role: 'user', content: `benchmark streaming run=${RUN_TAG} vu=${__VU} iter=${__ITER}${PAD}` }],
    stream: true,
  });
  const res = http.post(`${BASE}/v1/chat/completions`, payload, {
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${KEY}` },
    tags: { engine: ENGINE, governance: GOV, runtime: RUNTIME, mode: 'sse' },
    timeout: '60s',
  });
  // Includes('[DONE]') alone can be fooled by a gateway-synthesized terminator
  // or by the words "without [DONE]" inside an in-band error. Require the
  // real terminator LINE and explicitly forbid any in-band error frame, so a
  // silently truncated stream shows up in gateway_failure_rate.
  const ok = check(res, {
    'status is 200': (r) => r.status === 200,
    'stream completed': (r) => typeof r.body === 'string' && r.body.includes('data: [DONE]'),
    'no in-band error': (r) => typeof r.body === 'string' && !r.body.includes('event: error'),
  });
  latency.add(res.timings.duration);
  requests.add(1);
  failures.add(ok ? 0 : 1);
}
