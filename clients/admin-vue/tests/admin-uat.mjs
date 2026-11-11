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
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { captchaFromRedis } from '../../../scripts/lib/redis-captcha.mjs';
import { adminPageState } from '../../../scripts/lib/ui-assert.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ADMIN = (process.env.ADMIN_URL || 'http://localhost/admin').replace(/\/$/, '');
const CHANNEL = process.env.PW_CHANNEL || 'chrome';
const HEADED = process.env.PW_HEADED === '1';
const OUT = path.resolve(__dirname, '../output/playwright');
const OPS_PHONE = '13900000001';
const OPS_PASSWORD = '123456';

fs.mkdirSync(OUT, { recursive: true });

const results = [];

function record(id, name, category, status, detail, evidence) {
  let normalized = status;
  if (status === true) normalized = 'PASS';
  else if (status === false) normalized = 'FAIL';
  results.push({
    id,
    name,
    category,
    status: normalized,
    detail,
    evidence,
    at: new Date().toISOString()
  });
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

// 图形验证码读取见顶部 import 的共享模块：它在设了 REDIS_HOST 时走 TCP 直连。
// 原内联版硬编码 `docker exec ai-cabinet-redis-1` —— CI 里没有该容器，必然 exit 2。

async function waitPageCaptchaId(page, timeoutMs = 8000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const id = await page
      .locator('button.captcha-img-btn[data-captcha-id]')
      .first()
      .getAttribute('data-captcha-id');
    if (id) return id;
    await page.waitForTimeout(200);
  }
  throw new Error('login page captcha not loaded');
}

async function captchaForPage(page) {
  for (let attempt = 0; attempt < 4; attempt++) {
    try {
      const capId = await waitPageCaptchaId(page, 5000);
      return { captchaId: capId, captchaCode: (await captchaFromRedis(capId)).toUpperCase() };
    } catch {
      // Redis 偶发未写入：刷新图形码；仍失败则整页重载
      try {
        await page.locator('button.captcha-img-btn').first().click({ timeout: 3000, force: true });
        await page.waitForTimeout(800);
      } catch {
        await page.reload({ waitUntil: 'domcontentloaded' });
        await page.waitForTimeout(1200);
      }
    }
  }
  throw new Error('unable to resolve admin captcha from redis');
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
  let token = await page.evaluate(
    () =>
      sessionStorage.getItem('admin_token') ||
      localStorage.getItem('admin_cookie_auth') ||
      localStorage.getItem('admin_token') ||
      ''
  );
  if (!token && /验证码错误|验证码/i.test(text)) {
    const cap2 = await captchaForPage(page);
    await fillElInput(page, '图形验证码…', cap2.captchaCode);
    await page.locator('button.submit-btn, button:has-text("登录")').first().click();
    await page.waitForTimeout(2500);
    text = await bodyText(page);
    token = await page.evaluate(
      () =>
        sessionStorage.getItem('admin_token') ||
        localStorage.getItem('admin_cookie_auth') ||
        localStorage.getItem('admin_token') ||
        ''
    );
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
    // R15-M2：旧断言 /运营工作台|设备|订单|.../ 匹配 document.body.innerText——侧栏菜单
    // 常驻文案恒真（内容区白屏/接口全 4xx 也 PASS）。改结构化断言：页头标题精确匹配
    // + 工作台内容区独有统计卡在位。
    const dashSt = await page.evaluate(() => {
      const norm = (s) => (s || '').replace(/\s+/g, ' ').trim();
      return {
        titleOk:
          norm(document.querySelector('.page-card-head__title .title')?.innerText) === '运营工作台',
        tiles: document.querySelectorAll('.stat-tile').length
      };
    });
    const dashOk = dashSt.titleOk && dashSt.tiles > 0;
    text = await bodyText(page);
    const e5 = await shot(page, '05-dashboard');
    record(
      'A-05',
      '运营工作台',
      '功能',
      dashOk ? 'PASS' : 'FAIL',
      `titleOk=${dashSt.titleOk} statTiles=${dashSt.tiles} | ${text.split('\n').slice(0, 8).join(' | ')}`,
      e5
    );
    dashOk ? pass++ : fail++;

    // —— 订单 / 开门记录 / 设备：三端履约运营主路径 ——
    // R15-M2：旧断言对 body.innerText 匹配侧栏常驻词恒真；换 adminPageState 结构化
    // 断言（页头标题精确匹配 + 表格在位 + 非 loading + 有行或已水合空态）。
    const bizPages = [
      { id: 'A-06', name: '订单管理', path: '/orders', title: '订单管理' },
      { id: 'A-07', name: '开门记录', path: '/sessions', title: '开门记录' },
      { id: 'A-08', name: '设备列表', path: '/devices', title: '设备管理' }
    ];
    for (const p of bizPages) {
      await page.goto(`${ADMIN}${p.path}`, { waitUntil: 'domcontentloaded' });
      await page.waitForTimeout(1800);
      const st = await adminPageState(page, { title: p.title });
      const evidence = await shot(page, p.id.toLowerCase());
      record(
        p.id,
        p.name,
        '功能',
        st.ok ? 'PASS' : 'FAIL',
        `titleOk=${st.titleOk} tablePresent=${st.tablePresent} rows=${st.rows} loading=${st.loading}`,
        evidence
      );
      st.ok ? pass++ : fail++;
    }

    // 争议列表点开首行（若有）看详情抽屉/页
    await page.goto(`${ADMIN}/disputes`, { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(1500);
    const openedDispute = await page.evaluate(() => {
      const row = document.querySelector('.el-table__body tr, .el-table__row');
      if (!row) return false;
      row.dispatchEvent(new MouseEvent('click', { bubbles: true }));
      return true;
    });
    await page.waitForTimeout(1500);
    text = await bodyText(page);
    const detailOk =
      !openedDispute || /工单|会话|识别|录像|商品|免单|确认|KEEP|WAIVE|CONFIRM|详情/.test(text);
    const e9 = await shot(page, '09-dispute-detail');
    record(
      'A-09',
      '争议详情可打开',
      '功能',
      detailOk ? 'PASS' : 'FAIL',
      `opened=${openedDispute} body=${text.split('\n').slice(0, 8).join(' | ')}`,
      e9
    );
    detailOk ? pass++ : fail++;

    // R15-M2：旧写法统计 4xx 资源数后无条件 PASS（severe 只进日志）——改 fail-closed。
    const severe = await page
      .evaluate(() => {
        const entries = performance.getEntriesByType('resource') || [];
        return entries.filter((e) => e.name.includes('/api/') && e.responseStatus >= 400).length;
      })
      .catch(() => -1);
    record(
      'A-QUAL-01',
      '控制台无阻断性错误',
      '质量',
      severe === 0 ? 'PASS' : 'FAIL',
      `http4xxResources=${severe}`,
      null
    );
    severe === 0 ? pass++ : fail++;
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
