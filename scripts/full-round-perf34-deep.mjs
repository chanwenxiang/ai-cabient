/**
 * PERF-3 MinIO 并发上传 + PERF-4 vision recognize 深压
 * 用法：node scripts/full-round-perf34-deep.mjs
 */
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

const NET = process.env.DOCKER_NET || 'ai-cabinet_default';
const VISION = process.env.VISION_BASE || 'http://127.0.0.1:18082';
const KEY = process.env.VISION_API_KEY || 'dev-vision-key-change-me';
const DEVICE = process.env.DEVICE_ID || '777740024057';
const localFile = path.resolve('testdata/bottle.jpg');
const stamp = Date.now();
const prefix = `perf134/${stamp}`;
const n3 = Number(process.env.PERF3_N || 60);
const c3 = Number(process.env.PERF3_C || 12);
const n4 = Number(process.env.PERF4_N || 200);
const c4 = Number(process.env.PERF4_C || 40);
const OUT = 'docs/uat-screenshots/2026-09-12/full-round-perf34-deep.json';

function pct(a, p) {
  const s = [...a].sort((x, y) => x - y);
  return s[Math.min(s.length - 1, Math.ceil((p / 100) * s.length) - 1)];
}
function summarize(times, errors, n, extra = {}) {
  return {
    n,
    errors,
    errorRate: n ? errors / n : 0,
    p50: pct(times, 50),
    p95: pct(times, 95),
    p99: pct(times, 99),
    max: times.length ? Math.max(...times) : null,
    ...extra
  };
}
async function pool(n, c, fn) {
  const q = Array.from({ length: n }, (_, i) => i);
  await Promise.all(
    Array.from({ length: c }, async () => {
      while (q.length) {
        const i = q.shift();
        if (i === undefined) break;
        await fn(i);
      }
    })
  );
}

const report = { at: new Date().toISOString(), perf3: {}, perf4: {} };

// PERF-3
{
  const times = [];
  let errors = 0;
  const t0 = Date.now();
  console.log(`PERF-3 MinIO n=${n3} c=${c3}`);
  await pool(n3, c3, async (i) => {
    const key = `${prefix}/obj-${i}.jpg`;
    const s = Date.now();
    const r = spawnSync(
      'docker',
      [
        'run',
        '--rm',
        '--network',
        NET,
        '-v',
        `${localFile}:/data/bottle.jpg:ro`,
        '--entrypoint',
        '/bin/sh',
        'minio/mc',
        '-c',
        `mc alias set local http://minio:9000 minioadmin minioadmin >/dev/null && mc cp /data/bottle.jpg local/cabinet-videos/${key}`
      ],
      { encoding: 'utf8', timeout: 180000 }
    );
    times.push(Date.now() - s);
    if (r.status !== 0) {
      errors += 1;
      if (errors <= 3) console.warn('mc-fail', (r.stderr || r.stdout || '').slice(0, 240));
    }
  });
  report.perf3 = summarize(times, errors, n3, {
    elapsedMs: Date.now() - t0,
    prefix: `cabinet-videos/${prefix}`,
    objectBytes: fs.statSync(localFile).size,
    pass: errors === 0
  });
  console.log('PERF-3', JSON.stringify(report.perf3));
}

// PERF-4
{
  const times = [];
  let errors = 0;
  const t0 = Date.now();
  console.log(`PERF-4 vision n=${n4} c=${c4}`);
  await pool(n4, c4, async (i) => {
    const s = Date.now();
    try {
      const r = await fetch(`${VISION}/api/v2/vision/recognize`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-Internal-Api-Key': KEY
        },
        body: JSON.stringify({
          session_id: `perf4-${stamp}-${i}-${crypto.randomBytes(2).toString('hex')}`,
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
  });
  const elapsed = Date.now() - t0;
  report.perf4 = summarize(times, errors, n4, {
    elapsedMs: elapsed,
    approxTps: n4 / (elapsed / 1000),
    pass: errors / n4 < 0.01 && pct(times, 95) < 2000
  });
  console.log('PERF-4', JSON.stringify(report.perf4));
}

report.summary = {
  perf3Pass: !!report.perf3.pass,
  perf4Pass: !!report.perf4.pass
};
fs.mkdirSync('docs/uat-screenshots/2026-09-12', { recursive: true });
fs.writeFileSync(OUT, JSON.stringify(report, null, 2));
console.log('WROTE', OUT, report.summary);
process.exit(report.summary.perf3Pass && report.summary.perf4Pass ? 0 : 1);
