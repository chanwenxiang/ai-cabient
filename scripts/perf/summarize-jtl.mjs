/**
 * Summarize JMeter results.jtl → summary.json
 */
import fs from 'fs';

const jtl = process.argv[2] || 'docs/uat-screenshots/2026-09-12/jmeter-order-read/results.jtl';
const out = process.argv[3] || 'docs/uat-screenshots/2026-09-12/jmeter-order-read/summary.json';

const lines = fs.readFileSync(jtl, 'utf8').trim().split(/\r?\n/);
const hdr = lines[0].split(',');
const i = {};
hdr.forEach((h, n) => {
  i[h] = n;
});

const by = {};
let ok = 0;
let bad = 0;
const all = [];
let max = 0;
let sum = 0;

for (let n = 1; n < lines.length; n++) {
  const c = lines[n].split(',');
  const el = Number(c[i.elapsed]);
  const lab = c[i.label];
  const succ = c[i.success] === 'true';
  if (succ) ok += 1;
  else bad += 1;
  all.push(el);
  sum += el;
  if (el > max) max = el;
  if (!by[lab]) by[lab] = { t: [], ok: 0, bad: 0 };
  by[lab].t.push(el);
  if (succ) by[lab].ok += 1;
  else by[lab].bad += 1;
}

function pct(a, p) {
  const s = [...a].sort((x, y) => x - y);
  return s[Math.min(s.length - 1, Math.ceil((p / 100) * s.length) - 1)];
}

const total = lines.length - 1;
const summary = {
  at: new Date().toISOString(),
  source: jtl,
  total,
  ok,
  bad,
  errorRate: total ? bad / total : 0,
  avg: total ? Math.round(sum / total) : null,
  p50: pct(all, 50),
  p95: pct(all, 95),
  p99: pct(all, 99),
  max,
  thresholds: { p95_ms: 800, error_rate: 0.001 },
  labels: {},
  pass: false
};
for (const [k, v] of Object.entries(by)) {
  summary.labels[k] = {
    n: v.t.length,
    ok: v.ok,
    bad: v.bad,
    p50: pct(v.t, 50),
    p95: pct(v.t, 95),
    p99: pct(v.t, 99)
  };
}
summary.pass = summary.errorRate < 0.001 && summary.p95 < 800;
fs.writeFileSync(out, JSON.stringify(summary, null, 2));
console.log(JSON.stringify(summary, null, 2));
process.exit(summary.pass ? 0 : 1);
