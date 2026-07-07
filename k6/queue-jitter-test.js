/**
 * Queue Jitter 부하 테스트 — Before/After 비교
 *
 * 목적:
 *   토큰 발급 후 [0, jitterMs] 랜덤 지연이 주문 API로의 Thundering Herd 완화에 효과가 있는지 측정.
 *
 * 사용법:
 *   [Before — Jitter 미적용]
 *   ./gradlew :apps:commerce-api:bootRun --args='--queue.jitter.max-ms=0'
 *   docker run --rm -v "C:\Users\USER\loop-pack-be-l2-vol4-java\k6:/scripts" grafana/k6 run \
 *     -e BASE_URL=http://host.docker.internal:8080 \
 *     -e USER_COUNT=100 -e PRODUCT_ID=1 \
 *     --summary-export=/scripts/results-jitter-0.json \
 *     /scripts/queue-jitter-test.js
 *
 *   [After — Jitter=500ms]
 *   ./gradlew :apps:commerce-api:bootRun --args='--queue.jitter.max-ms=500'
 *   docker run --rm -v "C:\Users\USER\loop-pack-be-l2-vol4-java\k6:/scripts" grafana/k6 run \
 *     -e BASE_URL=http://host.docker.internal:8080 \
 *     -e USER_COUNT=100 -e PRODUCT_ID=1 \
 *     --summary-export=/scripts/results-jitter-500.json \
 *     /scripts/queue-jitter-test.js
 *
 * 사전 준비: PRODUCT_ID로 지정한 상품이 DB에 존재하고 충분한 재고(>= USER_COUNT)를 가지고 있어야 함.
 *
 * 관찰 지표:
 *   - order_latency p50/p95/p99 — 주문 API 응답 시간 분포
 *   - order_success — 성공 주문 수 (모든 VU가 성공해야 정상)
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const BASE_URL   = __ENV.BASE_URL   || 'http://localhost:8080';
const USER_COUNT = Number(__ENV.USER_COUNT || '100');
const PRODUCT_ID = Number(__ENV.PRODUCT_ID || '1');
const PW         = 'Pass123!';
const POLL_MS    = 100; // 서버 pollAfter(1s) 대신 100ms로 고정 — READY 감지 지연을 최소화해 Jitter 효과를 왜곡하지 않음

const orderLatency = new Trend('order_latency', true);
const totalLatency = new Trend('total_latency', true);
const pollCount    = new Trend('poll_count');
const orderSuccess = new Counter('order_success');
const orderFail    = new Counter('order_fail');
const enterFail    = new Counter('enter_fail');
const readyTimeout = new Counter('ready_timeout');

export const options = {
  scenarios: {
    burst: {
      executor: 'per-vu-iterations',
      vus: USER_COUNT,
      iterations: 1,
      maxDuration: '5m',
    },
  },
  thresholds: {
    'checks': ['rate>0.9'],
  },
};

export function setup() {
  const suffix = String(Date.now()).slice(-6);
  const users = [];
  for (let i = 1; i <= USER_COUNT; i++) {
    const loginId = `k${suffix}u${i}`;
    const body = {
      loginId,
      password: PW,
      name: `k6u${i}`,
      email: `${loginId}@test.com`,
      birthDate: '2000-01-01',
      gender: 'MALE',
    };
    const res = http.post(`${BASE_URL}/api/v1/users`, JSON.stringify(body), {
      headers: { 'Content-Type': 'application/json' },
    });
    if (res.status !== 200) {
      throw new Error(`signup failed for ${loginId}: ${res.status} ${res.body}`);
    }
    users.push({ userId: res.json('data.id'), loginId });
  }
  console.log(`[setup] ${users.length} users created (loginId prefix: k${suffix}u)`);
  return { users };
}

export default function (data) {
  const idx = __VU - 1;
  const cred = data.users[idx];
  if (!cred) return;

  const t0 = Date.now();

  // 1. 대기열 진입
  const enterRes = http.post(
    `${BASE_URL}/api/v1/queue/enter`,
    JSON.stringify({ userId: cred.userId }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  if (enterRes.status !== 200) {
    enterFail.add(1);
    console.log(`[enter fail] userId=${cred.userId} status=${enterRes.status}`);
    return;
  }

  // 2. READY 될 때까지 폴링
  let polls = 0;
  let token = null;
  const MAX_POLLS = 3000; // 최대 5분 (100ms * 3000)
  while (polls < MAX_POLLS) {
    polls += 1;
    const posRes = http.get(`${BASE_URL}/api/v1/queue/position?userId=${cred.userId}`);
    if (posRes.status !== 200) {
      orderFail.add(1);
      return;
    }
    const status = posRes.json('data.status');
    if (status === 'READY') {
      token = posRes.json('data.token');
      break;
    }
    sleep(POLL_MS / 1000);
  }
  pollCount.add(polls);
  if (!token) {
    readyTimeout.add(1);
    return;
  }

  // 3. 주문 (측정 대상)
  const orderStart = Date.now();
  const orderRes = http.post(
    `${BASE_URL}/api/v1/orders`,
    JSON.stringify({ items: [{ productId: PRODUCT_ID, quantity: 1 }] }),
    {
      headers: {
        'Content-Type': 'application/json',
        'X-Loopers-LoginId': cred.loginId,
        'X-Loopers-LoginPw': PW,
        'X-Entry-Token': token,
      },
    }
  );
  const orderDurationMs = Date.now() - orderStart;
  orderLatency.add(orderDurationMs);
  totalLatency.add(Date.now() - t0);

  const ok = orderRes.status >= 200 && orderRes.status < 300;
  check(orderRes, { 'order 2xx': () => ok });
  if (ok) {
    orderSuccess.add(1);
  } else {
    orderFail.add(1);
    console.log(`[order fail] userId=${cred.userId} status=${orderRes.status} body=${(orderRes.body || '').substring(0, 200)}`);
  }
}
