/**
 * MA-041/MA-058 活栈回归：admin「新建仓间调拨」表单必须能真的建成。
 *
 * 背景（2026-11-02 三端审计）：MA-038 把后端收紧成「批次号 + 有效期必填否则 400」，
 * 但弹窗当时**没有有效期控件**、批次还标注「可空」，且 payload 从不发 expiryDate
 * ⇒ 运营台任何填写组合都 400（故障从「收货 500」前移成「创建 400」）。
 * 台账 MA-038 当时标的「活栈实证」是 API 直调，UI 路径没人复验过——本脚本就是补那条腿。
 *
 * 判据（三条都真才算过）：
 *   A 弹窗含「有效期」必填项，批次 placeholder 不再是「可空」；
 *   B 批次留空点创建 ⇒ 前端拦下并提示「请填写批次号」，且**不发** POST；
 *   C 填全后 POST 的 body 必须带 `expiryDate=YYYY-MM-DD` 与非空 batchNo ⇒ 2xx + 「调拨单已创建」，
 *     随后把这张 DRAFT 单取消掉（不留脏台账）。
 *
 * 用法：node scripts/verify-warehouse-transfer-form.mjs
 * 前提：本地栈在跑（gateway :80 → trade :18080）、超管 13900000001/123456、redis 容器可 docker exec。
 */
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { chromium } from 'playwright';
import { captchaFromRedis } from './lib/redis-captcha.mjs';

const BASE = process.env.E2E_BASE_URL || 'http://localhost';
const OUT = 'docs/module-audit/evidence';
const EXPIRY = '2027-01-31';
const BATCH = `B-VERIFY-${new Date().toISOString().slice(0, 10).replaceAll('-', '')}`;

const results = [];
const failures = [];
function check(name, ok, detail = '') {
  results.push(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ` — ${detail}` : ''}`);
  if (!ok) failures.push(name);
}

function redisCaptcha(id) {
  return captchaFromRedis(id);
}
function pickSkuId() {
  const sql = "SELECT sku_id FROM sku_catalog WHERE sku_id ~ '^[0-9]+$' ORDER BY sku_id LIMIT 1;";
  return execFileSync(
    'docker',
    [
      'exec',
      'ai-cabinet-postgres-1',
      'psql',
      '-U',
      'aicabinet',
      '-d',
      'aicabinet',
      '-t',
      '-A',
      '-c',
      sql
    ],
    {
      encoding: 'utf8'
    }
  ).trim();
}

async function adminLogin() {
  const cap = await fetch(`${BASE}/api/v2/auth/captcha`).then((r) => r.json());
  const captchaId = cap.data.captchaId;
  const res = await fetch(`${BASE}/api/v2/auth/admin-password-login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      phoneNumber: '13900000001',
      password: '123456',
      captchaId,
      captchaCode: await redisCaptcha(captchaId)
    })
  });
  const data = await res.json();
  if (data.code !== 0) throw new Error(`admin login failed: ${JSON.stringify(data)}`);
  return data.data.token;
}

async function main() {
  const skuId = pickSkuId();
  if (!skuId) throw new Error('dev 库里没有数字 SKU，夹具不成立');
  const token = await adminLogin();

  const browser = await chromium.launch({ channel: 'chrome', headless: false });
  const context = await browser.newContext({ viewport: { width: 1600, height: 950 } });
  const page = await context.newPage();

  // 先建立同源，再注入 token（admin 读 localStorage: admin_token / admin_token_expires）
  await page.goto(`${BASE}/admin/login`, { waitUntil: 'domcontentloaded' });
  await page.evaluate((t) => {
    localStorage.setItem('admin_cookie_auth', '1');
    localStorage.setItem('admin_token', t);
    localStorage.setItem('admin_token_expires', String(Date.now() + 1_700_000));
  }, token);

  await page.goto(`${BASE}/admin/warehouse/fulfillment`, { waitUntil: 'networkidle' });
  await page.getByText('仓间调拨', { exact: true }).first().click();
  await page.getByRole('button', { name: '新建调拨' }).first().click();
  const dialog = page.locator('.el-dialog:visible').last();
  await dialog.waitFor({ state: 'visible', timeout: 15_000 });

  // ---- A 结构判据 ----
  const hasExpiry = await dialog
    .getByText('有效期', { exact: true })
    .first()
    .isVisible()
    .catch(() => false);
  const batchPlaceholder = await dialog
    .locator('input[placeholder="收货按 SKU+批次入库"]')
    .first()
    .getAttribute('placeholder')
    .catch(() => null);
  const expiryInput = dialog.locator('input[placeholder="选择有效期"]').first();
  const expiryPresent = await expiryInput.isVisible().catch(() => false);
  check('A1 弹窗有「有效期」必填项', hasExpiry && expiryPresent, `批次占位符=${batchPlaceholder}`);
  check(
    'A3 批次不再标注「可空」（MA-041 的原始症状）',
    !!batchPlaceholder && !batchPlaceholder.includes('可空'),
    `placeholder=${batchPlaceholder}`
  );
  const formGridCols = await dialog
    .locator('.form-grid')
    .first()
    .evaluate((el) => getComputedStyle(el).gridTemplateColumns)
    .catch(() => '');
  check(
    'A2 弹窗按 form-grid 两列拼排（MA-058）',
    formGridCols.split(' ').length === 2,
    formGridCols
  );

  // ---- B 前端必填拦截，且不发请求 ----
  let posts = 0;
  const onRequest = (req) => {
    if (req.method() === 'POST' && req.url().includes('/warehouse/transfers')) posts += 1;
  };
  page.on('request', onRequest);
  await dialog.getByRole('button', { name: '创建' }).click();
  const warnBatch = await page
    .getByText('请填写批次号')
    .first()
    .isVisible({ timeout: 4000 })
    .then(() => true)
    .catch(() => false);
  check(
    'B1 批次留空被前端拦下（不发 POST）',
    warnBatch && posts === 0,
    `toast=${warnBatch} posts=${posts}`
  );

  // ---- C 填全并真的建成 ----
  await dialog.locator('input[placeholder="商品 SKU"]').first().fill(skuId);
  await dialog.locator('input[placeholder="收货按 SKU+批次入库"]').first().fill(BATCH);
  await expiryInput.click();
  await expiryInput.fill(EXPIRY);
  await expiryInput.press('Enter');
  // 🔴 不能用 Escape：el-dialog 监听 ESC 会把整个弹窗关掉（实测按钮 detached）
  await dialog
    .getByText('新建仓间调拨', { exact: true })
    .first()
    .click({ timeout: 3000 })
    .catch(() => {});

  const [req] = await Promise.all([
    page
      .waitForRequest((r) => r.method() === 'POST' && r.url().includes('/warehouse/transfers'), {
        timeout: 15_000
      })
      .catch(() => null),
    dialog.getByRole('button', { name: '创建' }).click()
  ]);
  check('C1 创建请求真的发出', req !== null);
  let transferId = null;
  let status = 0;
  if (req) {
    const body = JSON.parse(req.postData() || '{}');
    const line = body.lines?.[0] || {};
    check(
      'C2 payload 带非空 batchNo + ISO 有效期',
      typeof line.batchNo === 'string' &&
        line.batchNo.trim() === BATCH &&
        line.expiryDate === EXPIRY,
      `batchNo=${line.batchNo} expiryDate=${line.expiryDate}`
    );
    const resp = await req.response();
    status = resp ? resp.status() : 0;
    const json = resp ? await resp.json().catch(() => null) : null;
    transferId = json?.data?.transferId ?? json?.data?.transfer_id ?? null;
    check(
      'C3 后端接受（2xx 且 code=0）',
      status === 200 && json?.code === 0,
      `http=${status} code=${json?.code} msg=${json?.message ?? ''}`
    );
    const okToast = await page
      .getByText('调拨单已创建')
      .first()
      .isVisible({ timeout: 5000 })
      .then(() => true)
      .catch(() => false);
    check('C4 UI 提示创建成功并刷新列表', okToast, `transferId=${transferId}`);
  }

  fs.mkdirSync(OUT, { recursive: true });
  await page.screenshot({
    path: path.join(OUT, 'MA-041-transfer-form-created.png'),
    fullPage: false
  });

  page.off('request', onRequest);
  await browser.close();

  // ---- 清理：把刚建的 DRAFT 单取消，别在 dev 台账里留垃圾 ----
  if (transferId) {
    const cancel = await fetch(
      `${BASE}/api/v2/ops/admin/warehouse/transfers/${encodeURIComponent(transferId)}/cancel`,
      {
        method: 'POST',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
      }
    ).then((r) => r.json());
    console.log(`cleanup: 取消调拨单 ${transferId} → code=${cancel.code} ${cancel.message ?? ''}`);
  }

  console.log(results.join('\n'));
  if (failures.length) {
    console.log(`RESULT: FAIL（${failures.length} 条不成立）`);
    process.exit(1);
  }
  console.log(`RESULT: PASS（sku=${skuId} batch=${BATCH} transfer=${transferId ?? '-'}）`);
}

main().catch((e) => {
  console.error('脚本异常：', e?.message || e);
  process.exit(2);
});
