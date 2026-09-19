/**
 * 共享工具：uni-app H5 端的 Playwright 登录助手。
 *
 * 为什么要有这个模块
 * ------------------
 * 消费者端（consumer-mp）的短信登录在某个版本后**必须**先填「图形验证码」才能取短信：
 * `clients/consumer-mp/src/pages/login/login.vue` 的 `onSendCode()` 里
 *   `if (!captchaId.value || !captchaCode.value.trim()) { err = '请先填写图形验证码'; return; }`
 * 而 `captchaId` 只存在 Vue 的 JS 状态里（`captchaId.value = data.captchaId`），
 * **不落任何 DOM 属性** —— 脚本无法像 admin 端那样读 `button.captcha-img-btn[data-captcha-id]`。
 *
 * 结果：三套 H5 UAT 里凡是「消费者短信登录」的用例都在这一步静默失败（拿到空 token），
 * 长期以 FAIL 计入 ratchet 基线额度。consumer-h5-uat.mjs 先修好了自己的登录（脚本内私有实现），
 * 但 three-end-business-uat.mjs / three-end-dispute-ui-uat.mjs 各有一份**旧副本**没跟着修
 * （「修一类缺陷必扫其它端副本」）。本模块把该实现抽出来，三处共用一份。
 *
 * 取值链：拦截 `GET /api/v2/auth/captcha` 的响应体拿 `captchaId`
 *   → 从 Redis 读 `aicabinet:captcha:<id>` 原文（见 ./redis-captcha.mjs）
 *   → 填入「图形验证码」→ 取短信（演示万能码 123456）→ 提交。
 *
 * 两个已踩过的坑（勿回退）：
 * 1. `waitForResponse` 必须在**切 Tab 之前**挂上，否则会错过首屏自动加载的那次 captcha 请求。
 * 2. 网关 `infra/gateway/nginx.conf` 对 `/api/v2/auth/*` 有 `auth_ratelimit`（5r/s + burst 10），
 *    逐条用例都走一遍登录链路时 `/auth/captcha` 会返回 **429**。旧实现用 `r.ok()` 当判据，
 *    429 不满足即静默 12s 超时，抛出的却是「图形验证码接口未返回」——把「被限流」误报成
 *    「服务不可用」。故这里显式区分状态码并对 429 做有上限的线性退避。
 */
import { captchaFromRedis } from './redis-captcha.mjs';

export const DEFAULT_CONSUMER_PHONE = '13800138000';
export const DEFAULT_DEMO_SMS = '123456';

export async function bodyText(page) {
  return page.evaluate(() => document.body?.innerText || '');
}

/**
 * 点击文本：候选按「文本长度升序 → 元素类型（button 优先）→ 子元素数（叶子优先）」排序，
 * 命中后用真实鼠标点击元素中心（兼容 uni-button / uni-view 等自定义元素），
 * 不可见元素回退为原生 click。避免旧实现里 dispatchEvent + click 的双触发问题。
 */
export async function clickByText(page, text, { exact = false, timeout = 6000 } = {}) {
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

export async function clickByTestId(page, testId) {
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
 * 按 placeholder 填值：uni-app H5 输入框统一渲染为 uni-input 包装
 * （placeholder 在独立 div 上，内层 input 无 placeholder 属性），
 * 定位后使用真实键盘输入（Ctrl+A 清空后键入），兼容开发预填场景。
 * 注意：部分 uni-app H5 版本在键入时内部 ref 会滞后（DOM 值正确但提交值被截断），
 * 实测键入后需等待 uni-app 消化事件队列（约 500ms）；值已正确时直接跳过输入。
 */
export async function fillPlaceholder(page, placeholder, value) {
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

/**
 * 首屏隐私同意弹窗是覆盖整页的模态遮罩（role=dialog + aria-modal），
 * 会拦截全部指针事件。不先关掉它，登录表单完全无法点击 —— 旧版脚本正是在
 * 「填手机号」这一步超时，导致整套 UAT 中断。
 */
export async function dismissPrivacyConsent(page) {
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

/** 从 captcha API 响应体取出 captchaId（兼容 ApiResponse 包装） */
export function pickCaptchaId(body) {
  if (!body || typeof body !== 'object') return '';
  return body.data?.captchaId || body.captchaId || '';
}

/** 取一次**成功**的图形验证码响应（含 429 退避）。详见文件头「坑 2」。 */
export async function fetchCaptchaWithRetry(page, { attempts = 6, baseDelayMs = 900, log } = {}) {
  const out = log || (() => {});
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
      out(`○ [setup] captcha 命中网关限流 429（第 ${i + 1} 次），退避 ${wait}ms 重试`);
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

export async function hasConsumerToken(page) {
  return !!(await page.evaluate(
    () =>
      localStorage.getItem('consumer_token') ||
      sessionStorage.getItem('consumer_token') ||
      localStorage.getItem('consumer_cookie_auth') ||
      ''
  ));
}

/**
 * 消费者短信登录（含图形验证码）。
 *
 * 须在**切 Tab 前**挂 `waitForResponse`（本函数内部已处理）。
 * 返回 boolean：是否已落 token。
 */
export async function consumerLoginViaSms(
  page,
  { phone = DEFAULT_CONSUMER_PHONE, sms = DEFAULT_DEMO_SMS, log } = {}
) {
  const out = log || (() => {});
  await dismissPrivacyConsent(page);

  const resp = await fetchCaptchaWithRetry(page, { log: out });

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

  return hasConsumerToken(page);
}
