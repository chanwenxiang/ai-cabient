/**
 * Admin 全菜单 L1 轮转（MASTER §2.4）：可达/无白屏/无登录踢出/无乱码。
 * Usage:
 *   ADMIN_TOKEN=... node scripts/admin-menu-rotation.mjs
 *   or reads docs/uat-screenshots/2026-09-12/login-session.json
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const sessionPath = path.join(root, 'docs/uat-screenshots/2026-09-12/login-session.json');
const menuPath = path.join(root, 'docs/uat-screenshots/2026-09-12/menu-paths.json');
const outPath = path.join(root, 'docs/uat-screenshots/2026-09-12/admin-menu-rotation.json');

const session = JSON.parse(fs.readFileSync(sessionPath, 'utf8'));
const { menuPaths } = JSON.parse(fs.readFileSync(menuPath, 'utf8'));
const token = process.env.ADMIN_TOKEN || session.token;
if (!token) {
  console.error('missing ADMIN_TOKEN / login-session.json');
  process.exit(1);
}

const browser = await chromium.launch({ headless: true });
const context = await browser.newContext();
await context.addCookies([
  {
    name: 'aicabinet_admin_session',
    value: token,
    domain: 'localhost',
    path: '/',
    httpOnly: true,
    sameSite: 'Strict',
    expires: Math.floor(Date.now() / 1000) + 1700
  }
]);
const page = await context.newPage();
const consoleErrors = [];
page.on('pageerror', (e) => consoleErrors.push(String(e.message || e).slice(0, 120)));
page.on('console', (msg) => {
  if (msg.type() === 'error') consoleErrors.push(`console:${msg.text().slice(0, 100)}`);
});

await page.goto('http://localhost/admin/login', { waitUntil: 'domcontentloaded' });
await page.evaluate((t) => {
  localStorage.setItem('admin_cookie_auth', '1');
  localStorage.setItem('admin_userId', '100000001');
  localStorage.setItem('admin_token_expires', String(Date.now() + 1_700_000));
  localStorage.setItem('admin_token', t);
}, token);

const results = [];
for (const p of menuPaths) {
  const before = consoleErrors.length;
  try {
    await page.goto(`http://localhost/admin${p}`, { waitUntil: 'networkidle', timeout: 30000 });
  } catch {
    // interrupted navigations: still inspect final URL
  }
  await page.waitForTimeout(400);
  const info = await page.evaluate(() => {
    const text = (document.body?.innerText || '').replace(/\s+/g, ' ').trim();
    const title = document.title || '';
    return {
      url: location.pathname + location.search,
      title,
      textLen: text.length,
      snippet: text.slice(0, 60),
      isLogin: location.pathname.includes('/login'),
      is404: /页面不存在|404|Not Found/i.test(title + ' ' + text.slice(0, 120)),
      blank: text.length < 12,
      hasMojibake: /Ã.|Â./.test(text.slice(0, 200))
    };
  });
  const errs = consoleErrors
    .slice(before)
    .filter((e) => !/favicon|ResizeObserver|Download the Vue|Failed to load resource/.test(e));
  const ok = !info.isLogin && !info.is404 && !info.blank && !info.hasMojibake;
  results.push({ path: p, ok, ...info, consoleErrors: errs.slice(0, 3) });
}

const pass = results.filter((r) => r.ok).length;
const fail = results.filter((r) => !r.ok);
const report = {
  total: results.length,
  pass,
  failCount: fail.length,
  fail,
  results,
  at: new Date().toISOString()
};
fs.writeFileSync(outPath, JSON.stringify(report, null, 2));
console.log(
  JSON.stringify(
    { total: report.total, pass, failCount: fail.length, fails: fail.map((f) => f.path) },
    null,
    2
  )
);
await browser.close();
process.exit(fail.length ? 1 : 0);
