/**
 * 完整轮：券核销 + 商户要货审批闭环
 */
import { execSync } from 'node:child_process';
import fs from 'node:fs';
import { chromium } from 'playwright';

const BASE = 'http://127.0.0.1';
const TRADE = 'http://127.0.0.1:18080';
const OUT = 'docs/uat-screenshots/2026-09-12';
const UI = `${OUT}/browser-ui`;
const DEVICE = '777740024057';

fs.mkdirSync(UI, { recursive: true });

function redisGet(key) {
  return execSync(`docker exec ai-cabinet-redis-1 redis-cli --raw GET ${key}`, {
    encoding: 'utf8'
  }).trim();
}

async function adminLogin() {
  const cap = await fetch(`${BASE}/api/v2/auth/captcha`).then((r) => r.json());
  const id = cap.data.captchaId;
  const code = redisGet(`aicabinet:captcha:${id}`);
  const res = await fetch(`${BASE}/api/v2/auth/admin-password-login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      phoneNumber: '13900000001',
      password: '123456',
      captchaId: id,
      captchaCode: code
    })
  });
  const data = await res.json();
  if (data.code !== 0) throw new Error(JSON.stringify(data));
  return data.data.token;
}

async function merchantLogin() {
  const res = await fetch(`${BASE}/api/v2/auth/merchant-password-login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ phoneNumber: '13800138001', password: '123456' })
  });
  const data = await res.json();
  if (data.code !== 0) throw new Error(JSON.stringify(data));
  return data.data.token;
}

async function consumerLogin() {
  const res = await fetch(`${BASE}/api/v2/auth/password-login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ phoneNumber: '13800138000', password: '123456' })
  });
  const data = await res.json();
  if (data.code !== 0) throw new Error(JSON.stringify(data));
  return data.data;
}

async function api(token, method, path, body, base = BASE) {
  const res = await fetch(`${base}${path}`, {
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
    data = { raw: text.slice(0, 400) };
  }
  return { status: res.status, data };
}

const report = { at: new Date().toISOString(), cases: [] };
const push = (id, status, note) => {
  report.cases.push({ id, status, note });
  console.log(status, id, typeof note === 'string' ? note : JSON.stringify(note).slice(0, 220));
};

// --- Approval: merchant replenishment request → ops accept ---
const mchTok = await merchantLogin();
const opsTok = await adminLogin();
const reqBody = {
  deviceId: DEVICE,
  notes: '完整轮要货审批',
  lines: [{ skuId: 'SKU-DEMO-001', requestedQty: 2 }],
  evidenceFileIds: []
};
const submitted = await api(mchTok, 'POST', '/api/v2/merchant/replenishment/requests', reqBody);
const requestId = submitted.data?.data?.requestId;
if (!requestId) {
  push('P0-09-approval', 'FAIL', { submit: submitted.data });
} else {
  const pending = await api(opsTok, 'GET', '/api/v2/ops/admin/approvals/tasks?limit=20');
  const tasks = Array.isArray(pending.data?.data) ? pending.data.data : [];
  const hit = tasks.find(
    (t) =>
      String(t.bizId) === String(requestId) || String(t.title || '').includes(String(requestId))
  );
  const accepted = await api(
    opsTok,
    'POST',
    `/api/v2/ops/admin/replenishment/requests/${requestId}/accept`
  );
  const ok =
    accepted.data?.code === 0 &&
    String(accepted.data?.data?.status || '').toUpperCase() === 'ACCEPTED';
  push('P0-09-approval', ok ? 'PASS' : 'FAIL', {
    requestId,
    pendingBefore: tasks.length,
    pendingHit: !!hit,
    status: accepted.data?.data?.status,
    taskId: accepted.data?.data?.replenishmentTaskId,
    code: accepted.data?.code,
    message: accepted.data?.message
  });
}

// --- Coupon redeem: shopping → stop at DISPUTED/UNPAID → use coupon ---
let consumer;
try {
  consumer = await consumerLogin();
  push('consumer-login', 'PASS', { userId: consumer.userId });
} catch (e) {
  push('consumer-login', 'FAIL', String(e.message || e));
}

if (consumer?.token) {
  // Ensure unused coupon
  let coupons = await api(consumer.token, 'GET', '/api/v2/coupons?status=UNUSED');
  let list = Array.isArray(coupons.data?.data) ? coupons.data.data : [];
  let couponId = list[0]?.couponId;
  if (!couponId) {
    const issued = await api(opsTok, 'POST', '/api/v2/coupons/issue', {
      userId: consumer.userId,
      couponDefId: 1
    });
    couponId = issued.data?.data?.couponId;
  }

  let orderId = null;
  let orderStatus = null;
  let shopNote = '';
  try {
    const out = execSync(
      `powershell -NoProfile -ExecutionPolicy Bypass -File .\\scripts\\full-round-make-eligible-order.ps1 -BaseUrl ${TRADE} -DeviceId ${DEVICE} -ConsumerToken ${consumer.token}`,
      { encoding: 'utf8', timeout: 180000 }
    );
    shopNote = out.slice(-800);
    const om = out.match(/ORDER=(\d+)/);
    const sm = out.match(/OSTATUS=(\w+)/);
    if (om) orderId = om[1];
    if (sm) orderStatus = sm[1];
  } catch (e) {
    shopNote = String(e.stdout || e.message || e).slice(-1000);
    const om = shopNote.match(/ORDER=(\d+)/);
    const sm = shopNote.match(/OSTATUS=(\w+)/);
    if (om) orderId = om[1];
    if (sm) orderStatus = sm[1];
  }

  // Fallback: query orders API
  if (!orderId) {
    const orders = await api(consumer.token, 'GET', '/api/v2/orders?page=0&size=10');
    const items = orders.data?.data?.items || orders.data?.data || [];
    const arr = Array.isArray(items) ? items : [];
    const eligible = arr.find((o) =>
      ['DISPUTED', 'UNPAID', 'PENDING', 'CREATED'].includes(String(o.status || '').toUpperCase())
    );
    if (eligible) {
      orderId = eligible.orderId;
      orderStatus = eligible.status;
    }
    shopNote += ` | fallbackOrders=${arr.length}`;
  }

  if (!couponId || !orderId) {
    push('P0-06-coupon-redeem', 'BLOCK', {
      couponId,
      orderId,
      orderStatus,
      shopNote: shopNote.slice(0, 500)
    });
  } else {
    // If order already PAID, cannot redeem — mark BLOCK with reason
    if (['PAID', 'COMPLETED', 'REFUNDED'].includes(String(orderStatus || '').toUpperCase())) {
      push('P0-06-coupon-redeem', 'BLOCK', {
        couponId,
        orderId,
        orderStatus,
        reason: 'order not eligible'
      });
    } else {
      const used = await api(
        consumer.token,
        'POST',
        `/api/v2/coupons/use?couponId=${couponId}&orderId=${encodeURIComponent(orderId)}&deviceId=${DEVICE}`
      );
      const ok =
        used.data?.code === 0 && String(used.data?.data?.status || '').toUpperCase() === 'USED';
      push('P0-06-coupon-redeem', ok ? 'PASS' : 'FAIL', {
        couponId,
        orderId,
        orderStatus,
        code: used.data?.code,
        status: used.data?.data?.status,
        discountCents: used.data?.data?.discountCents ?? used.data?.data?.denominationCents,
        message: used.data?.message
      });
    }
  }
}

// UI screenshots
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
await page.context().addCookies([
  {
    name: 'aicabinet_admin_session',
    value: opsTok,
    domain: 'localhost',
    path: '/',
    httpOnly: true,
    sameSite: 'Strict',
    expires: Math.floor(Date.now() / 1000) + 1700
  }
]);
await page.goto('http://localhost/admin/login', { waitUntil: 'domcontentloaded' });
await page.evaluate((t) => {
  localStorage.setItem('admin_cookie_auth', '1');
  localStorage.setItem('admin_token', t);
  localStorage.setItem('admin_token_expires', String(Date.now() + 1_700_000));
}, opsTok);
await page
  .goto('http://localhost/admin/replenishment', { waitUntil: 'networkidle' })
  .catch(() => {});
await page.waitForTimeout(1000);
await page.screenshot({ path: `${UI}/p0-approval-replen-request.png`, fullPage: true });
await page.goto('http://localhost/admin/coupons', { waitUntil: 'networkidle' }).catch(() => {});
await page.waitForTimeout(800);
await page.screenshot({ path: `${UI}/p0-coupon-after-redeem.png`, fullPage: true });
await browser.close();

report.summary = {
  pass: report.cases.filter((c) => c.status === 'PASS').length,
  fail: report.cases.filter((c) => c.status === 'FAIL').length,
  block: report.cases.filter((c) => c.status === 'BLOCK').length,
  total: report.cases.length
};
fs.writeFileSync(`${OUT}/full-round-p0-coupon-approval.json`, JSON.stringify(report, null, 2));
console.log('SUMMARY', report.summary);
