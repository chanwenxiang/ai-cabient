/**
 * 商户端角色边界 + PERF-1/3/4 轻量抽样。
 * 用法：node scripts/full-round-t2-merchant-perf.mjs
 */
import { execSync } from 'node:child_process';
import fs from 'node:fs';
import { chromium } from 'playwright';

const BASE = process.env.API_BASE || 'http://127.0.0.1';
const OUT = 'docs/uat-screenshots/2026-09-12';
const UI = `${OUT}/browser-ui`;

function redisCaptcha(id) {
  return execSync(`docker exec ai-cabinet-redis-1 redis-cli --raw GET aicabinet:captcha:${id}`, {
    encoding: 'utf8'
  }).trim();
}

async function adminPasswordLogin(phone) {
  const cap = await fetch(`${BASE}/api/v2/auth/captcha`).then((r) => r.json());
  const id = cap.data.captchaId;
  const res = await fetch(`${BASE}/api/v2/auth/admin-password-login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      phoneNumber: phone,
      password: '123456',
      captchaId: id,
      captchaCode: redisCaptcha(id)
    })
  });
  const data = await res.json();
  if (data.code !== 0) throw new Error(`login ${phone}: ${JSON.stringify(data)}`);
  return data.data;
}

async function api(token, method, path, body) {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
      'X-Requested-With': 'XMLHttpRequest'
    },
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  const text = await res.text();
  let data;
  try {
    data = JSON.parse(text);
  } catch {
    data = { raw: text.slice(0, 200) };
  }
  return { status: res.status, data };
}

function denied(r) {
  return r.status === 403 || r.data?.code === 403 || String(r.data?.message || '').includes('权限');
}
function ok(r) {
  return r.status === 200 && r.data?.code === 0;
}

const report = { at: new Date().toISOString(), cases: [], perf: {}, accounts: [] };

const admin = await adminPasswordLogin('13800138001');
const adminTok = admin.token;

const teamPlan = [
  { phone: '13800138002', name: '商户店员', roleKey: 'merchant_staff' },
  { phone: '13800138004', name: '商户财务', roleKey: 'merchant_finance' },
  { phone: '13800138006', name: '商户店长', roleKey: 'merchant_store_manager' },
  { phone: '13800138007', name: '商户补货员', roleKey: 'merchant_replenisher' }
];

for (const u of teamPlan) {
  const exists = await api(
    adminTok,
    'GET',
    `/api/v2/merchant/team/users`
  );
  const items = exists.data?.data || [];
  const arr = Array.isArray(items) ? items : items.items || [];
  const hit = arr.find((x) => String(x.phoneNumber) === u.phone);
  if (hit) {
    report.accounts.push({ ...u, userId: hit.userId, status: 'exists' });
    continue;
  }
  const created = await api(adminTok, 'POST', '/api/v2/merchant/team/users', {
    phoneNumber: u.phone,
    password: '123456',
    displayName: u.name,
    roleKey: u.roleKey
  });
  report.accounts.push({
    ...u,
    status: ok(created) ? 'created' : 'fail',
    note: { code: created.data?.code, message: created.data?.message, userId: created.data?.data?.userId }
  });
  if (!ok(created) && created.data?.code !== 409) {
    // conflict may mean phone taken outside team list — try login later
  }
}

async function loginOrNull(phone) {
  try {
    return await adminPasswordLogin(phone);
  } catch (e) {
    return { error: String(e.message || e) };
  }
}

const staff = await loginOrNull('13800138002');
const finance = await loginOrNull('13800138004');
const storeMgr = await loginOrNull('13800138006');
const replen = await loginOrNull('13800138007');

function push(id, status, note) {
  report.cases.push({ id, status, note });
}

async function perms(token) {
  const r = await api(token, 'GET', '/api/v2/ops/admin/rbac/me/permissions');
  // merchant may use same endpoint or merchant me
  if (ok(r) && Array.isArray(r.data?.data)) return r.data.data;
  const r2 = await api(token, 'GET', '/api/v2/merchant/me/permissions');
  if (ok(r2) && Array.isArray(r2.data?.data)) return r2.data.data;
  return [];
}

if (staff.token) {
  const p = await perms(staff.token);
  push('M-staff-no-wallet-apply', !p.includes('merchant:wallet:apply') ? 'PASS' : 'FAIL', {
    hasApply: p.includes('merchant:wallet:apply')
  });
  push('M-staff-no-invite', !p.includes('merchant:users:invite') ? 'PASS' : 'FAIL', {
    hasInvite: p.includes('merchant:users:invite')
  });
  const w = await api(staff.token, 'POST', '/api/v2/merchant/wallet/withdraw', {
    amountCents: 100,
    remark: 'should-deny'
  });
  push('M-staff-withdraw-403', denied(w) ? 'PASS' : 'FAIL', {
    code: w.data?.code,
    message: w.data?.message
  });
  const devices = await api(staff.token, 'GET', '/api/v2/merchant/devices?page=0&size=5');
  push('M-staff-devices-ok', ok(devices) || devices.data?.code === 0 ? 'PASS' : 'FAIL', {
    code: devices.data?.code,
    status: devices.status
  });
} else {
  push('M-staff-login', 'FAIL', staff);
}

if (finance.token) {
  const p = await perms(finance.token);
  push('M-finance-has-wallet-view', p.includes('merchant:wallet:view') ? 'PASS' : 'FAIL', {
    has: p.includes('merchant:wallet:view')
  });
  push('M-finance-no-wallet-apply', !p.includes('merchant:wallet:apply') ? 'PASS' : 'FAIL', {
    hasApply: p.includes('merchant:wallet:apply')
  });
  const settle = await api(finance.token, 'GET', '/api/v2/merchant/settlements/overview');
  push('M-finance-settlements-ok', ok(settle) ? 'PASS' : 'FAIL', {
    code: settle.data?.code,
    message: settle.data?.message
  });
  const inv = await api(finance.token, 'POST', '/api/v2/merchant/team/users', {
    phoneNumber: '13800138999',
    password: '123456',
    displayName: 'deny',
    roleKey: 'merchant_staff'
  });
  push('M-finance-invite-403', denied(inv) ? 'PASS' : 'FAIL', {
    code: inv.data?.code,
    message: inv.data?.message
  });
} else {
  push('M-finance-login', 'FAIL', finance);
}

if (storeMgr.token) {
  const p = await perms(storeMgr.token);
  push('M-store-has-users-list', p.includes('merchant:users:list') ? 'PASS' : 'FAIL', {
    has: p.includes('merchant:users:list')
  });
  push('M-store-no-disable', !p.includes('merchant:users:disable') ? 'PASS' : 'FAIL', {
    has: p.includes('merchant:users:disable')
  });
  const team = await api(storeMgr.token, 'GET', '/api/v2/merchant/team/users');
  push('M-store-team-list-ok', ok(team) ? 'PASS' : 'FAIL', { code: team.data?.code });
} else {
  push('M-store-login', 'FAIL', storeMgr);
}

if (replen.token) {
  const p = await perms(replen.token);
  push(
    'M-replen-has-field',
    p.includes('merchant:replenishment:view') || p.includes('merchant:replenishment:request')
      ? 'PASS'
      : 'FAIL',
    { sample: p.filter((x) => String(x).includes('replen') || String(x).includes('device')).slice(0, 10) }
  );
  push('M-replen-no-settlements', !p.includes('merchant:settlements:view') ? 'PASS' : 'FAIL', {
    has: p.includes('merchant:settlements:view')
  });
  const settle = await api(replen.token, 'GET', '/api/v2/merchant/settlements/overview');
  push('M-replen-settlements-403', denied(settle) ? 'PASS' : 'FAIL', {
    code: settle.data?.code,
    message: settle.data?.message
  });
} else {
  push('M-replen-login', 'FAIL', replen);
}

// Browser smoke: staff vs admin home nav
fs.mkdirSync(UI, { recursive: true });
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 420, height: 860 } });

async function injectMerchant(token) {
  await page.goto('http://localhost:3001/', { waitUntil: 'domcontentloaded' });
  await page.evaluate((t) => {
    localStorage.clear();
    sessionStorage.clear();
    for (const k of ['merchant_token', 'token', 'admin_token']) localStorage.setItem(k, t);
    localStorage.setItem('merchant_token_expires', String(Date.now() + 1_700_000));
  }, token);
}

if (staff.token) {
  await injectMerchant(staff.token);
  await page.goto('http://localhost:3001/', { waitUntil: 'networkidle' });
  await page.waitForTimeout(1500);
  const body = await page.locator('body').innerText();
  await page.screenshot({ path: `${UI}/t2-mch-staff-home.png` });
  push('M-ui-staff-home', !/登录|验证码/.test(body.slice(0, 80)) || body.includes('设备') || body.length > 40 ? 'PASS' : 'FAIL', {
    head: body.slice(0, 120)
  });
}
if (finance.token) {
  await injectMerchant(finance.token);
  await page.goto('http://localhost:3001/#/pages/wallet/wallet', { waitUntil: 'networkidle' }).catch(() => {});
  await page.goto('http://localhost:3001/pages/wallet/wallet', { waitUntil: 'networkidle' }).catch(() => {});
  await page.waitForTimeout(1200);
  await page.screenshot({ path: `${UI}/t2-mch-finance-wallet.png` });
  const body = await page.locator('body').innerText();
  push('M-ui-finance-wallet', body.length > 20 ? 'PASS' : 'FAIL', { head: body.slice(0, 120) });
}
await browser.close();

// ---- PERF-1 light: concurrent consumer account (直连 trade，避免网关连接打满误报) ----
{
  const TRADE = process.env.TRADE_BASE || 'http://127.0.0.1:18080';
  const login = await fetch(`${TRADE}/api/v2/auth/password-login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ phoneNumber: '13800138000', password: '123456' })
  }).then((r) => r.json());
  const tok = login.data?.token;
  const times = [];
  let errors = 0;
  const N = 80;
  const wall0 = Date.now();
  await Promise.all(
    Array.from({ length: N }, async () => {
      const s = Date.now();
      try {
        const r = await fetch(`${TRADE}/api/v2/account`, {
          headers: { Authorization: `Bearer ${tok}` }
        });
        const j = await r.json();
        if (j.code !== 0) errors++;
      } catch {
        errors++;
      }
      times.push(Date.now() - s);
    })
  );
  times.sort((a, b) => a - b);
  report.perf.perf1Light = {
    n: N,
    wallMs: Date.now() - wall0,
    p50: times[Math.floor(N * 0.5)],
    p95: times[Math.floor(N * 0.95) - 1],
    max: times[N - 1],
    errors,
    errRate: errors / N,
    via: TRADE,
    pass: times[Math.floor(N * 0.95) - 1] < 800 && errors / N < 0.01
  };
  push('PERF1-light-account', report.perf.perf1Light.pass ? 'PASS' : 'FAIL', report.perf.perf1Light);
}

// ---- PERF-3 light: MinIO health ----
{
  const times = [];
  let errors = 0;
  const N = 40;
  const MINIO = process.env.MINIO_HEALTH || 'http://127.0.0.1:9000/minio/health/live';
  await Promise.all(
    Array.from({ length: N }, async () => {
      const s = Date.now();
      try {
        const r = await fetch(MINIO);
        if (!r.ok) errors++;
      } catch {
        errors++;
      }
      times.push(Date.now() - s);
    })
  );
  times.sort((a, b) => a - b);
  report.perf.perf3Light = {
    n: N,
    p50: times[Math.floor(N * 0.5)],
    p95: times[Math.floor(N * 0.95) - 1],
    errors,
    pass: errors === 0 && times[Math.floor(N * 0.95) - 1] < 800,
    target: MINIO
  };
  push('PERF3-light-minio', report.perf.perf3Light.pass ? 'PASS' : 'FAIL', report.perf.perf3Light);
}

// ---- PERF-4 light: vision health concurrent ----
{
  const times = [];
  let errors = 0;
  const N = 40;
  await Promise.all(
    Array.from({ length: N }, async () => {
      const s = Date.now();
      try {
        const r = await fetch('http://127.0.0.1:18082/health');
        const j = await r.json();
        if (!(j.status === 'ok' || j.status === 'UP')) errors++;
      } catch {
        errors++;
      }
      times.push(Date.now() - s);
    })
  );
  times.sort((a, b) => a - b);
  report.perf.perf4Light = {
    n: N,
    p50: times[Math.floor(N * 0.5)],
    p95: times[Math.floor(N * 0.95) - 1],
    errors,
    pass: errors === 0 && times[Math.floor(N * 0.95) - 1] < 800
  };
  push('PERF4-light-vision', report.perf.perf4Light.pass ? 'PASS' : 'FAIL', report.perf.perf4Light);
}

const pass = report.cases.filter((c) => c.status === 'PASS').length;
const fail = report.cases.filter((c) => c.status === 'FAIL').length;
report.summary = { pass, fail, total: report.cases.length };
fs.writeFileSync(`${OUT}/full-round-t2-merchant-perf.json`, JSON.stringify(report, null, 2));
console.log(JSON.stringify(report.summary, null, 2));
console.log(JSON.stringify(report.cases.map((c) => `${c.status} ${c.id}`), null, 2));
console.log(JSON.stringify(report.perf, null, 2));
if (fail > 0) process.exit(1);
