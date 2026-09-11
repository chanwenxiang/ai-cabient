/**
 * 管理后台 a11y 冒烟（Playwright + API mock，无需后端）。
 * 检查：跳过链接、主内容锚点、深色对比度 token、登录表单字段、工作台可聚焦 KPI。
 *
 * Usage:
 *   ADMIN_BASE=http://127.0.0.1:3000/admin/ node scripts/smoke-admin-a11y.mjs
 */
import { spawn } from 'node:child_process';
import { setTimeout as sleep } from 'node:timers/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const ADMIN_BASE = (process.env.ADMIN_BASE || 'http://127.0.0.1:3000/admin/').replace(/\/?$/, '/');

function ok(data) {
  return {
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ code: 0, message: 'ok', data })
  };
}

function relativeLuminance(hex) {
  const h = hex.replace('#', '').trim();
  const full =
    h.length === 3
      ? h
          .split('')
          .map((c) => c + c)
          .join('')
      : h;
  const n = Number.parseInt(full, 16);
  const rgb = [(n >> 16) & 255, (n >> 8) & 255, n & 255].map((v) => {
    const s = v / 255;
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2];
}

function contrastRatio(fg, bg) {
  const L1 = relativeLuminance(fg);
  const L2 = relativeLuminance(bg);
  const hi = Math.max(L1, L2);
  const lo = Math.min(L1, L2);
  return (hi + 0.05) / (lo + 0.05);
}

async function waitHttpOk(url, attempts = 60) {
  for (let i = 0; i < attempts; i++) {
    try {
      const res = await fetch(url, { redirect: 'manual' });
      if (res.status > 0 && res.status < 500) return;
    } catch {
      /* retry */
    }
    await sleep(500);
  }
  throw new Error(`服务未就绪: ${url}`);
}

async function ensureAdminDev() {
  if (process.env.ADMIN_BASE) {
    await waitHttpOk(ADMIN_BASE);
    return null;
  }
  try {
    const res = await fetch(ADMIN_BASE, { redirect: 'manual' });
    if (res.status > 0 && res.status < 500) return null;
  } catch {
    /* start */
  }
  const child = spawn(
    process.platform === 'win32' ? 'pnpm.cmd' : 'pnpm',
    ['exec', 'vite', '--host', '127.0.0.1', '--port', '3000'],
    { cwd: path.join(root, 'clients', 'admin-vue'), stdio: 'ignore', shell: true }
  );
  await waitHttpOk(ADMIN_BASE);
  return child;
}

async function main() {
  let vite = null;
  try {
    vite = await ensureAdminDev();
    const browser = await chromium.launch({ headless: process.env.HEADED !== '1' });
    const page = await browser.newPage();

    await page.route('**/api/v2/**', async (route) => {
      const p = new URL(route.request().url()).pathname;
      if (p.includes('/rbac/me/permissions')) {
        return route.fulfill(
          ok([
            'ops:dashboard:view',
            'ops:order:list',
            'ops:device:list',
            'ops:finance:view',
            'ops:exception:list'
          ])
        );
      }
      if (p.includes('/rbac/me/nav')) {
        return route.fulfill(
          ok([
            'ops:dashboard:view',
            'ops:order:list',
            'ops:device:list',
            'ops:finance:view',
            'ops:exception:list'
          ])
        );
      }
      if (p.endsWith('/rbac/me')) {
        return route.fulfill(
          ok({
            userId: 1,
            phoneNumber: '13900000001',
            name: 'a11y冒烟',
            roleNames: ['ADMIN'],
            permissionCount: 5,
            globalDataScope: true
          })
        );
      }
      if (p.includes('/dicts/runtime')) return route.fulfill(ok({}));
      if (p.includes('/auth/refresh')) {
        return route.fulfill(ok({ token: 'smoke-token', userId: '1', expiresInSeconds: 3600 }));
      }
      if (p.includes('/workbench-bundle') || p.includes('/workbench') || p.includes('/stats')) {
        return route.fulfill(
          ok({
            devicesOnSale: 12,
            devicesSalesLocked: 1,
            offlineDevices: 2,
            deviceOnline: 10,
            deviceTotal: 12,
            revenueTodayCents: 12800,
            openExceptionCount: 3,
            workbench: {
              devicesOnSale: 12,
              devicesSalesLocked: 1,
              offlineDevices: 2
            },
            stats: {
              deviceOnline: 10,
              deviceTotal: 12,
              revenueTodayCents: 12800
            }
          })
        );
      }
      if (p.includes('/exceptions')) {
        return route.fulfill(ok({ items: [], total: 3 }));
      }
      if (p.includes('/merchant-onboarding')) {
        return route.fulfill(ok({ items: [], total: 0 }));
      }
      return route.fulfill(ok({}));
    });

    // --- 登录页 ---
    await page.goto(`${ADMIN_BASE}login`, { waitUntil: 'domcontentloaded' });
    const skip = page.locator('a.skip-link');
    await skip.focus();
    if (!(await skip.isVisible())) throw new Error('跳过链接聚焦后应可见');
    const skipHref = await skip.getAttribute('href');
    if (skipHref !== '#main-content')
      throw new Error(`跳过链接 href 应为 #main-content，实际 ${skipHref}`);

    const phone = page.locator('input[autocomplete="tel"], input[type="tel"]').first();
    await phone.waitFor({ timeout: 10_000 });
    const spell = await phone.getAttribute('spellcheck');
    if (spell !== 'false') throw new Error('登录手机号应 spellcheck=false');

    // --- 鉴权后工作台 ---
    await page.addInitScript(() => {
      localStorage.setItem('admin_token', 'smoke-token');
      localStorage.setItem('admin_userId', '1');
      localStorage.setItem('admin_token_expires', String(Date.now() + 3_600_000));
    });
    await page.goto(`${ADMIN_BASE}dashboard`, { waitUntil: 'domcontentloaded' });
    await page.locator('#main-content').waitFor({ timeout: 15_000 });

    documentTheme: {
      await page.evaluate(() => document.documentElement.setAttribute('data-theme', 'dark'));
      const tokens = await page.evaluate(() => {
        const s = getComputedStyle(document.documentElement);
        return {
          text: s.getPropertyValue('--layout-text').trim() || '#e7ecf3',
          muted: s.getPropertyValue('--layout-muted').trim(),
          card: s.getPropertyValue('--layout-card').trim(),
          regular: s.getPropertyValue('--el-text-color-regular').trim()
        };
      });
      const mutedOnCard = contrastRatio(tokens.muted, tokens.card);
      const regularOnCard = contrastRatio(tokens.regular || tokens.text, tokens.card);
      if (mutedOnCard < 4.5) {
        throw new Error(
          `深色 muted/card 对比度 ${mutedOnCard.toFixed(2)} < 4.5（${tokens.muted} on ${tokens.card}）`
        );
      }
      if (regularOnCard < 4.5) {
        throw new Error(
          `深色 regular/card 对比度 ${regularOnCard.toFixed(2)} < 4.5（${tokens.regular} on ${tokens.card}）`
        );
      }
      console.log(
        `  dark contrast muted=${mutedOnCard.toFixed(2)} regular=${regularOnCard.toFixed(2)}`
      );
    }

    const kpi = page.locator('.stat-tile[role="button"]').first();
    if ((await kpi.count()) > 0) {
      await kpi.focus();
      const label = await kpi.getAttribute('aria-label');
      if (!label) throw new Error('可点击 KPI 缺少 aria-label');
    }

    const search = page.getByRole('button', { name: '全局搜索' });
    await search.waitFor({ timeout: 10_000 });

    console.log('PASS smoke-admin-a11y');
    await browser.close();
  } finally {
    if (vite && !vite.killed) {
      try {
        vite.kill('SIGTERM');
      } catch {
        /* ignore */
      }
    }
  }
}

main().catch((err) => {
  console.error('FAIL smoke-admin-a11y:', err instanceof Error ? err.message : err);
  process.exit(1);
});
