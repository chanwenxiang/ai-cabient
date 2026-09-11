/**
 * 管理后台订单列表翻页竞态冒烟（Playwright + API mock，无需后端/容器）。
 *
 * 场景：第 0 页响应故意变慢；连点翻到第 1 页后，断言表格只保留新页数据（旧页不得回写）。
 *
 * Usage:
 *   ADMIN_BASE=http://127.0.0.1:3000/admin/ node scripts/smoke-admin-list-race.mjs
 *   HEADED=1 …（有头浏览器）
 *
 * 会自动尝试启动 Vite dev（若 ADMIN_BASE 未指向已运行实例）。
 */
import { spawn } from 'node:child_process';
import { setTimeout as sleep } from 'node:timers/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');

const ADMIN_BASE = (process.env.ADMIN_BASE || 'http://127.0.0.1:3000/admin/').replace(/\/?$/, '/');
const HEADED = process.env.HEADED === '1';
const MARKER_OLD = 'SMOKE-ORDER-PAGE-OLD';
const MARKER_NEW = 'SMOKE-ORDER-PAGE-NEW';

function ok(data) {
  return {
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ code: 0, message: 'ok', data })
  };
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
  // 勿用 pnpm --filter … dev（Cursor/Windows 下偶发 exit 2）；直接在包目录起 Vite
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
    const browser = await chromium.launch({ headless: !HEADED });
    const page = await browser.newPage();

    const orderHits = [];

    await page.route('**/api/v2/**', async (route) => {
      const req = route.request();
      const u = new URL(req.url());
      const p = u.pathname;

      if (p.includes('/rbac/me/permissions')) {
        return route.fulfill(ok(['ops:order:list', 'ops:dashboard:view']));
      }
      if (p.includes('/rbac/me/nav')) {
        return route.fulfill(ok(['ops:order:list', 'ops:dashboard:view']));
      }
      if (p.endsWith('/rbac/me') || p.includes('/rbac/me?')) {
        return route.fulfill(
          ok({
            userId: 1,
            phoneNumber: '13900000001',
            name: '竞态冒烟',
            roleNames: ['ADMIN'],
            permissionCount: 2,
            globalDataScope: true
          })
        );
      }
      if (p.includes('/dicts/runtime')) {
        return route.fulfill(ok({}));
      }
      if (p.includes('/auth/refresh')) {
        return route.fulfill(ok({ token: 'smoke-token', userId: '1', expiresInSeconds: 3600 }));
      }

      const isOrderList = p === '/api/v2/ops/admin/orders' || p.endsWith('/ops/admin/orders');

      if (isOrderList && req.method() === 'GET') {
        const pageNum = Number(u.searchParams.get('page') || '0');
        orderHits.push(pageNum);
        // 旧页更慢：若无 loadSeq，后返回的 page0 会盖住 page1
        const delayMs = pageNum === 0 ? 900 : 80;
        await sleep(delayMs);
        const marker = pageNum === 0 ? MARKER_OLD : MARKER_NEW;
        return route.fulfill(
          ok({
            items: [
              {
                orderId: marker,
                status: 'PAID',
                totalAmountCents: 1000,
                createdAt: '2026-01-01T00:00:00Z',
                payChannel: 'WECHAT',
                inventoryDeducted: true,
                userId: 'u1',
                deviceId: 'd1',
                deviceName: '柜1'
              }
            ],
            total: 40,
            page: pageNum,
            size: 20
          })
        );
      }

      return route.fulfill(ok({}));
    });

    await page.addInitScript(() => {
      localStorage.setItem('admin_token', 'smoke-token');
      localStorage.setItem('admin_userId', '1');
      localStorage.setItem('admin_token_expires', String(Date.now() + 3_600_000));
      localStorage.setItem('admin_permissions', '[]');
      localStorage.setItem('admin_active_nav', '[]');
    });

    await page.goto(`${ADMIN_BASE}orders`, { waitUntil: 'domcontentloaded' });
    await page.getByText(MARKER_OLD, { exact: false }).first().waitFor({ timeout: 15_000 });

    // 连点下一页（模拟用户狂点翻页）
    const nextBtn = page.locator('.el-pagination button.btn-next').first();
    await nextBtn.waitFor({ state: 'visible', timeout: 10_000 });
    for (let i = 0; i < 4; i++) {
      await nextBtn.click({ force: true });
      await sleep(40);
    }

    // 等慢请求也结束
    await sleep(1200);

    const bodyText = await page.locator('.el-table').innerText();
    const hasNew = bodyText.includes(MARKER_NEW);
    const hasOld = bodyText.includes(MARKER_OLD);

    if (!hasNew) {
      throw new Error(`期望表格含 ${MARKER_NEW}，实际:\n${bodyText.slice(0, 500)}`);
    }
    if (hasOld) {
      throw new Error(
        `竞态失败：旧页 ${MARKER_OLD} 仍覆盖表格。orderHits=${JSON.stringify(orderHits)}`
      );
    }

    console.log('PASS smoke-admin-list-race');
    console.log(`  order page hits: ${JSON.stringify(orderHits)}`);
    console.log(`  table kept ${MARKER_NEW}, discarded ${MARKER_OLD}`);
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
  console.error('FAIL smoke-admin-list-race:', err instanceof Error ? err.message : err);
  process.exit(1);
});
