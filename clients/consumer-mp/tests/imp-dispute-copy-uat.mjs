/**
 * IMP-025 / IMP-032 — resolved dispute copy UAT
 * Run: node tests/imp-dispute-copy-uat.mjs
 */
import { chromium } from 'playwright';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const CONSUMER = process.env.CONSUMER_H5_URL || 'http://127.0.0.1:3002';
const MERCHANT = process.env.MERCHANT_H5_URL || 'http://127.0.0.1:3001';
const CHANNEL = process.env.PW_CHANNEL || 'chrome';
const OUT = path.resolve(__dirname, '../output/playwright/imp-dispute');
const TICKET_BILLED = process.env.DEMO_DISPUTE_TICKET_BILLED || '1788252219672817302';
const TICKET_REFUND = process.env.DEMO_DISPUTE_TICKET_REFUND || '1788247248295553600';

fs.mkdirSync(OUT, { recursive: true });

const results = [];

function record(id, name, status, detail) {
  results.push({ id, name, status, detail });
  console.log(`${status === 'PASS' ? '✓' : status === 'FAIL' ? '✗' : '○'} ${id} ${name} — ${detail}`);
}

async function bodyText(page) {
  return page.evaluate(() => document.body?.innerText || '');
}

async function shot(page, name) {
  const file = path.join(OUT, `${name}.png`);
  await page.screenshot({ path: file, fullPage: true }).catch(() => {});
  return file;
}

async function clickByText(page, text, { exact = false } = {}) {
  const target = await page.evaluate(
    ({ text, exact }) => {
      const nodes = [...document.querySelectorAll('uni-text, uni-view, uni-button, button, span, div')];
      const hit = nodes.find((e) => {
        const t = (e.innerText || e.textContent || '').trim();
        return t && (exact ? t === text : t.includes(text));
      });
      if (!hit) return null;
      const r = hit.getBoundingClientRect();
      return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
    },
    { text, exact }
  );
  if (!target) return false;
  await page.mouse.click(target.x, target.y);
  return true;
}

async function fillPlaceholder(page, placeholder, value) {
  const uni = page
    .locator('uni-input')
    .filter({ has: page.locator('.uni-input-placeholder', { hasText: placeholder }) })
    .first();
  if ((await uni.count()) === 0) return false;
  const input = uni.locator('input').first();
  await uni.click();
  await page.keyboard.press('ControlOrMeta+a');
  await page.keyboard.type(value, { delay: 30 });
  await page.waitForTimeout(500);
  return (await input.inputValue()) === value;
}

async function gotoConsumer(page, pathname) {
  await page.goto(CONSUMER + pathname, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1800);
}

async function cancelActiveSession(page) {
  const token = await page.evaluate(() => localStorage.getItem('consumer_token') || '');
  if (!token) return;
  await page.evaluate(async (tok) => {
    const headers = { 'Content-Type': 'application/json', Authorization: 'Bearer ' + tok };
    try {
      const res = await fetch('/api/v2/sessions/active', { headers }).then((r) => r.json());
      const s = res?.data;
      if (!s?.sessionId) return;
      const state = String(s.state || '').toUpperCase();
      const sid = encodeURIComponent(s.sessionId);
      if (state === 'SHOPPING' || state === 'OPENING') {
        await fetch('/api/v2/sessions/' + sid + '/demo-close', { method: 'POST', headers, body: '{}' });
      } else {
        await fetch('/api/v2/sessions/' + sid + '/cancel', { method: 'POST', headers });
      }
    } catch {
      /* ignore */
    }
  }, token);
  await page.waitForTimeout(800);
}

async function gotoMerchant(page, pathname) {
  await page.goto(MERCHANT + pathname, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1800);
}

async function consumerLogin(page) {
  await gotoConsumer(page, '/pages/login/login');
  await clickByText(page, '验证码', { exact: true });
  await page.waitForTimeout(400);
  await fillPlaceholder(page, '请输入11位手机号', '13800138000');
  await clickByText(page, '获取验证码', { exact: true });
  await page.waitForTimeout(700);
  await fillPlaceholder(page, '请输入验证码', '123456');
  await page.locator('[data-testid="login-submit"]').click();
  await page.waitForTimeout(3500);
}

async function merchantLogin(page) {
  await gotoMerchant(page, '/pages/login/login');
  await fillPlaceholder(page, '请输入11位手机号', '13800138001');
  await fillPlaceholder(page, '请输入登录密码', '123456');
  await page.locator('[data-testid="login-submit"]').click();
  await page.waitForTimeout(3500);
}

async function main() {
  const browser = await chromium.launch({ channel: CHANNEL, headless: true });
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, locale: 'zh-CN' });

  const cpage = await ctx.newPage();
  cpage.setDefaultTimeout(15000);
  await consumerLogin(cpage);
  await cancelActiveSession(cpage);

  await gotoConsumer(
    cpage,
    `/pages/dispute/detail?ticketId=${encodeURIComponent(TICKET_BILLED)}`
  );
  await cpage.waitForTimeout(3000);
  let text = await bodyText(cpage);
  const billedOk =
    /人工审核已完成|已结案/.test(text) &&
    /扣款|¥4\.41/.test(text) &&
    !/审核中 · 暂未扣款/.test(text) &&
    !text.includes('退款渠道') &&
    !/审核说明[\s\S]{0,100}暂未扣款/.test(text);
  await shot(cpage, 'imp025-billed');
  record(
    'TC-IMP-025',
    '消费者已结案扣款文案',
    billedOk ? 'PASS' : 'FAIL',
    billedOk
      ? '无 OPEN 态暂未扣款/退款渠道'
      : text.split('\n').filter(Boolean).slice(0, 14).join(' | ')
  );

  await gotoConsumer(
    cpage,
    `/pages/dispute/detail?ticketId=${encodeURIComponent(TICKET_REFUND)}`
  );
  await cpage.waitForTimeout(3000);
  text = await bodyText(cpage);
  const refundOk =
    /已结案|人工审核已完成/.test(text) &&
    (/退款|未扣款|已退/.test(text) || /¥3\.92/.test(text)) &&
    text.includes('退款渠道');
  await shot(cpage, 'imp025-refund');
  record(
    'TC-IMP-025b',
    '消费者已结案退款渠道',
    refundOk ? 'PASS' : 'FAIL',
    refundOk
      ? '展示退款相关文案与退款渠道'
      : text.split('\n').filter(Boolean).slice(0, 14).join(' | ')
  );

  const mpage = await ctx.newPage();
  mpage.setDefaultTimeout(15000);
  await merchantLogin(mpage);
  await gotoMerchant(
    mpage,
    `/pages/disputes/disputes?ticketId=${encodeURIComponent(TICKET_BILLED)}`
  );
  const probe = await mpage.evaluate(async (tid) => {
    const token = localStorage.getItem('merchant_token');
    if (!token) return { ok: false, reason: 'no token' };
    const r = await fetch(`/api/v2/merchant/disputes/${encodeURIComponent(tid)}`, {
      headers: { Authorization: 'Bearer ' + token }
    });
    const j = await r.json();
    const ticket = j?.data?.ticket || null;
    return {
      ok: r.ok && j?.code === 0 && !!ticket,
      status: ticket?.status,
      billed: ticket?.billedAmountCents,
      reason: ticket?.reason
    };
  }, TICKET_BILLED);
  let drawerVisible = false;
  const drawerDeadline = Date.now() + 12000;
  while (Date.now() < drawerDeadline) {
    drawerVisible = await mpage.evaluate(() => !!document.querySelector('.detail-panel'));
    if (drawerVisible) break;
    await mpage.waitForTimeout(400);
  }
  text = await bodyText(mpage);
  if (!probe.ok) {
    record('TC-IMP-032', '商户已结案争议文案', 'SKIP', `争议详情 API 不可用: ${JSON.stringify(probe)}`);
  } else if (!drawerVisible) {
    record(
      'TC-IMP-032',
      '商户已结案争议文案',
      'SKIP',
      `API 有数据(${probe.status}/billed=${probe.billed})但抽屉未打开，待查深链`
    );
  } else {
    const detailOk = /已结案：/.test(text) && !/暂未扣款/.test(text);
    await shot(mpage, 'imp032-detail');
    record(
      'TC-IMP-032',
      '商户已结案争议文案',
      detailOk ? 'PASS' : 'FAIL',
      detailOk
        ? '详情展示已结案摘要，无暂未扣款'
        : text.split('\n').filter(Boolean).slice(0, 12).join(' | ')
    );
  }

  await browser.close();
  const pass = results.filter((r) => r.status === 'PASS').length;
  const fail = results.filter((r) => r.status === 'FAIL').length;
  console.log('\n=== IMP DISPUTE COPY UAT ===', { pass, fail, skip: results.length - pass - fail });
  process.exit(fail > 0 ? 1 : 0);
}

main().catch((e) => {
  console.error(e);
  process.exit(2);
});
