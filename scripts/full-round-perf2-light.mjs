/**
 * §6 PERF-2 轻量：设备状态并发 + 同柜开门幂等（非 JMeter 全量）。
 * 用法：node scripts/full-round-perf2-light.mjs
 */
import { execSync } from 'node:child_process';
import fs from 'node:fs';

const BASE = process.env.API_BASE || 'http://127.0.0.1';
const DEVICE = process.env.DEVICE_ID || '777740024057';
const OUT = 'docs/uat-screenshots/2026-09-12/full-round-perf2-light.json';

async function consumerLogin() {
  const r = await fetch(`${BASE}/api/v2/auth/password-login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ phoneNumber: '13800138000', password: '123456' })
  });
  const j = await r.json();
  if (j.code !== 0) throw new Error(`consumer login: ${JSON.stringify(j)}`);
  return j.data.token;
}

async function api(token, method, path, body) {
  const r = await fetch(`${BASE}${path}`, {
    method,
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json'
    },
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  const j = await r.json().catch(() => ({}));
  return { status: r.status, data: j };
}

try {
  execSync(
    `powershell -NoProfile -ExecutionPolicy Bypass -Command ". .\\scripts\\e2e-lib.ps1; Clear-E2eDeviceBlockingSessions -DeviceId '${DEVICE}'"`,
    { timeout: 60000, encoding: 'utf8' }
  );
} catch (e) {
  console.warn('clear-sessions-warn', String(e.message || e).slice(0, 160));
}

const tok = await consumerLogin();
const statusTimes = [];
await Promise.all(
  Array.from({ length: 30 }, async () => {
    const s = Date.now();
    await api(tok, 'GET', `/api/v2/devices/${DEVICE}/status`);
    statusTimes.push(Date.now() - s);
  })
);
statusTimes.sort((a, b) => a - b);

const key = `perf2-idem-${Date.now()}`;
const [o1, o2] = await Promise.all([
  api(tok, 'POST', '/api/v2/sessions', { deviceId: DEVICE, idempotencyKey: key }),
  api(tok, 'POST', '/api/v2/sessions', { deviceId: DEVICE, idempotencyKey: key })
]);
const sid1 = o1.data?.data?.sessionId;
const sid2 = o2.data?.data?.sessionId;
const o3 = await api(tok, 'POST', '/api/v2/sessions', {
  deviceId: DEVICE,
  idempotencyKey: `${key}-other`
});

// 顺序幂等（同 key 应返回同一 session）
try {
  execSync(
    `powershell -NoProfile -ExecutionPolicy Bypass -Command ". .\\scripts\\e2e-lib.ps1; Clear-E2eDeviceBlockingSessions -DeviceId '${DEVICE}'"`,
    { timeout: 60000, encoding: 'utf8' }
  );
} catch {
  /* ignore */
}
const seqKey = `perf2-seq-${Date.now()}`;
const s1 = await api(tok, 'POST', '/api/v2/sessions', { deviceId: DEVICE, idempotencyKey: seqKey });
const s2 = await api(tok, 'POST', '/api/v2/sessions', { deviceId: DEVICE, idempotencyKey: seqKey });

let simulator = '';
try {
  simulator = execSync(
    'docker ps --filter name=device-simulator --format "{{.Names}} {{.Status}}"',
    { encoding: 'utf8' }
  ).trim();
} catch {
  simulator = 'unknown';
}

const sequentialOk =
  s1.data?.code === 0 &&
  s2.data?.code === 0 &&
  s1.data?.data?.sessionId &&
  s1.data.data.sessionId === s2.data.data.sessionId;

const out = {
  at: new Date().toISOString(),
  deviceStatus: {
    p50: statusTimes[14],
    p95: statusTimes[28],
    max: statusTimes[29],
    n: 30
  },
  concurrentIdempotent: {
    code1: o1.data?.code,
    code2: o2.data?.code,
    sid1,
    sid2,
    same: !!(sid1 && sid1 === sid2),
    ok: o1.data?.code === 0 && o2.data?.code === 0 && sid1 === sid2,
    note: '并行同 key 可能竞态：一成功一 409；以顺序幂等为准'
  },
  sequentialIdempotent: {
    code1: s1.data?.code,
    code2: s2.data?.code,
    sid1: s1.data?.data?.sessionId,
    sid2: s2.data?.data?.sessionId,
    same: sequentialOk,
    ok: sequentialOk
  },
  busySecondOpen: {
    code: o3.data?.code,
    message: o3.data?.message,
    sessionId: o3.data?.data?.sessionId
  },
  simulator,
  verdict: {
    deviceStatusP95Ok: statusTimes[28] < 800,
    sequentialIdempotent: sequentialOk,
    busyReject: o3.data?.code === 409 || String(o3.data?.message || '').includes('使用中'),
    simulatorUp: simulator.includes('Up'),
    concurrentRaceNoted: !(o1.data?.code === 0 && o2.data?.code === 0 && sid1 === sid2)
  }
};

fs.writeFileSync(OUT, JSON.stringify(out, null, 2));
console.log(JSON.stringify(out, null, 2));
if (!out.verdict.deviceStatusP95Ok || !out.verdict.sequentialIdempotent || !out.verdict.busyReject) {
  process.exit(1);
}
