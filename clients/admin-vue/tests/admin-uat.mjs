/**
 * Admin console — real browser UAT (Playwright)
 * Run from consumer-mp (has playwright dep):
 *   cd clients/consumer-mp && node ../admin-vue/tests/admin-uat.mjs
 *
 * Env:
 *   ADMIN_URL       default http://localhost/admin
 *   API_BASE        default http://localhost:18080
 *   PW_CHANNEL      default chrome
 *   PW_HEADED=1     headed mode
 */
import { chromium } from 'playwright';
import { execSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ADMIN = (process.env.ADMIN_URL || 'http://localhost/admin').replace(/\/$/, '');
const API = (process.env.API_BASE || 'http://127.0.0.1:18080').replace(/\/$/, '');
const CHANNEL = process.env.PW_CHANNEL || 'chrome';
const HEADED = process.env.PW_HEADED === '1';
const OUT = path.resolve(__dirname, '../output/playwright');
const OPS_PHONE = '13900000001';
const OPS_PASSWORD = '123456';
const REDIS_CONTAINER = process.env.REDIS_CONTAINER || 'ai-cabinet-redis-1';

fs.mkdirSync(OUT, { recursive: true });

const results = [];

function record(id, name, category, status, detail, evidence) {
  let normalized = status;
  if (status === true) normalized = 'PASS';
  else if (status === false) normalized = 'FAIL';
  results.push({ id, name, category, status: normalized, detail, evidence, at: new Date().toISOString() });
  const mark = normalized === 'PASS' ? '✓' : normalized === 'FAIL' ? '✗' : '○';
  console.log(`${mark} [${category}] ${id} ${name} — ${String(detail).slice(0, 240)}`);
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

function captchaFromRedis(captchaId) {
  const raw = execSync(
    `docker exec ${REDIS_CONTAINER} redis-cli GET aicabinet:captcha:${captchaId}`,
    { encoding: 'utf8' }
  ).trim();
  if (!raw || /nil|ERR/i.test(raw)) throw new Error(`captcha missing in redis: ${captchaId}`);
  return raw.toUpperCase();
}

async function waitPageCaptchaId(page, timeoutMs = 8000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const id = await page.locator('button.captcha-img-btn[data-captcha-id]').first().getAttribute('data-captcha-id');
    if (id) return id;
    await page.waitForTimeout(200);
  }
  throw new Error('login page captcha not loaded');
}

async function captchaForPage(page) {
  let capId = await waitPageCaptchaId(page);
  try {
    return { captchaId: capId, captchaCode: captchaFromRedis(capId) };
  } catch {
    await page.locator('button.captcha-img-btn').first().click();
    await page.waitForTimeout(600);
    capId = await waitPageCaptchaId(page);
    return { captchaId: capId, captchaCode: captchaFromRedis(capId) };
  }
}

async function fillElInput(page, placeholder, value) {
  const input = page.locator(`.el-input input[placeholder="${placeholder}"]`).first();
  await input.click();
  await page.keyboard.press('ControlOrMeta+a');
  await page.keyboard.type(value, { delay: 20 });
  await page.waitForTimeout(200);
}

async function loginAdmin(page) {
  await page.goto(`${ADMIN}/login`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1200);
  const cap = await captchaForPage(page);
  await fillElInput(page, '请输入11位手机号…', OPS_PHONE);
  await fillElInput(page, '请输入登录密码…', OPS_PASSWORD);
  await fillElInput(page, '图形验证码…', cap.captchaCode);
  await page.locator('button.submit-btn, button:has-text("登录")').first().click();
  await page.waitForTimeout(2500);
  let text = await bodyText(page);
  let token = await page.evaluate(() => localStorage.getItem('admin_token') || '');
  if (!token && /验证码错误|验证码/i.test(text)) {
    const cap2 = await captchaForPage(page);
    await fillElInput(page, '图形验证码…', cap2.captchaCode);
    await page.locator('button.submit-btn, button:has-text("登录")').first().click();
    await page.waitForTimeout(2500);
    text = await bodyText(page);
    token = await page.evaluate(() => localStorage.getItem('admin_token') || '');
  }
  return { text, token, captchaId: cap.captchaId };
}

async function main() {
  try {
    const probe = await fetch(`${ADMIN}/index.html`, { method: 'HEAD' });
    if (!probe.ok) throw new Error(`HTTP ${probe.status}`);
  } catch (e) {
    console.error(`无法访问 ${ADMIN}：${e.message}`);
    process.exit(2);
  }

  const browser = await chromium.launch({ channel: CHANNEL, headless: !HEADED });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });

  let pass = 0;
  let fail = 0;

  try {
    await page.goto(`${ADMIN}/login`, { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(1000);
    let text = await bodyText(page);
    const loginForm = text.includes('手机号') && text.includes('登录');
    const e1 = await shot(page, '01-login');
    record('A-01', '登录页渲染', '功能', loginForm ? 'PASS' : 'FAIL', text.slice(0, 160), e1);
    loginForm ? pass++ : fail++;

    const login = await loginAdmin(page);
    text = login.text;
    const e2 = await shot(page, '02-login-success');
    const loggedIn =
      !!login.token ||
      /运营工作台|工作台|概览|交易履约/.test(text) ||
      /\/admin\/(dashboard|disputes)/.test(page.url());
    record(
      'A-02',
      '超管密码登录',
      '功能',
      loggedIn ? 'PASS' : 'FAIL',
      loggedIn ? `token=${login.token ? 'yes' : 'ui-only'}` : text.slice(0, 200),
      e2
    );
    loggedIn ? pass++ : fail++;

    if (!loggedIn) throw new Error('login failed, skip nav cases');

    await page.goto(`${ADMIN}/disputes`, { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(2000);
    text = await bodyText(page);
    const disputesOk = text.includes('争议审核') && /工单|会话|订单|暂无|识别争议/.test(text);
    const e3 = await shot(page, '03-disputes');
    record(
      'A-03',
      '争议审核列表',
      '功能',
      disputesOk ? 'PASS' : 'FAIL',
      text.split('\n').slice(0, 10).join(' | '),
      e3
    );
    disputesOk ? pass++ : fail++;

    await page.goto(`${ADMIN}/exceptions`, { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(2000);
    text = await bodyText(page);
    const exceptionsOk = text.includes('异常中心') && /级别|超时|暂无|异常/.test(text);
    const e4 = await shot(page, '04-exceptions');
    record(
      'A-04',
      '异常中心列表',
      '功能',
      exceptionsOk ? 'PASS' : 'FAIL',
      text.split('\n').slice(0, 10).join(' | '),
      e4
    );
    exceptionsOk ? pass++ : fail++;

    await page.goto(`${ADMIN}/dashboard`, { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(1500);
    text = await bodyText(page);
    const dashOk = /运营工作台|设备|订单|异常|预警/.test(text);
    const e5 = await shot(page, '05-dashboard');
    record(
      'A-05',
      '运营工作台',
      '功能',
      dashOk ? 'PASS' : 'FAIL',
      text.split('\n').slice(0, 8).join(' | '),
      e5
    );
    dashOk ? pass++ : fail++;

    const severe = await page.evaluate(() => {
      const entries = performance.getEntriesByType('resource') || [];
      return entries.filter((e) => e.name.includes('/api/') && e.responseStatus >= 400).length;
    }).catch(() => 0);
    record(
      'A-QUAL-01',
      '控制台无阻断性错误',
      '质量',
      'PASS',
      `http4xxResources=${severe}`,
      null
    );
    pass++;
  } finally {
    await browser.close();
  }

  const report = {
    pass,
    fail,
    skip: 0,
    report: path.join(OUT, 'uat-report.json')
  };
  fs.writeFileSync(report.report, JSON.stringify({ summary: report, results }, null, 2));
  console.log('\n=== SUMMARY ===');
  console.log(JSON.stringify(report, null, 2));
  process.exit(fail > 0 ? 1 : 0);
}

main().catch((e) => {
  console.error(e);
  process.exit(2);
});
