/**
 * 运营台「告警渠道」页面实机检查（真实浏览器，非 HTTP 层判据）。
 *
 * 为什么必须用真浏览器：告警渠道（尤其飞书）配错时平台**仍返回 HTTP 200**，只在响应体里带
 * 业务码。仅凭接口判据会得到「保存成功 = 收得到」的假绿。本脚本登录运营台、渲染 /alert-rules、
 * 点「测试发送」，把**每个渠道的真实投递结果**（含平台业务码）取回来，并存证截图。
 *
 * Run: node clients/admin-vue/tests/admin-alert-channel-uat.mjs
 * Env: ADMIN_URL / PW_CHANNEL / PW_HEADED / REDIS_CONTAINER
 *
 * ⚠️ Webhook URL 里的 token 是唯一凭据，脚本对**所有**输出的 URL 做脱敏后才落盘。
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
const OUT = path.resolve(__dirname, '../output/playwright/alert-channel');
const OPS_PHONE = '13900000001';
const OPS_PASSWORD = '123456';
const REDIS_CONTAINER = process.env.REDIS_CONTAINER || 'ai-cabinet-redis-1';

fs.mkdirSync(OUT, { recursive: true });

const results = [];

function record(id, name, status, detail, evidence = null) {
  results.push({ id, name, status, detail, evidence, at: new Date().toISOString() });
  const mark = status === 'PASS' ? '✓' : status === 'FAIL' ? '✗' : '○';
  console.log(`${mark} ${id} ${name} — ${String(detail).slice(0, 220)}`);
}

/** URL 里的 token 是唯一凭据：任何落盘输出先脱敏。 */
function maskSecrets(s) {
  return String(s ?? '')
    .replace(/(\/hook\/)[A-Za-z0-9_-]{6,}/g, '$1***')
    .replace(/(access_token=)[A-Za-z0-9]{6,}/g, '$1***')
    .replace(/([?&]key=)[A-Za-z0-9_-]{6,}/g, '$1***');
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

/**
 * 视口截图（非整页）。
 * ⚠️ 模态框是 fixed 定位，`fullPage: true` 会把它渲染到视口之外而**拍不到** ——
 * 于是「弹窗证据」变成一张没有弹窗的图，等于没有证据。
 */
async function shotViewport(page, name) {
  const file = path.join(OUT, `${name}.png`);
  try {
    await page.screenshot({ path: file, fullPage: false });
    return file;
  } catch {
    return null;
  }
}

async function bodyText(page) {
  return page.evaluate(() => document.body?.innerText || '');
}

// ---------- 登录（图形码从 redis 取，与 admin-uat.mjs 同法） ----------

function captchaFromRedis(captchaId) {
  const raw = execSync(
    `docker exec ${REDIS_CONTAINER} redis-cli GET aicabinet:captcha:${captchaId}`,
    { encoding: 'utf8' }
  ).trim();
  if (!raw || /nil|ERR/i.test(raw)) throw new Error(`captcha missing in redis: ${captchaId}`);
  return raw.toUpperCase();
}

async function captchaForPage(page) {
  for (let attempt = 0; attempt < 4; attempt++) {
    try {
      const id = await page
        .locator('button.captcha-img-btn[data-captcha-id]')
        .first()
        .getAttribute('data-captcha-id', { timeout: 5000 });
      if (id) return captchaFromRedis(id);
    } catch {
      /* 图形码偶发未写入 redis：刷新后重试 */
    }
    try {
      await page.locator('button.captcha-img-btn').first().click({ timeout: 3000, force: true });
      await page.waitForTimeout(800);
    } catch {
      await page.reload({ waitUntil: 'domcontentloaded' });
      await page.waitForTimeout(1200);
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
  for (let attempt = 0; attempt < 3; attempt++) {
    const code = await captchaForPage(page);
    await fillElInput(page, '请输入11位手机号…', OPS_PHONE);
    await fillElInput(page, '请输入登录密码…', OPS_PASSWORD);
    await fillElInput(page, '图形验证码…', code);
    await page.locator('button.submit-btn, button:has-text("登录")').first().click();
    await page.waitForTimeout(2500);
    const token = await page.evaluate(
      () =>
        sessionStorage.getItem('admin_token') ||
        localStorage.getItem('admin_cookie_auth') ||
        localStorage.getItem('admin_token') ||
        ''
    );
    if (token) return token;
  }
  throw new Error('admin login failed after 3 attempts');
}

// ---------- 主流程 ----------

async function main() {
  const browser = await chromium.launch({ channel: CHANNEL, headless: !HEADED });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' });
  const page = await ctx.newPage();
  page.setDefaultTimeout(20000);

  const consoleErrors = [];
  page.on('console', (m) => {
    if (m.type() === 'error') consoleErrors.push(m.text());
  });

  try {
    await loginAdmin(page);
    record('AC-01', '登录运营台', 'PASS', 'token 已取得');

    await page.goto(`${ADMIN}/alert-rules`, { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(2500);
    let text = await bodyText(page);
    const shotList = await shot(page, 'alert-rules-list');

    const hasPage = /告警规则/.test(text);
    record(
      'AC-02',
      '告警规则页渲染',
      hasPage ? 'PASS' : 'FAIL',
      text.split('\n').slice(0, 10).join(' | '),
      shotList
    );

    const hasTestBtn = (await page.locator('button:has-text("测试发送")').count()) > 0;
    record(
      'AC-03',
      '「测试发送」按钮存在（= 有编辑权限）',
      hasTestBtn ? 'PASS' : 'FAIL',
      `count=${await page.locator('button:has-text("测试发送")').count()}`
    );

    // 告警渠道分组与飞书配置键是否在列表里
    const hasFeishuKey = text.includes('ops.alert.feishu_webhook');
    record(
      'AC-04',
      '飞书 Webhook 配置键在列表中',
      hasFeishuKey ? 'PASS' : 'FAIL',
      hasFeishuKey ? '找到 ops.alert.feishu_webhook' : '未在首屏列表中找到（可能需翻页/搜索）'
    );

    // 点「测试发送」——这一步会真的向已配置渠道投递一条消息
    if (hasTestBtn) {
      await page.locator('button:has-text("测试发送")').first().click();
      let dialog = null;
      try {
        await page.waitForSelector('.el-message-box', { timeout: 30000 });
        const title = await page.locator('.el-message-box__title').first().innerText();
        const body = await page.locator('.el-message-box__message').first().innerText();
        dialog = { title: title.trim(), body: body.trim() };
      } catch (e) {
        dialog = { title: '(no dialog)', body: `wait failed: ${e.message}` };
      }
      const shotDialog = await shotViewport(page, 'alert-channel-test-send');

      const safeTitle = maskSecrets(dialog.title);
      const safeBody = maskSecrets(dialog.body);
      const okTitle = /全部成功/.test(safeTitle);
      const hasFeishu = /FEISHU/.test(safeBody);
      record(
        'AC-05',
        '「测试发送」返回真实投递结果',
        okTitle && hasFeishu ? 'PASS' : 'FAIL',
        `${safeTitle} :: ${safeBody.replace(/\s+/g, ' ')}`,
        shotDialog
      );

      // 关掉弹窗
      try {
        await page
          .locator('.el-message-box__btns button, .el-button--primary:has-text("知道了")')
          .first()
          .click({ timeout: 5000 });
      } catch {
        await page.keyboard.press('Escape');
      }
    } else {
      record('AC-05', '「测试发送」返回真实投递结果', 'FAIL', '按钮不存在，跳过');
    }

    const blocking = consoleErrors.filter((e) => !/favicon|ResizeObserver/i.test(e));
    record(
      'AC-06',
      '控制台无阻断性错误',
      blocking.length === 0 ? 'PASS' : 'FAIL',
      blocking.slice(0, 3).join(' || ') || 'clean'
    );

    const failed = results.filter((r) => r.status === 'FAIL').length;
    const summary = {
      admin: ADMIN,
      at: new Date().toISOString(),
      total: results.length,
      failed,
      results
    };
    fs.writeFileSync(path.join(OUT, 'summary.json'), JSON.stringify(summary, null, 2), 'utf8');
    console.log(
      `\n${results.length - failed}/${results.length} PASS  → ${path.join(OUT, 'summary.json')}`
    );
    return failed === 0 ? 0 : 1;
  } finally {
    await browser.close();
  }
}

main()
  .then((code) => process.exit(code))
  .catch((e) => {
    console.error('FATAL:', maskSecrets(e?.stack || e?.message || e));
    process.exit(2);
  });
