import { chromium } from 'playwright';
import { execSync } from 'child_process';
import fs from 'fs';
import path from 'path';

const outDir = path.resolve('docs/uat-screenshots/2026-08-05');
fs.mkdirSync(outDir, { recursive: true });

function redis(cmd) {
  return execSync(`docker exec ai-cabinet-redis-1 redis-cli ${cmd}`, { encoding: 'utf8' }).trim();
}

const browser = await chromium.launch({
  headless: true,
  channel: 'chrome'
});
const page = await browser.newPage();
const apiErrors = [];

page.on('response', async (res) => {
  if (!res.url().includes('/api/')) return;
  if (res.status() < 400) return;
  let body = '';
  try {
    body = (await res.text()).slice(0, 240);
  } catch {
    /* ignore */
  }
  apiErrors.push({ status: res.status(), url: res.url(), body });
});

await page.goto('http://localhost/admin/login', { waitUntil: 'domcontentloaded', timeout: 60000 });
await page.waitForTimeout(1200);

const keys = redis('KEYS aicabinet:captcha:*')
  .split(/\r?\n/)
  .map((s) => s.trim())
  .filter(Boolean);
if (!keys.length) throw new Error('no captcha in redis');
const key = keys[keys.length - 1];
const code = redis(`GET ${key}`);

await page.locator('input').nth(0).fill('13900000001');
await page.locator('input[type="password"]').fill('123456');
const inputs = page.locator('input:visible');
const n = await inputs.count();
for (let i = 0; i < n; i++) {
  const ph = (await inputs.nth(i).getAttribute('placeholder')) || '';
  if (/验证码|captcha/i.test(ph)) {
    await inputs.nth(i).fill(code);
    break;
  }
}
await page.getByRole('button', { name: /登录/ }).click();
await page.waitForTimeout(3000);
console.log('afterLogin', page.url());

async function visit(routePath, name) {
  apiErrors.length = 0;
  await page.goto(`http://localhost/admin${routePath}`, {
    waitUntil: 'domcontentloaded',
    timeout: 60000
  });
  await page.waitForTimeout(3000);
  const bodyText = await page.locator('body').innerText();
  const hasBusy = /系统繁忙|请求失败 \(500\)|NoSuchMethodError/.test(bodyText);
  let category = null;
  if (name === 'skus') {
    const item = page.locator('.el-form-item').filter({ hasText: /^类目$/ }).first();
    const select = item.locator('.el-select').first();
    await select.waitFor({ state: 'visible', timeout: 15000 });
    const input = select.locator('input').first();
    const inputCount = await select.locator('input').count();
    const readonly = inputCount ? await input.getAttribute('readonly') : null;
    await select.click();
    await page.waitForTimeout(500);
    const options = await page.locator('.el-select-dropdown:visible .el-option').allTextContents();
    // try typing — should not freely create values when filterable is off
    let typedAccepted = false;
    if (inputCount) {
      await input.fill('随便输入的类目XYZ');
      await page.waitForTimeout(300);
      const val = await input.inputValue().catch(() => '');
      typedAccepted = val.includes('随便输入');
    }
    await page.keyboard.press('Escape');
    category = { inputCount, readonly, typedAccepted, options: options.slice(0, 10) };
  }
  const shot = path.join(outDir, `${name}.png`);
  await page.screenshot({ path: shot, fullPage: true });
  const result = {
    name,
    url: page.url(),
    hasBusy,
    apiErrors: [...apiErrors],
    category,
    shot
  };
  console.log(JSON.stringify(result, null, 2));
  return result;
}

const results = [];
results.push(await visit('/orders', 'orders'));
results.push(await visit('/skus', 'skus'));
results.push(await visit('/sku-vision', 'sku-vision'));

await browser.close();

const failed = results.filter((r) => r.hasBusy || r.apiErrors.some((e) => e.status >= 500));
if (failed.length) {
  console.error('SMOKE_FAILED', failed.map((f) => f.name));
  process.exit(1);
}
console.log('SMOKE_OK');
