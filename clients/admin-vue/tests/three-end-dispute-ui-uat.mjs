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
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { captchaFromRedis } from '../../../scripts/lib/redis-captcha.mjs';
import { consumerLoginViaSms } from '../../../scripts/lib/h5-login.mjs';
import { adminPageState, mpListPageState, pollUntil } from '../../../scripts/lib/ui-assert.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, '../../..');
const CONSUMER = (process.env.CONSUMER_H5_URL || 'http://127.0.0.1:3002').replace(/\/$/, '');
const MERCHANT = (process.env.MERCHANT_H5_URL || 'http://127.0.0.1:3001').replace(/\/$/, '');
const ADMIN = (process.env.ADMIN_URL || 'http://localhost/admin').replace(/\/$/, '');
const CHANNEL = process.env.PW_CHANNEL || 'chrome';
const HEADED = process.env.PW_HEADED === '1';
const OUT = path.resolve(__dirname, '../output/playwright/dispute-flow');
/**
 * 已知失败基线（ratchet）：用例级失败数 ≤ 该值不算回归，超出则 exit 1。
 * 目的是让 CI 抓住「整轮崩溃」与「新增失败」；已知缺陷修复后请下调此值。
 */
const UAT_MAX_FAIL = Number(process.env.UAT_MAX_FAIL ?? 0);
const DISPUTE_FILE = process.env.OPEN_DISPUTE_JSON || path.join(ROOT, '.tmp/open-dispute.json');
/**
 * 种子工单是**一次性消耗品**：D-A04 会把它免单结案，于是第二次跑本套件必然「种子失效」。
 * 与其永远 SKIP，不如让套件自己把前置条件重建出来（在 `seedTicketInOpenList` 判失效时才触发，
 * 且只在本机能跑 PowerShell 时启用）。
 *   OPEN_DISPUTE_AUTOSEED=0 可关闭；关闭后种子失效退回 SKIP（不再是 FAIL）。
 */
const AUTOSEED =
  (process.env.OPEN_DISPUTE_AUTOSEED ?? (process.platform === 'win32' ? '1' : '0')) !== '0';

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

/** 图形验证码读取已抽到共享模块 scripts/lib/redis-captcha.mjs（支持 REDIS_HOST 直连）。 */

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
      const code = (await captchaFromRedis(capId)).toUpperCase();
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

/**
 * 点某工单所在行的「详情」，返回**实际被点的那条工单号**。
 *
 * 为什么要返回 targetId 而不是只返回布尔：判据是「工作台展示的工单 == 点之前该行显示的工单」。
 * 若种子工单已被上一次运行结案（不在 OPEN 列表里），就退化为点首行——
 * 断言强度不变（照样能红），但 D-A03 不再依赖种子是否新鲜（种子新鲜度是 D-A04 的事）。
 */
async function clickDetailForTicket(page, preferredTicketId) {
  return page.evaluate((tid) => {
    const rows = [...document.querySelectorAll('.report-table .el-table__body tr.el-table__row')];
    if (!rows.length) return { clicked: false, reason: 'no-rows', targetId: '' };
    const idOf = (r) => (r.querySelector('.cell-id')?.innerText || '').trim();
    const tail = String(tid || '').slice(-8);
    const hit =
      (tid && rows.find((r) => idOf(r) === String(tid))) ||
      (tid && rows.find((r) => idOf(r).slice(-8) === tail)) ||
      rows[0];
    const targetId = idOf(hit);
    // 操作列是**图标按钮**（无可见文字），唯一稳定的定位是 aria-label="详情"。
    const btn =
      hit.querySelector('td.col-action button[aria-label="详情"]') ||
      [...hit.querySelectorAll('button')].find(
        (b) => (b.getAttribute('aria-label') || '') === '详情'
      );
    if (!btn) return { clicked: false, reason: 'detail-button-not-found', targetId };
    btn.click();
    return { clicked: true, reason: 'ok', targetId };
  }, preferredTicketId);
}

/**
 * 关闭当前抽屉（若已打开）。
 *
 * 必要性：列表页支持 `?ticketId=` 深链，`openFocusedTicket()` 会在加载后**自动**打开工作台。
 * 若不先关掉，「点详情后抽屉是开的」就分辨不出是深链开的还是点击开的——判据会失去意义。
 */
async function closeDrawerIfOpen(page) {
  const drawer = page.locator('.el-drawer.dispute-workbench');
  const wasOpen =
    (await drawer.count()) > 0 &&
    (await drawer
      .first()
      .isVisible()
      .catch(() => false));
  if (!wasOpen) return { wasOpen: false, closed: true };
  await page
    .locator('.el-drawer.dispute-workbench .el-drawer__close-btn')
    .first()
    .click({ timeout: 3000 })
    .catch(() => {});
  const st = await pollUntil(
    page,
    async () => ({
      ok: (await page.locator('.el-drawer.dispute-workbench').count()) === 0
    }),
    { timeoutMs: 4000 }
  );
  return { wasOpen: true, closed: !!st?.ok };
}

/**
 * 争议审单工作台是否打开，且展示的是**指定**工单。
 *
 * 旧判据 `clicked || /工单|会话|免单/.test(text)` 两半都靠不住：
 *  · `clicked` 只证明表格里有 `<tr>`（el-table 没绑 `@row-click`，点行没有任何开详情的副作用）；
 *  · 「免单」出现在页头提示「同屏对照录像改 SKU 后一键落账或免单」里 ⇒ 恒真。
 */
async function disputeWorkbenchState(page, expectTicketId) {
  return page.evaluate((tid) => {
    const drawer = document.querySelector('.el-drawer.dispute-workbench');
    const visible = !!drawer && drawer.offsetHeight > 0;
    const title = (drawer?.querySelector('.el-drawer__title')?.innerText || '').trim();
    const desc = drawer?.querySelector('.workbench-desc');
    // 「工单」是 .workbench-desc 里第一个 descriptions-item，其内容即 selected.ticketId
    const shownTicket = (desc?.querySelector('.cell-id')?.innerText || '').trim();
    const titleOk = title === '争议审单工作台';
    const ticketOk = !!tid && shownTicket === String(tid);
    return {
      ok: visible && titleOk && ticketOk,
      visible,
      title,
      shownTicket,
      detail: `drawer=${visible} title=${JSON.stringify(title)} shown=${JSON.stringify(shownTicket)} expect=${JSON.stringify(String(tid || ''))}`
    };
  }, expectTicketId);
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

async function merchantLogin(page) {
  // 商户端：密码登录，无图形验证码
  await page.goto(`${MERCHANT}/pages/login/login`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(700);
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
  await page.keyboard.type('13800138001', { delay: 20 });
  await pwd.click();
  await page.keyboard.press('ControlOrMeta+a');
  await page.keyboard.type('123456', { delay: 20 });
  await page.locator('[data-testid="login-submit"]').first().click();
  await page.waitForTimeout(2200);
  return !!(await page.evaluate(
    () =>
      localStorage.getItem('merchant_token') || localStorage.getItem('merchant_cookie_auth') || ''
  ));
}

/**
 * 消费者短信登录（含图形验证码）。
 *
 * 旧实现在「获取验证码」之前**从不填图形验证码**，而
 * `consumer-mp/src/pages/login/login.vue` 的 `onSendCode()` 要求
 * `captchaId && captchaCode` 才发短信 —— 于是 D-C01 恒为 fail。
 * `captchaId` 不落 DOM（只在 Vue 状态里），只能「拦截 captcha 响应 + Redis 读码」，
 * 该逻辑已抽到 `scripts/lib/h5-login.mjs`（三套 H5 UAT 共用一份）。
 */
async function consumerLogin(page) {
  await page.goto(`${CONSUMER}/pages/login/login`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(700);
  return consumerLoginViaSms(page).catch(() => false);
}

/**
 * 种子里那条工单是否**真的还在 OPEN 列表里**。
 *
 * 脚本原本只校验「`OPEN_DISPUTE_JSON` 文件存在 + 有 ticketId/sessionId」，
 * **不校验工单是否还有效** —— 而该文件是 `.gitignore` 的（CI 里根本不存在），
 * 本机又会被上一条 UAT 自己结案（D-A04 免单）或演示库重建变成「指向不存在的工单」。
 * 结果 D-A04 长期恒红，报的却是 `hasWaive=false`，看不出是「种子失效」还是「产品坏了」。
 * 这里显式区分：工单不在 OPEN 列表 → SKIP（并提示重跑种子脚本），而不是记成失败。
 */
async function seedTicketInOpenList(page, ticketId) {
  return page.evaluate((tid) => {
    const rows = [...document.querySelectorAll('.el-table__body tr, .el-table__row')];
    if (!rows.length) return false;
    const tail = String(tid).slice(-8);
    return rows.some((r) => (r.innerText || '').includes(tail));
  }, ticketId);
}

/**
 * 重建 OPEN 争议种子（调用仓库里的 create-open-dispute.ps1）。
 * 该脚本自身是幂等的：记录里的工单仍 OPEN 就直接复用，否则新造一条。
 */
function reseedOpenDispute() {
  const script = path.join(ROOT, 'scripts', 'create-open-dispute.ps1');
  if (!fs.existsSync(script)) return { ok: false, output: `找不到 ${script}` };
  const r = spawnSync('powershell', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', script], {
    cwd: ROOT,
    encoding: 'utf8',
    timeout: 240000,
    windowsHide: true
  });
  const output = `${r.stdout || ''}\n${r.stderr || ''}`.trim();
  const ok = r.status === 0 && /OPEN dispute (ready|reused)/.test(output);
  return { ok, output, status: r.status };
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
  let ticketId = String(dispute.ticketId || '');
  let sessionId = String(dispute.sessionId || '');
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
    // 🔴 不再用 `bodyText.includes('争议审核')`：`src/config/menu.ts:108` 的侧栏菜单标题就是
    // 「争议审核」⇒ 内容区整块没渲染也会绿。改判内容区标题精确匹配 + `.report-table` 已水合。
    const dA02 = await adminPageState(page, { title: '争议审核' });
    record('D-A02', '打开待审争议页', dA02.ok, dA02.detail, await shot(page, '02-disputes'));
    dA02.ok ? pass++ : fail++;
    let text = await bodyText(page);

    // 种子有效性必须在**列表还完整时**判（后面会点开抽屉、甚至结案）
    let seedOk = await seedTicketInOpenList(page, ticketId);
    let seedNote = '';
    if (!seedOk && AUTOSEED) {
      // 工单已被上一次运行结案 ⇒ 自动重建前置条件（脚本幂等：仍 OPEN 就直接复用）
      const rs = reseedOpenDispute();
      if (rs.ok) {
        const fresh = JSON.parse(fs.readFileSync(DISPUTE_FILE, 'utf8').replace(/^\uFEFF/, ''));
        ticketId = String(fresh.ticketId || '');
        sessionId = String(fresh.sessionId || '');
        await page.goto(
          `${ADMIN}/disputes?status=OPEN&ticketId=${encodeURIComponent(ticketId)}&sessionId=${encodeURIComponent(sessionId)}`,
          { waitUntil: 'domcontentloaded' }
        );
        await adminPageState(page, { title: '争议审核' });
        seedOk = await seedTicketInOpenList(page, ticketId);
        seedNote = `自动重造种子 ticket=${ticketId} → 有效=${seedOk}；`;
      } else {
        seedNote = `自动重造种子失败(status=${rs.status}): ${rs.output.slice(-400)}；`;
      }
    }
    console.log(`[种子] 工单 ${ticketId} 出现在 OPEN 列表中: ${seedOk} ${seedNote}`);

    // 深链 `?ticketId=` 会自动开工作台（`openFocusedTicket`）⇒ 必须先关掉，
    // 「点详情之后抽屉是开的」才等于「点击真的打开了抽屉」。
    const pre = await closeDrawerIfOpen(page);
    const detailClick = pre.closed
      ? await clickDetailForTicket(page, seedOk ? ticketId : '')
      : { clicked: false, reason: 'drawer-close-failed', targetId: '' };
    const dA03 =
      detailClick.clicked === true
        ? await pollUntil(page, () => disputeWorkbenchState(page, detailClick.targetId), {
            timeoutMs: 8000
          })
        : { ok: false, detail: `未点到「详情」按钮：${detailClick.reason}` };
    if (detailClick.reason === 'no-rows') {
      // 列表里一条 OPEN 争议都没有（数据问题，不是产品缺陷）⇒ 诚实 SKIP，不冒充通过。
      record(
        'D-A03',
        '打开争议详情（工作台展示该行工单）',
        'SKIP',
        'OPEN 列表为空，无可点的「详情」（需先造争议：scripts/create-open-dispute.ps1）',
        await shot(page, '03-detail')
      );
    } else {
      record(
        'D-A03',
        '打开争议详情（工作台展示该行工单）',
        dA03.ok,
        `autoDrawerClosed=${pre.closed} click=${detailClick.clicked}(${detailClick.reason}) target=${detailClick.targetId} ${dA03.detail}`,
        await shot(page, '03-detail')
      );
      dA03.ok ? pass++ : fail++;
    }

    if (!seedOk) {
      // 种子失效：一个按钮都不点（列表里可能有别的 OPEN 单，误点会影响无关工单）
      if (AUTOSEED) {
        // 已经尝试重建前置条件仍失败 ⇒ fail-closed，不冒充通过、也不假装「只是跳过」
        record(
          'D-A04',
          '运营 UI 免单结案',
          false,
          `无法建立前置条件（OPEN 种子工单）：${seedNote}`,
          await shot(page, '04-seed-missing')
        );
        fail++;
      } else {
        record(
          'D-A04',
          '运营 UI 免单结案',
          'SKIP',
          `种子工单 ${ticketId} 不在 OPEN 列表（已被上一次 UAT 结案 / 演示库重建）；OPEN_DISPUTE_AUTOSEED=0 已关闭自动重造 ⇒ 请重跑 scripts/create-open-dispute.ps1`,
          await shot(page, '04-seed-missing')
        );
      }
    } else {
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
      if (
        /无录像|尚未加载|无法播放|录像加载/.test(text) ||
        !(await page.locator('video').count())
      ) {
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
    }

    // —— 消费者 ——
    const cOk = await consumerLogin(page);
    record(
      'D-C01',
      '消费者登录（短信 + 图形验证码）',
      cOk,
      cOk ? 'ok' : 'fail',
      await shot(page, '05-consumer-login')
    );
    cOk ? pass++ : fail++;
    await page.goto(`${CONSUMER}/pages/orders/orders`, { waitUntil: 'domcontentloaded' });
    // 免单后：可能显示已退款/已完成/¥0。旧判据 `/订单|…/` 会被底部 tabbar 的「订单」满足。
    const cSee = await mpListPageState(page, {
      contentSel: '.orders-main',
      itemSel: '.order-card'
    });
    record(
      'D-C02',
      '消费者订单页可见结算结果',
      cSee.ok,
      `${cSee.detail} | ${(await bodyText(page)).split('\n').slice(0, 6).join(' | ')}`,
      await shot(page, '06-consumer-orders')
    );
    cSee.ok ? pass++ : fail++;

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
    const mSee = await mpListPageState(page, {
      contentSel: '.page-body .filter-panel',
      itemSel: '.page-body .card'
    });
    record(
      'D-M02',
      '商户订单页可打开',
      mSee.ok,
      mSee.detail,
      await shot(page, '08-merchant-orders')
    );
    mSee.ok ? pass++ : fail++;

    await page.goto(`${MERCHANT}/pages/disputes/disputes`, { waitUntil: 'domcontentloaded' });
    // 该页没有 .filter-panel，内容分支由 `.tabs-pill` + 列表/空态/错误态共同界定
    const mDisp = await mpListPageState(page, {
      contentSel: '.page-body .tabs-pill',
      itemSel: '.page-body .card'
    });
    record(
      'D-M03',
      '商户争议页状态',
      mDisp.ok,
      mDisp.detail,
      await shot(page, '09-merchant-disputes')
    );
    mDisp.ok ? pass++ : fail++;
  } finally {
    await browser.close();
  }

  // SKIP ≠ FAIL：种子失效（如 D-A04 的 OPEN 工单已被结案）不算回归，也**不计入 PASS**，
  // 单独计数以免「跳过」被读成「通过」。ratchet 只吃 fail。
  const skip = results.filter((r) => r.status === 'SKIP').length;
  const summary = { pass, fail, skip, ticketId, sessionId, report: path.join(OUT, 'report.json') };
  fs.writeFileSync(summary.report, JSON.stringify({ summary, results }, null, 2));
  console.log('\n=== DISPUTE UI FLOW ===');
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
