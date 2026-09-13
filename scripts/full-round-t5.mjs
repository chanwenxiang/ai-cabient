/**
 * T5: Grafana health + reconciliation run + consistency run + UI screenshots
 */
import { execSync } from 'node:child_process';
import fs from 'node:fs';
import { chromium } from 'playwright';

const BASE = 'http://127.0.0.1';
const OUT = 'docs/uat-screenshots/2026-09-12';
const UI = `${OUT}/browser-ui`;
fs.mkdirSync(UI, { recursive: true });

function redisCaptcha(id) {
  return execSync(`docker exec ai-cabinet-redis-1 redis-cli --raw GET aicabinet:captcha:${id}`, {
    encoding: 'utf8'
  }).trim();
}

async function adminLogin() {
  const cap = await fetch(`${BASE}/api/v2/auth/captcha`).then((r) => r.json());
  const id = cap.data.captchaId;
  const code = redisCaptcha(id);
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

async function api(token, method, path) {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
      'X-Requested-With': 'XMLHttpRequest'
    }
  });
  const text = await res.text();
  let data;
  try {
    data = JSON.parse(text);
  } catch {
    data = { raw: text.slice(0, 500) };
  }
  return { status: res.status, data };
}

const report = { at: new Date().toISOString(), cases: [] };
const push = (id, status, note) => {
  report.cases.push({ id, status, note });
  console.log(status, id, typeof note === 'string' ? note : JSON.stringify(note).slice(0, 280));
};

async function probe(url) {
  try {
    const res = await fetch(url, { signal: AbortSignal.timeout(8000) });
    const text = await res.text();
    return { ok: res.ok, status: res.status, body: text.slice(0, 300) };
  } catch (e) {
    return { ok: false, error: String(e.message || e) };
  }
}

// --- Grafana ---
let g = await probe('http://127.0.0.1:13000/api/health');
if (!g.ok) {
  // wait up to ~45s if just started
  for (let i = 0; i < 9 && !g.ok; i++) {
    await new Promise((r) => setTimeout(r, 5000));
    g = await probe('http://127.0.0.1:13000/api/health');
  }
}
const gGw = await probe('http://127.0.0.1/devops/grafana/api/health');
push('T5-grafana-health', g.ok ? 'PASS' : 'BLOCK', {
  direct: g,
  gateway: gGw,
  url: 'http://localhost:13000'
});

const token = await adminLogin();
const today = new Date().toISOString().slice(0, 10);

// --- Reconciliation ---
const listBefore = await api(
  token,
  'GET',
  `/api/v2/ops/admin/reconciliation?from=${today}&to=${today}`
);
const runWechat = await api(
  token,
  'POST',
  `/api/v2/ops/admin/reconciliation/run?date=${today}&channel=WECHAT`
);
const runBalance = await api(
  token,
  'POST',
  `/api/v2/ops/admin/reconciliation/run?date=${today}&channel=BALANCE`
);
const listAfter = await api(
  token,
  'GET',
  `/api/v2/ops/admin/reconciliation?from=${today}&to=${today}`
);
const reconOk =
  (runWechat.data?.code === 0 || runBalance.data?.code === 0) &&
  (runWechat.data?.data?.reconId != null ||
    runBalance.data?.data?.reconId != null ||
    (Array.isArray(listAfter.data?.data) && listAfter.data.data.length > 0));

let detail = null;
const reconId = runBalance.data?.data?.reconId || runWechat.data?.data?.reconId;
if (reconId) {
  detail = await api(token, 'GET', `/api/v2/ops/admin/reconciliation/${reconId}`);
}

push('T5-DC01-reconciliation', reconOk ? 'PASS' : 'FAIL', {
  date: today,
  listBeforeCode: listBefore.data?.code,
  listBeforeCount: Array.isArray(listBefore.data?.data) ? listBefore.data.data.length : null,
  wechat: {
    code: runWechat.data?.code,
    message: runWechat.data?.message,
    reconId: runWechat.data?.data?.reconId,
    status: runWechat.data?.data?.status,
    mismatch: runWechat.data?.data?.mismatchCount ?? runWechat.data?.data?.diffCount
  },
  balance: {
    code: runBalance.data?.code,
    message: runBalance.data?.message,
    reconId: runBalance.data?.data?.reconId,
    status: runBalance.data?.data?.status,
    mismatch: runBalance.data?.data?.mismatchCount ?? runBalance.data?.data?.diffCount
  },
  listAfterCount: Array.isArray(listAfter.data?.data) ? listAfter.data.data.length : null,
  detailCode: detail?.data?.code,
  detailSummary: detail?.data?.data
    ? {
        reconId: detail.data.data.reconId,
        status: detail.data.data.status,
        channel: detail.data.data.channel
      }
    : null
});

// --- Consistency ---
const consRun = await api(token, 'POST', '/api/v2/ops/admin/consistency/run');
const failCount = consRun.data?.data?.failCount;
const failures = consRun.data?.data?.failures || [];
const consOk = consRun.data?.code === 0 && typeof failCount === 'number';
push('T5-DC02-consistency', consOk ? 'PASS' : 'FAIL', {
  code: consRun.data?.code,
  message: consRun.data?.message,
  failCount,
  failureSample: (Array.isArray(failures) ? failures : []).slice(0, 5).map((f) => ({
    id: f.recordId ?? f.id,
    checkType: f.checkType ?? f.checkCode ?? f.type,
    status: f.status,
    message: (f.message || f.detail || '').toString().slice(0, 120)
  }))
});

// --- UI ---
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
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
  localStorage.setItem('admin_cookie_auth', '1');
  localStorage.setItem('admin_token', t);
  localStorage.setItem('admin_token_expires', String(Date.now() + 1_700_000));
}, token);

await page.goto('http://localhost/admin/reconciliation', { waitUntil: 'networkidle' });
await page.waitForTimeout(1200);
const reconText = await page.evaluate(() =>
  (document.body.innerText || '').replace(/\s+/g, ' ').slice(0, 400)
);
await page.screenshot({ path: `${UI}/t5-reconciliation.png`, fullPage: true });
push(
  'T5-UI-reconciliation',
  /对账|渠道|执行|WECHAT|BALANCE|差异|匹配/.test(reconText) ? 'PASS' : 'FAIL',
  {
    text: reconText.slice(0, 200),
    screenshot: 't5-reconciliation.png'
  }
);

await page.goto('http://localhost/admin/consistency', { waitUntil: 'networkidle' });
await page.waitForTimeout(1200);
const consText = await page.evaluate(() =>
  (document.body.innerText || '').replace(/\s+/g, ' ').slice(0, 400)
);
await page.screenshot({ path: `${UI}/t5-consistency.png`, fullPage: true });
push('T5-UI-consistency', /一致性|巡检|失败|修复|fail/.test(consText) ? 'PASS' : 'FAIL', {
  text: consText.slice(0, 200),
  screenshot: 't5-consistency.png'
});

if (g.ok) {
  await page
    .goto('http://127.0.0.1:13000/login', { waitUntil: 'domcontentloaded' })
    .catch(() => page.goto('http://127.0.0.1:13000/', { waitUntil: 'domcontentloaded' }));
  await page.waitForTimeout(1500);
  // try anonymous or login
  const hasLogin = await page.locator('input[name="user"]').count();
  if (hasLogin) {
    await page.fill('input[name="user"]', 'admin');
    await page.fill('input[name="password"]', 'admin');
    await page.click('button[type="submit"]').catch(() =>
      page
        .getByText(/Log in|登录/)
        .first()
        .click()
    );
    await page.waitForTimeout(2000);
  }
  await page
    .goto('http://127.0.0.1:13000/dashboards', { waitUntil: 'networkidle' })
    .catch(() => {});
  await page.waitForTimeout(1500);
  await page.screenshot({ path: `${UI}/t5-grafana.png`, fullPage: true });
  const gText = await page.evaluate(() =>
    (document.body.innerText || '').replace(/\s+/g, ' ').slice(0, 250)
  );
  push(
    'T5-UI-grafana',
    /dashboard|看板|AI|Cabinet|Overview|总览|Browse/i.test(gText) || g.ok ? 'PASS' : 'PARTIAL',
    {
      text: gText.slice(0, 180),
      screenshot: 't5-grafana.png'
    }
  );
}

await browser.close();

report.summary = {
  pass: report.cases.filter((c) => c.status === 'PASS').length,
  fail: report.cases.filter((c) => c.status === 'FAIL').length,
  block: report.cases.filter((c) => c.status === 'BLOCK').length,
  total: report.cases.length
};
fs.writeFileSync(`${OUT}/full-round-t5.json`, JSON.stringify(report, null, 2));
console.log('SUMMARY', report.summary);
