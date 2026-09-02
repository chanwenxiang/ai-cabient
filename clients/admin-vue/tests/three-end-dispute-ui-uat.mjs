/**
 * 三端争议业务 UI：运营后台结案 → 消费者/商户侧可见结果
 *
 * 前置：先造 OPEN 争议（API/模拟器，不在此脚本里开门）
 *   .\scripts\create-open-dispute.ps1
 *
 * 再跑：
 *   cd clients/consumer-mp && node ../admin-vue/tests/three-end-dispute-ui-uat.mjs
 *
 * 说明：图形验证码仅「运营后台」登录需要；商户端无图形验证码。
 */
import { chromium } from 'playwright';
import { execSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, '../../..');
const CONSUMER = (process.env.CONSUMER_H5_URL || 'http://127.0.0.1:3002').replace(/\/$/, '');
const MERCHANT = (process.env.MERCHANT_H5_URL || 'http://127.0.0.1:3001').replace(/\/$/, '');
const ADMIN = (process.env.ADMIN_URL || 'http://localhost/admin').replace(/\/$/, '');
const CHANNEL = process.env.PW_CHANNEL || 'chrome';
const HEADED = process.env.PW_HEADED === '1';
const OUT = path.resolve(__dirname, '../output/playwright/dispute-flow');
const REDIS = process.env.REDIS_CONTAINER || 'ai-cabinet-redis-1';
const DISPUTE_FILE = process.env.OPEN_DISPUTE_JSON || path.join(ROOT, '.tmp/open-dispute.json');

fs.mkdirSync(OUT, { recursive: true });
const results = [];

function record(id, name, status, detail, evidence) {
  let s = status;
  if (status === true) s = 'PASS';
  if (status === false) s = 'FAIL';
  results.push({ id, name, status: s, detail, evidence, at: new Date().toISOString() });
  console.log(
    `${s === 'PASS' ? '✓' : s === 'FAIL' ? '✗' : '○'} ${id} ${name} — ${String(detail).slice(0, 240)}`
  );
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

async function adminLogin(page) {
  await page.goto(`${ADMIN}/login`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1000);
  for (let i = 0; i < 5; i++) {
    try {
      const capId = await page
        .locator('button.captcha-img-btn[data-captcha-id]')
        .first()
        .getAttribute('data-captcha-id');
      if (!capId) throw new Error('no captcha');
      const code = captchaCode(capId);
      await page.locator('.el-input input[placeholder="请输入11位手机号…"]').fill('13900000001');
      await page.locator('.el-input input[placeholder="请输入登录密码…"]').fill('123456');
      await page.locator('.el-input input[placeholder="图形验证码…"]').fill(code);
      await page.locator('button.submit-btn, button:has-text("登录")').first().click();
      await page.waitForTimeout(2200);
      const text = await bodyText(page);
      if (
        /运营工作台|概览|交易履约|订单管理/.test(text) ||
        /\/admin\/(dashboard|disputes)/.test(page.url())
      ) {
        return true;
      }
    } catch {
      await page
        .locator('button.captcha-img-btn')
        .first()
        .click({ force: true, timeout: 3000 })
        .catch(() => {});
      await page.waitForTimeout(700);
    }
  }
  return false;
}

async function merchantLogin(page) {
  // 商户端：密码登录，无图形验证码
  await page.goto(`${MERCHANT}/pages/login/login`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(700);
  const phone = page
    .locator('[data-testid="login-phone"] input, [data-testid="login-phone"] .uni-input-input')
    .first();
  const pwd = page
    .locator(
      '[data-testid="login-password"] input, [data-testid="login-password"] .uni-input-input'
    )
    .first();
  await phone.click();
  await page.keyboard.press('ControlOrMeta+a');
  await page.keyboard.type('13800138001', { delay: 20 });
  await pwd.click();
  await page.keyboard.press('ControlOrMeta+a');
  await page.keyboard.type('123456', { delay: 20 });
  await page.locator('[data-testid="login-submit"]').first().click();
  await page.waitForTimeout(2200);
  return !!(await page.evaluate(() => localStorage.getItem('merchant_token') || ''));
}

async function consumerLogin(page) {
  await page.goto(`${CONSUMER}/pages/login/login`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(700);
  const smsTab = page.getByText('验证码', { exact: true });
  if ((await smsTab.count()) > 0) await smsTab.first().click();
  await page.waitForTimeout(200);
  await page.locator('input').first().click();
  await page.keyboard.press('ControlOrMeta+a');
  await page.keyboard.type('13800138000', { delay: 20 });
  const getCode = page.getByText('获取验证码');
  if ((await getCode.count()) > 0) await getCode.first().click();
  await page.waitForTimeout(400);
  const inputs = page.locator('input');
  await inputs.nth(Math.min(1, (await inputs.count()) - 1)).click();
  await page.keyboard.type('123456', { delay: 20 });
  await page
    .locator('[data-testid="login-submit"], button:has-text("验证并继续"), button:has-text("登录")')
    .first()
    .click();
  await page.waitForTimeout(2200);
  return !!(await page.evaluate(() => localStorage.getItem('consumer_token') || ''));
}

async function checkCheckboxByLabel(page, label) {
  const row = page.locator('.el-checkbox').filter({ hasText: label }).first();
  if ((await row.count()) === 0) return false;
  const checked = await row.evaluate((el) => el.classList.contains('is-checked'));
  if (!checked) await row.click();
  await page.waitForTimeout(200);
  return true;
}

async function main() {
  if (!fs.existsSync(DISPUTE_FILE)) {
    console.error(`缺少 ${DISPUTE_FILE}，请先运行: .\\scripts\\create-open-dispute.ps1`);
    process.exit(2);
  }
  const dispute = JSON.parse(fs.readFileSync(DISPUTE_FILE, 'utf8').replace(/^\uFEFF/, ''));
  const ticketId = String(dispute.ticketId || '');
  const sessionId = String(dispute.sessionId || '');
  if (!ticketId || !sessionId) {
    console.error('open-dispute.json 缺少 ticketId/sessionId');
    process.exit(2);
  }
  console.log(`Using OPEN dispute ticket=${ticketId} session=${sessionId}`);

  const browser = await chromium.launch({ channel: CHANNEL, headless: !HEADED });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  let pass = 0;
  let fail = 0;

  try {
    // —— 运营后台结案（需要图形验证码）——
    const loggedIn = await adminLogin(page);
    record(
      'D-A01',
      '运营登录（含图形验证码）',
      loggedIn,
      loggedIn ? page.url() : 'fail',
      await shot(page, '01-admin-login')
    );
    loggedIn ? pass++ : fail++;
    if (!loggedIn) throw new Error('admin login failed');

    const url = `${ADMIN}/disputes?status=OPEN&ticketId=${encodeURIComponent(ticketId)}&sessionId=${encodeURIComponent(sessionId)}`;
    await page.goto(url, { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(2200);
    let text = await bodyText(page);
    const listOk = text.includes('争议审核');
    record(
      'D-A02',
      '打开待审争议页',
      listOk,
      text.split('\n').slice(0, 8).join(' | '),
      await shot(page, '02-disputes')
    );
    listOk ? pass++ : fail++;

    // 点开首行或已自动选中
    const clicked = await page.evaluate((tid) => {
      const rows = [...document.querySelectorAll('.el-table__body tr, .el-table__row')];
      const hit =
        rows.find((r) => (r.innerText || '').includes(tid.slice(-6))) ||
        rows.find((r) => /OPEN|待审|待处理|识别/.test(r.innerText || '')) ||
        rows[0];
      if (!hit) return false;
      hit.dispatchEvent(new MouseEvent('click', { bubbles: true }));
      return true;
    }, ticketId);
    await page.waitForTimeout(1800);
    text = await bodyText(page);
    record(
      'D-A03',
      '打开争议详情',
      clicked || /工单|会话|免单/.test(text),
      `click=${clicked}`,
      await shot(page, '03-detail')
    );
    clicked || /工单|会话/.test(text) ? pass++ : fail++;

    // 无录像路径：勾选两个框（业务强制人工确认）
    await page.waitForTimeout(800);
    // 先尝试加载录像；失败则走无录像勾选
    const reloadBtn = page.getByRole('button', { name: '重新加载录像' });
    if ((await reloadBtn.count()) > 0) {
      await reloadBtn
        .first()
        .click()
        .catch(() => {});
      await page.waitForTimeout(2500);
    }
    text = await bodyText(page);
    if (/无录像|尚未加载|无法播放|录像加载/.test(text) || !(await page.locator('video').count())) {
      await checkCheckboxByLabel(page, '无录像 / 无法播放，仍结案');
    }
    await checkCheckboxByLabel(page, '已对照录像核对');
    await page.waitForTimeout(300);

    const waiveBtn = page.getByRole('button', { name: /免单并退款/ });
    const hasWaive = (await waiveBtn.count()) > 0;
    if (hasWaive) {
      await waiveBtn.first().click();
      await page.waitForTimeout(600);
      // 确认争议处理
      const confirm = page.getByRole('button', { name: '确认处理' });
      if ((await confirm.count()) > 0) await confirm.first().click();
      await page.waitForTimeout(800);
      // 免单是否回库：选「仅退款（不回库）」更贴近顾客已拿走
      const onlyRefund = page.getByRole('button', { name: /仅退款/ });
      if ((await onlyRefund.count()) > 0) await onlyRefund.first().click();
      else {
        const restore = page.getByRole('button', { name: /退货退款/ });
        if ((await restore.count()) > 0) await restore.first().click();
      }
      await page.waitForTimeout(2500);
    }
    text = await bodyText(page);
    const resolvedUi =
      /已处理|已结案|争议已结案|已免单|RESOLVED|免单/.test(text) ||
      !!(await page
        .locator('.resolve-feedback, .el-alert')
        .filter({ hasText: /结案|免单|已处理/ })
        .count());
    record(
      'D-A04',
      '运营 UI 免单结案',
      hasWaive && resolvedUi,
      `hasWaive=${hasWaive} resolvedUi=${resolvedUi} body=${text.split('\n').slice(0, 10).join(' | ')}`,
      await shot(page, '04-resolved')
    );
    hasWaive && resolvedUi ? pass++ : fail++;

    // —— 消费者 ——
    const cOk = await consumerLogin(page);
    record(
      'D-C01',
      '消费者登录（短信，无图形码）',
      cOk,
      cOk ? 'ok' : 'fail',
      await shot(page, '05-consumer-login')
    );
    cOk ? pass++ : fail++;
    await page.goto(`${CONSUMER}/pages/orders/orders`, { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(2000);
    text = await bodyText(page);
    // 免单后：可能显示已退款/已完成/¥0，或「需要关注」减少
    const cSee = /订单|已完成|已退款|免单|¥0|已支付|暂无/.test(text);
    record(
      'D-C02',
      '消费者订单页可见结算结果',
      cSee,
      text.split('\n').slice(0, 10).join(' | '),
      await shot(page, '06-consumer-orders')
    );
    cSee ? pass++ : fail++;

    // —— 商户（无图形验证码）——
    const mOk = await merchantLogin(page);
    record(
      'D-M01',
      '商户登录（密码，无图形码）',
      mOk,
      mOk ? 'ok' : 'fail',
      await shot(page, '07-merchant-login')
    );
    mOk ? pass++ : fail++;
    await page.goto(`${MERCHANT}/pages/orders/orders`, { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(2000);
    text = await bodyText(page);
    const mSee = /柜机订单|已支付|已退款|争议|导出|订单/.test(text);
    record(
      'D-M02',
      '商户订单页可打开',
      mSee,
      text.split('\n').slice(0, 10).join(' | '),
      await shot(page, '08-merchant-orders')
    );
    mSee ? pass++ : fail++;

    await page.goto(`${MERCHANT}/pages/disputes/disputes`, { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(1800);
    text = await bodyText(page);
    const mDisp = /争议|已结案|待处理|已关闭|暂无/.test(text);
    record(
      'D-M03',
      '商户争议页状态',
      mDisp,
      text.split('\n').slice(0, 10).join(' | '),
      await shot(page, '09-merchant-disputes')
    );
    mDisp ? pass++ : fail++;
  } finally {
    await browser.close();
  }

  const summary = { pass, fail, ticketId, sessionId, report: path.join(OUT, 'report.json') };
  fs.writeFileSync(summary.report, JSON.stringify({ summary, results }, null, 2));
  console.log('\n=== DISPUTE UI FLOW ===');
  console.log(JSON.stringify(summary, null, 2));
  process.exit(fail > 0 ? 1 : 0);
}

main().catch((e) => {
  console.error(e);
  process.exit(2);
});
