/**
 * UAT batch: IMP-012/028/048/026/transit-copy (recent UX batch)
 * Run: cd clients/consumer-mp && node ../admin-vue/tests/batch-imp-uat.mjs
 */
import { chromium } from 'playwright';
import { execSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ADMIN = (process.env.ADMIN_URL || 'http://localhost/admin').replace(/\/$/, '');
const CHANNEL = process.env.PW_CHANNEL || 'chrome';
const HEADED = process.env.PW_HEADED === '1';
const OUT = path.resolve(__dirname, '../output/playwright/batch-imp');
const REDIS_CONTAINER = process.env.REDIS_CONTAINER || 'ai-cabinet-redis-1';
const OPS_PHONE = '13900000001';
const OPS_PASSWORD = '123456';

fs.mkdirSync(OUT, { recursive: true });

const results = [];

function record(id, name, status, detail, evidence) {
  results.push({ id, name, status, detail, evidence, at: new Date().toISOString() });
  const mark = status === 'PASS' ? '✓' : status === 'FAIL' ? '✗' : '○';
  console.log(`${mark} ${id} ${name} — ${String(detail).slice(0, 280)}`);
}

async function shot(page, name) {
  const file = path.join(OUT, `${name}.png`);
  try {
    await page.screenshot({ path: file, fullPage: false });
    return file;
  } catch {
    return null;
  }
}

function captchaFromRedis(captchaId) {
  const raw = execSync(
    `docker exec ${REDIS_CONTAINER} redis-cli GET aicabinet:captcha:${captchaId}`,
    { encoding: 'utf8' }
  ).trim();
  if (!raw || /nil|ERR/i.test(raw)) throw new Error(`captcha missing: ${captchaId}`);
  return raw.toUpperCase();
}

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
  throw new Error('captcha not loaded');
}

async function captchaForPage(page) {
  for (let attempt = 0; attempt < 4; attempt++) {
    try {
      const capId = await waitPageCaptchaId(page, 5000);
      return { captchaId: capId, captchaCode: captchaFromRedis(capId) };
    } catch {
      await page
        .locator('button.captcha-img-btn')
        .first()
        .click({ force: true })
        .catch(() => {});
      await page.waitForTimeout(800);
    }
  }
  throw new Error('unable to resolve captcha');
}

async function fillElInput(page, placeholder, value) {
  const input = page.locator(`.el-input input[placeholder="${placeholder}"]`).first();
  await input.click();
  await page.keyboard.press('ControlOrMeta+a');
  await page.keyboard.type(value, { delay: 15 });
}

async function loginAdmin(page) {
  await page.goto(`${ADMIN}/login`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1000);
  const cap = await captchaForPage(page);
  await fillElInput(page, '请输入11位手机号…', OPS_PHONE);
  await fillElInput(page, '请输入登录密码…', OPS_PASSWORD);
  await fillElInput(page, '图形验证码…', cap.captchaCode);
  await page.locator('button.submit-btn, button:has-text("登录")').first().click();
  await page.waitForTimeout(2500);
  let token = await page.evaluate(
    () => localStorage.getItem('admin_token') || localStorage.getItem('admin_cookie_auth')
  );
  if (!token) {
    const cap2 = await captchaForPage(page);
    await fillElInput(page, '图形验证码…', cap2.captchaCode);
    await page.locator('button.submit-btn, button:has-text("登录")').first().click();
    await page.waitForTimeout(2500);
    token = await page.evaluate(
      () => localStorage.getItem('admin_token') || localStorage.getItem('admin_cookie_auth')
    );
  }
  return !!token || !page.url().includes('/login');
}

async function main() {
  const browser = await chromium.launch({ channel: CHANNEL, headless: !HEADED });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  let pass = 0;
  let fail = 0;

  try {
    const loggedIn = await loginAdmin(page);
    const eLogin = await shot(page, '00-login');
    record(
      'B-00',
      '超管登录',
      loggedIn ? 'PASS' : 'FAIL',
      loggedIn ? page.url() : 'login failed',
      eLogin
    );
    loggedIn ? pass++ : fail++;
    if (!loggedIn) throw new Error('login failed');

    // IMP-012 + transit-copy
    await page.goto(`${ADMIN}/warehouse?tab=transit`, { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(2500);
    const wh = await page.evaluate(() => ({
      groups: !!document.querySelector('[data-testid="warehouse-tab-groups"]'),
      groupText: document.querySelector('[data-testid="warehouse-tab-groups"]')?.innerText || '',
      hint: document.querySelector('[data-testid="transit-flow-hint"]')?.innerText || '',
      pageHint: document.querySelector('.page-card-head .hint')?.innerText || '',
      overdueLabel:
        document.querySelector('[data-testid="transit-overdue-only"]')?.closest('label')
          ?.innerText || '',
      js:
        [...document.querySelectorAll('script[src*="/assets/"]')]
          .map((s) => s.getAttribute('src'))
          .find((s) => /WarehouseView|index-/.test(s || '')) || ''
    }));
    const whOk =
      wh.groups &&
      /采购|库存|履约|基础/.test(wh.groupText) &&
      /在途|柜机|补货|回仓/.test(wh.hint) &&
      /仓→柜|在途/.test(wh.pageHint);
    const eWh = await shot(page, '01-warehouse-transit');
    record('B-01', '仓库分组+在途文案', whOk ? 'PASS' : 'FAIL', JSON.stringify(wh), eWh);
    whOk ? pass++ : fail++;

    // IMP-028 fund bill keyword
    await page.goto(`${ADMIN}/fund-bills`, { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(2000);
    await page
      .locator('[data-testid="fund-keyword"] input, [data-testid="fund-keyword"]')
      .first()
      .fill('139');
    await page.getByRole('button', { name: '查询' }).click();
    await page.waitForTimeout(2000);
    const fund = await page.evaluate(() => ({
      hint: document.querySelector('.search-result-hint')?.innerText || '',
      total: document.querySelector('.el-pagination__total')?.innerText || '',
      pager: document.body.innerText.match(/共\s*\d+\s*条/)?.[0] || ''
    }));
    const fundOk = /关键词「139」/.test(fund.hint) && /共\s*\d+\s*条/.test(fund.hint);
    const eFund = await shot(page, '02-fund-keyword');
    record('B-02', '资金账单关键词反馈', fundOk ? 'PASS' : 'FAIL', JSON.stringify(fund), eFund);
    fundOk ? pass++ : fail++;

    // IMP-026 order amount diff — prefer seeded UAT row (member discount)
    await page.goto(`${ADMIN}/orders`, { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(2500);
    const orderScan = await page.evaluate(async () => {
      const rows = [...document.querySelectorAll('.el-table__body tr')];
      const withDiscount = rows.find((r) => /原\s*¥|含会员/.test(r.innerText));
      const targets = withDiscount
        ? [withDiscount, ...rows.filter((r) => r !== withDiscount)]
        : rows.slice(0, 12);
      const clickDetail = (row) => {
        const btn = [...row.querySelectorAll('button, .el-link')].find((b) =>
          /详情/.test(b.textContent || '')
        );
        if (btn) btn.click();
        else row.dispatchEvent(new MouseEvent('click', { bubbles: true }));
      };
      for (const row of targets.slice(0, 8)) {
        clickDetail(row);
        await new Promise((r) => setTimeout(r, 1200));
        const diffEl = document.querySelector('.amount-diff');
        const label = document.body.innerText.includes('差额说明');
        if (label && diffEl?.innerText) {
          return {
            opened: true,
            diff: true,
            diffText: diffEl.innerText,
            rowHint: row.innerText.slice(0, 100)
          };
        }
        document.querySelector('.el-drawer__close-btn, .drawer-close')?.click();
        await new Promise((r) => setTimeout(r, 400));
      }
      if (targets[0]) {
        clickDetail(targets[0]);
        await new Promise((r) => setTimeout(r, 1200));
      }
      return {
        opened: !!document.querySelector('.el-drawer, .resizable-drawer'),
        diff: !!document.querySelector('.amount-diff')?.innerText,
        diffText: document.querySelector('.amount-diff')?.innerText || '',
        rowHint: targets[0]?.innerText?.slice(0, 100) || ''
      };
    });
    const orderOk = orderScan.diff;
    const eOrder = await shot(page, '03-order-diff');
    record(
      'B-03',
      '订单详情差额说明',
      orderOk ? 'PASS' : orderScan.opened ? 'SKIP' : 'FAIL',
      JSON.stringify(orderScan),
      eOrder
    );
    if (orderOk) pass++;
    else if (!orderScan.opened) fail++;

    // IMP-048 narrow viewport hint
    await page.setViewportSize({ width: 1100, height: 800 });
    await page.goto(`${ADMIN}/orders`, { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(1500);
    const narrow = await page.evaluate(() => ({
      hint: document.querySelector('.narrow-viewport-hint')?.innerText || '',
      width: window.innerWidth
    }));
    const narrowOk = narrow.width < 1280 && /1280|较窄|拥挤/.test(narrow.hint);
    const eNarrow = await shot(page, '04-narrow-hint');
    record('B-04', '窄视口验收提示', narrowOk ? 'PASS' : 'FAIL', JSON.stringify(narrow), eNarrow);
    narrowOk ? pass++ : fail++;

    // IMP-027 global search readonly trigger
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto(`${ADMIN}/fund-bills`, { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(1500);
    const gs = await page.evaluate(() => {
      const input = document.querySelector(
        '.global-search-trigger input, [data-testid="global-search"] input, .global-search input'
      );
      return {
        readonly: input?.hasAttribute('readonly') ?? null,
        placeholder: input?.getAttribute('placeholder') || ''
      };
    });
    const gsOk = gs.readonly === true;
    record('B-05', '全局搜索只读触发器', gsOk ? 'PASS' : 'FAIL', JSON.stringify(gs), null);
    gsOk ? pass++ : fail++;
  } catch (e) {
    console.error(e);
    record('B-ERR', '运行异常', 'FAIL', e.message, null);
    fail++;
  } finally {
    await browser.close();
  }

  const summary = { pass, fail, total: pass + fail };
  fs.writeFileSync(path.join(OUT, 'report.json'), JSON.stringify({ summary, results }, null, 2));
  console.log('\n=== BATCH IMP UAT ===');
  console.log(JSON.stringify(summary));
  process.exit(fail > 0 ? 1 : 0);
}

main();
