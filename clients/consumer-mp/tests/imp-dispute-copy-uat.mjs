/**
 * IMP-025 / IMP-032 — 已结案争议文案 UAT（消费者端 + 商户端）
 * Run: node tests/imp-dispute-copy-uat.mjs
 * Requires: 消费者 H5 :3002 + 商户 H5 :3001 + 网关/后台（与 consumer-h5-uat 同一套栈）
 *
 * Env overrides:
 *   CONSUMER_H5_URL / MERCHANT_H5_URL / PW_CHANNEL / PW_HEADED
 *   DEMO_DISPUTE_TICKET_BILLED / DEMO_DISPUTE_TICKET_REFUND  固定工单号（仅作探测首选）
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * 2026-09-20 加固（本条套件此前**从未真正跑通过**）：
 *
 * ① 旧实现自带一份私有「消费者登录」：既不关首屏隐私弹窗、也不填图形验证码。
 *    实测在第一屏 `fillPlaceholder('请输入11位手机号')` 就超时 —— 隐私遮罩拦截了全部
 *    指针事件（TimeoutError → exit 2），**三个用例一个都没跑到**。而它既不在 CI 的
 *    e2e-h5 步骤里、也不在任何 package.json script 里，所以崩了半年也没人发现。
 *    改为共用 `scripts/lib/h5-login.mjs`（与三套 H5 UAT 同一份）。
 *
 * ② 两个工单号曾硬编码 `1788252219672817302` / `1788247248295553600`，
 *    **库里 0 行命中**（全库 16 条工单，编号完全不同）⇒ 与 merchant 侧 `M-10c` 同类的
 *    「环境漂移」缺陷：在新播种的库里必然取不到数据。改为**发现式** —— 从本消费者自己的
 *    `/api/v2/disputes/mine` 里按结论文挑（挑不到 → SKIP 并写明原因，不是 FAIL）。
 *
 * ③ 断言由「整页文本正则」换成「该页专属容器 + 与 API 真值等值」：
 *    结构锚点 `.status-title` / `.bill-row .bill-amount` / `.info-value.mono`(购物单号)，
 *    并**绑定实体**（金额、单号、标题都与该工单的详情接口返回值逐项对齐）。
 *    另加**反向对照**：纯扣款结案**必须没有**「退款渠道」行（`shouldShowConsumerRefundChannel`
 *    仅在终态且 `refundedAmountCents > 0` 时为真）。有/无两侧都断言，判据才有区分力。
 *
 * ④ `TC-IMP-032` 的 `!/暂未扣款/` 原本打在**整页文本**上，而抽屉背后的工单列表里
 *    「待审核」那条的合法文案正是"本次暂未扣款" ⇒ 恒为假（merchant 侧的同款缺陷已修，
 *    这份是没跟上的旧副本）。改为只看 `.app-sheet` 内部，并绑定被点工单号。
 * ⑤ 2026-09-20 第三轮：**接入 CI**（ci.yml 的「Imp dispute copy UAT」步骤），并由
 *    `scripts/check-uat-consumers.mjs` 门禁守住「每个 `*-uat.mjs` 都必须有执行消费者」。
 *    它当初之所以能烂半年，根因就是**没有任何东西执行它** ⇒ 从判据上堵死该形态。
 *    同时把 `main().catch` 的退出码由 2 改为 **3**：CI 只把 exit 2（环境未就绪：登录未落
 *    token / 接口不可用）降级为 warning，exit 3（**脚本自身崩溃**）必须让 job 变红 ——
 *    否则「按 2 降级」会把脚本 bug 一起吞成假绿。
 * ─────────────────────────────────────────────────────────────────────────────
 */
import { chromium } from 'playwright';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { consumerLoginViaSms, dismissPrivacyConsent } from '../../../scripts/lib/h5-login.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const CONSUMER = process.env.CONSUMER_H5_URL || 'http://127.0.0.1:3002';
const MERCHANT = process.env.MERCHANT_H5_URL || 'http://127.0.0.1:3001';
const CHANNEL = process.env.PW_CHANNEL || 'chrome';
const OUT = path.resolve(__dirname, '../output/playwright/imp-dispute');
const TICKET_BILLED_HINT = process.env.DEMO_DISPUTE_TICKET_BILLED || '';
const TICKET_REFUND_HINT = process.env.DEMO_DISPUTE_TICKET_REFUND || '';

fs.mkdirSync(OUT, { recursive: true });

const results = [];

function record(id, name, status, detail) {
  results.push({ id, name, status, detail });
  console.log(
    `${status === 'PASS' ? '✓' : status === 'FAIL' ? '✗' : '○'} ${id} ${name} — ${detail}`
  );
}

async function shot(page, name) {
  const file = path.join(OUT, `${name}.png`);
  await page.screenshot({ path: file, fullPage: true }).catch(() => {});
  return file;
}

/** `¥3.50` → 350（只用于**数值等值**比对，避免把格式化规则抄一份进来） */
function centsOf(text) {
  const m = String(text || '').match(/-?\d+(?:\.\d+)?/);
  return m ? Math.round(Number(m[0]) * 100) : NaN;
}

const n = (v) => (v == null ? 0 : Number(v));
const isTerminal = (s) => /^(RESOLVED|CLOSED)$/.test(String(s || '').toUpperCase());

/** 页面标题的真值规则（与 src/utils/dispute-copy.ts 的 consumerDisputeReviewCopy 一致） */
function expectedStatusTitle(dto) {
  if (dto?.consumerReviewTitle && dto?.consumerReviewDetail) return dto.consumerReviewTitle;
  return '人工审核已完成';
}

async function fillPlaceholder(page, placeholder, value) {
  const uni = page
    .locator('uni-input')
    .filter({ has: page.locator('.uni-input-placeholder', { hasText: placeholder }) })
    .first();
  if ((await uni.count()) === 0) return false;
  const input = uni.locator('input').first();
  if ((await input.inputValue()) === value) return true;
  await uni.click();
  await page.keyboard.press('ControlOrMeta+a');
  await page.keyboard.type(value, { delay: 30 });
  await page.waitForTimeout(500);
  return (await input.inputValue()) === value;
}

async function gotoConsumer(page, pathname) {
  await page.goto(CONSUMER + pathname, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1500);
  await dismissPrivacyConsent(page);
}

async function consumerLogin(page) {
  await gotoConsumer(page, '/pages/login/login');
  return consumerLoginViaSms(page).catch(() => false);
}

async function merchantLogin(page) {
  await page.goto(MERCHANT + '/pages/login/login', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1500);
  await dismissPrivacyConsent(page);
  await fillPlaceholder(page, '请输入11位手机号', '13800138001');
  await fillPlaceholder(page, '请输入登录密码', '123456');
  await page.locator('[data-testid="login-submit"]').click();
  await page.waitForTimeout(3500);
  await dismissPrivacyConsent(page);
}

/** 本消费者自己的争议列表（发现式取数的唯一来源，按登录态过滤 —— 正是「我能不能看」的判据） */
async function listMyDisputes(page) {
  return page.evaluate(async () => {
    try {
      const r = await fetch('/api/v2/disputes/mine', { credentials: 'same-origin' });
      if (!r.ok) return { ok: false, status: r.status, items: [] };
      const j = await r.json();
      const d = j?.data;
      const items = Array.isArray(d) ? d : d?.items || d?.content || [];
      return { ok: true, status: r.status, items };
    } catch (e) {
      return { ok: false, error: String((e && e.message) || e), items: [] };
    }
  });
}

/** 单条详情（页面自己用的就是这个接口 ⇒ 断言与页面同源） */
async function fetchMyDisputeDetail(page, ticketId) {
  return page.evaluate(async (tid) => {
    try {
      const r = await fetch(`/api/v2/disputes/mine/detail?ticketId=${encodeURIComponent(tid)}`, {
        credentials: 'same-origin'
      });
      const j = await r.json().catch(() => null);
      return { ok: r.ok, status: r.status, data: j?.data || null };
    } catch (e) {
      return { ok: false, error: String((e && e.message) || e) };
    }
  }, ticketId);
}

/** 消费者争议详情的**结构锚点**读取（不做任何文本正则猜测） */
async function readConsumerDetail(page) {
  return page.evaluate(() => {
    const t = (s) => document.querySelector(s)?.innerText?.trim() ?? '';
    const infoRows = [...document.querySelectorAll('.info-row')].map((r) => ({
      label: (r.querySelector('.info-label')?.innerText || '').trim(),
      value: (r.querySelector('.info-value')?.innerText || '').trim()
    }));
    const root = document.querySelector('.page-root');
    return {
      pageRoot: !!root && root.offsetHeight > 0,
      statusTitle: t('.status-title'),
      billLabel: t('.bill-row .bill-label'),
      billAmount: t('.bill-row .bill-amount'),
      bizNo: t('.info-value.mono'),
      refundRow: infoRows.find((r) => r.label === '退款渠道') || null,
      infoRows,
      notFound: (document.body?.innerText || '').includes('未找到审核单'),
      err: t('.err')
    };
  });
}

/** 打开详情并等到「已结案卡片 / 未找到 / 错误」三者之一出现 */
async function openConsumerDetail(page, ticketId) {
  await gotoConsumer(page, `/pages/dispute/detail?ticketId=${encodeURIComponent(ticketId)}`);
  const deadline = Date.now() + 12000;
  let st = await readConsumerDetail(page);
  while (!st.billAmount && !st.notFound && !st.err && Date.now() < deadline) {
    await page.waitForTimeout(300);
    st = await readConsumerDetail(page);
  }
  return st;
}

async function main() {
  const browser = await chromium.launch({ channel: CHANNEL, headless: true });
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, locale: 'zh-CN' });

  const cpage = await ctx.newPage();
  cpage.setDefaultTimeout(15000);
  const consumerOk = await consumerLogin(cpage);
  if (!consumerOk) {
    await browser.close();
    console.error('SETUP FAIL：消费者短信登录未落 token（图形验证码/Redis/网关限流？）');
    process.exit(2);
  }

  const listed = await listMyDisputes(cpage);
  if (!listed.ok) {
    await browser.close();
    console.error(`SETUP FAIL：/api/v2/disputes/mine 不可用：${JSON.stringify(listed)}`);
    process.exit(2);
  }

  const terminal = listed.items.filter((t) => isTerminal(t.status));
  const billedOnly =
    (TICKET_BILLED_HINT
      ? listed.items.find((t) => String(t.ticketId) === TICKET_BILLED_HINT)
      : null) ||
    terminal.find((t) => n(t.billedAmountCents) > 0 && n(t.refundedAmountCents) <= 0) ||
    null;
  const withRefund =
    (TICKET_REFUND_HINT
      ? listed.items.find((t) => String(t.ticketId) === TICKET_REFUND_HINT)
      : null) ||
    terminal.find((t) => n(t.refundedAmountCents) > 0) ||
    null;

  // —— TC-IMP-025：已结案**扣款**结案（无退款渠道） ——
  if (!billedOnly) {
    record(
      'TC-IMP-025',
      '消费者已结案扣款文案',
      'SKIP',
      `本消费者名下没有「终态且 billed>0/refunded=0」的工单（/api/v2/disputes/mine 共 ${listed.items.length} 条，终态 ${terminal.length} 条）→ 无扣款结案文案可验`
    );
  } else {
    const detail = await fetchMyDisputeDetail(cpage, billedOnly.ticketId);
    const dto = detail.data || billedOnly;
    const st = await openConsumerDetail(cpage, billedOnly.ticketId);
    const expTitle = expectedStatusTitle(dto);
    const ok =
      st.pageRoot &&
      !st.notFound &&
      st.billLabel === '最终扣款' &&
      centsOf(st.billAmount) === n(dto.billedAmountCents) &&
      st.bizNo === String(dto.sessionId) &&
      st.statusTitle === expTitle &&
      !st.refundRow;
    await shot(cpage, 'imp025-billed');
    record(
      'TC-IMP-025',
      '消费者已结案扣款文案',
      ok ? 'PASS' : 'FAIL',
      ok
        ? `扣款结案：最终扣款=${st.billAmount}(=${n(dto.billedAmountCents)}分)、购物单号=${st.bizNo}、标题「${st.statusTitle}」、无退款渠道行（ticket=${billedOnly.ticketId}）`
        : `pageRoot=${st.pageRoot} notFound=${st.notFound} bill="${st.billLabel}/${st.billAmount}"(期望 ${n(dto.billedAmountCents)}分) bizNo=${st.bizNo}(期望 ${dto.sessionId}) title="${st.statusTitle}"(期望 "${expTitle}") refundRow=${JSON.stringify(st.refundRow)}`
    );
  }

  // —— TC-IMP-025b：已结案**退款**结案（必须展示退款渠道） ——
  if (!withRefund) {
    record(
      'TC-IMP-025b',
      '消费者已结案退款渠道',
      'SKIP',
      `本消费者名下没有 refundedAmountCents>0 的终态工单（/api/v2/disputes/mine 共 ${listed.items.length} 条）→ 无退款渠道可验`
    );
  } else {
    const detail = await fetchMyDisputeDetail(cpage, withRefund.ticketId);
    const dto = detail.data || withRefund;
    const st = await openConsumerDetail(cpage, withRefund.ticketId);
    const ok =
      st.pageRoot &&
      !st.notFound &&
      !!st.refundRow &&
      !!st.refundRow.value &&
      centsOf(st.billAmount) === n(dto.billedAmountCents) &&
      st.bizNo === String(dto.sessionId);
    await shot(cpage, 'imp025-refund');
    record(
      'TC-IMP-025b',
      '消费者已结案退款渠道',
      ok ? 'PASS' : 'FAIL',
      ok
        ? `退款结案：退款渠道=${st.refundRow.value}、最终扣款=${st.billAmount}、单号=${st.bizNo}（ticket=${withRefund.ticketId}）`
        : `pageRoot=${st.pageRoot} notFound=${st.notFound} refundRow=${JSON.stringify(st.refundRow)} bill="${st.billAmount}"(期望 ${n(dto.billedAmountCents)}分) bizNo=${st.bizNo}(期望 ${dto.sessionId})`
    );
  }

  // —— TC-IMP-032：商户端已结案争议文案（断言只看抽屉内部，并绑定工单号） ——
  const mpage = await ctx.newPage();
  mpage.setDefaultTimeout(15000);
  await merchantLogin(mpage);
  const merchantResolvedId = await mpage.evaluate(async () => {
    try {
      // 商家 H5 走 HttpOnly Cookie 鉴权：localStorage 里只有 `merchant_cookie_auth='1'` 标记。
      // 把它当 JWT 拼 `Bearer 1` 会让服务端判非法令牌并短路 Cookie 鉴权 → 401 → 探测恒空。
      const token =
        localStorage.getItem('merchant_token') || sessionStorage.getItem('merchant_token') || '';
      const res = await fetch('/api/v2/merchant/disputes?page=0&size=50', {
        headers: token ? { Authorization: 'Bearer ' + token } : {},
        credentials: 'same-origin'
      });
      if (!res.ok) return '';
      const json = await res.json();
      const items = json?.data?.items || json?.data?.content || [];
      const hit = items.find((t) =>
        /^(RESOLVED|CLOSED)$/.test(String(t.status || '').toUpperCase())
      );
      return hit ? String(hit.ticketId) : '';
    } catch {
      return '';
    }
  });

  if (!merchantResolvedId) {
    record(
      'TC-IMP-032',
      '商户已结案争议文案',
      'SKIP',
      '本商家可见争议里没有 RESOLVED/CLOSED 工单（/api/v2/merchant/disputes 探测为空）→ 无已结案文案可验'
    );
  } else {
    await mpage.goto(
      `${MERCHANT}/pages/disputes/disputes?ticketId=${encodeURIComponent(merchantResolvedId)}`,
      { waitUntil: 'domcontentloaded' }
    );
    await mpage.waitForTimeout(1500);
    const deadline = Date.now() + 12000;
    let drawer = false;
    while (Date.now() < deadline) {
      drawer = await mpage.evaluate(() => !!document.querySelector('.app-sheet'));
      if (drawer) break;
      await mpage.waitForTimeout(400);
    }
    // 断言只取**抽屉内部**文本：`bodyText(page)` 会把抽屉背后的工单列表一起吃进来，
    // 而列表里「待审核」那条的合法文案正是"本次暂未扣款" ⇒ 旧写法恒为假（假红）。
    const sheetText = await mpage.evaluate(() => {
      const el = document.querySelector('.app-sheet');
      return el ? el.innerText || '' : '';
    });
    const ok =
      drawer &&
      sheetText.includes(merchantResolvedId) &&
      /已结案：/.test(sheetText) &&
      !/暂未扣款/.test(sheetText);
    await shot(mpage, 'imp032-detail');
    record(
      'TC-IMP-032',
      '商户已结案争议文案',
      ok ? 'PASS' : 'FAIL',
      ok
        ? `抽屉绑定工单 ${merchantResolvedId}，展示已结案摘要且无「暂未扣款」`
        : `drawer=${drawer} 含工单号=${sheetText.includes(merchantResolvedId)} sheet=${sheetText.split('\n').filter(Boolean).slice(0, 8).join(' | ')}`
    );
  }

  await browser.close();
  const pass = results.filter((r) => r.status === 'PASS').length;
  const fail = results.filter((r) => r.status === 'FAIL').length;
  const skip = results.length - pass - fail;
  console.log('\n=== IMP DISPUTE COPY UAT ===', { pass, fail, skip });
  // 🔴 `pass=0 且全 SKIP` ⇒ 退出码仍是 0（无 fail），CI 上是一个**绿色步骤** —— 读者会
  // 以为「争议文案被测过了」，实际**一条断言都没跑**。这是「信号在骗读者」，比红色更危险：
  // 红色会让人去查，绿色让人放心。
  // 无法把它变红（CI 库里确实没有终态争议，这是**事实**不是错误），但**必须让它可见**：
  // 下面这行是 GitHub Actions 的 workflow command，CI 会渲染成黄色告警 annotation；
  // 在本地跑时就只是一行普通输出，没有副作用。
  if (pass === 0 && fail === 0 && skip > 0) {
    console.log(
      `::warning::imp-dispute-copy UAT 本次 0 条断言（${skip} 条全部 SKIP：本账号名下没有终态争议工单）。"步骤绿" ≠ "争议文案被验证过" —— CI 缺争议种子链路，详见 .github/workflows/ci.yml 的 Three-end dispute UAT 步骤注释。`
    );
  }
  process.exit(fail > 0 ? 1 : 0);
}

main().catch((e) => {
  console.error(e);
  // exit 3 = **脚本自身崩溃**（未预期异常：选择器 typo、页面结构变更、空引用…），
  // 与 exit 2 = **环境未就绪**（消费者登录未落 token / /api/v2/disputes/mine 不可用）区分开。
  // 🔴 CI 只降级 exit 2；exit 3 必须让 job 变红 —— 否则降级会把脚本 bug 吞成假绿。
  process.exit(3);
});
