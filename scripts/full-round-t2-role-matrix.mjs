/**
 * T2 角色矩阵续测：确保财务/运营/补货演示号存在，并抽样侧栏可达 + 直链拒绝 + 写 403。
 * 用法：node scripts/full-round-t2-role-matrix.mjs
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

async function login(phone) {
  const cap = await fetch(`${BASE}/api/v2/auth/captcha`).then((r) => r.json());
  const id = cap.data.captchaId;
  const code = redisCaptcha(id);
  const res = await fetch(`${BASE}/api/v2/auth/admin-password-login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ phoneNumber: phone, password: '123456', captchaId: id, captchaCode: code })
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
    data = { raw: text.slice(0, 300) };
  }
  return { status: res.status, data };
}

function isDenied(r) {
  return r.status === 403 || r.data?.code === 403 || String(r.data?.message || '').includes('权限');
}

function isOk(r) {
  return r.status === 200 && (r.data?.code === 0 || r.data?.code === undefined);
}

async function ensureAccount(adminTok, phone, name, roleKey, roles) {
  const role = roles.find((r) => r.roleKey === roleKey);
  if (!role?.roleId) throw new Error(`missing role ${roleKey}`);
  const list = await api(adminTok, 'GET', `/api/v2/ops/admin/rbac/operators?page=0&size=50&phone=${phone}`);
  const items = list.data?.data?.items || list.data?.data?.list || list.data?.data || [];
  const arr = Array.isArray(items) ? items : [];
  let user = arr.find((u) => String(u.phoneNumber) === phone);
  if (!user) {
    const created = await api(adminTok, 'POST', '/api/v2/ops/admin/rbac/operators', {
      phoneNumber: phone,
      name,
      password: '123456',
      status: 'ACTIVE',
      roleIds: [role.roleId]
    });
    if (!isOk(created)) throw new Error(`create ${phone}: ${JSON.stringify(created.data)}`);
    user = created.data?.data;
  } else {
    await api(adminTok, 'PUT', `/api/v2/ops/admin/rbac/users/${user.userId}/roles`, [role.roleId]);
  }
  return { phone, name, roleKey, userId: user?.userId || user?.id, roleId: role.roleId };
}

async function injectAdmin(page, token) {
  await page.context().addCookies([
    {
      name: 'aicabinet_admin_session',
      value: token,
      domain: 'localhost',
      path: '/',
      httpOnly: true,
      sameSite: 'Strict',
      expires: Math.floor(Date.now() / 1000) + 1700
    }
  ]);
  await page.goto('http://localhost/admin/login', { waitUntil: 'domcontentloaded' });
  await page.evaluate((t) => {
    localStorage.clear();
    sessionStorage.clear();
    localStorage.setItem('admin_cookie_auth', '1');
    localStorage.setItem('admin_token', t);
    localStorage.setItem('admin_token_expires', String(Date.now() + 1_700_000));
  }, token);
}

const report = { at: new Date().toISOString(), accounts: [], cases: [] };

const admin = await login('13900000001');
const adminTok = admin.token;
const rolesRes = await api(adminTok, 'GET', '/api/v2/ops/admin/rbac/roles');
const roles = rolesRes.data?.data || [];

report.accounts.push(
  await ensureAccount(adminTok, '13900000002', '财务演示', 'finance', roles),
  await ensureAccount(adminTok, '13900000003', '运营演示', 'operator', roles),
  await ensureAccount(adminTok, '13900000004', '补货演示', 'replenisher', roles)
);

const finance = await login('13900000002');
const operator = await login('13900000003');
const replenisher = await login('13900000004');

async function loadPermNav(token) {
  const perms = await api(token, 'GET', '/api/v2/ops/admin/rbac/me/permissions');
  const nav = await api(token, 'GET', '/api/v2/ops/admin/rbac/me/nav');
  return {
    perms: perms.data?.data || [],
    nav: nav.data?.data || []
  };
}

const financePN = await loadPermNav(finance.token);
const operatorPN = await loadPermNav(operator.token);
const replenPN = await loadPermNav(replenisher.token);

function pushCase(id, status, note) {
  report.cases.push({ id, status, note });
}

// --- permission expectations ---
pushCase(
  'T2-finance-has-finance-view',
  financePN.perms.includes('ops:finance:view') ? 'PASS' : 'FAIL',
  { has: financePN.perms.includes('ops:finance:view') }
);
pushCase(
  'T2-finance-no-order-list',
  !financePN.perms.includes('ops:order:list') ? 'PASS' : 'FAIL',
  { hasOrder: financePN.perms.includes('ops:order:list') }
);
pushCase(
  'T2-finance-no-rbac-role',
  !financePN.perms.includes('ops:rbac:role') && !financePN.perms.includes('ops:admin')
    ? 'PASS'
    : 'FAIL',
  { hasRole: financePN.perms.includes('ops:rbac:role'), hasAdmin: financePN.perms.includes('ops:admin') }
);
pushCase(
  'T2-operator-has-order-dispute',
  financePN &&
    operatorPN.perms.includes('ops:order:list') &&
    (operatorPN.perms.includes('ops:dispute') || operatorPN.perms.includes('ops:dispute:list'))
    ? 'PASS'
    : 'FAIL',
  {
    order: operatorPN.perms.includes('ops:order:list'),
    dispute: operatorPN.perms.includes('ops:dispute')
  }
);
pushCase(
  'T2-operator-no-finance-view',
  !operatorPN.perms.includes('ops:finance:view') && !operatorPN.perms.includes('ops:admin')
    ? 'PASS'
    : 'FAIL',
  { hasFinance: operatorPN.perms.includes('ops:finance:view') }
);
pushCase(
  'T2-operator-no-rbac-role',
  !operatorPN.perms.includes('ops:rbac:role') && !operatorPN.perms.includes('ops:admin')
    ? 'PASS'
    : 'FAIL',
  { hasRole: operatorPN.perms.includes('ops:rbac:role') }
);
pushCase(
  'T2-replenisher-has-replen-or-warehouse',
  replenPN.perms.includes('ops:replenishment:list') ||
    replenPN.perms.includes('ops:warehouse:list') ||
    replenPN.perms.some((p) => String(p).includes('replen'))
    ? 'PASS'
    : 'FAIL',
  {
    sample: replenPN.perms.filter((p) => /replen|warehouse|device/.test(String(p))).slice(0, 12)
  }
);

// --- API write / deep reads ---
{
  const r = await api(finance.token, 'POST', '/api/v2/ops/admin/devices', {
    deviceName: 'finance-should-deny',
    merchantId: 'MCH-DEFAULT'
  });
  pushCase('T2-finance-device-create-403', isDenied(r) ? 'PASS' : 'FAIL', {
    code: r.data?.code,
    message: r.data?.message,
    status: r.status
  });
}
{
  const r = await api(finance.token, 'GET', '/api/v2/ops/admin/rbac/roles');
  pushCase('T2-finance-roles-denied-or-empty', isDenied(r) || !(r.data?.data || []).length ? 'PASS' : 'FAIL', {
    code: r.data?.code,
    status: r.status,
    n: Array.isArray(r.data?.data) ? r.data.data.length : null
  });
}
{
  const r = await api(operator.token, 'GET', '/api/v2/ops/admin/finance/stats');
  // may 403 or empty depending on perm; finance:view absent => 403 expected
  pushCase('T2-operator-finance-stats-403', isDenied(r) ? 'PASS' : 'FAIL', {
    code: r.data?.code,
    message: r.data?.message,
    status: r.status
  });
}
{
  const r = await api(operator.token, 'GET', '/api/v2/ops/admin/orders?page=0&size=5');
  pushCase('T2-operator-orders-ok', isOk(r) ? 'PASS' : 'FAIL', {
    code: r.data?.code,
    status: r.status
  });
}
{
  const r = await api(finance.token, 'GET', '/api/v2/ops/admin/finance/stats');
  pushCase('T2-finance-stats-ok', isOk(r) ? 'PASS' : 'FAIL', {
    code: r.data?.code,
    status: r.status
  });
}

// --- Browser: sidebar + deep links ---
fs.mkdirSync(UI, { recursive: true });
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });

async function sidebarTitles() {
  const texts = await page.locator('.el-menu-item, .el-sub-menu__title').allTextContents();
  return texts.map((t) => t.replace(/\s+/g, ' ').trim()).filter(Boolean);
}

async function checkDeepLink(token, path, expectForbidden, shotName) {
  await injectAdmin(page, token);
  await page.goto(`http://localhost/admin${path}`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1200);
  const url = page.url();
  const body = await page.locator('body').innerText();
  const forbidden =
    url.includes('/forbidden') ||
    body.includes('无权限') ||
    body.includes('页面不存在') ||
    body.includes('没有权限');
  const ok = expectForbidden ? forbidden : !forbidden && !url.includes('/login');
  await page.screenshot({ path: `${UI}/${shotName}.png`, fullPage: false });
  pushCase(`T2-deeplink-${shotName}`, ok ? 'PASS' : 'FAIL', { url, forbidden, expectForbidden });
}

await injectAdmin(page, finance.token);
await page.goto('http://localhost/admin/dashboard', { waitUntil: 'networkidle' });
await page.waitForTimeout(1500);
const financeSide = await sidebarTitles();
await page.screenshot({ path: `${UI}/t2-finance-sidebar.png`, fullPage: false });
pushCase(
  'T2-finance-sidebar-has-finance',
  financeSide.some((t) => t.includes('财务')) ? 'PASS' : 'FAIL',
  { sample: financeSide.slice(0, 30) }
);
pushCase(
  'T2-finance-sidebar-no-orders',
  !financeSide.some((t) => t === '订单管理' || t.includes('订单管理')) ? 'PASS' : 'FAIL',
  { hasOrders: financeSide.some((t) => t.includes('订单')) }
);
pushCase(
  'T2-finance-sidebar-no-roles',
  !financeSide.some((t) => t.includes('角色管理') || t.includes('运营账号')) ? 'PASS' : 'FAIL',
  { hit: financeSide.filter((t) => /角色|账号|菜单/.test(t)) }
);

await injectAdmin(page, operator.token);
await page.goto('http://localhost/admin/dashboard', { waitUntil: 'networkidle' });
await page.waitForTimeout(1500);
const operatorSide = await sidebarTitles();
await page.screenshot({ path: `${UI}/t2-operator-sidebar.png`, fullPage: false });
pushCase(
  'T2-operator-sidebar-has-orders',
  operatorSide.some((t) => t.includes('订单')) ? 'PASS' : 'FAIL',
  { sample: operatorSide.slice(0, 30) }
);
pushCase(
  'T2-operator-sidebar-no-finance-profit',
  !operatorSide.some((t) => t.includes('财务毛利')) ? 'PASS' : 'FAIL',
  { hit: operatorSide.filter((t) => t.includes('财务')) }
);

await checkDeepLink(finance.token, '/orders', true, 't2-finance-orders-forbidden');
await checkDeepLink(finance.token, '/finance', false, 't2-finance-finance-ok');
await checkDeepLink(operator.token, '/roles', true, 't2-operator-roles-forbidden');
await checkDeepLink(operator.token, '/disputes', false, 't2-operator-disputes-ok');
await checkDeepLink(operator.token, '/finance', true, 't2-operator-finance-forbidden');

await browser.close();

const pass = report.cases.filter((c) => c.status === 'PASS').length;
const fail = report.cases.filter((c) => c.status === 'FAIL').length;
report.summary = { pass, fail, total: report.cases.length };
report.permCounts = {
  finance: financePN.perms.length,
  operator: operatorPN.perms.length,
  replenisher: replenPN.perms.length
};

fs.writeFileSync(`${OUT}/full-round-t2-role-matrix.json`, JSON.stringify(report, null, 2));
console.log(JSON.stringify(report.summary, null, 2));
console.log(JSON.stringify(report.cases.map((c) => `${c.status} ${c.id}`), null, 2));
if (fail > 0) process.exit(1);
