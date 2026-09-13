/**
 * PERF-1 千级并发基线 + PERF-3 MinIO 上传 + PERF-4 vision 识别深压。
 * 本机无 JMeter 时用 node 等价口径（USERS/RAMP/门槛对齐 MASTER §6）；
 * 若有 JMeter，可另跑 scripts/perf/order_read_scale.jmx。
 *
 * 用法：node scripts/full-round-perf134-scale.mjs
 * 环境变量：USERS=1000 RAMP_MS=60000 HOLD_MS=30000 TRADE_BASE=http://127.0.0.1:18080
 */
import { execSync, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

const TRADE = process.env.TRADE_BASE || 'http://127.0.0.1:18080';
const BASE = process.env.API_BASE || 'http://127.0.0.1';
const VISION = process.env.VISION_BASE || 'http://127.0.0.1:18082';
const VISION_KEY = process.env.VISION_API_KEY || 'dev-vision-key-change-me';
const MINIO = process.env.MINIO_ENDPOINT || 'http://127.0.0.1:9000';
const USERS = Number(process.env.USERS || 1000);
const RAMP_MS = Number(process.env.RAMP_MS || 60_000);
const HOLD_MS = Number(process.env.HOLD_MS || 30_000);
const OUT = 'docs/uat-screenshots/2026-09-12';
const NET = process.env.DOCKER_NET || 'ai-cabinet_default';
const DEVICE = process.env.DEVICE_ID || '777740024057';

function percentile(sorted, p) {
  if (!sorted.length) return null;
  const idx = Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1);
  return sorted[idx];
}

function summarize(times, errors, label, n) {
  const sorted = [...times].sort((a, b) => a - b);
  return {
    label,
    n,
    errors,
    errorRate: n ? errors / n : 0,
    p50: percentile(sorted, 50),
    p95: percentile(sorted, 95),
    p99: percentile(sorted, 99),
    max: sorted[sorted.length - 1] ?? null
  };
}

async function consumerLogin() {
  const r = await fetch(`${BASE}/api/v2/auth/password-login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ phoneNumber: '13800138000', password: '123456' })
  });
  const j = await r.json();
  if (j.code !== 0) throw new Error(`login: ${JSON.stringify(j)}`);
  return j.data.token;
}

async function get(token, url) {
  const s = Date.now();
  try {
    const r = await fetch(url, {
      headers: { Authorization: `Bearer ${token}`, 'X-Requested-With': 'XMLHttpRequest' }
    });
    const j = await r.json().catch(() => ({}));
    const ok = r.status === 200 && (j.code === 0 || j.code === undefined);
    return { ms: Date.now() - s, ok, status: r.status };
  } catch {
    return { ms: Date.now() - s, ok: false, status: 0 };
  }
}

const report = {
  at: new Date().toISOString(),
  label: 'PERF-1/3/4 scale (node; jmeter optional via scripts/perf/order_read_scale.jmx)',
  thresholds: { perf1_p95_ms: 800, perf1_error_rate: 0.001 },
  perf1: {},
  perf3: {},
  perf4: {},
  jmeter: { attempted: false }
};

const tok = await consumerLogin();
console.log('login ok');

// ---------- PERF-1: ramp to USERS over RAMP_MS, hold HOLD_MS ----------
{
  const paths = [
    `${TRADE}/api/v2/orders?page=0&size=5`,
    `${TRADE}/api/v2/account`,
    `${TRADE}/api/v2/devices/${DEVICE}/status`
  ];
  const times = [];
  let errors = 0;
  let total = 0;
  const start = Date.now();
  const endAt = start + RAMP_MS + HOLD_MS;
  let active = 0;
  let nextVuAt = start;
  const vuGap = RAMP_MS / USERS;
  const workers = [];

  async function vuLoop(id) {
    active += 1;
    let i = 0;
    while (Date.now() < endAt) {
      const url = paths[i % paths.length];
      i += 1;
      const r = await get(tok, url);
      total += 1;
      times.push(r.ms);
      if (!r.ok) errors += 1;
      // light think time to avoid pure spin
      await new Promise((r) => setTimeout(r, 20));
    }
    active -= 1;
  }

  console.log(`PERF-1 ramp ${USERS} VU over ${RAMP_MS}ms, hold ${HOLD_MS}ms`);
  while (Date.now() < start + RAMP_MS) {
    while (workers.length < USERS && Date.now() >= nextVuAt) {
      workers.push(vuLoop(workers.length));
      nextVuAt += vuGap;
    }
    await new Promise((r) => setTimeout(r, 25));
  }
  // ensure all VUs started
  while (workers.length < USERS) {
    workers.push(vuLoop(workers.length));
  }
  await Promise.all(workers);
  const elapsed = Date.now() - start;
  const sum = summarize(times, errors, 'perf1-read-mix', total);
  sum.users = USERS;
  sum.rampMs = RAMP_MS;
  sum.holdMs = HOLD_MS;
  sum.elapsedMs = elapsed;
  sum.approxTps = total / (elapsed / 1000);
  sum.pass =
    sum.p95 != null &&
    sum.p95 < report.thresholds.perf1_p95_ms &&
    sum.errorRate < report.thresholds.perf1_error_rate;
  report.perf1 = sum;
  console.log('PERF-1', JSON.stringify(sum));
}

// ---------- PERF-3: concurrent MinIO PUT via mc ----------
{
  const stamp = Date.now();
  const prefix = `perf134/${stamp}`;
  const localFile = path.resolve('testdata/bottle.jpg');
  const n = Number(process.env.PERF3_N || 80);
  const concurrency = Number(process.env.PERF3_C || 20);
  const times = [];
  let errors = 0;

  // alias once
  spawnSync(
    'docker',
    [
      'run',
      '--rm',
      `--network`,
      NET,
      'minio/mc',
      'alias',
      'set',
      'local',
      'http://minio:9000',
      'minioadmin',
      'minioadmin'
    ],
    { encoding: 'utf8' }
  );

  async function uploadOne(i) {
    const key = `${prefix}/obj-${i}.jpg`;
    const s = Date.now();
    const r = spawnSync(
      'docker',
      [
        'run',
        '--rm',
        `--network`,
        NET,
        '-v',
        `${localFile}:/data/bottle.jpg:ro`,
        'minio/mc',
        'cp',
        '/data/bottle.jpg',
        `local/cabinet-videos/${key}`
      ],
      { encoding: 'utf8', timeout: 120000 }
    );
    const ms = Date.now() - s;
    times.push(ms);
    if (r.status !== 0) {
      errors += 1;
      if (errors <= 3) console.warn('minio-fail', r.stderr?.slice(0, 200));
    }
    return key;
  }

  console.log(`PERF-3 MinIO upload n=${n} c=${concurrency}`);
  const queue = Array.from({ length: n }, (_, i) => i);
  const t0 = Date.now();
  await Promise.all(
    Array.from({ length: concurrency }, async () => {
      while (queue.length) {
        const i = queue.shift();
        if (i === undefined) break;
        await uploadOne(i);
      }
    })
  );
  const sum = summarize(times, errors, 'perf3-minio-put', n);
  sum.elapsedMs = Date.now() - t0;
  sum.prefix = `cabinet-videos/${prefix}`;
  sum.objectBytes = fs.statSync(localFile).size;
  sum.pass = sum.errorRate === 0;
  report.perf3 = sum;
  console.log('PERF-3', JSON.stringify(sum));
}

// ---------- PERF-4: concurrent vision recognize ----------
{
  const n = Number(process.env.PERF4_N || 200);
  const concurrency = Number(process.env.PERF4_C || 40);
  const times = [];
  let errors = 0;
  const t0 = Date.now();

  async function one(i) {
    const s = Date.now();
    try {
      const r = await fetch(`${VISION}/api/v2/vision/recognize`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-Internal-Api-Key': VISION_KEY
        },
        body: JSON.stringify({
          session_id: `perf4-${Date.now()}-${i}-${crypto.randomBytes(3).toString('hex')}`,
          device_id: DEVICE,
          video_uri: 'minio://cabinet-videos/testdata/bottle.jpg'
        })
      });
      const j = await r.json().catch(() => ({}));
      times.push(Date.now() - s);
      if (!(r.status === 200 && j.task_id)) errors += 1;
    } catch {
      times.push(Date.now() - s);
      errors += 1;
    }
  }

  console.log(`PERF-4 vision recognize n=${n} c=${concurrency}`);
  const queue = Array.from({ length: n }, (_, i) => i);
  await Promise.all(
    Array.from({ length: concurrency }, async () => {
      while (queue.length) {
        const i = queue.shift();
        if (i === undefined) break;
        await one(i);
      }
    })
  );
  const sum = summarize(times, errors, 'perf4-recognize', n);
  sum.elapsedMs = Date.now() - t0;
  sum.approxTps = n / (sum.elapsedMs / 1000);
  sum.pass = sum.errorRate < 0.01 && sum.p95 != null && sum.p95 < 2000;
  report.perf4 = sum;
  console.log('PERF-4', JSON.stringify(sum));
}

// ---------- optional: try JMeter docker if image pullable quickly ----------
{
  const jmx = path.resolve('scripts/perf/order_read_scale.jmx');
  if (fs.existsSync(jmx) && process.env.RUN_JMETER === '1') {
    report.jmeter.attempted = true;
    const outDir = path.resolve(`${OUT}/jmeter-order-read`);
    fs.mkdirSync(outDir, { recursive: true });
    const r = spawnSync(
      'docker',
      [
        'run',
        '--rm',
        '--network',
        'host',
        '-v',
        `${path.resolve('scripts/perf')}:/jmeter`,
        '-v',
        `${outDir}:/out`,
        'justb4/jmeter:5.5',
        '-n',
        '-t',
        '/jmeter/order_read_scale.jmx',
        '-l',
        '/out/results.jtl',
        '-e',
        '-o',
        '/out/report',
        `-JUSERS=${Math.min(USERS, 200)}`,
        '-JRAMP=30',
        '-JDURATION=60',
        `-JBASE_HOST=127.0.0.1`,
        `-JBASE_PORT=18080`,
        `-JTOKEN=${tok}`
      ],
      { encoding: 'utf8', timeout: 600000 }
    );
    report.jmeter = {
      attempted: true,
      status: r.status,
      stderr: (r.stderr || '').slice(0, 500),
      stdout: (r.stdout || '').slice(-800)
    };
    console.log('JMETER', report.jmeter.status, report.jmeter.stdout?.slice(-200));
  } else {
    report.jmeter.note = 'skipped (set RUN_JMETER=1 to try justb4/jmeter); node scale is primary evidence';
  }
}

report.summary = {
  perf1Pass: !!report.perf1.pass,
  perf3Pass: !!report.perf3.pass,
  perf4Pass: !!report.perf4.pass
};

fs.mkdirSync(OUT, { recursive: true });
fs.writeFileSync(`${OUT}/full-round-perf134-scale.json`, JSON.stringify(report, null, 2));
console.log('WROTE', `${OUT}/full-round-perf134-scale.json`);
console.log('SUMMARY', JSON.stringify(report.summary));
process.exit(report.summary.perf1Pass && report.summary.perf3Pass && report.summary.perf4Pass ? 0 : 1);
