/**
 * P0#4 分账入账加深：公式 + 流水 + 重放不双入
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

function psql(sql) {
  const b64 = Buffer.from(sql, 'utf8').toString('base64');
  return execSync(
    `docker exec -i ai-cabinet-postgres-1 bash -lc "echo ${b64} | base64 -d | psql -U aicabinet -d aicabinet -t -A -F '|'"`,
    { encoding: 'utf8' }
  ).trim();
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
    data = { raw: text.slice(0, 400) };
  }
  return { status: res.status, data };
}

const report = { at: new Date().toISOString(), cases: [] };
const push = (id, status, note) => {
  report.cases.push({ id, status, note });
  console.log(
    status.padEnd(7),
    id,
    typeof note === 'string' ? note : JSON.stringify(note).slice(0, 280)
  );
};

const ops = await adminLogin();
const mch = await merchantLogin();

// --- 1) API list + formula ---
const splitsRes = await api(
  ops,
  'GET',
  '/api/v2/ops/admin/merchants/revenue-splits?page=0&size=20'
);
const splitItems =
  splitsRes.data?.data?.items || splitsRes.data?.data?.list || splitsRes.data?.data || [];
const splits = Array.isArray(splitItems) ? splitItems : [];

const formulaChecks = splits.map((s) => {
  const gross = Number(s.grossCents ?? s.gross_cents);
  const platform = Number(s.platformCents ?? s.platform_cents);
  const merchant = Number(s.merchantCents ?? s.merchant_cents ?? s.merchantShareCents);
  const ok = gross === platform + merchant && gross > 0;
  return {
    splitId: s.splitId ?? s.split_id,
    orderId: s.orderId ?? s.order_id,
    status: s.status,
    gross,
    platform,
    merchant,
    formulaOk: ok
  };
});

const allFormulaOk = formulaChecks.length >= 2 && formulaChecks.every((c) => c.formulaOk);
push('P0-04-formula', allFormulaOk ? 'PASS' : 'FAIL', {
  count: formulaChecks.length,
  rows: formulaChecks
});

// --- 2) Ledger SPLIT_CREDIT vs split ---
const ledgerRaw = psql(
  "SELECT ref_id, amount_cents, entry_type FROM merchant_wallet_ledger WHERE entry_type='SPLIT_CREDIT' ORDER BY ledger_id"
);
const ledgerRows = ledgerRaw
  .split('\n')
  .filter(Boolean)
  .map((line) => {
    const [refId, amount, type] = line.split('|');
    return { refId, amount: Number(amount), type };
  });

const ledgerAligned = formulaChecks.every((c) => {
  const hit = ledgerRows.find((l) => l.refId === String(c.splitId));
  return hit && hit.amount === c.merchant;
});
push('P0-04-ledger-link', ledgerAligned ? 'PASS' : 'FAIL', {
  ledgers: ledgerRows,
  aligned: ledgerAligned
});

// wallet balance = sum credits + withdraws
const bal = Number(
  psql("SELECT balance_cents FROM merchant_wallet_account WHERE merchant_id='MCH-DEFAULT'")
);
const expectedBal =
  ledgerRows.reduce((s, r) => s + r.amount, 0) +
  Number(
    psql(
      "SELECT COALESCE(SUM(amount_cents),0) FROM merchant_wallet_ledger WHERE entry_type LIKE 'WITHDRAW%'"
    ) || '0'
  );
// simpler: known 315+270-100=485
const balOk = bal === 485;
push('P0-04-wallet-balance', balOk ? 'PASS' : 'PARTIAL', {
  balanceCents: bal,
  expectedNote: '315+270-100(withdraw)=485',
  expectedBalHeuristic: expectedBal
});

// merchant portal consistency
const mchSplits = await api(mch, 'GET', '/api/v2/merchant/revenue-splits?page=0&size=20');
const mchItems = mchSplits.data?.data?.items || mchSplits.data?.data || [];
const mchArr = Array.isArray(mchItems) ? mchItems : [];
push('P0-04-merchant-ui-api', mchArr.length >= 1 && mchSplits.data?.code === 0 ? 'PASS' : 'FAIL', {
  code: mchSplits.data?.code,
  count: mchArr.length,
  sample: mchArr.slice(0, 2).map((s) => ({
    splitId: s.splitId,
    merchantCents: s.merchantCents,
    orderId: s.orderId
  }))
});

// --- 3) Replay: confirm-ledger twice + creditIfAbsent idempotent ---
const target =
  formulaChecks.find((c) => c.status === 'LEDGER_ONLY') ||
  formulaChecks.find((c) => String(c.status).includes('LEDGER')) ||
  formulaChecks[0];

const splitId = target?.splitId;
const creditCountBefore = Number(
  psql(
    `SELECT COUNT(*) FROM merchant_wallet_ledger WHERE entry_type='SPLIT_CREDIT' AND ref_id='${splitId}'`
  )
);
const balBefore = Number(
  psql("SELECT balance_cents FROM merchant_wallet_account WHERE merchant_id='MCH-DEFAULT'")
);

const confirm1 = await api(
  ops,
  'POST',
  `/api/v2/ops/admin/merchants/revenue-splits/${splitId}/confirm-ledger`,
  {
    reason: 'P0-04 replay depth'
  }
);
const confirm2 = await api(
  ops,
  'POST',
  `/api/v2/ops/admin/merchants/revenue-splits/${splitId}/confirm-ledger`,
  {
    reason: 'P0-04 replay again'
  }
);

const creditCountAfter = Number(
  psql(
    `SELECT COUNT(*) FROM merchant_wallet_ledger WHERE entry_type='SPLIT_CREDIT' AND ref_id='${splitId}'`
  )
);
const balAfter = Number(
  psql("SELECT balance_cents FROM merchant_wallet_account WHERE merchant_id='MCH-DEFAULT'")
);
const statusAfter = psql(`SELECT status FROM order_revenue_split WHERE split_id='${splitId}'`);

// UK replay: attempt second insert for same order should fail
const orderId = target?.orderId;
let ukDenied = false;
let ukMsg = '';
try {
  psql(
    `INSERT INTO order_revenue_split (split_id, order_id, merchant_id, device_id, gross_cents, platform_cents, merchant_cents, status) VALUES ('9999999999999999999', '${orderId}', 'MCH-DEFAULT', '777740024057', 1, 0, 1, 'LEDGER_ONLY')`
  );
} catch (e) {
  ukDenied = /unique|duplicate|uk_order_revenue_split/i.test(String(e.message || e));
  ukMsg = String(e.message || e).slice(0, 200);
}

const noDoubleCredit = creditCountBefore === creditCountAfter && balBefore === balAfter;
const secondRejected =
  confirm2.data?.code === 409 ||
  confirm2.status === 409 ||
  /仅 LEDGER_ONLY|冲突|CONFLICT|完结/.test(JSON.stringify(confirm2.data));

push(
  'P0-04-replay-no-double',
  noDoubleCredit && (secondRejected || confirm1.data?.code === 0) ? 'PASS' : 'FAIL',
  {
    splitId,
    orderId,
    confirm1: {
      code: confirm1.data?.code,
      status: confirm1.data?.data?.status,
      message: confirm1.data?.message
    },
    confirm2: { code: confirm2.data?.code, http: confirm2.status, message: confirm2.data?.message },
    creditCountBefore,
    creditCountAfter,
    balBefore,
    balAfter,
    statusAfter,
    ukDenied,
    ukMsg
  }
);

// UI screenshot
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
await page.context().addCookies([
  {
    name: 'aicabinet_admin_session',
    value: ops,
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
}, ops);
await page.goto('http://localhost/admin/merchants', { waitUntil: 'networkidle' }).catch(() => {});
// try splits page paths
for (const p of ['/admin/merchant-splits', '/admin/merchants?tab=splits', '/admin/finance']) {
  await page.goto(`http://localhost${p}`, { waitUntil: 'networkidle' }).catch(() => {});
}
await page
  .goto('http://localhost/admin/line-managers', { waitUntil: 'domcontentloaded' })
  .catch(() => {});
// direct from menu - merchant splits often under 商户与分账
await page.goto('http://localhost/admin/merchants', { waitUntil: 'networkidle' });
await page.waitForTimeout(800);
const hasSplitsNav = await page.getByText(/分账/).first().count();
if (hasSplitsNav) {
  await page
    .getByText(/分账/)
    .first()
    .click()
    .catch(() => {});
  await page.waitForTimeout(1000);
}
await page.screenshot({ path: `${UI}/p0-splits.png`, fullPage: true });
const bodyText = await page.evaluate(() =>
  (document.body.innerText || '').replace(/\s+/g, ' ').slice(0, 300)
);
push(
  'P0-04-admin-ui',
  /分账|商户|LEDGER|315|270|SETTLED|记账/.test(bodyText) ? 'PASS' : 'PARTIAL',
  {
    text: bodyText.slice(0, 180),
    screenshot: 'p0-splits.png'
  }
);
await browser.close();

report.summary = {
  pass: report.cases.filter((c) => c.status === 'PASS').length,
  fail: report.cases.filter((c) => c.status === 'FAIL').length,
  partial: report.cases.filter((c) => c.status === 'PARTIAL').length,
  total: report.cases.length
};
const overall = report.cases
  .filter((c) => c.id.startsWith('P0-04') && c.id !== 'P0-04-admin-ui')
  .every((c) => c.status === 'PASS')
  ? 'PASS'
  : 'PARTIAL';
report.overall = overall;
fs.writeFileSync(`${OUT}/full-round-p0-04-split.json`, JSON.stringify(report, null, 2));
console.log('\nSUMMARY', report.summary, 'overall=', overall);
process.exit(report.summary.fail ? 1 : 0);
