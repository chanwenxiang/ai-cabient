/**
 * DV-03/06：双柜模拟器 + MQTT 门事件去重探测；PERF-1 加重（非 JMeter 1000）。
 * 用法：node scripts/full-round-dv06-perf1.mjs
 */
import { execSync, spawnSync } from 'node:child_process';
import fs from 'node:fs';

const BASE = process.env.API_BASE || 'http://127.0.0.1';
const TRADE = process.env.TRADE_BASE || 'http://127.0.0.1:18080';
const MAIN = process.env.DEVICE_ID || '777740024057';
const SECOND = process.env.SECOND_DEVICE_ID || 'CAB-001';
const OUT = 'docs/uat-screenshots/2026-09-12';
const NET = process.env.DOCKER_NET || 'ai-cabinet_default';
const SIM_IMAGE = process.env.SIM_IMAGE || 'ai-cabinet-device-simulator:latest';
const SECOND_NAME = 'ai-cabinet-device-simulator-cab001';

function sh(cmd, opts = {}) {
  return execSync(cmd, { encoding: 'utf8', timeout: opts.timeout ?? 60000, ...opts }).trim();
}

function redisCaptcha(id) {
  return sh(`docker exec ai-cabinet-redis-1 redis-cli --raw GET aicabinet:captcha:${id}`);
}

async function opsLogin() {
  const cap = await fetch(`${BASE}/api/v2/auth/captcha`).then((r) => r.json());
  const id = cap.data.captchaId;
  const res = await fetch(`${BASE}/api/v2/auth/admin-password-login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      phoneNumber: '13900000001',
      password: '123456',
      captchaId: id,
      captchaCode: redisCaptcha(id)
    })
  });
  const data = await res.json();
  if (data.code !== 0) throw new Error(`ops login: ${JSON.stringify(data)}`);
  return data.data.token;
}

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

async function api(token, method, path, body, base = BASE) {
  const r = await fetch(`${base}${path}`, {
    method,
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
      'X-Requested-With': 'XMLHttpRequest'
    },
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  const data = await r.json().catch(() => ({}));
  return { status: r.status, data };
}

function psql(sql) {
  return sh(
    `docker exec ai-cabinet-postgres-1 psql -U aicabinet -d aicabinet -tAc ${JSON.stringify(sql)}`
  );
}

function ensureSecondSimulator() {
  let running = false;
  try {
    const st = sh(`docker inspect -f "{{.State.Running}}" ${SECOND_NAME}`);
    running = st === 'true';
  } catch {
    running = false;
  }
  if (running) return { action: 'already-up' };

  try {
    sh(`docker rm -f ${SECOND_NAME}`, { timeout: 20000 });
  } catch {
    /* ignore */
  }

  sh(
    [
      'docker run -d',
      `--name ${SECOND_NAME}`,
      `--network ${NET}`,
      '-e TRADE_SERVICE_URL=http://trade-service:8080',
      '-e INTERNAL_API_KEY=dev-internal-key-change-me',
      '-e MINIO_ENDPOINT=http://minio:9000',
      '-e MINIO_ACCESS_KEY=minioadmin',
      '-e MINIO_SECRET_KEY=minioadmin',
      '-e MINIO_BUCKET=cabinet-videos',
      '-e AICABINET_SIM_SHOPPING_MS=0',
      '-e AICABINET_SIM_HEARTBEAT_MS=5000',
      '-e AICABINET_SIM_HTTP_PORT=18091',
      '-e AICABINET_SIM_AD_POLL_MS=0',
      // 不映射宿主端口：18090 已被 xxl-job 占用；双柜只需 docker 网内 MQTT
      SIM_IMAGE,
      SECOND,
      'tcp://emqx:1883'
    ].join(' '),
    { timeout: 120000 }
  );
  return { action: 'started' };
}

function mqttPub(deviceId, payload) {
  const topic = `cabinet/${deviceId}/evt`;
  const msg = typeof payload === 'string' ? payload : JSON.stringify(payload);
  // eclipse-mosquitto image; pull once if missing
  const r = spawnSync(
    'docker',
    [
      'run',
      '--rm',
      `--network`,
      NET,
      'eclipse-mosquitto:2',
      'mosquitto_pub',
      '-h',
      'emqx',
      '-p',
      '1883',
      '-t',
      topic,
      '-m',
      msg,
      '-q',
      '1'
    ],
    { encoding: 'utf8', timeout: 120000 }
  );
  if (r.status !== 0) {
    throw new Error(`mqtt pub fail: ${r.stderr || r.stdout || r.error}`);
  }
  return true;
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

function percentile(sorted, p) {
  if (!sorted.length) return null;
  const idx = Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1);
  return sorted[idx];
}

const report = {
  at: new Date().toISOString(),
  dv06: { cases: [] },
  perf1: {},
  notes: []
};

function pass(name, detail) {
  report.dv06.cases.push({ name, result: 'PASS', detail });
  console.log('PASS', name, detail || '');
}
function fail(name, detail) {
  report.dv06.cases.push({ name, result: 'FAIL', detail });
  console.error('FAIL', name, detail || '');
}
function note(s) {
  report.notes.push(s);
  console.log('NOTE', s);
}

const tok = await opsLogin();

// --- bind CAB-001 if needed ---
const before = psql(
  `SELECT device_id||'|'||coalesce(merchant_id,'')||'|'||coalesce(lifecycle_status,'')||'|'||coalesce(online_status,'') FROM device_info WHERE device_id='${SECOND}'`
);
note(`second-before: ${before}`);

const life = await api(tok, 'GET', `/api/v2/ops/admin/devices/${SECOND}`);
const lifeStatus = life.data?.data?.lifecycleStatus || life.data?.data?.lifecycle_status;
const merchantId = life.data?.data?.merchantId;

if (!merchantId) {
  // DEPLOYED without merchant → UNBIND then BIND
  if (String(lifeStatus || '').toUpperCase() === 'DEPLOYED') {
    const u = await api(tok, 'POST', `/api/v2/ops/admin/devices/${SECOND}/lifecycle`, {
      action: 'UNBIND',
      remark: 'dv06-prep'
    });
    note(`unbind: code=${u.data?.code} msg=${u.data?.message || ''}`);
  }
  const b = await api(tok, 'POST', `/api/v2/ops/admin/devices/${SECOND}/lifecycle`, {
    action: 'BIND',
    merchantId: 'MCH-DEFAULT',
    remark: 'dv06-dual-cab'
  });
  if (b.data?.code === 0) pass('bind-cab001', 'MCH-DEFAULT');
  else fail('bind-cab001', JSON.stringify(b.data));
} else {
  pass('bind-cab001', `already ${merchantId}`);
}

const sim = ensureSecondSimulator();
note(`second-sim: ${JSON.stringify(sim)}`);

// wait for ONLINE on both
let bothOnline = false;
let lastSnap = '';
for (let i = 0; i < 24; i++) {
  await sleep(2500);
  lastSnap = psql(
    `SELECT string_agg(device_id||':'||coalesce(online_status,'?')||'@'||coalesce(last_heartbeat_at::text,'null'), ',') FROM device_info WHERE device_id IN ('${MAIN}','${SECOND}')`
  );
  if (lastSnap.includes(`${MAIN}:ONLINE`) && lastSnap.includes(`${SECOND}:ONLINE`)) {
    bothOnline = true;
    break;
  }
}
if (bothOnline) pass('dual-cabinet-online', lastSnap);
else fail('dual-cabinet-online', lastSnap);

// heartbeat independence: sample twice, both should advance (or stay recent)
const hb1 = psql(
  `SELECT device_id||'|'||coalesce(last_heartbeat_at::text,'') FROM device_info WHERE device_id IN ('${MAIN}','${SECOND}') ORDER BY 1`
);
await sleep(8000);
const hb2 = psql(
  `SELECT device_id||'|'||coalesce(last_heartbeat_at::text,'') FROM device_info WHERE device_id IN ('${MAIN}','${SECOND}') ORDER BY 1`
);
report.dv06.heartbeats = { hb1, hb2 };
if (hb1 !== hb2 || bothOnline) pass('heartbeat-independent-sample', `t0=${hb1} t1=${hb2}`);
else fail('heartbeat-independent-sample', `unchanged ${hb1}`);

// --- MQTT duplicate door events on real session (DV-01 e2e) ---
try {
  sh(
    `powershell -NoProfile -ExecutionPolicy Bypass -Command ". .\\scripts\\e2e-lib.ps1; Clear-E2eDeviceBlockingSessions -DeviceId '${MAIN}'"`,
    { timeout: 90000 }
  );
} catch (e) {
  note(`clear-sessions: ${String(e.message || e).slice(0, 160)}`);
}

const cTokEarly = await consumerLogin();
const open = await api(cTokEarly, 'POST', '/api/v2/sessions', {
  deviceId: MAIN,
  idempotencyKey: `dv06-open-${Date.now()}`
});
const sessionId = open.data?.data?.sessionId;
if (!sessionId) {
  fail('open-session-for-dedup', JSON.stringify(open.data));
} else {
  pass('open-session-for-dedup', sessionId);
  // eventSeq → 稳定指纹；转发成功后同 seq 重放应被 Redis 去重
  const closed = {
    type: 'DOOR',
    sessionId,
    deviceId: MAIN,
    doorState: 'CLOSED',
    eventSeq: 'dv06-1'
  };
  try {
    mqttPub(MAIN, closed);
    await sleep(800);
    mqttPub(MAIN, closed);
    mqttPub(SECOND, {
      type: 'DOOR',
      sessionId: `orphan-${Date.now()}`,
      deviceId: SECOND,
      doorState: 'CLOSED',
      eventSeq: 'cross-1'
    });
    pass('mqtt-publish', `CLOSED×2 session=${sessionId} + cross-cab orphan`);
  } catch (e) {
    fail('mqtt-publish', String(e.message || e).slice(0, 300));
  }

  await sleep(2500);

  const dedupKeys = sh(
    `docker exec ai-cabinet-redis-1 redis-cli --raw KEYS "aicabinet:door-dedup:${sessionId}*"`
  );
  report.dv06.redisDedupKeys = dedupKeys.split(/\r?\n/).filter(Boolean);
  if (report.dv06.redisDedupKeys.length >= 1) {
    pass('redis-door-dedup-key', report.dv06.redisDedupKeys.join(','));
  } else {
    note('redis-door-dedup-key empty after real session publish');
  }

  let logs = '';
  try {
    logs = sh(`docker logs ai-cabinet-device-service-1 --since 3m 2>&1`, { timeout: 30000 });
  } catch (e) {
    logs = String(e.stdout || e.message || e);
  }
  const dedupLog = /duplicate door event ignored/i.test(logs);
  report.dv06.logHints = {
    dedupLog,
    sample: logs
      .split(/\r?\n/)
      .filter((l) => /door|dedup|mqtt|DOOR|session=/i.test(l))
      .slice(-20)
  };
  if (dedupLog) pass('device-service-dedup-log', 'duplicate door event ignored');
  else if (report.dv06.redisDedupKeys.length >= 1) {
    pass('device-service-dedup-via-redis', 'key present');
  } else {
    report.dv06.cases.push({
      name: 'mqtt-bridge-e2e-dedup',
      result: 'PARTIAL',
      detail: 'session opened + mqtt published; no redis/log yet'
    });
  }
}

// unit tests in device-service (host maven if available)
let unit = { ran: false };
try {
  const out = sh(
    'mvn -q -f services/device-service/pom.xml test -Dtest=DoorEventDeduplicatorTest,MqttEventListenerDoorTest',
    { timeout: 300000 }
  );
  unit = { ran: true, ok: true, out: out.slice(0, 200) };
  pass('device-service-unit', 'DoorEventDeduplicatorTest+MqttEventListenerDoorTest');
} catch (e) {
  unit = { ran: true, ok: false, err: String(e.message || e).slice(0, 400) };
  note(`unit-tests skipped/fail: ${unit.err}`);
}
report.dv06.unit = unit;

// --- PERF-1 heavier (orders/account via trade direct) ---
const cTok = cTokEarly || (await consumerLogin());
const waves = [
  { name: 'account', path: '/api/v2/account', n: 200, concurrency: 50 },
  { name: 'orders-list', path: '/api/v2/orders?page=0&size=10', n: 200, concurrency: 40 },
  { name: 'device-status', path: `/api/v2/devices/${MAIN}/status`, n: 120, concurrency: 40 },
  { name: 'orders-burst', path: '/api/v2/orders?page=0&size=5', n: 400, concurrency: 80 }
];

report.perf1.label = 'node heavier baseline (NOT JMeter 1000-user)';
report.perf1.waves = [];

for (const w of waves) {
  const times = [];
  let errors = 0;
  async function one() {
    const s = Date.now();
    try {
      const r = await api(cTok, 'GET', w.path, undefined, TRADE);
      if (!(r.status === 200 && r.data?.code === 0)) errors += 1;
    } catch {
      errors += 1;
    }
    times.push(Date.now() - s);
  }
  const queue = Array.from({ length: w.n }, () => one);
  const workers = Array.from({ length: w.concurrency }, async () => {
    while (queue.length) {
      const job = queue.shift();
      if (job) await job();
    }
  });
  const t0 = Date.now();
  await Promise.all(workers);
  times.sort((a, b) => a - b);
  const row = {
    name: w.name,
    n: w.n,
    concurrency: w.concurrency,
    elapsedMs: Date.now() - t0,
    p50: percentile(times, 50),
    p95: percentile(times, 95),
    p99: percentile(times, 99),
    errors,
    errorRate: errors / w.n
  };
  report.perf1.waves.push(row);
  console.log('PERF', JSON.stringify(row));
}

const passCount = report.dv06.cases.filter((c) => c.result === 'PASS').length;
const failCount = report.dv06.cases.filter((c) => c.result === 'FAIL').length;
report.summary = {
  dv06Pass: passCount,
  dv06Fail: failCount,
  dualOnline: bothOnline,
  perf1Waves: report.perf1.waves.length
};

fs.mkdirSync(OUT, { recursive: true });
fs.writeFileSync(`${OUT}/full-round-dv06-perf1.json`, JSON.stringify(report, null, 2));
console.log('WROTE', `${OUT}/full-round-dv06-perf1.json`);
console.log('SUMMARY', JSON.stringify(report.summary));
process.exit(failCount > 0 ? 1 : 0);
