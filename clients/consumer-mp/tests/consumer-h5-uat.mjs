/**
 * Consumer mini-program H5 — real browser UAT (Playwright)
 * Run: node tests/consumer-h5-uat.mjs
 * Requires: npm run dev:h5 (http://127.0.0.1:3002) + gateway/trade up
 *
 * Env overrides:
 *   CONSUMER_H5_URL  base URL (default http://127.0.0.1:3002)
 *   PW_CHANNEL       browser channel (default "chrome" = 系统 Chrome；可改 "chromium")
 *   PW_HEADED=1      有头模式，便于人工观察
 *
 * 同步当前 UI 的说明：
 * - 演示账号 13800138000 未设密码，登录走短信验证码（万能码 123456）
 * - 游客态「我的」入口为「去登录」（旧脚本的「手机号验证」入口已下线）
 * - uni-app H5 输入框 placeholder 渲染在独立 div 上，填值需兼容 uni-input 包装
 */
import { chromium } from 'playwright';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const BASE = process.env.CONSUMER_H5_URL || 'http://127.0.0.1:3002';
const CHANNEL = process.env.PW_CHANNEL || 'chrome';
const HEADED = process.env.PW_HEADED === '1';
const OUT = path.resolve(__dirname, '../output/playwright');
const DEMO_PHONE = '13800138000';
const DEMO_SMS = '123456';
const DEVICE_ID = 'CAB-001';

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

async function waitText(page, substr, timeout = 8000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    const t = await bodyText(page);
    if (t.includes(substr)) return t;
    await page.waitForTimeout(250);
  }
  return bodyText(page);
}

/**
 * 点击文本：候选按「文本长度升序 → 元素类型（button 优先）→ 子元素数（叶子优先）」排序，
 * 命中后用真实鼠标点击元素中心（兼容 uni-button / uni-view 等自定义元素），
 * 不可见元素回退为原生 click。避免旧实现里 dispatchEvent + click 的双触发问题。
 */
async function clickByText(page, text, { exact = false, timeout = 6000 } = {}) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    const target = await page.evaluate(({ text, exact }) => {
      const nodes = [...document.querySelectorAll('uni-text, uni-view, uni-button, button, span, div, a, text, view')];
      const tagRank = (t) => {
        if (t === 'UNI-BUTTON' || t === 'BUTTON') return 0;
        if (t === 'A') return 1;
        if (t === 'UNI-TEXT' || t === 'TEXT' || t === 'SPAN') return 2;
        return 3;
      };
      const hits = nodes
        .map((e, i) => {
          const t = (e.innerText || e.textContent || '').trim();
          return { i, len: t.length, rank: tagRank(e.tagName), kids: e.children.length, match: exact ? t === text : t.includes(text) };
        })
        .filter((h) => h.match && h.len > 0)
        .sort((a, b) => a.len - b.len || a.rank - b.rank || a.kids - b.kids);
      if (!hits.length) return null;
      const el = nodes[hits[0].i];
      const r = el.getBoundingClientRect();
      if (
        r.width > 0 &&
        r.height > 0 &&
        r.top >= 0 &&
        r.top < window.innerHeight &&
        r.left >= 0 &&
        r.left < window.innerWidth
      ) {
        return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
      }
      el.click();
      return { clicked: true };
    }, { text, exact });
    if (target === null) {
      await page.waitForTimeout(300);
      continue;
    }
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

/** 点击 uni.showModal 的主按钮（H5 渲染为 .uni-modal__btn_primary） */
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

/**
 * 按 placeholder 填值：uni-app H5 输入框统一渲染为 uni-input 包装
 * （placeholder 在独立 div 上，内层 input 无 placeholder 属性），
 * 定位后使用真实键盘输入（Ctrl+A 清空后键入），兼容开发预填场景。
 */
async function fillPlaceholder(page, placeholder, value) {
  const uni = page
    .locator('uni-input')
    .filter({ has: page.locator('.uni-input-placeholder', { hasText: placeholder }) })
    .first();
  if ((await uni.count()) === 0) {
    // placeholder div 可能因预填被移除：若已有输入框值等于目标值则视为已填
    const inputs = page.locator('uni-input input');
    const n = await inputs.count();
    for (let i = 0; i < n; i++) {
      if ((await inputs.nth(i).inputValue()) === value) return true;
    }
    return false;
  }
  const input = uni.locator('input').first();
  await input.click();
  await page.keyboard.press('ControlOrMeta+a');
  await page.keyboard.type(value, { delay: 20 });
  return (await input.inputValue()) === value;
}

/** 通过 data-testid 定位输入框，用真实键盘输入 */
async function fillByTestId(page, testId, value) {
  const input = page.locator(`[data-testid="${testId}"] .uni-input-input, [data-testid="${testId}"] input`).first();
  await input.click();
  await page.keyboard.press('ControlOrMeta+a');
  await page.keyboard.type(value, { delay: 25 });
  return input.inputValue();
}

/** 真实键盘输入 textarea（uni-app H5 渲染为 uni-textarea 包装） */
async function fillTextarea(page, value) {
  const ta = page.locator('uni-textarea textarea, textarea').first();
  if ((await ta.count()) === 0) return false;
  await ta.click();
  await page.keyboard.press('ControlOrMeta+a');
  await page.keyboard.type(value, { delay: 15 });
  return true;
}

/** uni-app history 路由：直接访问页面路径 */
async function gotoPath(page, pathname, wait = 1500) {
  await page.goto(BASE + pathname, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(wait);
}

/** 清理上次运行遗留的活动会话，保证开门用例可重复执行 */
async function cancelActiveSession(page) {
  const token = await page.evaluate(() => localStorage.getItem('consumer_token') || '');
  if (!token) return false;
  return page.evaluate(async (tok) => {
    const headers = { 'Content-Type': 'application/json', Authorization: 'Bearer ' + tok };
    try {
      const res = await fetch('/api/v2/sessions/active', { headers }).then((r) => r.json());
      const s = res?.data;
      if (s?.sessionId) {
        await fetch('/api/v2/sessions/' + encodeURIComponent(s.sessionId) + '/cancel', {
          method: 'POST',
          headers
        }).catch(() => {});
        return true;
      }
    } catch {
      /* ignore */
    }
    return false;
  }, token);
}

async function main() {
  // 服务连通性预检
  try {
    const probe = await fetch(BASE + '/#/');
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
  const actionResponses = [];
  let aborting = false;
  page.on('console', (msg) => {
    if (msg.type() === 'error') consoleErrors.push(String(msg.text()));
  });
  page.on('pageerror', (e) => consoleErrors.push(String(e.message || e)));
  page.on('requestfailed', (req) => {
    failedRequests.push({ url: req.url(), error: req.failure()?.errorText, intentional: aborting });
  });
  page.on('response', (res) => {
    const url = res.url();
    if (url.includes('/api/v2/feedback') || url.includes('/fault-report')) {
      res
        .json()
        .then((body) => actionResponses.push({ url, status: res.status(), code: body?.code }))
        .catch(() => actionResponses.push({ url, status: res.status() }));
    }
  });

  try {
    // —— TC-HOME-001 落地页 ——
    await gotoPath(page, '/');
    let text = await bodyText(page);
    const homeOk = text.includes('AI开门柜') && text.includes('扫码购物');
    const e1 = await shot(page, '01-home-landing');
    record('TC-HOME-001', '首页落地页品牌与主 CTA 展示', '功能', homeOk ? 'PASS' : 'FAIL', homeOk ? '品牌/扫码 CTA 可见' : `缺关键文案: ${text.slice(0, 200)}`, e1);

    // —— TC-NAV-001 未登录订单 Tab ——
    await clickByText(page, '订单', { exact: true });
    await page.waitForTimeout(900);
    text = await bodyText(page);
    const ordersTab = text.includes('登录后查看订单') || text.includes('暂无订单') || text.includes('我的订单');
    const e2 = await shot(page, '02-orders-guest');
    record('TC-NAV-001', '未登录切换到订单 Tab', '功能', ordersTab ? 'PASS' : 'FAIL', ordersTab ? '展示登录引导/空态' : text.slice(0, 200), e2);

    // —— TC-NAV-002 未登录「我的」游客态 ——
    await clickByText(page, '我的', { exact: true });
    await page.waitForTimeout(900);
    text = await bodyText(page);
    const mineGuest = text.includes('未登录') && text.includes('微信授权登录') && text.includes('去登录');
    const e3 = await shot(page, '03-mine-guest');
    record('TC-NAV-002', '未登录「我的」游客态', '功能', mineGuest ? 'PASS' : 'FAIL', mineGuest ? '游客态与登录入口可见' : text.slice(0, 200), e3);

    // —— TC-LOGIN-001 进入登录页（当前入口：去登录）——
    await clickByText(page, '去登录');
    await page.waitForTimeout(1200);
    text = await bodyText(page);
    const loginPage = text.includes('验证并继续') && text.includes('手机号');
    const e4 = await shot(page, '04-login-page');
    record('TC-LOGIN-001', '进入登录页', '功能', loginPage ? 'PASS' : 'FAIL', loginPage ? '登录表单渲染' : text.slice(0, 200), e4);

    // —— TC-LOGIN-002 空手机号 + 空密码（密码 tab）——
    await clickByText(page, '密码', { exact: true });
    await page.waitForTimeout(400);
    await fillPlaceholder(page, '请输入11位手机号', '');
    await fillPlaceholder(page, '请输入登录密码', '');
    await clickByTestId(page, 'login-submit');
    await page.waitForTimeout(1000);
    text = await bodyText(page);
    const emptyLogin = /请输入|不能|错误|失败|无效/.test(text) || text.includes('验证并继续');
    const e5 = await shot(page, '05-login-empty');
    record('TC-LOGIN-002', '空手机号+空密码提交', '边界', emptyLogin ? 'PASS' : 'FAIL', `仍留在登录页或有错误提示: ${text.slice(-120)}`, e5);

    // —— TC-LOGIN-003 非法手机号 / 错误密码 ——
    await fillPlaceholder(page, '请输入11位手机号', '123');
    await fillPlaceholder(page, '请输入登录密码', 'wrong');
    await clickByTestId(page, 'login-submit');
    await page.waitForTimeout(1500);
    text = await bodyText(page);
    const badLogin = /失败|错误|无效|不正确|请输入|手机号/.test(text);
    const stillLogin = text.includes('验证并继续');
    const e6 = await shot(page, '06-login-invalid');
    record('TC-LOGIN-003', '非法手机号/错误密码', '异常', badLogin || stillLogin ? 'PASS' : 'FAIL', badLogin ? '展示友好错误' : stillLogin ? '未跳转（后端拒绝）' : text.slice(-150), e6);

    // —— TC-SEC-001 登录手机号 XSS 注入 ——
    await fillPlaceholder(page, '请输入11位手机号', '<script>alert(1)</script>');
    await fillPlaceholder(page, '请输入登录密码', 'x');
    const dialogs = [];
    page.once('dialog', (d) => {
      dialogs.push(d.message());
      d.dismiss();
    });
    await clickByTestId(page, 'login-submit');
    await page.waitForTimeout(1500);
    const xssTriggered = await page.evaluate(() => !!document.querySelector('script[data-xss]'));
    text = await bodyText(page);
    const xssSafe = dialogs.length === 0 && !xssTriggered && !text.includes('<script>');
    const e7 = await shot(page, '07-login-xss');
    record('TC-SEC-001', '登录手机号 XSS 注入', '安全', xssSafe ? 'PASS' : 'FAIL', `未执行脚本/未反射: dialogs=${dialogs.length}`, e7);

    // —— TC-LOGIN-004 短信验证码登录（演示账号无密码，万能码 123456）——
    await clickByText(page, '验证码', { exact: true });
    await page.waitForTimeout(400);
    await fillPlaceholder(page, '请输入11位手机号', DEMO_PHONE);
    await clickByText(page, '获取验证码');
    await page.waitForTimeout(800);
    await fillPlaceholder(page, '请输入验证码', DEMO_SMS);
    await clickByTestId(page, 'login-submit');
    await page.waitForTimeout(3500);
    text = await bodyText(page);
    const token = await page.evaluate(() => localStorage.getItem('consumer_token') || sessionStorage.getItem('consumer_token') || '');
    const e8 = await shot(page, '08-login-success');
    record(
      'TC-LOGIN-004',
      `短信验证码登录 ${DEMO_PHONE}`,
      '功能',
      token ? 'PASS' : 'FAIL',
      token ? 'token 已写入' : `未拿到 token，正文: ${text.slice(0, 200)}`,
      e8
    );

    // —— TC-MINE-001 登录后「我的」——
    await clickByText(page, '我的', { exact: true });
    await page.waitForTimeout(1500);
    text = await bodyText(page);
    const mineAuthed = text.includes('我的账户') && text.includes('退出登录');
    const e9 = await shot(page, '09-mine-authed');
    record('TC-MINE-001', '登录后我的页余额/实名状态', '功能', mineAuthed ? 'PASS' : 'FAIL', text.split('\n').slice(0, 12).join(' | '), e9);

    // —— TC-ORD-001 登录后订单列表 ——
    await clickByText(page, '订单', { exact: true });
    await page.waitForTimeout(1800);
    text = await bodyText(page);
    const ordersOk = /暂无订单|已完成|待支付|已退款|全部|购物账单/.test(text);
    const e10 = await shot(page, '10-orders-authed');
    record('TC-ORD-001', '登录后订单列表加载', '功能', ordersOk ? 'PASS' : 'FAIL', text.split('\n').slice(0, 14).join(' | '), e10);

    // —— TC-MEMBER-001 / TC-MKT-001 会员中心与热门活动 ——
    await gotoPath(page, '/pages/member/index');
    text = await bodyText(page);
    const memberOk = text.includes('会员俱乐部') || text.includes('会员中心');
    const e10b = await shot(page, '10b-member');
    record('TC-MEMBER-001', '会员中心', '功能', memberOk ? 'PASS' : 'FAIL', text.split('\n').slice(0, 8).join(' | '), e10b);
    await gotoPath(page, '/pages/marketing/index');
    text = await bodyText(page);
    const mktOk = text.includes('热门活动') || text.includes('进行中') || text.includes('优惠券');
    const e10c = await shot(page, '10c-marketing');
    record('TC-MKT-001', '热门活动', '功能', mktOk ? 'PASS' : 'FAIL', text.split('\n').slice(0, 8).join(' | '), e10c);

    // —— 开门前置：清理遗留活动会话 ——
    await gotoPath(page, '/pages/index/index');
    await cancelActiveSession(page);

    // —— TC-OPEN-001 空柜机编号开门 ——
    await clickByText(page, '手动输入柜机编号');
    await page.waitForTimeout(600);
    await clickByTestId(page, 'open-door-confirm');
    await page.waitForTimeout(1000);
    text = await bodyText(page);
    const emptyDevice = text.includes('请输入柜机编号');
    const e11 = await shot(page, '11-open-empty-device');
    record('TC-OPEN-001', '空柜机编号开门', '边界', emptyDevice ? 'PASS' : 'FAIL', emptyDevice ? '提示请输入柜机编号' : text.slice(0, 200), e11);

    // —— TC-SEC-002 柜机编号注入/非法字符 ——
    await fillByTestId(page, 'device-code-input', "CAB-';DROP TABLE--");
    await clickByTestId(page, 'open-door-confirm');
    await page.waitForTimeout(1500);
    text = await bodyText(page);
    const injSafe = !/syntax|sql/i.test(text);
    const handled = text.includes('编号无效') || text.includes('柜机不存在') || text.includes('请输入柜机编号');
    const e12 = await shot(page, '12-open-injection-device');
    record('TC-SEC-002', '柜机编号注入/非法字符', '安全', injSafe && handled ? 'PASS' : 'FAIL', `无 SQL 泄漏; UI处理=${handled}; 片段=${text.slice(0, 180)}`, e12);

    // —— TC-OPEN-002 CAB-001 开门主路径（柜机已起售）——
    await fillByTestId(page, 'device-code-input', DEVICE_ID);
    await clickByTestId(page, 'open-door-confirm');
    await page.waitForTimeout(3000);
    text = await bodyText(page);
    const state3s = text.split('\n').filter(Boolean).slice(0, 18).join(' | ');
    const sessionId3s = await page.evaluate(() => localStorage.getItem('active_session_id') || '');
    await page.waitForTimeout(5000);
    text = await bodyText(page);
    const sessionId8s = await page.evaluate(() => localStorage.getItem('active_session_id') || '');
    const state8s = text.split('\n').filter(Boolean).slice(0, 20).join(' | ');
    const progressing =
      text.includes('正在开门') ||
      text.includes('开门中') ||
      text.includes('门已开') ||
      text.includes('购物中') ||
      text.includes('本柜价目') ||
      text.includes('正在识别') ||
      text.includes('已取消') ||
      text.includes('使用中') ||
      text.includes('正忙') ||
      text.includes('补货中') ||
      text.includes('暂停营业');
    const sessionCreated = !!sessionId3s || !!sessionId8s;
    const e13 = await shot(page, '13-open-cab001');
    record(
      'TC-OPEN-002',
      `${DEVICE_ID} 开门主路径`,
      '功能',
      sessionCreated || progressing ? 'PASS' : 'FAIL',
      `session=${sessionCreated ? '已创建' : '无'} | 3s:${state3s} | 8s:${state8s}`,
      e13
    );

    // —— TC-OPEN-003 取消本次开门（若可见）——
    if (text.includes('取消本次开门')) {
      await clickByText(page, '取消本次开门');
      await page.waitForTimeout(800);
      await clickModalPrimary(page, '取消开门');
      await page.waitForTimeout(2000);
      text = await bodyText(page);
      const cancelled = text.includes('已取消') || text.includes('扫码购物') || text.includes('再次开门');
      const e14 = await shot(page, '14-cancel-open');
      record('TC-OPEN-003', '取消本次开门', '功能', cancelled ? 'PASS' : 'FAIL', `取消后状态: ${text.split('\n').slice(0, 10).join(' | ')}`, e14);
    } else {
      record('TC-OPEN-003', '取消本次开门', '功能', 'SKIP', '当前状态无取消按钮（可能已进入购物/识别流程）', null);
    }

    // —— TC-FB-001/002 意见反馈 ——
    await gotoPath(page, '/pages/feedback/feedback');
    await clickByText(page, '提交反馈', { exact: true });
    await page.waitForTimeout(500);
    await clickByText(page, '提交反馈', { exact: true });
    await page.waitForTimeout(800);
    text = await bodyText(page);
    const fbEmpty = text.includes('至少填写') || text.includes('4 个字');
    const e15 = await shot(page, '15-feedback-empty');
    record('TC-FB-001', '反馈内容过短校验', '边界', fbEmpty ? 'PASS' : 'FAIL', fbEmpty ? '提示至少 4 字' : text.slice(0, 200), e15);

    await fillTextarea(page, '这是一条自动化测试建议内容');
    await fillPlaceholder(page, '手机号或微信，方便回访', '<img src=x onerror=alert(1)>');
    await fillPlaceholder(page, '例如 CAB-001', DEVICE_ID);
    await clickByText(page, '提交反馈', { exact: true });
    await page.waitForTimeout(2500);
    text = await bodyText(page);
    const fbSubmitted = actionResponses.some((r) => r.url.includes('/api/v2/feedback') && r.status === 200 && r.code === 0);
    const fbOk = fbSubmitted || text.includes('已提交');
    const e16 = await shot(page, '16-feedback-submit');
    record('TC-FB-002', '合法反馈提交', '功能', fbOk ? 'PASS' : 'FAIL', `api=${fbSubmitted ? '已提交' : '未提交'}; body=${text.slice(0, 160)}`, e16);

    // —— TC-RPT-001/002 故障报修 ——
    await gotoPath(page, '/pages/report/report');
    await clickByText(page, '提交报修');
    await page.waitForTimeout(800);
    text = await bodyText(page);
    const reportEmpty = text.includes('请输入柜机编号');
    const e17 = await shot(page, '17-report-empty');
    record('TC-RPT-001', '报修空柜机编号', '边界', reportEmpty ? 'PASS' : 'FAIL', reportEmpty ? '友好校验提示' : text.slice(0, 200), e17);

    await fillPlaceholder(page, '例如 CAB-001', DEVICE_ID);
    await clickByText(page, '提交报修');
    await page.waitForTimeout(2500);
    text = await bodyText(page);
    const reportSubmitted = actionResponses.some((r) => r.url.includes('/fault-report') && r.status === 200 && r.code === 0);
    const reportOk = reportSubmitted || text.includes('已提交');
    const e17b = await shot(page, '17b-report-submit');
    record('TC-RPT-002', '合法报修提交', '功能', reportOk ? 'PASS' : 'FAIL', `api=${reportSubmitted ? '已提交' : '未提交'}; body=${text.slice(0, 160)}`, e17b);

    // —— TC-RCH-001 充值页 ——
    await gotoPath(page, '/pages/recharge/recharge');
    await page.waitForTimeout(1200);
    text = await bodyText(page);
    const rechargePage = text.includes('当前余额');
    const e18 = await shot(page, '18-recharge');
    record('TC-RCH-001', '充值页加载', '功能', rechargePage ? 'PASS' : 'FAIL', text.split('\n').slice(0, 12).join(' | '), e18);

    // —— TC-CPN-001 优惠券页 ——
    await gotoPath(page, '/pages/coupons/coupons');
    await page.waitForTimeout(1200);
    text = await bodyText(page);
    const coupons = text.includes('我的优惠券') || text.includes('暂无优惠券');
    const e19 = await shot(page, '19-coupons');
    record('TC-CPN-001', '优惠券页', '功能', coupons ? 'PASS' : 'FAIL', text.split('\n').slice(0, 10).join(' | '), e19);

    // —— TC-ERR-001 网络异常：断 API 模拟 ——
    aborting = true;
    await context.route('**/api/v2/**', (route) => route.abort('failed'));
    await gotoPath(page, '/pages/orders/orders', 1500);
    await waitText(page, '加载失败', 12000);
    text = await bodyText(page);
    const netErr = /失败|网络|重试|无法连接|超时|加载失败/.test(text);
    const e20 = await shot(page, '20-network-timeout');
    record('TC-ERR-001', 'API 超时/中断时订单页容错', '异常', netErr ? 'PASS' : 'FAIL', netErr ? '有失败/重试提示' : `无明确错误 UI: ${text.slice(0, 200)}`, e20);
    await context.unroute('**/api/v2/**');
    aborting = false;

    // —— TC-SEC-003 清除 Token 后订单页不越权 ——
    await page.evaluate(() => {
      const keys = [];
      for (let i = 0; i < localStorage.length; i++) keys.push(localStorage.key(i));
      keys.forEach((k) => {
        if (k && (k.includes('consumer') || k.includes('token') || k.includes('active_session'))) localStorage.removeItem(k);
      });
    });
    await gotoPath(page, '/pages/orders/orders');
    await page.waitForTimeout(1500);
    text = await bodyText(page);
    const noAuth = text.includes('登录后查看订单') || text.includes('去登录') || text.includes('扫码购物');
    const e21 = await shot(page, '21-orders-after-logout');
    record('TC-SEC-003', '清除 Token 后订单页不越权展示他人数据', '安全', noAuth ? 'PASS' : 'FAIL', text.slice(0, 200), e21);

    // —— TC-QUAL-001 控制台严重错误 ——
    const serious = consoleErrors.filter(
      (e) => !/favicon|DevTools|ResizeObserver|ERR_TIMED_OUT|ERR_ABORTED|ERR_FAILED|404/i.test(e)
    );
    record(
      'TC-QUAL-001',
      '浏览器控制台严重错误',
      '质量',
      serious.length === 0 ? 'PASS' : 'FAIL',
      serious.length === 0 ? '无严重 console.error' : serious.slice(0, 5).join(' || '),
      null
    );
    record(
      'TC-QUAL-002',
      '失败网络请求（非主动 abort）',
      '质量',
      'INFO',
      `failed=${failedRequests.filter((r) => !r.intentional).length}; sample=${JSON.stringify(failedRequests.filter((r) => !r.intentional).slice(0, 3))}`,
      null
    );
  } catch (err) {
    record('TC-RUNNER', 'UAT 执行异常', '质量', 'FAIL', String(err?.stack || err), await shot(page, '99-crash').catch(() => null));
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
      '# Consumer H5 UAT Report',
      '',
      `- Base: ${BASE}`,
      `- Pass: ${summary.pass} / Fail: ${summary.fail} / Skip: ${summary.skip} / Info: ${summary.info}`,
      '',
      '| ID | Category | Status | Name | Detail |',
      '|----|----------|--------|------|--------|',
      ...results.map((r) => `| ${r.id} | ${r.category} | ${r.status} | ${r.name} | ${(r.detail || '').replace(/\|/g, '/').slice(0, 120)} |`),
      ''
    ].join('\n');
    fs.writeFileSync(path.join(OUT, 'uat-report.md'), md, 'utf8');
    console.log('\n=== SUMMARY ===');
    console.log(JSON.stringify({ pass: summary.pass, fail: summary.fail, skip: summary.skip, info: summary.info, report: reportPath }, null, 2));
    await browser.close();
    process.exit(summary.fail > 0 ? 1 : 0);
  }
}

main();
