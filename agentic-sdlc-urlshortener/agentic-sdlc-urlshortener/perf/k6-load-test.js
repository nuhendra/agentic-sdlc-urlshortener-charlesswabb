// k6 HTTP load test for the URL shortener (realistic network-level load against a running
// instance). Install k6 (https://k6.io) and run:
//
//   mvn -pl url-shortener spring-boot:run          # in one terminal
//   k6 run perf/k6-load-test.js                    # in another
//
// Override the target and credentials with env vars, e.g.:
//   k6 run -e BASE_URL=http://localhost:8080 -e USERNAME=agent -e PASSWORD=agent123 perf/k6-load-test.js
//
// The scenario authenticates once per virtual user, then drives a realistic read-heavy mix:
// mostly redirects (the hot path), with a smaller share of creates.

import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const USERNAME = __ENV.USERNAME || 'agent';
const PASSWORD = __ENV.PASSWORD || 'agent123';

export const options = {
  scenarios: {
    ramp: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 50 },   // ramp up
        { duration: '1m', target: 50 },     // steady load
        { duration: '15s', target: 0 },     // ramp down
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],          // < 1% errors
    http_req_duration: ['p(95)<300'],        // 95% of requests under 300ms
  },
};

function login() {
  const res = http.post(`${BASE_URL}/auth/token`, JSON.stringify({ username: USERNAME, password: PASSWORD }), {
    headers: { 'Content-Type': 'application/json' },
  });
  check(res, { 'login 200': (r) => r.status === 200 });
  return res.json('token');
}

export function setup() {
  // Create one short URL up front so the read path has something to resolve.
  const token = login();
  const res = http.post(`${BASE_URL}/api/v1/urls`, JSON.stringify({ url: 'https://schwab.com/load-seed' }), {
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
  });
  check(res, { 'seed created': (r) => r.status === 201 });
  return { token, shortCode: res.json('shortCode') };
}

export default function (data) {
  // 90% reads (redirect), 10% writes (create) — a read-heavy shortener workload.
  if (Math.random() < 0.9) {
    const res = http.get(`${BASE_URL}/${data.shortCode}`, { redirects: 0 });
    check(res, { 'redirect 302': (r) => r.status === 302 });
  } else {
    const res = http.post(
      `${BASE_URL}/api/v1/urls`,
      JSON.stringify({ url: `https://schwab.com/load/${Date.now()}-${Math.random()}` }),
      { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${data.token}` } },
    );
    check(res, { 'create 201': (r) => r.status === 201 });
  }
  sleep(0.1);
}
