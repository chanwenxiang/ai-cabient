/**
 * 三端业务流程 UI 联调（Playwright）
 * 覆盖：消费者登录/订单/视频 → 商户订单/视频 → 运营争议/异常/订单
 *
 * Run (reuse consumer-mp playwright dep):
 *   cd clients/consumer-mp && node ../admin-vue/tests/three-end-business-uat.mjs
 *
 * Env:
 *   CONSUMER_H5_URL  default http://127.0.0.1:3002
 *   MERCHANT_H5_URL  default http://127.0.0.1:3001
 *   ADMIN_URL        default http://localhost/admin
 *   API_BASE         default http://127.0.0.1:18080
 *   DEMO_ORDER_ID    default 1788233752744411094
 */
import { chromium } from 'playwright';
import { execSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const CONSUMER = (process.env.CONSUMER_H5_URL || 'http://127.0.0.1:3002').replace(/\/$/, '');
const MERCHANT = (process.env.MERCHANT_H5_URL || 'http://127.0.0.1:3001').replace(/\/$/, '');
const ADMIN = (process.env.ADMIN_URL || 'http://localhost/admin').replace(/\/$/, '');
const API = (process.env.API_BASE || 'http://127.0.0.1:18080').replace(/\/$/, '');
const DEMO_ORDER = process.env.DEMO_ORDER_ID || '1788233752744411094';
const CHANNEL = process.env.PW_CHANNEL || 'chrome';
const HEADED = process.env.PW_HEADED === '1';
const OUT = path.resolve(__dirname, '../output/playwright/three-end');
const REDIS = process.env.REDIS_CONTAINER || 'ai-cabinet-redis-1';

fs.mkdirSync(OUT, { recursive: true });
const results = [];

function record(id, name, status, detail, evidence) {
  let s = status;
  if (status === true) s = 'PASS';
  if (status === false) s = 'FAIL';
  results.push({ id, name, status: s, detail, evidence, at: new Date().toISOString() });
  console.log(`${s === 'PASS' ? '✓' : s === 'FAIL' ? '✗' : '○'} ${id} ${name} — ${String(detail).slice(0, 220)}`);
}

async function shot(page, name) {
  const file = path.join(OUT, `${name}.png`);
  try {
    await page.screenshot({ path: file, fullPage: true });
    return file;
  } catch {
    return null;
  }
}

async function bodyText(page) {
  return page.evaluate(() => document.body?.innerText || '');
}

function captchaCode(captchaId) {
  const raw = execSync(`docker exec ${REDIS} redis-cli GET aicabinet:captcha:${captchaId}`, {
    encoding: 'utf8'
  }).trim();
  if (!raw || /nil|ERR/i.test(raw)) throw new Error(`captcha missing ${captchaId}`);
  return raw.toUpperCase();
}

async function waitVideoPlayable(page, timeoutMs = 12000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const st = await page.evaluate(() => {
      const v = document.querySelector('video');
      if (!v) return { ok: false, readyState: 0, err: null, failedUi: true };
      return {
        ok: !v.error && v.readyState >= 2 && !/视频加载失败/.test(document.body?.innerText || ''),
        readyState: v.readyState,
        err: v.error ? v.error.code : null,
        failedUi: /视频加载失败/.test(document.body?.innerText || '')
      };
    });
    if (st.ok) return st;
    await page.waitForTimeout(400);
  }
  return page.evaluate(() => {
    const v = document.querySelector('video');
    return {
      ok: false,
      readyState: v?.readyState ?? 0,
      err: v?.error?.code ?? null,
      failedUi: /视频加载失败/.test(document.body?.innerText || '')
    };
  });
}

async function consumerLogin(page) {
  await page.goto(`${CONSUMER}/pages/login/login`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(800);
  // 验证码模式
  const smsTab = page.getByText('验证码', { exact: true });
  if ((await smsTab.count()) > 0) await smsTab.first().click();
  await page.waitForTimeout(300);
  const phone = page.locator('input').first();
  await phone.click();
  await page.keyboard.press('ControlOrMeta+a');
  await page.keyboard.type('13800138000', { delay: 20 });
  const getCode = page.getByText('获取验证码');
  if ((await getCode.count()) > 0) await getCode.first().click();
  await page.waitForTimeout(500);
  const inputs = page.locator('input');
  const n = await inputs.count();
  await inputs.nth(Math.min(1, n - 1)).click();
  await page.keyboard.type('123456', { delay: 20 });
  await page.locator('[data-testid="login-submit"], button:has-text("验证并继续"), button:has-text("登录")').first().click();
  await page.waitForTimeout(2500);
  return page.evaluate(() => localStorage.getItem('consumer_token') || '');
}

async function merchantLogin(page) {
  await page.goto(`${MERCHANT}/pages/login/login`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(800);
  const phone = page.locator('[data-testid="login-phone"] input, [data-testid="login-phone"] .uni-input-input').first();
  const pwd = page.locator('[data-testid="login-password"] input, [data-testid="login-password"] .uni-input-input').first();
  await phone.click();
  await page.keyboard.press('ControlOrMeta+a');
  await page.keyboard.type('13800138001', { delay: 25 });
  await pwd.click();
  await page.keyboard.press('ControlOrMeta+a');
  await page.keyboard.type('123456', { delay: 25 });
  await page.waitForTimeout(400);
  await page.locator('[data-testid="login-submit"]').first().click();
  await page.waitForTimeout(2500);
  return page.evaluate(() => localStorage.getItem('merchant_token') || '');
}

async function adminLogin(page) {
  await page.goto(`${ADMIN}/login`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1200);
  let capId = '';
  for (let i = 0; i < 4; i++) {
    try {
      capId = await page.locator('button.captcha-img-btn[data-captcha-id]').first().getAttribute('data-captcha-id');
      if (!capId) throw new Error('no id');
      const code = captchaCode(capId);
      await page.locator('.el-input input[placeholder="请输入11位手机号…"]').fill('13900000001');
      await page.locator('.el-input input[placeholder="请输入登录密码…"]').fill('123456');
      await page.locator('.el-input input[placeholder="图形验证码…"]').fill(code);
      await page.locator('button.submit-btn, button:has-text("登录")').first().click();
      await page.waitForTimeout(2200);
      const text = await bodyText(page);
      if (/运营工作台|概览|交易履约|订单管理/.test(text) || page.url().includes('/dashboard')) {
        return true;
      }
    } catch {
      await page.locator('button.captcha-img-btn').first().click({ force: true, timeout: 3000 }).catch(() => {});
      await page.waitForTimeout(700);
    }
  }
  return false;
}

async function main() {
  for (const u of [CONSUMER, MERCHANT, `${ADMIN}/index.html`]) {
    const r = await fetch(u, { method: 'HEAD' }).catch(() => null);
    if (!r || !r.ok) {
      console.error(`入口不可用: ${u}`);
      process.exit(2);
    }
  }

  const browser = await chromium.launch({ channel: CHANNEL, headless: !HEADED });
  const page = await browser.newPage({ viewport: { width: 1280, height: 860 } });
  let pass = 0;
  let fail = 0;

  try {
    // —— 消费者 ——
    const cToken = await consumerLogin(page);
    const e1 = await shot(page, 'c01-login');
    record('T-C01', '消费者登录', !!cToken, cToken ? 'token ok' : 'no token', e1);
    cToken ? pass++ : fail++;

    await page.goto(`${CONSUMER}/pages/orders/orders`, { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(1800);
    let text = await bodyText(page);
    const ordersOk = /订单|已支付|已完成|暂无/.test(text);
    record('T-C02', '消费者订单列表', ordersOk, text.split('\n').slice(0, 8).join(' | '), await shot(page, 'c02-orders'));
    ordersOk ? pass++ : fail++;

    await page.goto(
      `${CONSUMER}/pages/video/video?orderId=${encodeURIComponent(DEMO_ORDER)}`,
      { waitUntil: 'domcontentloaded' }
    );
    const cVideo = await waitVideoPlayable(page);
    record(
      'T-C03',
      '消费者订单购物视频',
      cVideo.ok,
      `readyState=${cVideo.readyState} err=${cVideo.err} failedUi=${cVideo.failedUi}`,
      await shot(page, 'c03-video')
    );
    cVideo.ok ? pass++ : fail++;

    // —— 商户 ——
    const mToken = await merchantLogin(page);
    record('T-M01', '商户登录', !!mToken, mToken ? 'token ok' : 'no token', await shot(page, 'm01-login'));
    mToken ? pass++ : fail++;

    await page.goto(`${MERCHANT}/pages/orders/orders`, { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(1800);
    text = await bodyText(page);
    const mOrders = /柜机订单|订单|已支付|导出/.test(text);
    record('T-M02', '商户柜机订单', mOrders, text.split('\n').slice(0, 8).join(' | '), await shot(page, 'm02-orders'));
    mOrders ? pass++ : fail++;

    await page.goto(
      `${MERCHANT}/pages/order-detail/order-detail?orderId=${encodeURIComponent(DEMO_ORDER)}`,
      { waitUntil: 'domcontentloaded' }
    );
    await page.waitForTimeout(1500);
    text = await bodyText(page);
    const hasBtn = text.includes('查看购物视频');
    if (hasBtn) {
      await page.getByText('查看购物视频', { exact: true }).first().click();
    }
    const mVideo = await waitVideoPlayable(page);
    record(
      'T-M03',
      '商户订单购物视频',
      hasBtn && mVideo.ok,
      `btn=${hasBtn} readyState=${mVideo.readyState} err=${mVideo.err}`,
      await shot(page, 'm03-video')
    );
    hasBtn && mVideo.ok ? pass++ : fail++;

    // —— 运营 ——
    const adminOk = await adminLogin(page);
    record('T-A01', '运营登录', adminOk, adminOk ? page.url() : 'login failed', await shot(page, 'a01-login'));
    adminOk ? pass++ : fail++;

    if (adminOk) {
      for (const p of [
        { id: 'T-A02', name: '争议审核', path: '/disputes', re: /争议审核|工单|识别/ },
        { id: 'T-A03', name: '异常中心', path: '/exceptions', re: /异常中心|级别|超时/ },
        { id: 'T-A04', name: '订单管理', path: '/orders', re: /订单|状态|金额/ }
      ]) {
        await page.goto(`${ADMIN}${p.path}`, { waitUntil: 'domcontentloaded' });
        await page.waitForTimeout(1600);
        text = await bodyText(page);
        const ok = p.re.test(text);
        record(p.id, p.name, ok, text.split('\n').slice(0, 8).join(' | '), await shot(page, p.id.toLowerCase()));
        ok ? pass++ : fail++;
      }
    }
  } finally {
    await browser.close();
  }

  const summary = { pass, fail, report: path.join(OUT, 'report.json') };
  fs.writeFileSync(summary.report, JSON.stringify({ summary, results }, null, 2));
  console.log('\n=== THREE-END BUSINESS UAT ===');
  console.log(JSON.stringify(summary, null, 2));
  process.exit(fail > 0 ? 1 : 0);
}

main().catch((e) => {
  console.error(e);
  process.exit(2);
});
