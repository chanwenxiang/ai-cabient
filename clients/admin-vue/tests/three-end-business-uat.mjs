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
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { captchaFromRedis } from '../../../scripts/lib/redis-captcha.mjs';
import { consumerLoginViaSms } from '../../../scripts/lib/h5-login.mjs';
import { adminPageState, mpListPageState } from '../../../scripts/lib/ui-assert.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const CONSUMER = (process.env.CONSUMER_H5_URL || 'http://127.0.0.1:3002').replace(/\/$/, '');
const MERCHANT = (process.env.MERCHANT_H5_URL || 'http://127.0.0.1:3001').replace(/\/$/, '');
const ADMIN = (process.env.ADMIN_URL || 'http://localhost/admin').replace(/\/$/, '');
const API = (process.env.API_BASE || 'http://127.0.0.1:18080').replace(/\/$/, '');
const DEMO_ORDER = process.env.DEMO_ORDER_ID || '1788233752744411094';
const CHANNEL = process.env.PW_CHANNEL || 'chrome';
const HEADED = process.env.PW_HEADED === '1';
const OUT = path.resolve(__dirname, '../output/playwright/three-end');
/**
 * 已知失败基线（ratchet）：用例级失败数 ≤ 该值不算回归，超出则 exit 1。
 * 目的是让 CI 抓住「整轮崩溃」与「新增失败」；已知缺陷修复后请下调此值。
 */
const UAT_MAX_FAIL = Number(process.env.UAT_MAX_FAIL ?? 0);

fs.mkdirSync(OUT, { recursive: true });
const results = [];

function record(id, name, status, detail, evidence) {
  let s = status;
  if (status === true) s = 'PASS';
  if (status === false) s = 'FAIL';
  results.push({ id, name, status: s, detail, evidence, at: new Date().toISOString() });
  console.log(
    `${s === 'PASS' ? '✓' : s === 'FAIL' ? '✗' : '○'} ${id} ${name} — ${String(detail).slice(0, 220)}`
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

/** 图形验证码读取已抽到共享模块 scripts/lib/redis-captcha.mjs（支持 REDIS_HOST 直连）。 */

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

/**
 * 首屏隐私同意弹窗是覆盖整页的模态遮罩（role=dialog + aria-modal），
 * 会拦截全部指针事件。不先关掉它，登录表单完全无法点击 —— 旧版脚本正是在
 * 「填手机号」这一步超时，导致整套 UAT 中断。
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

/**
 * 消费者短信登录。
 *
 * 旧实现**从不填图形验证码**，而 `consumer-mp/src/pages/login/login.vue` 的 `onSendCode()`
 * 明确要求 `captchaId && captchaCode` 才能取短信 —— 于是 T-C01 恒为「no token」。
 * 且 `captchaId` 只存在 Vue 的 JS 状态里、不落 DOM，脚本读不到，只能「拦截 captcha 响应 +
 * Redis 读码」。这段逻辑已抽到共享模块 `scripts/lib/h5-login.mjs`（consumer/merchant 侧
 * 先修好过，business/dispute 两套是没跟着修的旧副本）。
 */
async function consumerLogin(page) {
  await page.goto(`${CONSUMER}/pages/login/login`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(800);
  return consumerLoginViaSms(page).catch(() => false);
}

/**
 * 探测一个「真的有可播放录像」的订单号。
 *
 * 🔴 为什么不能沿用硬编码：`DEMO_ORDER_ID` 默认 `1788233752744411094` 是早期手工造单留下的
 * 常量，**全仓没有任何 Flyway 种子写它**（`grep -rn 1788233752744411094 *.sql` = 0 命中）。
 * 演示库一旦重建，该订单 0 行 → 视频页必然「视频加载失败」→ 用例**恒红**，却一直占着
 * `UAT_MAX_FAIL_BUSINESS` 的失败额度（与 merchant 侧 `DEMO_DISPUTE_TICKET_BILLED` 同一类病）。
 *
 * 做法与 `merchant-h5-uat.mjs` 的 M-10v、`consumer-h5-uat.mjs` 的 TC-OPEN-005 一致：
 * 先按提示值探测，再遍历订单列表逐个探测；都探测不到 → SKIP（**不是** FAIL）。
 * 种子可用时依然是真实断言，产品坏了照样红。
 */
async function probeOrderWithVideo(page, hint, kind = 'consumer') {
  return page.evaluate(
    async ({ h, kind, merchantDevice }) => {
      const token =
        localStorage.getItem(`${kind}_token`) || sessionStorage.getItem(`${kind}_token`) || '';
      const authHeaders = token ? { Authorization: 'Bearer ' + token } : {};
      const prefix = kind === 'merchant' ? '/api/v2/merchant/orders' : '/api/v2/orders';
      // 商户端 `deviceId` 是**可选**过滤条件（见 clients/merchant-mp/src/utils/merchant-api.ts:715）。
      // 旧实现在这里写死 `deviceId=CAB-001`，而 CAB-001 是 device_info 里一条**没有订单**的登记设备
      // ⇒ 列表恒为空 ⇒ T-M03 永久 SKIP，看不出是「没数据」还是「探测写错了」。改用与页面默认一致的
      // 「不带设备过滤」，需要时可用 MERCHANT_DEVICE_ID 覆盖。
      const listUrl =
        kind === 'merchant'
          ? `${prefix}?page=0&size=50${merchantDevice ? `&deviceId=${encodeURIComponent(merchantDevice)}` : ''}`
          : `${prefix}?page=0&size=30`;
      const probe = async (oid) => {
        if (!oid) return false;
        try {
          const r = await fetch(`${prefix}/${encodeURIComponent(oid)}/video`, {
            headers: authHeaders,
            credentials: 'same-origin'
          });
          if (!r.ok) return false;
          const buf = await r.arrayBuffer();
          // 过短或非 MP4 的「假成功」会在 <video> 里报 MEDIA_ERR_SRC_NOT_SUPPORTED
          if (buf.byteLength < 1024) return false;
          const bytes = new Uint8Array(buf);
          if (String.fromCharCode(...bytes.slice(4, 8)) !== 'ftyp') return false;
          // 🔴 只看容器不看编码是不够的：设备模拟器产出的会话录像编码是 **mp4v**
          // （MPEG-4 Part 2），容器合法、`<video>` 照样解不了 ⇒ 探测会「通过」而用例在
          // readyState 处变红，把一个环境问题误报成产品缺陷。这里要求编码是浏览器可解的。
          const codec = [
            [0x61, 0x76, 0x63, 0x31], // avc1
            [0x61, 0x76, 0x63, 0x33], // avc3
            [0x68, 0x76, 0x63, 0x31], // hvc1
            [0x68, 0x65, 0x76, 0x31], // hev1
            [0x76, 0x70, 0x30, 0x39], // vp09
            [0x76, 0x70, 0x30, 0x38], // vp08
            [0x61, 0x76, 0x30, 0x31] // av01
          ].some((cc) => {
            for (let i = 0; i + 4 <= bytes.length; i += 1) {
              if (
                bytes[i] === cc[0] &&
                bytes[i + 1] === cc[1] &&
                bytes[i + 2] === cc[2] &&
                bytes[i + 3] === cc[3]
              ) {
                return true;
              }
            }
            return false;
          });
          return codec;
        } catch {
          return false;
        }
      };
      if (h && (await probe(h))) return h;
      try {
        const res = await fetch(listUrl, { headers: authHeaders, credentials: 'same-origin' });
        const json = await res.json();
        const data = json?.data;
        const list = data?.items || data?.content || data?.records || [];
        for (const row of list) {
          const oid = String(row.orderId || '');
          if (await probe(oid)) return oid;
        }
      } catch {
        /* fall through */
      }
      return '';
    },
    { h: hint || '', kind, merchantDevice: process.env.MERCHANT_DEVICE_ID || '' }
  );
}

/** 无可用录像种子时的统一说明（与 merchant 侧 M-10v 的口径一致）。 */
const NO_VIDEO_SEED_HINT =
  '未找到含「浏览器可解编码」录像的订单（先用 node scripts/generate-demo-shopping-video.mjs 生成样例，再执行 scripts/seed-demo-shopping-video.ps1 -SessionId <id>）';

async function merchantLogin(page) {
  await page.goto(`${MERCHANT}/pages/login/login`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(800);
  await dismissPrivacyConsent(page);
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
  await page.keyboard.type('13800138001', { delay: 25 });
  await pwd.click();
  await page.keyboard.press('ControlOrMeta+a');
  await page.keyboard.type('123456', { delay: 25 });
  await page.waitForTimeout(400);
  await page.locator('[data-testid="login-submit"]').first().click();
  await page.waitForTimeout(2500);
  return page.evaluate(
    () =>
      localStorage.getItem('merchant_token') || localStorage.getItem('merchant_cookie_auth') || ''
  );
}

async function adminLogin(page) {
  await page.goto(`${ADMIN}/login`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1200);
  let capId = '';
  for (let i = 0; i < 4; i++) {
    try {
      capId = await page
        .locator('button.captcha-img-btn[data-captcha-id]')
        .first()
        .getAttribute('data-captcha-id');
      if (!capId) throw new Error('no id');
      const code = (await captchaFromRedis(capId)).toUpperCase();
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
  // 少数用例仍需整页文本（例如商户订单详情里「查看购物视频」按钮的存在性），统一在此声明。
  let text = '';

  try {
    // —— 消费者 ——
    const cToken = await consumerLogin(page);
    const e1 = await shot(page, 'c01-login');
    record('T-C01', '消费者登录', !!cToken, cToken ? 'token ok' : 'no token', e1);
    cToken ? pass++ : fail++;

    await page.goto(`${CONSUMER}/pages/orders/orders`, { waitUntil: 'domcontentloaded' });
    // 🔴 不再用 `/订单|已支付|暂无/.test(bodyText)`：底部 tabbar 就有「订单」，导航栏标题
    // 也有「我的订单」⇒ 列表整块没渲染也会绿。改判「内容分支容器 + 真实列表项/空态」。
    const cOrders = await mpListPageState(page, {
      contentSel: '.orders-main',
      itemSel: '.order-card'
    });
    record('T-C02', '消费者订单列表', cOrders.ok, cOrders.detail, await shot(page, 'c02-orders'));
    cOrders.ok ? pass++ : fail++;

    const cVideoOrder = await probeOrderWithVideo(page, DEMO_ORDER, 'consumer');
    if (!cVideoOrder) {
      record('T-C03', '消费者订单购物视频', 'SKIP', NO_VIDEO_SEED_HINT, null);
    } else {
      await page.goto(`${CONSUMER}/pages/video/video?orderId=${encodeURIComponent(cVideoOrder)}`, {
        waitUntil: 'domcontentloaded'
      });
      const cVideo = await waitVideoPlayable(page);
      record(
        'T-C03',
        '消费者订单购物视频',
        cVideo.ok,
        `order=${cVideoOrder} readyState=${cVideo.readyState} err=${cVideo.err} failedUi=${cVideo.failedUi}`,
        await shot(page, 'c03-video')
      );
      cVideo.ok ? pass++ : fail++;
    }

    // —— 商户 ——
    const mToken = await merchantLogin(page);
    record(
      'T-M01',
      '商户登录',
      !!mToken,
      mToken ? 'token ok' : 'no token',
      await shot(page, 'm01-login')
    );
    mToken ? pass++ : fail++;

    await page.goto(`${MERCHANT}/pages/orders/orders`, { waitUntil: 'domcontentloaded' });
    // 同上：商户端导航栏标题就是「柜机订单」⇒ 旧正则被外壳满足。改判内容容器 + 列表项。
    const mOrders = await mpListPageState(page, {
      contentSel: '.page-body .filter-panel',
      itemSel: '.page-body .card'
    });
    record('T-M02', '商户柜机订单', mOrders.ok, mOrders.detail, await shot(page, 'm02-orders'));
    mOrders.ok ? pass++ : fail++;

    const mVideoOrder = await probeOrderWithVideo(page, DEMO_ORDER, 'merchant');
    if (!mVideoOrder) {
      record('T-M03', '商户订单购物视频', 'SKIP', NO_VIDEO_SEED_HINT, null);
    } else {
      await page.goto(
        `${MERCHANT}/pages/order-detail/order-detail?orderId=${encodeURIComponent(mVideoOrder)}`,
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
        `order=${mVideoOrder} btn=${hasBtn} readyState=${mVideo.readyState} err=${mVideo.err}`,
        await shot(page, 'm03-video')
      );
      hasBtn && mVideo.ok ? pass++ : fail++;
    }

    // —— 运营 ——
    const adminOk = await adminLogin(page);
    record(
      'T-A01',
      '运营登录',
      adminOk,
      adminOk ? page.url() : 'login failed',
      await shot(page, 'a01-login')
    );
    adminOk ? pass++ : fail++;

    if (adminOk) {
      // 🔴 旧判据 `p.re.test(bodyText)` 是**恒真**的：`src/config/menu.ts:94/108/115` 的侧栏
      // 菜单标题就是「订单管理 / 争议审核 / 异常中心」，内容区整块没渲染也照样命中。
      // 改判：内容区标题（`.page-card-head__title .title`，仅 src/views/** 使用、侧栏不引用）
      // **精确**等于期望值，且该页 `.report-table` 已水合（有数据行或已渲染 el-empty）。
      for (const p of [
        { id: 'T-A02', name: '争议审核', path: '/disputes', title: '争议审核' },
        { id: 'T-A03', name: '异常中心', path: '/exceptions', title: '异常中心' },
        { id: 'T-A04', name: '订单管理', path: '/orders', title: '订单管理' }
      ]) {
        await page.goto(`${ADMIN}${p.path}`, { waitUntil: 'domcontentloaded' });
        const st = await adminPageState(page, { title: p.title });
        record(p.id, p.name, st.ok, st.detail, await shot(page, p.id.toLowerCase()));
        st.ok ? pass++ : fail++;
      }
    }
  } finally {
    await browser.close();
  }

  // SKIP ≠ FAIL：种子/数据缺失（如「含录像的订单」不存在）不算回归，但也**不计入 PASS**，
  // 单独计数以免「跳过」被读成「通过」。ratchet 只吃 fail。
  const skip = results.filter((r) => r.status === 'SKIP').length;
  const summary = { pass, fail, skip, report: path.join(OUT, 'report.json') };
  fs.writeFileSync(summary.report, JSON.stringify({ summary, results }, null, 2));
  console.log('\n=== THREE-END BUSINESS UAT ===');
  console.log(JSON.stringify(summary, null, 2));
  if (fail > UAT_MAX_FAIL) {
    console.error(`[uat] 失败 ${fail} 条，超出基线 ${UAT_MAX_FAIL}`);
  }
  process.exit(fail > UAT_MAX_FAIL ? 1 : 0);
}

main().catch((e) => {
  console.error(e);
  process.exit(2);
});
