import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

http.setResponseCallback(http.expectedStatuses(200, 429));

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ALGO = __ENV.ALGO || 'SLIDING_WINDOW';

const allowedHot = new Counter('allowed_hot');
const blockedHot = new Counter('blocked_hot');

export const options = {
    scenarios: {
        throughput: {
            executor: 'constant-vus',
            vus: 50,
            duration: '20s',
            exec: 'throughputTest',
        },
        hot_client: {
            executor: 'constant-vus',
            vus: 50,
            duration: '10s',
            startTime: '25s',
            exec: 'hotClientTest',
        },
    },
    thresholds: {
        'http_req_duration{scenario:throughput}': ['p(95)<100'],
        'http_reqs{scenario:throughput}': ['count>0'],
        'allowed_hot': ['count==5'],
        'checks': ['rate==1'],
    },
};

export function setup() {
    return { runId: Date.now() };
}

function sendCheck(clientId) {
    const res = http.post(
        `${BASE_URL}/api/v1/check`,
        JSON.stringify({ clientId: clientId, algorithm: ALGO }),
        { headers: { 'Content-Type': 'application/json' } }
    );
    check(res, { 'status is 200 or 429': (r) => r.status === 200 || r.status === 429 });
    return res;
}

export function throughputTest(data) {
    const id = Math.floor(Math.random() * 10000);
    sendCheck(`load-${data.runId}-${id}`);
}

export function hotClientTest(data) {
    const res = sendCheck(`hot-${data.runId}`);
    if (res.status === 200) {
        allowedHot.add(1);
    } else {
        blockedHot.add(1);
    }
}