/**
 * Merchant mini-program H5 — real browser UAT (Playwright)
 * Run: node tests/merchant-h5-uat.mjs
 * Requires: npm run dev:h5 (http://127.0.0.1:3001) + gateway/trade up
 *
 * Env overrides:
 *   MERCHANT_H5_URL    base URL (default http://127.0.0.1:3001)
 *   MERCHANT_PHONE     登录手机号（默认 13800138001）
 *   MERCHANT_PASSWORD  登录密码（默认 123456，若演示账号密码被重置请用环境变量覆盖）
 *   PW_CHANNEL         browser channel (default "chrome" = 系统 Chrome；可改 "chromium")
 *   PW_HEADED=1        有头模式，便于人工观察
 *
 * 说明：与消费者端 UAT 共用同一套交互策略（真实鼠标点击 + 键盘输入 + 路径导航），
 * 覆盖登录、工作台、Tab、经营工具、深层页面、退出登录、网络容错与控制台扫描。
 */
import { chromium } from 'playwright';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const BASE = process.env.MERCHANT_H5_URL || 'http://127.0.0.1:3001';
const CHANNEL = process.env.PW_CHANNEL || 'chrome';
const HEADED = process.env.PW_HEADED === '1';
const OUT = path.resolve(__dirname, '../output/playwright');
const DEMO_PHONE = process.env.MERCHANT_PHONE || '13800138001';
const DEMO_PASSWORD = process.env.MERCHANT_PASSWORD || '123456';

fs.mkdirSync(OUT, { recursive: true });

const results = [];

function record(id, name, category, status, detail, evidence) {
  results.push({ id, name, category, status, detail, evidence, at: new Date().toISOString() });
  const mark = status === 'PASS' ? '✓' : status === 'FAIL' ? '✗' : '○';
  console.log(`${mark} [${category}] ${id} ${name} — ${String(detail).slice(0, 240)}`);
}

async function shot(page, name) {
  const file = path.join(OUT, `${name}.png`);
  try {
    await page.screenshot({ path: file });
    return file;
  } catch {
    return null;
  }
}

async function bodyText(page) {
  return page.evaluate(() => document.body?.innerText || '');
}

async function waitText(page, substr, timeout = 10000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    const t = await bodyText(page);
    if (t.includes(substr)) return t;
    await page.waitForTimeout(250);
  }
  return bodyText(page);
}

/** 真实鼠标点击：文本长度升序 → 元素类型（button 优先）→ 叶子优先；避开底部 Tab 误触 */
async function clickByText(page, text, { exact = false, timeout = 6000 } = {}) {
  const deadline = Date.now() + timeout;
  const tabBarGuardPx = 72;
  while (Date.now() < deadline) {
    const target = await page.evaluate(
      ({ text, exact, tabBarGuardPx }) => {
        const nodes = [
          ...document.querySelectorAll(
            'uni-text, uni-view, uni-button, button, span, div, a, text, view'
          )
        ];
        const tagRank = (t) => {
          if (t === 'UNI-BUTTON' || t === 'BUTTON') return 0;
          if (t === 'A') return 1;
          if (t === 'UNI-TEXT' || t === 'TEXT' || t === 'SPAN') return 2;
          return 3;
        };
        const clickableAncestor = (el) => {
          const selectors = '.ops-card, .quick-item, [role="button"], uni-button, button, a';
          return el.closest?.(selectors) || el;
        };
        const hits = nodes
          .map((e, i) => {
            const t = (e.innerText || e.textContent || '').trim();
            return {
              i,
              len: t.length,
              rank: tagRank(e.tagName),
              kids: e.children.length,
              match: exact ? t === text : t.includes(text)
            };
          })
          .filter((h) => h.match && h.len > 0)
          .sort((a, b) => a.len - b.len || a.rank - b.rank || a.kids - b.kids);
        if (!hits.length) return null;
        const el = clickableAncestor(nodes[hits[0].i]);
        el.scrollIntoView({ block: 'center', inline: 'nearest' });
        const r = el.getBoundingClientRect();
        if (r.width <= 0 || r.height <= 0) {
          el.click();
          return { clicked: true };
        }
        let y = r.top + r.height / 2;
        const maxY = window.innerHeight - tabBarGuardPx;
        if (y > maxY) y = Math.max(r.top + 8, maxY - 8);
        return { x: r.left + r.width / 2, y, scrolled: true };
      },
      { text, exact, tabBarGuardPx }
    );
    if (target === null) {
      await page.waitForTimeout(300);
      continue;
    }
    if (target.scrolled) await page.waitForTimeout(350);
    if (target.clicked) return true;
    await page.mouse.click(target.x, target.y);
    return true;
  }
  return false;
}

async function clickByTestId(page, testId) {
  const target = await page.evaluate((id) => {
    const el = document.querySelector(`[data-testid="${id}"]`);
    if (!el) return null;
    const r = el.getBoundingClientRect();
    if (r.width > 0 && r.height > 0) return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
    el.click();
    return { clicked: true };
  }, testId);
  if (target === null) return false;
  if (target.clicked) return true;
  await page.mouse.click(target.x, target.y);
  return true;
}

/**
 * 按 placeholder 文本定位 uni-input，真实键盘输入。
 * 注意：部分 uni-app H5 版本在键入时内部 ref 会滞后（DOM 值正确但提交值被截断），
 * 实测键入后需等待 uni-app 消化事件队列（约 500ms）；值已正确时直接跳过输入。
 */
async function fillPlaceholder(page, placeholder, value) {
  const uni = page
    .locator('uni-input')
    .filter({ has: page.locator('.uni-input-placeholder', { hasText: placeholder }) })
    .first();
  if ((await uni.count()) === 0) {
    const inputs = page.locator('uni-input input');
    const n = await inputs.count();
    for (let i = 0; i < n; i++) {
      if ((await inputs.nth(i).inputValue()) === value) return true;
    }
    return false;
  }
  const input = uni.locator('input').first();
  if ((await input.inputValue()) === value) return true;
  // 点击外层 uni-input（部分页面内层 input 高度为 0，点击外层同样能聚焦）
  await uni.click();
  await page.keyboard.press('ControlOrMeta+a');
  await page.keyboard.type(value, { delay: 30 });
  // 等待 uni-app 完成内部 ref 同步，避免提交值被截断（竞态）
  await page.waitForTimeout(500);
  return (await input.inputValue()) === value;
}

/** 清空当前页面所有 uni-input 输入框（登录页仅手机号+密码，适配开发预填场景） */
async function clearInputs(page) {
  const inputs = page.locator('uni-input input');
  const n = await inputs.count();
  for (let i = 0; i < n; i++) {
    await inputs.nth(i).click();
    await page.keyboard.press('ControlOrMeta+a');
    await page.keyboard.press('Backspace');
  }
  return n;
}

/** uni-app history 路由：直接访问页面路径 */
async function gotoPath(page, pathname, wait = 1500) {
  await page.goto(BASE + pathname, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(wait);
}

/** 点击 uni.showModal 主按钮 */
async function clickModalPrimary(page, fallbackText) {
  const target = await page.evaluate(() => {
    const el =
      document.querySelector('.uni-modal__btn_primary') ||
      [...document.querySelectorAll('.uni-modal .uni-modal__btn')].pop();
    if (!el) return null;
    const r = el.getBoundingClientRect();
    if (r.width > 0 && r.height > 0) return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
    el.click();
    return { clicked: true };
  });
  if (target === null) return fallbackText ? clickByText(page, fallbackText) : false;
  if (target.clicked) return true;
  await page.mouse.click(target.x, target.y);
  return true;
}

async function main() {
  try {
    const probe = await fetch(BASE + '/');
    if (!probe.ok) throw new Error(`HTTP ${probe.status}`);
  } catch (e) {
    console.error(`无法访问 ${BASE}，请先启动 dev:h5 与后端网关：${e.message}`);
    process.exit(2);
  }

  const browser = await chromium.launch({ headless: !HEADED, channel: CHANNEL });
  const context = await browser.newContext({
    viewport: { width: 390, height: 844 },
    isMobile: true,
    hasTouch: true,
    locale: 'zh-CN'
  });
  const page = await context.newPage();
  page.setDefaultTimeout(15000);

  const consoleErrors = [];
  const failedRequests = [];
  const http4xx = [];
  const withdrawPosts = [];
  let aborting = false;
  page.on('console', (msg) => {
    if (msg.type() === 'error') consoleErrors.push(String(msg.text()));
  });
  page.on('pageerror', (e) => consoleErrors.push(String(e.message || e)));
  page.on('requestfailed', (req) => {
    failedRequests.push({ url: req.url(), error: req.failure()?.errorText, intentional: aborting });
  });
  page.on('response', (res) => {
    if (res.status() >= 400) http4xx.push(`${res.status()} ${res.url().replace(BASE, '')}`);
  });
  page.on('request', (req) => {
    if (req.url().includes('/withdraw') && req.method() === 'POST')
      withdrawPosts.push(req.postData());
  });

  try {
    // —— M-01 登录页 ——
    await gotoPath(page, '/');
    let text = await bodyText(page);
    const loginPage = text.includes('手机号') && text.includes('密码') && text.includes('登录');
    const e1 = await shot(page, '01-login');
    record(
      'M-01',
      '登录页渲染',
      '功能',
      loginPage ? 'PASS' : 'FAIL',
      loginPage ? '表单可见' : text.slice(0, 200),
      e1
    );

    // —— M-02 空提交 ——
    await clearInputs(page);
    await clickByTestId(page, 'login-submit');
    await page.waitForTimeout(1000);
    text = await bodyText(page);
    const emptyLogin = /请输入|错误|失败/.test(text) && text.includes('登录');
    const e2 = await shot(page, '02-login-empty');
    record(
      'M-02',
      '空手机号/空密码提交',
      '边界',
      emptyLogin ? 'PASS' : 'FAIL',
      `仍留在登录页或有错误提示: ${text.slice(-120)}`,
      e2
    );

    // —— M-03 非法手机号/错误密码 ——
    await clearInputs(page);
    await fillPlaceholder(page, '请输入11位手机号…', '123');
    await fillPlaceholder(page, '请输入登录密码…', 'wrong');
    await clickByTestId(page, 'login-submit');
    await page.waitForTimeout(1500);
    text = await bodyText(page);
    const badLogin = /失败|错误|无效|不正确|请输入|手机号/.test(text) && text.includes('登录');
    const e3 = await shot(page, '03-login-invalid');
    record(
      'M-03',
      '非法手机号/错误密码',
      '异常',
      badLogin ? 'PASS' : 'FAIL',
      badLogin ? '展示友好错误' : text.slice(-150),
      e3
    );

    // —— M-04 正常登录 ——
    await clearInputs(page);
    await fillPlaceholder(page, '请输入11位手机号…', DEMO_PHONE);
    await fillPlaceholder(page, '请输入登录密码…', DEMO_PASSWORD);
    await clickByTestId(page, 'login-submit');
    await page.waitForTimeout(3500);
    text = await bodyText(page);
    const token = await page.evaluate(() => localStorage.getItem('merchant_token') || '');
    const e4 = await shot(page, '04-login-success');
    record(
      'M-04',
      `密码登录 ${DEMO_PHONE}`,
      '功能',
      !!token,
      token ? 'token 已写入' : `无 token: ${text.slice(0, 200)}`,
      e4
    );

    // —— M-05 工作台 ——
    await page.waitForTimeout(1200);
    text = await bodyText(page);
    const homeOk = text.includes('扫码到柜') && (text.includes('你好') || text.includes('工作台'));
    const e5 = await shot(page, '05-home');
    record(
      'M-05',
      '工作台首页',
      '功能',
      homeOk ? 'PASS' : 'FAIL',
      text.split('\n').slice(0, 14).join(' | '),
      e5
    );

    // —— M-06/07/08 Tab：柜机 / 待办 / 我的 ——
    await clickByText(page, '柜机', { exact: true });
    await page.waitForTimeout(2000);
    text = await bodyText(page);
    const devicesOk = /柜机|暂无|在线|离线|停售/.test(text);
    const e6 = await shot(page, '06-devices');
    record(
      'M-06',
      '柜机列表',
      '功能',
      devicesOk ? 'PASS' : 'FAIL',
      text.split('\n').slice(0, 12).join(' | '),
      e6
    );

    await clickByText(page, '待办', { exact: true });
    await page.waitForTimeout(2000);
    text = await bodyText(page);
    const alertsOk = /待办|审核|离线|库存|临期|暂无/.test(text);
    const e7 = await shot(page, '07-alerts');
    record(
      'M-07',
      '待办页',
      '功能',
      alertsOk ? 'PASS' : 'FAIL',
      text.split('\n').slice(0, 12).join(' | '),
      e7
    );

    await clickByText(page, '我的', { exact: true });
    await page.waitForTimeout(1500);
    text = await bodyText(page);
    const mineOk = text.includes('退出登录');
    const e8 = await shot(page, '08-mine');
    record(
      'M-08',
      '我的页',
      '功能',
      mineOk ? 'PASS' : 'FAIL',
      text.split('\n').slice(0, 14).join(' | '),
      e8
    );

    // —— M-09 工作台经营工具 ——
    const homePages = [
      { label: '补货任务', key: 'replenishment', marker: /补货任务|待处理|扫码找柜/ },
      { label: '要货申请', key: 'request', marker: /发起要货|我的申请/ },
      { label: '点位定价', key: 'pricing', marker: /点位定价|全部柜机|基准/ },
      { label: '结算对账', key: 'settlements', marker: /结算对账|区间营收|商户所得/ },
      { label: '争议处理', key: 'disputes', marker: /争议处理|待处理|暂无待处理/ },
      { label: '经营分析', key: 'business', marker: /经营分析|经营毛利|营收/ }
    ];
    for (const p of homePages) {
      await gotoPath(page, '/pages/home/home');
      const ok = await clickByText(page, p.label, { exact: true });
      await page.waitForTimeout(2000);
      text = await bodyText(page);
      const markerOk = p.marker.test(text);
      record(
        'M-09-' + p.key,
        `进入 ${p.label}`,
        '功能',
        ok && markerOk ? 'PASS' : 'FAIL',
        ok ? text.split('\n').filter(Boolean).slice(0, 6).join(' | ') : '找不到入口',
        await shot(page, `09-${p.key}`)
      );
    }

    // —— M-09b 补货任务详情抽屉（只读）——
    await gotoPath(page, '/pages/replenishment/replenishment');
    const taskCards = await page.evaluate(() => document.querySelectorAll('.task-card').length);
    if (taskCards > 0) {
      await page.evaluate(() => {
        const c = document.querySelector('.task-card');
        if (c) c.click();
      });
      await page.waitForTimeout(2200);
      const sheetVisible = await page.evaluate(() => !!document.querySelector('.sheet'));
      const e9b = await shot(page, '09b-replenishment-detail');
      record(
        'M-09b',
        '补货任务详情抽屉',
        '功能',
        sheetVisible ? 'PASS' : 'FAIL',
        `sheet=${sheetVisible}`,
        e9b
      );
      await page.evaluate(() => {
        const el = document.querySelector('.mask');
        if (el) el.click();
      });
      await page.waitForTimeout(500);
    } else {
      record('M-09b', '补货任务详情抽屉', '功能', 'SKIP', '当前无补货任务', null);
    }

    // —— M-10 我的页深层导航 ——
    const minePages = [
      { label: '柜机订单', key: 'orders', marker: /柜机订单|暂无柜机订单|已支付/ },
      { label: '团队成员', key: 'team', marker: /团队成员|邀请成员/ },
      { label: '商户钱包', key: 'wallet', marker: /商户钱包|可用余额/ },
      { label: '分账明细', key: 'splits', marker: /分账明细|暂无分账异常/ },
      { label: '线长钱包', key: 'line-wallet', marker: /线长钱包|可用余额/ },
      { label: '通知公告', key: 'announcements', marker: /通知公告|暂无平台公告/ }
    ];
    for (const p of minePages) {
      await gotoPath(page, '/pages/mine/mine');
      const ok = await clickByText(page, p.label, { exact: true });
      await page.waitForTimeout(2000);
      text = await bodyText(page);
      const markerOk = p.marker.test(text);
      record(
        'M-10-' + p.key,
        `进入 ${p.label}`,
        '功能',
        ok && markerOk ? 'PASS' : 'FAIL',
        ok ? text.split('\n').filter(Boolean).slice(0, 6).join(' | ') : '找不到入口',
        await shot(page, `10-${p.key}`)
      );
    }

    // —— M-10e 提现表单校验（客户端拦截，不发请求）——
    await gotoPath(page, '/pages/wallet/wallet');
    await waitText(page, '申请提现', 10000);
    await fillPlaceholder(page, '提现金额（元）', '0');
    await clickByText(page, '申请提现', { exact: true });
    await page.waitForTimeout(800);
    const postsAfterZero = withdrawPosts.length;
    await fillPlaceholder(page, '提现金额（元）', '999999');
    await clickByText(page, '申请提现', { exact: true });
    await page.waitForTimeout(800);
    const postsAfterOver = withdrawPosts.length;
    const e10w = await shot(page, '10w-withdraw-validate');
    record(
      'M-10e',
      '提现表单校验',
      '功能',
      postsAfterZero === 0 && postsAfterOver === 0 ? 'PASS' : 'FAIL',
      `zero请求=${postsAfterZero} over请求=${postsAfterOver}（客户端应拦截，不发提现请求）`,
      e10w
    );

    // —— M-10b 订单详情 ——
    await gotoPath(page, '/pages/orders/orders');
    const clickedOrder = await page.evaluate(() => {
      const card = document.querySelector('.card');
      if (!card) return false;
      card.click();
      return true;
    });
    await page.waitForTimeout(2000);
    text = await bodyText(page);
    const orderDetailOk = clickedOrder && /订单详情|支付信息|商品清单|支付方式/.test(text);
    const e10d = await shot(page, '10d-order-detail');
    record(
      'M-10b',
      '订单详情',
      '功能',
      orderDetailOk ? 'PASS' : 'FAIL',
      text.split('\n').slice(0, 10).join(' | '),
      e10d
    );

    // —— M-10c 柜机详情 ——
    await gotoPath(page, '/pages/device-detail/device-detail?id=CAB-001');
    text = await bodyText(page);
    const devDetailOk = /测试柜|CAB-001|货道|柜机设置|在线|离线/.test(text);
    const e10e = await shot(page, '10e-device-detail');
    record(
      'M-10c',
      '柜机详情',
      '功能',
      devDetailOk ? 'PASS' : 'FAIL',
      text.split('\n').slice(0, 12).join(' | '),
      e10e
    );

    // —— M-10d 争议详情抽屉 ——
    await gotoPath(page, '/pages/disputes/disputes');
    const clickedDispute = await page.evaluate(() => {
      const card = document.querySelector('.card');
      if (!card) return false;
      card.click();
      return true;
    });
    await page.waitForTimeout(1800);
    const drawerVisible = await page.evaluate(() => !!document.querySelector('.detail-panel'));
    const e10f = await shot(page, '10f-dispute-drawer');
    record(
      'M-10d',
      '争议详情抽屉',
      '功能',
      clickedDispute && drawerVisible ? 'PASS' : 'FAIL',
      `click=${clickedDispute} drawer=${drawerVisible} body=${(await bodyText(page)).split('\n').slice(0, 8).join(' | ')}`,
      e10f
    );
    await page.evaluate(() => {
      const el = document.querySelector('.detail-mask');
      if (el) el.click();
    });
    await page.waitForTimeout(500);

    // —— M-11 网络容错：工作台 API 全断 ——
    aborting = true;
    await context.route('**/api/v2/**', (route) => route.abort('failed'));
    await gotoPath(page, '/pages/home/home', 2000);
    await page.waitForTimeout(2500);
    text = await bodyText(page);
    const degraded = /扫码到柜|你好|工作台|重试|网络/.test(text) && text.length > 20;
    const e11 = await shot(page, '11-network-fail');
    record(
      'M-11',
      'API 中断时工作台容错',
      '异常',
      degraded ? 'PASS' : 'FAIL',
      `页面仍可渲染/有容错: ${text.split('\n').slice(0, 8).join(' | ')}`,
      e11
    );
    await context.unroute('**/api/v2/**');
    aborting = false;

    // —— M-12 未登录访问工作台 → 跳登录 ——
    await page.evaluate(() => {
      const keys = [];
      for (let i = 0; i < localStorage.length; i++) keys.push(localStorage.key(i));
      keys.forEach((k) => {
        if (k && k.includes('merchant')) localStorage.removeItem(k);
      });
    });
    await gotoPath(page, '/pages/home/home', 1800);
    text = await bodyText(page);
    const redirectLogin = text.includes('手机号') && text.includes('登录');
    const e12 = await shot(page, '12-unauth-redirect');
    record(
      'M-12',
      '未登录访问工作台重定向登录',
      '安全',
      redirectLogin ? 'PASS' : 'FAIL',
      text.split('\n').slice(0, 6).join(' | '),
      e12
    );

    // —— M-13 退出登录 ——
    await fillPlaceholder(page, '请输入11位手机号…', DEMO_PHONE);
    await fillPlaceholder(page, '请输入登录密码…', DEMO_PASSWORD);
    await clickByTestId(page, 'login-submit');
    await page.waitForTimeout(3000);
    await gotoPath(page, '/pages/mine/mine');
    await clickByText(page, '退出登录', { exact: true });
    await page.waitForTimeout(800);
    await clickModalPrimary(page, '退出');
    await page.waitForTimeout(2000);
    text = await bodyText(page);
    const loggedOut = text.includes('手机号') && text.includes('登录');
    const e13 = await shot(page, '13-logout');
    record(
      'M-13',
      '退出登录',
      '功能',
      loggedOut ? 'PASS' : 'FAIL',
      text.split('\n').slice(0, 6).join(' | '),
      e13
    );

    // —— M-14/15 控制台与网络错误 ——
    const serious = consoleErrors.filter(
      (e) =>
        !/favicon|DevTools|ResizeObserver|ERR_TIMED_OUT|ERR_ABORTED|ERR_FAILED|Failed to load resource/i.test(
          e
        )
    );
    record(
      'M-14',
      '浏览器控制台严重错误',
      '质量',
      serious.length === 0 ? 'PASS' : 'FAIL',
      serious.length === 0
        ? `无严重 console.error；http4xx=${http4xx.length} ${http4xx.slice(0, 3).join(' , ')}`
        : serious.slice(0, 5).join(' || '),
      null
    );
    record(
      'M-15',
      '失败网络请求（非主动 abort）',
      '质量',
      'INFO',
      `failed=${failedRequests.filter((r) => !r.intentional).length}; sample=${JSON.stringify(failedRequests.filter((r) => !r.intentional).slice(0, 3))}`,
      null
    );
  } catch (err) {
    record(
      'M-RUNNER',
      'UAT 执行异常',
      '质量',
      'FAIL',
      String(err?.stack || err),
      await shot(page, '99-crash').catch(() => null)
    );
  } finally {
    const summary = {
      base: BASE,
      total: results.length,
      pass: results.filter((r) => r.status === 'PASS').length,
      fail: results.filter((r) => r.status === 'FAIL').length,
      skip: results.filter((r) => r.status === 'SKIP').length,
      info: results.filter((r) => r.status === 'INFO').length,
      results
    };
    const reportPath = path.join(OUT, 'uat-report.json');
    fs.writeFileSync(reportPath, JSON.stringify(summary, null, 2), 'utf8');
    const md = [
      '# Merchant H5 UAT Report',
      '',
      `- Base: ${BASE}`,
      `- Pass: ${summary.pass} / Fail: ${summary.fail} / Skip: ${summary.skip} / Info: ${summary.info}`,
      '',
      '| ID | Category | Status | Name | Detail |',
      '|----|----------|--------|------|--------|',
      ...results.map(
        (r) =>
          `| ${r.id} | ${r.category} | ${r.status} | ${r.name} | ${(r.detail || '').replace(/\|/g, '/').slice(0, 120)} |`
      ),
      ''
    ].join('\n');
    fs.writeFileSync(path.join(OUT, 'uat-report.md'), md, 'utf8');
    console.log('\n=== SUMMARY ===');
    console.log(
      JSON.stringify(
        {
          pass: summary.pass,
          fail: summary.fail,
          skip: summary.skip,
          info: summary.info,
          report: reportPath
        },
        null,
        2
      )
    );
    await browser.close();
    process.exit(summary.fail > 0 ? 1 : 0);
  }
}

main();
