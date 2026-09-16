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
 * - 发短信前须填图形验证码：拦截 /api/v2/auth/captcha 取 captchaId，再 Redis GET
 * - 游客态「我的」入口为「去登录」（旧脚本的「手机号验证」入口已下线）
 * - uni-app H5 输入框 placeholder 渲染在独立 div 上，填值需兼容 uni-input 包装
 */
import { chromium } from 'playwright';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { captchaFromRedis } from '../../../scripts/lib/redis-captcha.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const BASE = process.env.CONSUMER_H5_URL || 'http://127.0.0.1:3002';
const CHANNEL = process.env.PW_CHANNEL || 'chrome';
const HEADED = process.env.PW_HEADED === '1';
/** PW_MUTATE=1 时执行会产生数据的用例（充值到账/申诉/退款/领券），默认跳过 */
const MUTATE = process.env.PW_MUTATE === '1';
/**
 * 已知失败基线（ratchet）：用例级失败数 ≤ 该值不算回归，超出则 exit 1。
 * 目的是让 CI 抓住「整轮崩溃」与「新增失败」；已知缺陷修复后请下调此值。
 */
const UAT_MAX_FAIL = Number(process.env.UAT_MAX_FAIL ?? 0);
const OUT = path.resolve(__dirname, '../output/playwright');
const DEMO_PHONE = '13800138000';
const DEMO_SMS = '123456';
const DEVICE_ID = 'CAB-001';
// 争议工单号不再硬编码：演示库多次重建，写死的 id 已不存在（0 行），
// 会让 TC-IMP-025/025b 长期以「工单不存在」失败并占用 ratchet 基线额度。
// 默认留空 → 由用例内 API 探测（/api/v2/disputes/mine）挑选真实 RESOLVED 工单；探测不到则 SKIP。
const DEMO_DISPUTE_TICKET_BILLED = process.env.DEMO_DISPUTE_TICKET_BILLED || '';
const DEMO_DISPUTE_TICKET_REFUND = process.env.DEMO_DISPUTE_TICKET_REFUND || '';

fs.mkdirSync(OUT, { recursive: true });

const results = [];

function record(id, name, category, status, detail, evidence) {
  // 兼容历史调用传入 boolean；统一成 PASS/FAIL，避免 summary 漏计失败
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
    const target = await page.evaluate(
      ({ text, exact }) => {
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
      },
      { text, exact }
    );
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
 * 注意：部分 uni-app H5 版本在键入时内部 ref 会滞后（DOM 值正确但提交值被截断），
 * 实测键入后需等待 uni-app 消化事件队列（约 500ms）；值已正确时直接跳过输入。
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
  if ((await input.inputValue()) === value) return true;
  // 点击外层 uni-input（部分页面内层 input 高度为 0，点击外层同样能聚焦）
  await uni.click();
  await page.keyboard.press('ControlOrMeta+a');
  await page.keyboard.type(value, { delay: 30 });
  // 等待 uni-app 完成内部 ref 同步，避免提交值被截断（竞态）
  await page.waitForTimeout(500);
  return (await input.inputValue()) === value;
}

/** 图形验证码读取已抽到共享模块 scripts/lib/redis-captcha.mjs（支持 REDIS_HOST 直连）。 */

/** 从 captcha API 响应体取出 captchaId（兼容 ApiResponse 包装） */
function pickCaptchaId(body) {
  if (!body || typeof body !== 'object') return '';
  return body.data?.captchaId || body.captchaId || '';
}

/**
 * 切到短信 Tab → 等图形验证码 → Redis 读码 → 填手机号/图形码 → 取短信 → 登录。
 * 须在切 Tab 前挂上 waitForResponse，否则可能错过首次自动加载。
 */
/**
 * 取一次**成功**的图形验证码响应（含 429 退避）。
 *
 * 根因（W-6 更正）：网关 `infra/gateway/nginx.conf:37-38` 对 `/api/v2/auth/*` 施加
 * `auth_ratelimit`（5r/s + burst 10 + nodelay），登录链路（captcha → sms-code → login）
 * 每条用例都走一遍，累计十几条后 `/auth/captcha` 开始返回 **429**。
 * 旧实现用 `r.ok()` 当判据，429 不满足即静默 12s 超时，抛出的却是
 * 「图形验证码接口未返回」—— 把「被限流」误报成「服务不可用」。
 * 这里显式区分状态码，并只在 429 上做有上限的线性退避（限流是 5r/s，900ms×n 足够跨过窗口）。
 */
async function fetchCaptchaWithRetry(page, { attempts = 6, baseDelayMs = 900 } = {}) {
  for (let i = 0; i < attempts; i++) {
    const waiter = page
      .waitForResponse((r) => /\/api\/v2\/auth\/captcha(?:\?|$)/.test(r.url()), { timeout: 12000 })
      .catch(() => null);

    if (i === 0) {
      const smsTab = page.locator('[data-testid="login-tab-sms"]');
      if ((await smsTab.count()) > 0) {
        await smsTab.first().click();
      } else {
        await clickByText(page, '验证码', { exact: true });
      }
      await page.waitForTimeout(500);
    } else {
      // 点验证码图片本身即重新拉取（login.vue 的 @click="loadCaptcha"）
      await page
        .locator('.btn-captcha')
        .first()
        .click({ timeout: 5000 })
        .catch(() => {});
    }

    const resp = await waiter;
    if (!resp) continue;
    if (resp.status() === 429) {
      const wait = baseDelayMs * (i + 1);
      console.log(`○ [setup] captcha 命中网关限流 429（第 ${i + 1} 次），退避 ${wait}ms 重试`);
      await page.waitForTimeout(wait);
      continue;
    }
    if (resp.ok()) return resp;
  }
  throw new Error(
    `图形验证码连续 ${attempts} 次未取到 2xx（多为网关 auth_ratelimit 429 限流），` +
      '请检查 infra/gateway/nginx.conf 的 /api/v2/auth/ 限流配置'
  );
}

async function loginViaSms(page, phone = DEMO_PHONE, sms = DEMO_SMS) {
  await dismissPrivacyConsent(page);

  const resp = await fetchCaptchaWithRetry(page);

  const body = await resp.json().catch(() => null);
  const captchaId = pickCaptchaId(body);
  if (!captchaId) throw new Error(`captchaId 缺失: ${JSON.stringify(body)?.slice(0, 200)}`);
  const graphicCode = (await captchaFromRedis(captchaId)).toLowerCase();

  await fillPlaceholder(page, '请输入11位手机号', phone);
  const filledCaptcha = await fillPlaceholder(page, '图形验证码', graphicCode);
  if (!filledCaptcha) throw new Error('未能填入图形验证码');

  await clickByText(page, '获取验证码');
  await page.waitForTimeout(900);
  await fillPlaceholder(page, '请输入验证码', sms);
  await clickByTestId(page, 'login-submit');
  await page.waitForTimeout(3500);

  return !!(await page.evaluate(
    () =>
      localStorage.getItem('consumer_token') ||
      sessionStorage.getItem('consumer_token') ||
      localStorage.getItem('consumer_cookie_auth') ||
      ''
  ));
}

async function hasConsumerToken(page) {
  return !!(await page.evaluate(
    () =>
      localStorage.getItem('consumer_token') ||
      sessionStorage.getItem('consumer_token') ||
      localStorage.getItem('consumer_cookie_auth') ||
      ''
  ));
}

/** 中后段用例前保活会话（深链 401 / 静默 bootstrap 可能清掉 token） */
async function ensureLoggedIn(page) {
  if (await hasConsumerToken(page)) return true;
  await gotoPath(page, '/pages/login/login', 1200);
  return loginViaSms(page);
}

async function dismissLandingOverlays(page) {
  const text = await bodyText(page);
  if (text.includes('需要授权')) {
    await clickByText(page, '取消', { exact: true }).catch(() => {});
    await page.waitForTimeout(300);
  }
  // 关闭残留手动输入层，避免挡住后续「手动输入柜机编号」
  if (text.includes('确认并开门')) {
    await page.evaluate(() => {
      const nodes = [...document.querySelectorAll('uni-text, uni-view, span, div')];
      const cancel = nodes.find((e) => (e.innerText || '').trim() === '取消');
      if (cancel) cancel.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    await page.waitForTimeout(300);
  }
}

/** 通过 data-testid 定位输入框；H5 uni-input 键入易截断，需校验并重试或 DOM 直写 */
async function fillByTestId(page, testId, value) {
  const input = page
    .locator(`[data-testid="${testId}"] .uni-input-input, [data-testid="${testId}"] input`)
    .first();
  const wrap = page.locator(`[data-testid="${testId}"]`).first();

  for (let attempt = 0; attempt < 3; attempt++) {
    if ((await input.inputValue()) === value) return true;
    await wrap.click().catch(() => input.click());
    await page.keyboard.press('ControlOrMeta+a');
    await page.keyboard.press('Backspace');
    await page.waitForTimeout(120);
    await page.keyboard.type(value, { delay: 45 });
    await page.waitForTimeout(500);
    if ((await input.inputValue()) === value) return true;
  }

  await page.evaluate(
    ({ tid, val }) => {
      const root = document.querySelector(`[data-testid="${tid}"]`);
      const el = root?.querySelector('.uni-input-input, input');
      if (!el) return;
      el.value = val;
      el.dispatchEvent(new Event('input', { bubbles: true }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
    },
    { tid: testId, val: value }
  );
  await page.waitForTimeout(400);
  return (await input.inputValue()) === value;
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
/**
 * 首屏隐私同意弹窗（[data-testid=privacy-consent-dialog]）是覆盖整页的模态遮罩，
 * 会拦截全部指针事件。不先关掉它，登录表单完全无法点击 —— 旧版脚本正是在
 * 「填手机号」这一步 15s 超时，导致整套 UAT 中断（仅 5/44 条用例有机会执行）。
 */
async function dismissPrivacyConsent(page) {
  const dialog = page.locator('[data-testid="privacy-consent-dialog"]');
  if ((await dialog.count()) === 0) return false;
  if (
    !(await dialog
      .first()
      .isVisible()
      .catch(() => false))
  )
    return false;
  await page
    .evaluate(() => {
      const d = document.querySelector('[data-testid="privacy-consent-dialog"]');
      if (!d) return;
      const btn = [...d.querySelectorAll('uni-button, button, uni-view, uni-text, span, div')].find(
        (e) => (e.innerText || '').trim() === '同意并继续'
      );
      if (btn) btn.click();
    })
    .catch(() => {});
  await page.waitForTimeout(700);
  if (
    !(await dialog
      .first()
      .isVisible()
      .catch(() => false))
  )
    return true;
  // 兜底：直接落同意标记并重载（存储键与 packages/shared-uni/src/privacy-consent.ts 一致）
  await page.evaluate(() => localStorage.setItem('aicabinet_privacy_consent_v1', '1'));
  await page.reload({ waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1200);
  return true;
}

async function gotoPath(page, pathname, wait = 1500) {
  await page.goto(BASE + pathname, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(wait);
  await dismissPrivacyConsent(page);
}

/** 关闭 vision mock 强制人工审核，避免 UAT 开门后秒进争议页 */
async function disableVisionForceNeedReview() {
  const bases = [process.env.VISION_URL, 'http://127.0.0.1:18082', 'http://localhost:18082'].filter(
    Boolean
  );
  const key = process.env.VISION_API_KEY || 'dev-internal-key-change-me';
  for (const base of bases) {
    try {
      const r = await fetch(`${base.replace(/\/$/, '')}/api/v2/vision/debug/force-need-review`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-Internal-Api-Key': key
        },
        body: JSON.stringify({ enabled: false })
      });
      if (r.ok) return true;
    } catch {
      /* try next */
    }
  }
  return false;
}

/** 清理上次运行遗留的活动会话，保证开门用例可重复执行 */
async function cancelActiveSession(page) {
  // 与争议种子探测同理：H5 走 HttpOnly Cookie，localStorage 里**没有 JWT**，
  // `consumer_cookie_auth` 只是值为 '1' 的标记。旧实现把它当 token 拼成
  // `Authorization: Bearer 1`，服务端判为非法令牌 → 401 → 静默 return false，
  // 于是"清理残留会话"这一步**从未真正生效**（TC-QUAL-001 里那条 401 就是它）。
  return page.evaluate(async () => {
    const token =
      localStorage.getItem('consumer_token') || sessionStorage.getItem('consumer_token') || '';
    const headers = token
      ? { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token }
      : { 'Content-Type': 'application/json' };
    try {
      const res = await fetch('/api/v2/sessions/active', { headers, credentials: 'same-origin' });
      if (!res.ok) return false;
      const json = await res.json();
      const s = json?.data;
      if (!s?.sessionId) return false;
      const state = String(s.state || '').toUpperCase();
      const sid = encodeURIComponent(s.sessionId);
      if (state === 'SHOPPING' || state === 'OPENING') {
        await fetch('/api/v2/sessions/' + sid + '/demo-close', {
          method: 'POST',
          headers,
          credentials: 'same-origin',
          body: '{}'
        }).catch(() => {});
      } else {
        await fetch('/api/v2/sessions/' + sid + '/cancel', {
          method: 'POST',
          headers,
          credentials: 'same-origin'
        }).catch(() => {});
      }
      return true;
    } catch {
      /* ignore */
    }
    return false;
  });
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
  const http4xx = [];
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
    if (res.status() >= 400 && !url.includes('example.com'))
      http4xx.push(`${res.status()} ${url.replace(BASE, '')}`);
    const method = res.request().method();
    const interesting =
      (url.includes('/api/v2/feedback') && method === 'POST') ||
      url.includes('/fault-report') ||
      (url.includes('/api/v2/disputes') && method === 'POST') ||
      (url.includes('/refund') && method === 'POST') ||
      (url.includes('/mock-success') && method === 'POST') ||
      (url.includes('/marketing/campaigns/') && url.includes('/claim') && method === 'POST');
    if (interesting) {
      res
        .json()
        .then((body) =>
          actionResponses.push({ url, method, status: res.status(), code: body?.code })
        )
        .catch(() => actionResponses.push({ url, method, status: res.status() }));
    }
  });

  try {
    // —— TC-HOME-001 落地页 ——
    await gotoPath(page, '/');
    let text = await bodyText(page);
    const homeOk = text.includes('AI开门柜') && text.includes('扫码购物');
    const e1 = await shot(page, '01-home-landing');
    record(
      'TC-HOME-001',
      '首页落地页品牌与主 CTA 展示',
      '功能',
      homeOk ? 'PASS' : 'FAIL',
      homeOk ? '品牌/扫码 CTA 可见' : `缺关键文案: ${text.slice(0, 200)}`,
      e1
    );

    // —— TC-NAV-001 未登录订单 Tab ——
    await clickByText(page, '订单', { exact: true });
    await page.waitForTimeout(900);
    text = await bodyText(page);
    const ordersTab =
      text.includes('登录后查看订单') || text.includes('暂无订单') || text.includes('我的订单');
    const e2 = await shot(page, '02-orders-guest');
    record(
      'TC-NAV-001',
      '未登录切换到订单 Tab',
      '功能',
      ordersTab ? 'PASS' : 'FAIL',
      ordersTab ? '展示登录引导/空态' : text.slice(0, 200),
      e2
    );

    // —— TC-NAV-002 未登录「我的」游客态 ——
    await clickByText(page, '我的', { exact: true });
    await page.waitForTimeout(900);
    text = await bodyText(page);
    const mineGuest =
      text.includes('未登录') && text.includes('微信授权登录') && text.includes('去登录');
    const e3 = await shot(page, '03-mine-guest');
    record(
      'TC-NAV-002',
      '未登录「我的」游客态',
      '功能',
      mineGuest ? 'PASS' : 'FAIL',
      mineGuest ? '游客态与登录入口可见' : text.slice(0, 200),
      e3
    );

    // —— TC-LOGIN-001 进入登录页（当前入口：去登录）——
    await clickByText(page, '去登录');
    await page.waitForTimeout(1200);
    text = await bodyText(page);
    const loginPage = text.includes('验证并继续') && text.includes('手机号');
    const e4 = await shot(page, '04-login-page');
    record(
      'TC-LOGIN-001',
      '进入登录页',
      '功能',
      loginPage ? 'PASS' : 'FAIL',
      loginPage ? '登录表单渲染' : text.slice(0, 200),
      e4
    );

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
    record(
      'TC-LOGIN-002',
      '空手机号+空密码提交',
      '边界',
      emptyLogin ? 'PASS' : 'FAIL',
      `仍留在登录页或有错误提示: ${text.slice(-120)}`,
      e5
    );

    // —— TC-LOGIN-003 非法手机号 / 错误密码 ——
    await fillPlaceholder(page, '请输入11位手机号', '123');
    await fillPlaceholder(page, '请输入登录密码', 'wrong');
    await clickByTestId(page, 'login-submit');
    await page.waitForTimeout(1500);
    text = await bodyText(page);
    const badLogin = /失败|错误|无效|不正确|请输入|手机号/.test(text);
    const stillLogin = text.includes('验证并继续');
    const e6 = await shot(page, '06-login-invalid');
    record(
      'TC-LOGIN-003',
      '非法手机号/错误密码',
      '异常',
      badLogin || stillLogin ? 'PASS' : 'FAIL',
      badLogin ? '展示友好错误' : stillLogin ? '未跳转（后端拒绝）' : text.slice(-150),
      e6
    );

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
    record(
      'TC-SEC-001',
      '登录手机号 XSS 注入',
      '安全',
      xssSafe ? 'PASS' : 'FAIL',
      `未执行脚本/未反射: dialogs=${dialogs.length}`,
      e7
    );

    // —— TC-LOGIN-004 短信验证码登录（演示账号无密码，万能码 123456 + 图形验证码）——
    let loginOk = false;
    let loginErr = '';
    try {
      loginOk = await loginViaSms(page);
    } catch (e) {
      loginErr = e instanceof Error ? e.message : String(e);
    }
    text = await bodyText(page);
    const token = await page.evaluate(
      () =>
        localStorage.getItem('consumer_token') ||
        sessionStorage.getItem('consumer_token') ||
        localStorage.getItem('consumer_cookie_auth') ||
        ''
    );
    const e8 = await shot(page, '08-login-success');
    record(
      'TC-LOGIN-004',
      `短信验证码登录 ${DEMO_PHONE}`,
      '功能',
      token || loginOk ? 'PASS' : 'FAIL',
      token || loginOk
        ? '登录态已建立（H5 为 HttpOnly Cookie，本地只有 consumer_cookie_auth 标记，无 JWT）（含图形验证码）'
        : `未拿到 token，err=${loginErr}，正文: ${text.slice(0, 200)}`,
      e8
    );

    // —— TC-MINE-001 登录后「我的」——
    await clickByText(page, '我的', { exact: true });
    await page.waitForTimeout(1500);
    text = await bodyText(page);
    const mineAuthed = text.includes('我的账户') && text.includes('退出登录');
    const e9 = await shot(page, '09-mine-authed');
    record(
      'TC-MINE-001',
      '登录后我的页余额/实名状态',
      '功能',
      mineAuthed ? 'PASS' : 'FAIL',
      text.split('\n').slice(0, 12).join(' | '),
      e9
    );

    // —— TC-ORD-001 登录后订单列表 ——
    await clickByText(page, '订单', { exact: true });
    const ordDeadline = Date.now() + 12000;
    let ordersOk = false;
    while (Date.now() < ordDeadline) {
      await page.waitForTimeout(800);
      text = await bodyText(page);
      ordersOk = /暂无订单|已完成|待支付|已退款|全部|购物账单/.test(text);
      if (ordersOk) break;
      if (text.includes('重试')) {
        await clickByText(page, '重试', { exact: true }).catch(() => {});
      }
    }
    const e10 = await shot(page, '10-orders-authed');
    record(
      'TC-ORD-001',
      '登录后订单列表加载',
      '功能',
      ordersOk ? 'PASS' : 'FAIL',
      text.split('\n').slice(0, 14).join(' | '),
      e10
    );

    // —— TC-PULL-001 下拉刷新 ——
    const pullOk = await page.evaluate(() => {
      try {
        uni.startPullDownRefresh();
        return true;
      } catch {
        return false;
      }
    });
    await page.waitForTimeout(1500);
    text = await bodyText(page);
    const pullRendered = pullOk && /我的订单|订单|登录后查看订单/.test(text);
    const e10p = await shot(page, '10p-pull-refresh');
    record(
      'TC-PULL-001',
      '下拉刷新',
      '功能',
      pullRendered ? 'PASS' : 'FAIL',
      `start=${pullOk} body=${text.split('\n').slice(0, 4).join(' | ')}`,
      e10p
    );

    // —— TC-ORDD-001 订单详情 ——
    // 前置：列表里得先有订单卡片。等列表「落定」（卡片 或 空态 出现，都代表请求已返回），
    // 不再只靠固定 sleep —— CI 上接口冷启动时会踩空，把「还没渲染」误报成「点不开」。
    await page
      .waitForFunction(
        () =>
          !!document.querySelector('.order-card') || document.body.innerText.includes('暂无订单'),
        null,
        { timeout: 15000 }
      )
      .catch(() => {});
    await page.waitForTimeout(300);

    const orderCardCount = await page.evaluate(
      () => document.querySelectorAll('.order-card').length
    );
    text = await bodyText(page);

    if (orderCardCount === 0) {
      // 「0 张卡片」有两种相反含义，必须先分清再下结论：
      //   (a) 环境本来就没有订单 → 前置不满足，记 SKIP（CI 全新种子就是这情况）；
      //   (b) 接口有订单、列表却没渲染 → **真缺陷**，必须 FAIL。
      // 只判 FAIL 会把 (a) 当缺陷；只判 SKIP 会把 (b) 藏起来。故直连接口取一条独立事实。
      const apiOrderCount = await page.evaluate(async () => {
        const token =
          localStorage.getItem('consumer_token') || sessionStorage.getItem('consumer_token');
        const headers = token ? { Authorization: 'Bearer ' + token } : {};
        try {
          const r = await fetch('/api/v2/orders?page=0&size=5', {
            headers,
            credentials: 'same-origin'
          });
          if (!r.ok) return -1;
          const j = await r.json();
          const items = j?.data?.items || j?.data?.content || [];
          return Array.isArray(items) ? items.length : -1;
        } catch {
          return -1;
        }
      });
      const e10aSkip = await shot(page, '10a-order-detail');
      const apiBroken = apiOrderCount > 0;
      record(
        'TC-ORDD-001',
        '订单详情',
        '功能',
        apiBroken ? 'FAIL' : 'SKIP',
        (apiBroken
          ? `接口有 ${apiOrderCount} 条订单但列表渲染 0 张卡片（疑似列表渲染缺陷）— `
          : `前置不满足：环境无订单（接口返回 ${apiOrderCount} 条），无法验证详情页 — `) +
          `列表 0 张卡片；页面文案：${text.split('\n').filter(Boolean).slice(0, 6).join(' | ')}`,
        e10aSkip
      );
    } else {
      const clickedOrder = await page.evaluate(() => {
        const card = document.querySelector('.order-card');
        if (!card) return false;
        card.click();
        return true;
      });
      await page.waitForTimeout(2000);
      text = await bodyText(page);
      const orderDetailOk = clickedOrder && /订单详情|支付信息|商品清单|支付方式/.test(text);
      const e10a = await shot(page, '10a-order-detail');
      record(
        'TC-ORDD-001',
        '订单详情',
        '功能',
        orderDetailOk ? 'PASS' : 'FAIL',
        `cards=${orderCardCount} | ${text.split('\n').slice(0, 12).join(' | ')}`,
        e10a
      );
    }

    // —— TC-VIDEO-001 购物视频播放页（本地 sample，禁止再用 example.com 假地址冒充通过）——
    const demoVideoCandidates = [
      `${BASE}/static/demo-shopping.mp4`,
      `${BASE}/demo-shopping.mp4`,
      'http://127.0.0.1:9000/cabinet-videos/demo/sample-shopping.mp4'
    ];
    let playableVideoUrl = '';
    for (const candidate of demoVideoCandidates) {
      try {
        const head = await fetch(candidate, { method: 'HEAD' });
        const ctype = (head.headers.get('content-type') || '').toLowerCase();
        if (head.ok && ctype.includes('video')) {
          playableVideoUrl = candidate;
          break;
        }
      } catch {
        /* try next */
      }
    }
    if (!playableVideoUrl) {
      playableVideoUrl = demoVideoCandidates[0];
    }
    await gotoPath(page, '/pages/video/video?url=' + encodeURIComponent(playableVideoUrl));
    await page.waitForTimeout(2500);
    text = await bodyText(page);
    const videoState = await page.evaluate(() => {
      const v = document.querySelector('video');
      if (!v) {
        return {
          hasVideo: false,
          readyState: 0,
          errCode: null,
          failedUi: /视频加载失败|缺少视频地址/.test(document.body?.innerText || '')
        };
      }
      return {
        hasVideo: true,
        readyState: v.readyState,
        errCode: v.error ? v.error.code : null,
        networkState: v.networkState,
        currentSrc: v.currentSrc || v.src || '',
        failedUi: /视频加载失败/.test(document.body?.innerText || '')
      };
    });
    // readyState >= 2 (HAVE_CURRENT_DATA) 视为可播放；无 error 且未展示失败态
    const videoOk =
      videoState.hasVideo &&
      !videoState.failedUi &&
      videoState.errCode == null &&
      Number(videoState.readyState) >= 2;
    const e10v = await shot(page, '10v-video-page');
    record(
      'TC-VIDEO-001',
      '购物视频播放页',
      '功能',
      videoOk ? 'PASS' : 'FAIL',
      videoOk
        ? `可播放 url=${playableVideoUrl} readyState=${videoState.readyState}`
        : `不可播放 url=${playableVideoUrl} state=${JSON.stringify(videoState)} body=${text
            .split('\n')
            .slice(0, 6)
            .join(' | ')}`,
      e10v
    );

    // —— TC-IMP-025 已结案争议文案（扣款/退款渠道）——
    // 单号不再硬编码：旧常量 1788252219672817302 / 1788247248295553600 在演示库里**均 0 行命中**
    // （`select count(*) from dispute_ticket where ticket_id=...`），页面直接落到「争议工单不存在」，
    // 用例恒红、白占 UAT_MAX_FAIL 基线额度。改为从「我的争议」接口探测真实可用的种子：
    //   025  需要一条 RESOLVED/CLOSED 且**已扣款**（billedAmountCents>0）的工单；
    //   025b 需要一条 RESOLVED/CLOSED 且**已退款**（refundedAmountCents>0）的工单。
    // 当前演示库**没有已退款结案的工单**（三条结案工单 refundedAmountCents 均为 null，
    // 且前端 `shouldShowConsumerRefundChannel` 要求 refunded>0 才渲染「退款渠道」行），
    // 因此 025b 记 SKIP 并写明所缺种子 —— SKIP 会如实暴露覆盖缺口，比一条永远不可能 PASS 的
    // 假红更诚实（假红会把基线额度吃掉，让真实回归静默通过）。
    await cancelActiveSession(page);
    const disputeSeeds = await page.evaluate(async () => {
      // 消费者 H5 默认走 **HttpOnly Cookie 鉴权**（见 src/utils/consumer-api.ts:27-28：
      // `consumer_cookie_auth` 只是"服务端已写 Cookie"的本地标记，**JWT 不落 localStorage**）。
      // 因此不能无条件塞 `Authorization: Bearer <空串>` —— 空 Bearer 会被服务端判为
      // 无效令牌并**短路掉 Cookie 鉴权** → 401。那样会把"探测失败"伪装成"库里没种子"，
      // 又是一次静默假绿。只在真有 token（小程序端/显式注入）时才带该头。
      const token =
        localStorage.getItem('consumer_token') || sessionStorage.getItem('consumer_token') || '';
      const headers = token ? { Authorization: 'Bearer ' + token } : {};
      try {
        const res = await fetch('/api/v2/disputes/mine', { headers, credentials: 'same-origin' });
        if (!res.ok) {
          return {
            billedId: '',
            refundedId: '',
            resolvedCount: -1,
            probeError: 'HTTP ' + res.status
          };
        }
        const json = await res.json();
        const items = Array.isArray(json?.data) ? json.data : json?.data?.items || [];
        const done = items.filter((t) =>
          /^(RESOLVED|CLOSED)$/.test(String(t.status || '').toUpperCase())
        );
        const billed = done.find((t) => Number(t.billedAmountCents ?? 0) > 0);
        const refunded = done.find((t) => Number(t.refundedAmountCents ?? 0) > 0);
        return {
          billedId: billed ? String(billed.ticketId) : '',
          refundedId: refunded ? String(refunded.ticketId) : '',
          resolvedCount: done.length,
          probeError: ''
        };
      } catch (e) {
        return {
          billedId: '',
          refundedId: '',
          resolvedCount: -1,
          probeError: String((e && e.message) || e)
        };
      }
    });
    const billedTicketId = DEMO_DISPUTE_TICKET_BILLED || disputeSeeds.billedId;
    const refundTicketId = DEMO_DISPUTE_TICKET_REFUND || disputeSeeds.refundedId;

    if (billedTicketId) {
      await gotoPath(page, `/pages/dispute/detail?ticketId=${encodeURIComponent(billedTicketId)}`);
      await page.waitForTimeout(3000);
      text = await bodyText(page);
      const imp25BilledOk =
        /人工审核已完成|已结案/.test(text) &&
        /扣款/.test(text) &&
        !/暂未扣款/.test(text) &&
        !text.includes('退款渠道');
      const e25a = await shot(page, '25-dispute-resolved-billed');
      record(
        'TC-IMP-025',
        '已结案扣款争议文案',
        'UX',
        imp25BilledOk ? 'PASS' : 'FAIL',
        imp25BilledOk
          ? `无 OPEN 态暂未扣款/退款渠道（ticket=${billedTicketId}）`
          : text.split('\n').filter(Boolean).slice(0, 12).join(' | '),
        e25a
      );
    } else {
      // 探测失败 ≠ 没种子。两者必须分开记，否则鉴权坏了也会显示成"演示库无种子"。
      record(
        'TC-IMP-025',
        '已结案扣款争议文案',
        'UX',
        disputeSeeds.probeError ? 'FAIL' : 'SKIP',
        disputeSeeds.probeError
          ? `争议种子探测失败（${disputeSeeds.probeError}）—— 不能据此判定"库里无已扣款工单"`
          : `演示库无「已结案且已扣款」的争议种子（结案工单共 ${disputeSeeds.resolvedCount} 条，billed>0 的 0 条）`,
        null
      );
    }

    if (refundTicketId) {
      await gotoPath(page, `/pages/dispute/detail?ticketId=${encodeURIComponent(refundTicketId)}`);
      await page.waitForTimeout(3000);
      text = await bodyText(page);
      const imp25RefundOk =
        /已结案|人工审核已完成/.test(text) &&
        /退款|未扣款|已退/.test(text) &&
        text.includes('退款渠道');
      const e25b = await shot(page, '25b-dispute-resolved-refund');
      record(
        'TC-IMP-025b',
        '已结案退款争议文案',
        'UX',
        imp25RefundOk ? 'PASS' : 'FAIL',
        imp25RefundOk
          ? `展示退款结论与退款渠道（ticket=${refundTicketId}）`
          : text.split('\n').filter(Boolean).slice(0, 12).join(' | '),
        e25b
      );
    } else {
      record(
        'TC-IMP-025b',
        '已结案退款争议文案',
        'UX',
        disputeSeeds.probeError ? 'FAIL' : 'SKIP',
        disputeSeeds.probeError
          ? `争议种子探测失败（${disputeSeeds.probeError}）—— 不能据此判定"库里无已退款工单"`
          : '演示库无「已结案且已退款」的争议种子（需 refundedAmountCents>0；' +
              `当前结案工单共 ${disputeSeeds.resolvedCount} 条，全部 refunded=null）` +
              ' → 前端 shouldShowConsumerRefundChannel 为假，「退款渠道」行不会渲染',
        null
      );
    }

    // —— TC-RFND-001 立即退款（PW_MUTATE=1 时执行）——
    if (MUTATE) {
      await gotoPath(page, '/pages/orders/orders');
      await page.evaluate(() => {
        const c = document.querySelector('.order-card');
        if (c) c.click();
      });
      await page.waitForTimeout(2000);
      text = await bodyText(page);
      if (text.includes('立即退款')) {
        await clickByText(page, '立即退款', { exact: true });
        await page.waitForTimeout(600);
        // 面板内「确认退款」提交按钮 → uni.showModal 确认弹窗
        await clickByText(page, '确认退款', { exact: true });
        await page.waitForTimeout(600);
        await clickModalPrimary(page, '确认退款');
        await page.waitForTimeout(2500);
        const refundHit = actionResponses.some(
          (r) =>
            r.url.includes('/refund') &&
            r.method === 'POST' &&
            (r.status === 200 || r.status === 409)
        );
        const e10r = await shot(page, '10r-refund');
        record(
          'TC-RFND-001',
          '立即退款',
          '功能',
          refundHit ? 'PASS' : 'FAIL',
          `api=${refundHit}`,
          e10r
        );
      } else {
        record('TC-RFND-001', '立即退款', '功能', 'SKIP', '订单不可退款（状态/策略限制）', null);
      }
    } else {
      record('TC-RFND-001', '立即退款', '功能', 'SKIP', '未开启 PW_MUTATE（避免变更数据）', null);
    }

    // —— TC-DSP-001 账单申诉提交（PW_MUTATE=1 时执行）——
    if (MUTATE) {
      await gotoPath(page, '/pages/orders/orders');
      await page.evaluate(() => {
        const c = document.querySelectorAll('.order-card')[1];
        if (c) c.click();
      });
      await page.waitForTimeout(2000);
      text = await bodyText(page);
      if (text.includes('提交账单申诉') || text.includes('申请退款')) {
        await clickByText(page, '提交账单申诉', { exact: true });
        await page.waitForTimeout(600);
        await fillTextarea(page, '自动化测试：商品数量识别有误，请核对');
        await clickByText(page, '提交申诉', { exact: true });
        await page.waitForTimeout(2500);
        const disputeHit = actionResponses.some(
          (r) =>
            r.url.includes('/api/v2/disputes') &&
            r.method === 'POST' &&
            (r.status === 200 || r.status === 409)
        );
        const e10s = await shot(page, '10s-dispute');
        record(
          'TC-DSP-001',
          '账单申诉提交',
          '功能',
          disputeHit ? 'PASS' : 'FAIL',
          `api=${disputeHit}`,
          e10s
        );
      } else {
        record('TC-DSP-001', '账单申诉提交', '功能', 'SKIP', '订单不可申诉（状态/已有申诉）', null);
      }
    } else {
      record(
        'TC-DSP-001',
        '账单申诉提交',
        '功能',
        'SKIP',
        '未开启 PW_MUTATE（避免变更数据）',
        null
      );
    }

    // —— TC-MEMBER-001 / TC-MKT-001 会员中心与热门活动 ——
    await ensureLoggedIn(page);
    await gotoPath(page, '/pages/member/index');
    text = await bodyText(page);
    const memberOk = text.includes('会员俱乐部') || text.includes('会员中心');
    const e10b = await shot(page, '10b-member');
    record(
      'TC-MEMBER-001',
      '会员中心',
      '功能',
      memberOk ? 'PASS' : 'FAIL',
      text.split('\n').slice(0, 8).join(' | '),
      e10b
    );
    await gotoPath(page, '/pages/marketing/index');
    text = await bodyText(page);
    const mktOk = text.includes('热门活动') || text.includes('进行中') || text.includes('优惠券');
    const e10c = await shot(page, '10c-marketing');
    record(
      'TC-MKT-001',
      '热门活动',
      '功能',
      mktOk ? 'PASS' : 'FAIL',
      text.split('\n').slice(0, 8).join(' | '),
      e10c
    );

    // —— TC-CLM-001 热门活动领券（PW_MUTATE=1 时执行）——
    if (MUTATE) {
      await gotoPath(page, '/pages/marketing/index', 2000);
      const claimClick = await page.evaluate(() => {
        const cards = [...document.querySelectorAll('.campaign')];
        const card = cards.find(
          (c) => /领取/.test(c.innerText || '') && !/已领取/.test(c.innerText || '')
        );
        if (!card) return false;
        card.click();
        return true;
      });
      await page.waitForTimeout(2500);
      const claimHit = actionResponses.some(
        (r) =>
          r.url.includes('/claim') && r.method === 'POST' && (r.status === 200 || r.status === 409)
      );
      const e10cl = await shot(page, '10cl-claim');
      record(
        'TC-CLM-001',
        '热门活动领券',
        '功能',
        !claimClick ? 'SKIP' : claimHit ? 'PASS' : 'FAIL',
        !claimClick ? '当前无未领取活动' : `click=true api=${claimHit}`,
        e10cl
      );
    } else {
      record(
        'TC-CLM-001',
        '热门活动领券',
        '功能',
        'SKIP',
        '未开启 PW_MUTATE（避免重复领券）',
        null
      );
    }

    // —— TC-DEEP-001 深链启动：柜机号参数直达 ——
    await ensureLoggedIn(page);
    await gotoPath(page, '/pages/index/index?deviceId=CAB-999', 2500);
    text = await bodyText(page);
    const deepLinkOk = /柜机不存在|编号无效|不存在/.test(text);
    const e10d = await shot(page, '10d-deep-link');
    record(
      'TC-DEEP-001',
      '深链启动 deviceId=CAB-999',
      '功能',
      deepLinkOk ? 'PASS' : 'FAIL',
      text.split('\n').slice(0, 10).join(' | '),
      e10d
    );

    // —— 开门前置：关 mock 强制审核 + 清理遗留活动会话 ——
    await disableVisionForceNeedReview();
    await ensureLoggedIn(page);
    await gotoPath(page, '/pages/index/index');
    await cancelActiveSession(page);
    await dismissLandingOverlays(page);

    // —— TC-OPEN-001 空柜机编号开门 ——
    await clickByText(page, '手动输入柜机编号');
    await page.waitForTimeout(600);
    await clickByTestId(page, 'open-door-confirm');
    await page.waitForTimeout(1000);
    text = await bodyText(page);
    const emptyDevice = text.includes('请输入柜机编号');
    const e11 = await shot(page, '11-open-empty-device');
    record(
      'TC-OPEN-001',
      '空柜机编号开门',
      '边界',
      emptyDevice ? 'PASS' : 'FAIL',
      emptyDevice ? '提示请输入柜机编号' : text.slice(0, 200),
      e11
    );

    // —— TC-SEC-002 柜机编号注入/非法字符 ——
    await fillByTestId(page, 'device-code-input', "CAB-';DROP TABLE--");
    await clickByTestId(page, 'open-door-confirm');
    await page.waitForTimeout(1500);
    text = await bodyText(page);
    const injSafe = !/syntax|sql/i.test(text);
    const handled =
      text.includes('编号无效') || text.includes('柜机不存在') || text.includes('请输入柜机编号');
    const e12 = await shot(page, '12-open-injection-device');
    record(
      'TC-SEC-002',
      '柜机编号注入/非法字符',
      '安全',
      injSafe && handled ? 'PASS' : 'FAIL',
      `无 SQL 泄漏; UI处理=${handled}; 片段=${text.slice(0, 180)}`,
      e12
    );

    // —— TC-OPEN-002 CAB-001 开门主路径 ——
    // 前置：柜机必须 ONLINE **且** available（未停售 / 无占用会话 / 不在补货）。
    // 演示库现状：`device_info` 三台柜机 `sales_locked` 全为 true（原因「离线超时自动停售」），
    // 因为恢复在线后的自动解锁 `DeviceStableOnlineAutoUnlockService` **默认关闭**
    // （system_config `DEVICE_STABLE_ONLINE_AUTO_UNLOCK_ENABLED` 默认 false，
    //  见 DeviceStableOnlineAutoUnlockService.java:79-87），需人工解锁或开配置。
    // 于是**当前没有一台柜机可以开门**。这种情况必须记 SKIP 并写明原因：
    // 「环境里没有可售柜机」≠「开门功能坏了」。若仍按原样判 FAIL，
    // 就是一条结构上不可能 PASS 的用例长年占着 UAT_MAX_FAIL 基线额度，把真实回归吃掉。
    await ensureLoggedIn(page);
    await dismissLandingOverlays(page);
    await gotoPath(page, '/pages/index/index');
    await cancelActiveSession(page);
    await page.waitForTimeout(500);

    const cabStatusProbe = await page.evaluate(async (deviceId) => {
      try {
        const r = await fetch('/api/v2/devices/' + encodeURIComponent(deviceId) + '/status', {
          credentials: 'same-origin'
        });
        const j = await r.json().catch(() => null);
        return { httpStatus: r.status, data: j?.data || null };
      } catch (e) {
        return { httpStatus: 0, data: null, err: String((e && e.message) || e) };
      }
    }, DEVICE_ID);
    const cabStatus = cabStatusProbe.data || {};
    const cabOnline =
      cabStatus.online === true || String(cabStatus.onlineStatus || '').toUpperCase() === 'ONLINE';
    const cabAvailable = cabStatus.available === true;
    // 开门主路径的前置是「在线 **且** 可售」，只判 available 不够：CI 实测该接口在设备离线时
    // 仍可能返回 available=true，于是用例走进成功分支却只能看到「该柜机当前离线」→ 记 FAIL。
    // 环境没数据/设备离线 ≠ 开门主路径坏了，这种情况必须 SKIP。
    const canOpen = cabOnline && cabAvailable;

    // 无论可不可售，都把「输入编号 → 确认开门」这条 UI 路径走完：
    // 可售走成功分支（TC-OPEN-002），不可售走拒绝分支（TC-OPEN-005）。
    await clickByText(page, '手动输入柜机编号');
    await page.waitForTimeout(600);
    const deviceFilled = await fillByTestId(page, 'device-code-input', DEVICE_ID);
    await clickByTestId(page, 'open-door-confirm');
    await page.waitForTimeout(3000);
    text = await bodyText(page);
    const state3s = text.split('\n').filter(Boolean).slice(0, 18).join(' | ');
    const sessionId3s = await page.evaluate(() => localStorage.getItem('active_session_id') || '');
    const shoppingEarly = /门已开|购物中|本柜价目|正在开门|开门中/.test(text);
    // 已进入购物态则不再多等 5s，避免 mock 识别把会话推进到争议/审核页
    if (canOpen && !shoppingEarly && !sessionId3s) {
      await page.waitForTimeout(5000);
      text = await bodyText(page);
    }
    const sessionId8s = await page.evaluate(() => localStorage.getItem('active_session_id') || '');
    const state8s = text.split('\n').filter(Boolean).slice(0, 20).join(' | ');
    const progressing =
      shoppingEarly ||
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
      canOpen ? (sessionCreated || progressing ? 'PASS' : 'FAIL') : 'SKIP',
      canOpen
        ? `filled=${deviceFilled} session=${sessionCreated ? '已创建' : '无'} | 3s:${state3s} | 8s:${state8s}`
        : `前置不满足：柜机不可开门（online=${cabOnline} available=${cabAvailable}` +
            ` busyReason=${cabStatus.busyReason || 'n/a'}）→ 无法验证开门主路径。` +
            '设备离线或处于停售/占用/补货态时属环境前置不满足；' +
            `filled=${deviceFilled}；实测文案：${text.split('\n').filter(Boolean).slice(0, 8).join(' | ')}`,
      e13
    );

    // —— TC-OPEN-005 不可售/离线柜机的开门拒绝（把「明确拒绝」当成契约来钉）——
    // 这条不依赖环境可售性：柜机没开成时**必须给出明确的业务文案且不创建会话**，
    // 而不是静默失败或白屏。TC-OPEN-002 SKIP 时它正好补上覆盖。
    if (!canOpen) {
      const refuseMsg = /该柜机当前离线|暂停营业|正在补货|正在被使用|柜机不存在/.test(text);
      const e13b = await shot(page, '13b-open-refused');
      record(
        'TC-OPEN-005',
        '不可售柜机开门被明确拒绝',
        'UX',
        refuseMsg && !sessionCreated ? 'PASS' : 'FAIL',
        `refuseMsg=${refuseMsg} sessionCreated=${sessionCreated} | ` +
          text.split('\n').filter(Boolean).slice(0, 8).join(' | '),
        e13b
      );
    } else {
      record(
        'TC-OPEN-005',
        '不可售柜机开门被明确拒绝',
        'UX',
        'SKIP',
        '当前柜机可售，未产生拒绝分支（该分支需一台停售/离线柜机）',
        null
      );
    }

    // —— TC-IMP-024 商品步进器 72rpx 热区（IMP-024）——
    const shoppingForStepper = /门已开|购物中|本柜价目|本柜商品|请点选商品/.test(text);
    if (!sessionCreated || !shoppingForStepper) {
      record(
        'TC-IMP-024',
        '商品步进器 72rpx 热区',
        'UX',
        'SKIP',
        '未进入购物态，无法验收步进器尺寸',
        null
      );
    } else {
      const stepperDeadline = Date.now() + 10000;
      let stepperMetrics = null;
      while (Date.now() < stepperDeadline) {
        stepperMetrics = await page.evaluate(() => {
          const btn = document.querySelector('.stepper-btn');
          if (!btn) return null;
          const r = btn.getBoundingClientRect();
          const expected = (window.innerWidth / 750) * 72;
          return {
            width: Math.round(r.width * 10) / 10,
            height: Math.round(r.height * 10) / 10,
            expected: Math.round(expected * 10) / 10,
            count: document.querySelectorAll('.stepper-btn').length
          };
        });
        if (stepperMetrics) break;
        await page.waitForTimeout(400);
      }
      const e24 = await shot(page, '24-imp-stepper-72');
      if (!stepperMetrics) {
        record(
          'TC-IMP-024',
          '商品步进器 72rpx 热区',
          'UX',
          'SKIP',
          'mockEnabled 关闭或未渲染 .stepper-btn',
          e24
        );
      } else {
        const tol = 3;
        const ok =
          Math.abs(stepperMetrics.width - stepperMetrics.expected) <= tol &&
          Math.abs(stepperMetrics.height - stepperMetrics.expected) <= tol &&
          stepperMetrics.width >= 36;
        record(
          'TC-IMP-024',
          '商品步进器 72rpx 热区',
          'UX',
          ok ? 'PASS' : 'FAIL',
          `measured=${stepperMetrics.width}x${stepperMetrics.height}px expected≈${stepperMetrics.expected}px buttons=${stepperMetrics.count}`,
          e24
        );
      }
    }

    // —— TC-RESTORE-001 会话恢复：刷新后恢复购物态 ——
    if (!sessionCreated) {
      record(
        'TC-RESTORE-001',
        '刷新后会话恢复',
        '功能',
        'SKIP',
        'TC-OPEN-002 未创建会话（柜机停售/开门失败），跳过恢复验证',
        null
      );
    } else {
      await page.reload({ waitUntil: 'domcontentloaded' });
      await page.waitForTimeout(2500);
      const restoreDeadline = Date.now() + 8000;
      while (Date.now() < restoreDeadline) {
        text = await bodyText(page);
        if (/门已开|购物中|本柜价目|正在开门|开门中|本柜商品/.test(text)) break;
        await page.waitForTimeout(300);
      }
      text = await bodyText(page);
      const restoreOk = /门已开|购物中|本柜价目|正在开门|开门中|本柜商品/.test(text);
      const e13b = await shot(page, '13b-session-restore');
      record(
        'TC-RESTORE-001',
        '刷新后会话恢复',
        '功能',
        restoreOk ? 'PASS' : 'FAIL',
        text.split('\n').filter(Boolean).slice(0, 12).join(' | '),
        e13b
      );
    }

    // —— TC-OPEN-003 取消本次开门（若可见）——
    if (text.includes('取消本次开门')) {
      await clickByText(page, '取消本次开门');
      await page.waitForTimeout(800);
      await clickModalPrimary(page, '取消开门');
      await page.waitForTimeout(2000);
      text = await bodyText(page);
      const cancelled =
        text.includes('已取消') || text.includes('扫码购物') || text.includes('再次开门');
      const e14 = await shot(page, '14-cancel-open');
      record(
        'TC-OPEN-003',
        '取消本次开门',
        '功能',
        cancelled ? 'PASS' : 'FAIL',
        `取消后状态: ${text.split('\n').slice(0, 10).join(' | ')}`,
        e14
      );
    } else {
      record(
        'TC-OPEN-003',
        '取消本次开门',
        '功能',
        'SKIP',
        '当前状态无取消按钮（可能已进入购物/识别流程）',
        null
      );
    }
    // 开门用例后清理活动会话，避免柜机占用影响后续/下次运行
    await cancelActiveSession(page);

    // —— TC-FB-001/002 意见反馈 ——
    await ensureLoggedIn(page);
    await gotoPath(page, '/pages/feedback/feedback');
    await clickByText(page, '提交反馈', { exact: true });
    await page.waitForTimeout(500);
    await clickByText(page, '提交反馈', { exact: true });
    await page.waitForTimeout(800);
    text = await bodyText(page);
    const fbEmpty = text.includes('至少填写') || text.includes('4 个字');
    const e15 = await shot(page, '15-feedback-empty');
    record(
      'TC-FB-001',
      '反馈内容过短校验',
      '边界',
      fbEmpty ? 'PASS' : 'FAIL',
      fbEmpty ? '提示至少 4 字' : text.slice(0, 200),
      e15
    );

    await fillTextarea(page, '这是一条自动化测试建议内容');
    await fillPlaceholder(page, '手机号或微信，方便回访', '<img src=x onerror=alert(1)>');
    // 真值来源 clients/consumer-mp/src/pages/feedback/feedback.vue（"柜机编号（选填）"字段）
    // 旧值 '例如 CAB-001' 是 admin 端 SkuVisionEnrollView 的 placeholder，抄错了 →
    // 这一步一直静默空转（选填字段，不报错，也就没人发现 XSS 载荷从未进过编号框）。
    await fillPlaceholder(page, '请输入柜机编号（选填）', DEVICE_ID);
    await clickByText(page, '提交反馈', { exact: true });
    await page.waitForTimeout(2500);
    text = await bodyText(page);
    const fbSubmitted = actionResponses.some(
      (r) => r.url.includes('/api/v2/feedback') && r.status === 200 && r.code === 0
    );
    const fbOk = fbSubmitted || text.includes('已提交');
    const e16 = await shot(page, '16-feedback-submit');
    record(
      'TC-FB-002',
      '合法反馈提交',
      '功能',
      fbOk ? 'PASS' : 'FAIL',
      `api=${fbSubmitted ? '已提交' : '未提交'}; body=${text.slice(0, 160)}`,
      e16
    );

    // —— TC-FB-003 投诉类型入口（意见反馈已覆盖投诉）——
    await gotoPath(page, '/pages/feedback/feedback');
    text = await bodyText(page);
    const complaintOk = text.includes('投诉');
    const e16b = await shot(page, '16b-feedback-complaint');
    record(
      'TC-FB-003',
      '意见反馈含「投诉」类型',
      '功能',
      complaintOk ? 'PASS' : 'FAIL',
      text.split('\n').slice(0, 10).join(' | '),
      e16b
    );

    // —— TC-RPT-001/002 故障报修 ——
    await ensureLoggedIn(page);
    await gotoPath(page, '/pages/report/report');
    await clickByText(page, '提交报修');
    await page.waitForTimeout(800);
    text = await bodyText(page);
    const reportEmpty = text.includes('请输入柜机编号');
    const e17 = await shot(page, '17-report-empty');
    record(
      'TC-RPT-001',
      '报修空柜机编号',
      '边界',
      reportEmpty ? 'PASS' : 'FAIL',
      reportEmpty ? '友好校验提示' : text.slice(0, 200),
      e17
    );

    // 真值来源 clients/consumer-mp/src/pages/report/report.vue
    // 旧值 '例如 CAB-001' 取自 admin 端 SkuVisionEnrollView 的 placeholder（抄错）→
    // 该输入框永远填不进值，TC-RPT-002 结构上不可能 PASS，却常年占基线额度。
    await fillPlaceholder(page, '请输入柜机编号', DEVICE_ID);
    await clickByText(page, '提交报修');
    await page.waitForTimeout(2500);
    text = await bodyText(page);
    const reportSubmitted = actionResponses.some(
      (r) => r.url.includes('/fault-report') && r.status === 200 && r.code === 0
    );
    const reportOk = reportSubmitted || text.includes('已提交');
    const e17b = await shot(page, '17b-report-submit');
    record(
      'TC-RPT-002',
      '合法报修提交',
      '功能',
      reportOk ? 'PASS' : 'FAIL',
      `api=${reportSubmitted ? '已提交' : '未提交'}; body=${text.slice(0, 160)}`,
      e17b
    );

    // —— TC-RCH-001 充值页 ——
    await gotoPath(page, '/pages/recharge/recharge');
    await page.waitForTimeout(1200);
    text = await bodyText(page);
    const rechargePage = text.includes('当前余额');
    const e18 = await shot(page, '18-recharge');
    record(
      'TC-RCH-001',
      '充值页加载',
      '功能',
      rechargePage ? 'PASS' : 'FAIL',
      text.split('\n').slice(0, 12).join(' | '),
      e18
    );

    // —— TC-RCH-002 模拟充值到账（PW_MUTATE=1 时执行）——
    if (MUTATE) {
      const clickedMock = await clickByText(page, '模拟到账 ¥20.00');
      await page.waitForTimeout(2500);
      const mockHit = actionResponses.some(
        (r) => r.url.includes('/mock-success') && r.method === 'POST' && r.status === 200
      );
      const e18b = await shot(page, '18b-recharge-mock');
      record(
        'TC-RCH-002',
        '模拟充值到账',
        '功能',
        clickedMock && mockHit ? 'PASS' : 'FAIL',
        `click=${clickedMock} api=${mockHit}`,
        e18b
      );
    } else {
      record(
        'TC-RCH-002',
        '模拟充值到账',
        '功能',
        'SKIP',
        '未开启 PW_MUTATE（避免变更余额）',
        null
      );
    }

    // —— TC-CPN-001 优惠券页 ——
    await ensureLoggedIn(page);
    await gotoPath(page, '/pages/coupons/coupons');
    await page.waitForTimeout(1200);
    text = await bodyText(page);
    const coupons = text.includes('我的优惠券') || text.includes('暂无优惠券');
    const e19 = await shot(page, '19-coupons');
    record(
      'TC-CPN-001',
      '优惠券页',
      '功能',
      coupons ? 'PASS' : 'FAIL',
      text.split('\n').slice(0, 10).join(' | '),
      e19
    );

    // —— TC-ANNC-001/002 公告列表与详情 ——
    await gotoPath(page, '/pages/announcements/announcements');
    text = await bodyText(page);
    const anncCards = await page.evaluate(() => document.querySelectorAll('.card').length);
    const anncOk = /通知公告|公告/.test(text);
    const e19a = await shot(page, '19a-announcements');
    record(
      'TC-ANNC-001',
      '公告列表',
      '功能',
      anncOk ? 'PASS' : 'FAIL',
      text.split('\n').slice(0, 8).join(' | '),
      e19a
    );
    if (anncCards === 0) {
      record(
        'TC-ANNC-002',
        '公告详情',
        '功能',
        'SKIP',
        '当前无公告数据（空态组件文本不进入 innerText，按卡片数判断）',
        null
      );
    } else {
      const clickedAnn = await page.evaluate(() => {
        const card = document.querySelector('.card');
        if (!card) return false;
        card.click();
        return true;
      });
      await page.waitForTimeout(1800);
      text = await bodyText(page);
      const anncDetailOk = clickedAnn && /公告详情|公告/.test(text);
      const e19b = await shot(page, '19b-announcement-detail');
      record(
        'TC-ANNC-002',
        '公告详情',
        '功能',
        anncDetailOk ? 'PASS' : 'FAIL',
        text.split('\n').slice(0, 8).join(' | '),
        e19b
      );
    }

    // —— TC-HELP-001 帮助中心 ——
    await gotoPath(page, '/pages/help/help');
    text = await bodyText(page);
    const helpOk = /常见问题|联系客服|退款|客服热线/.test(text);
    const e19c = await shot(page, '19c-help');
    record(
      'TC-HELP-001',
      '帮助中心',
      '功能',
      helpOk ? 'PASS' : 'FAIL',
      text.split('\n').slice(0, 10).join(' | '),
      e19c
    );

    // —— TC-BAL-001 余额明细分页（演示账号有流水则应出列表；空态文案亦可接受）——
    // 选择器真值来源：clients/consumer-mp/src/pages/balance/balance.vue
    //   列表项 class = `log-row`，加载更多 class = `more`。
    // 本用例此前查的是 `.transaction-row` / `.transaction-more`（重构前的旧类名），
    // 两个都取不到 → 恒为 rows=0/more=false，**永远不可能 PASS**，
    // 却一直计入 UAT_MAX_FAIL 基线，等于用一条假红掩盖了余额区域的所有真实回归。
    // 这类「选择器失效导致的静默假红」由 scripts/check-uat-selectors.mjs 兜底。
    await ensureLoggedIn(page);
    await gotoPath(page, '/pages/mine/mine');
    await clickByText(page, '余额明细', { exact: true });
    await page.waitForTimeout(2200);
    text = await bodyText(page);
    // 等待列表或空态（避免仍停在「加载中…」）
    await page
      .waitForFunction(
        () =>
          document.querySelectorAll('.log-row').length > 0 ||
          /暂无余额流水|暂无流水/.test(document.body.innerText || ''),
        null,
        { timeout: 8000 }
      )
      .catch(() => {});
    text = await bodyText(page);
    let balRows = await page.evaluate(() => document.querySelectorAll('.log-row').length);
    const hasMoreBtn = await page.evaluate(() => !!document.querySelector('.more'));
    const balEmpty = /暂无余额流水|暂无流水/.test(text);
    if (hasMoreBtn) {
      await page.evaluate(() => {
        const btn = document.querySelector('.more');
        if (btn) btn.click();
      });
      await page.waitForTimeout(1500);
      balRows = await page.evaluate(() => document.querySelectorAll('.log-row').length);
    }
    const balRowsAfter = await page.evaluate(() => document.querySelectorAll('.log-row').length);
    const e19d = await shot(page, '19d-balance-transactions');
    record(
      'TC-BAL-001',
      '余额明细分页',
      '功能',
      balRows > 0 || hasMoreBtn || balEmpty ? 'PASS' : 'FAIL',
      `rows=${balRows} more=${hasMoreBtn} rowsAfter=${balRowsAfter} empty=${balEmpty}`,
      e19d
    );

    // —— TC-BAL-002 冻结/释放类流水金额非零渲染（W-4 防回归）——
    // W-4：PREAUTH_FREEZE/RELEASE 等只改冻结额的流水，后端按 (after-before) 算金额恒为 0，
    // 前端于是把 89% 的流水渲染成「¥0.00」。修复后这类流水取操作金额 + 方向符号。
    // 断言按**行内标题**定位，只约束冻结/释放四类，不会因将来出现合法的零金额流水而误报。
    let holdZero = [];
    let holdTotal = 0;
    if (!balEmpty && balRows > 0) {
      const rows = await page.evaluate(() => {
        const HOLD_LABELS = ['开门预授权冻结', '开门预授权释放', '退款申请冻结', '退款冻结释放'];
        return [...document.querySelectorAll('.log-row')]
          .map((r) => ({
            label: (r.querySelector('.log-title')?.innerText || '').trim(),
            amount: (r.querySelector('.log-amount')?.innerText || '').trim()
          }))
          .filter((r) => HOLD_LABELS.includes(r.label));
      });
      holdTotal = rows.length;
      holdZero = rows
        .filter((r) => /^[+-]?¥?0\.00$/.test(r.amount.replace(/\s/g, '')))
        .map((r) => r.label);
    }
    const e19e = await shot(page, '19e-balance-hold-amounts');
    record(
      'TC-BAL-002',
      '冻结/释放流水金额非零（W-4）',
      '功能',
      // 无冻结/释放数据时记 SKIP（而不是静默 PASS），避免"没数据"被当成"验过了"
      holdTotal === 0 ? 'SKIP' : holdZero.length === 0 ? 'PASS' : 'FAIL',
      `冻结/释放行=${holdTotal} 其中显示 ¥0.00 的=${holdZero.length}${holdZero.length ? ' -> ' + holdZero.join(',') : ''}`,
      e19e
    );

    // —— TC-ERR-001 网络异常：断 API 模拟 ——
    await ensureLoggedIn(page);
    aborting = true;
    await context.route('**/api/v2/**', (route) => route.abort('failed'));
    await gotoPath(page, '/pages/orders/orders', 1500);
    await waitText(page, '加载失败', 12000);
    text = await bodyText(page);
    const netErr = /失败|网络|重试|无法连接|超时|加载失败/.test(text);
    const e20 = await shot(page, '20-network-timeout');
    record(
      'TC-ERR-001',
      'API 超时/中断时订单页容错',
      '异常',
      netErr ? 'PASS' : 'FAIL',
      netErr ? '有失败/重试提示' : `无明确错误 UI: ${text.slice(0, 200)}`,
      e20
    );
    await context.unroute('**/api/v2/**');
    aborting = false;

    // —— TC-LGOUT-001 退出登录并重新登录 ——
    await gotoPath(page, '/pages/mine/mine');
    await clickByText(page, '退出登录', { exact: true });
    await page.waitForTimeout(800);
    await clickModalPrimary(page, '退出');
    await page.waitForTimeout(2000);
    text = await bodyText(page);
    const loggedOut = text.includes('未登录');
    const e20a = await shot(page, '20a-logout');
    record(
      'TC-LGOUT-001',
      '退出登录',
      '功能',
      loggedOut ? 'PASS' : 'FAIL',
      text.split('\n').slice(0, 6).join(' | '),
      e20a
    );

    await clickByText(page, '去登录');
    await page.waitForTimeout(1200);
    let reloginOk = false;
    let reloginErr = '';
    try {
      reloginOk = await loginViaSms(page);
    } catch (e) {
      reloginErr = e instanceof Error ? e.message : String(e);
    }
    const tokenAfter = await page.evaluate(
      () =>
        localStorage.getItem('consumer_token') ||
        sessionStorage.getItem('consumer_token') ||
        localStorage.getItem('consumer_cookie_auth') ||
        ''
    );
    const e20b = await shot(page, '20b-relogin');
    record(
      'TC-LGOUT-002',
      '重新登录',
      '功能',
      tokenAfter || reloginOk ? 'PASS' : 'FAIL',
      tokenAfter || reloginOk
        ? '登录态已建立（HttpOnly Cookie，本地无 JWT）（含图形验证码）'
        : `未拿到 token，err=${reloginErr}`,
      e20b
    );

    // —— TC-SEC-003 清除 Token 后订单页不越权 ——
    await page.evaluate(() => {
      const keys = [];
      for (let i = 0; i < localStorage.length; i++) keys.push(localStorage.key(i));
      keys.forEach((k) => {
        if (k && (k.includes('consumer') || k.includes('token') || k.includes('active_session')))
          localStorage.removeItem(k);
      });
    });
    await gotoPath(page, '/pages/orders/orders');
    await page.waitForTimeout(1500);
    text = await bodyText(page);
    const noAuth =
      text.includes('登录后查看订单') || text.includes('去登录') || text.includes('扫码购物');
    const e21 = await shot(page, '21-orders-after-logout');
    record(
      'TC-SEC-003',
      '清除 Token 后订单页不越权展示他人数据',
      '安全',
      noAuth ? 'PASS' : 'FAIL',
      text.slice(0, 200),
      e21
    );

    // —— TC-QUAL-001 控制台严重错误 ——
    const serious = consoleErrors.filter(
      (e) =>
        !/favicon|DevTools|ResizeObserver|ERR_TIMED_OUT|ERR_ABORTED|ERR_FAILED|ERR_BLOCKED_BY_ORB|404|Failed to load resource/i.test(
          e
        )
    );
    record(
      'TC-QUAL-001',
      '浏览器控制台严重错误',
      '质量',
      serious.length === 0 ? 'PASS' : 'FAIL',
      serious.length === 0
        ? `无严重 console.error；http4xx=${http4xx.length} ${http4xx.slice(0, 3).join(' , ')}`
        : serious.slice(0, 5).join(' || '),
      null
    );
    const realFailed = failedRequests.filter(
      (r) => !r.intentional && !r.url.includes('example.com')
    );
    record(
      'TC-QUAL-002',
      '失败网络请求（非主动 abort）',
      '质量',
      'INFO',
      `failed=${realFailed.length}; sample=${JSON.stringify(realFailed.slice(0, 3))}`,
      null
    );
  } catch (err) {
    record(
      'TC-RUNNER',
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
      '# Consumer H5 UAT Report',
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
    if (summary.fail > UAT_MAX_FAIL) {
      console.error(`\n[uat] 失败 ${summary.fail} 条，超出基线 ${UAT_MAX_FAIL}`);
    }
    process.exit(summary.fail > UAT_MAX_FAIL ? 1 : 0);
  }
}

main();
