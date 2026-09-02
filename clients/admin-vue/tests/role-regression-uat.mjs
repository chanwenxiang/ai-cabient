/**
 * P4 multi-role regression UAT (finance / replenisher / viewer)
 * Run: node clients/admin-vue/tests/role-regression-uat.mjs
 */
import { chromium } from 'playwright';
import { execSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ADMIN = (process.env.ADMIN_URL || 'http://localhost/admin').replace(/\/$/, '');
const CHANNEL = process.env.PW_CHANNEL || 'chrome';
const HEADED = process.env.PW_HEADED !== '0';
const OUT = path.resolve(__dirname, '../output/playwright/role-regression');
const REDIS_CONTAINER = process.env.REDIS_CONTAINER || 'ai-cabinet-redis-1';
const PASSWORD = '123456';

fs.mkdirSync(OUT, { recursive: true });

const results = [];
let pass = 0;
let fail = 0;

function record(id, name, status, detail, evidence) {
  results.push({ id, name, status, detail, evidence, at: new Date().toISOString() });
  const mark = status === 'PASS' ? '✓' : status === 'FAIL' ? '✗' : '○';
  console.log(`${mark} ${id} ${name} — ${String(detail).slice(0, 280)}`);
  if (status === 'PASS') pass++;
  else if (status === 'FAIL') fail++;
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
  await page.keyboard.type(value, { delay: 12 });
}

async function logoutIfNeeded(page) {
  await page.goto(`${ADMIN}/login`, { waitUntil: 'domcontentloaded' }).catch(() => {});
  await page.evaluate(() => {
    try {
      localStorage.clear();
      sessionStorage.clear();
    } catch {
      // ignore opaque origins
    }
  });
  await page.context().clearCookies();
}

async function loginAs(page, phone) {
  await logoutIfNeeded(page);
  await page.goto(`${ADMIN}/login`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(900);
  const cap = await captchaForPage(page);
  await fillElInput(page, '请输入11位手机号…', phone);
  await fillElInput(page, '请输入登录密码…', PASSWORD);
  await fillElInput(page, '图形验证码…', cap.captchaCode);
  await page.locator('button.submit-btn, button:has-text("登录")').first().click();
  await page.waitForTimeout(2800);
  const url = page.url();
  const ok = !url.includes('/login');
  return { ok, url };
}

async function sidebarText(page) {
  return page.evaluate(() => {
    const aside = document.querySelector('.el-aside, .layout-aside, .sidebar');
    return (aside?.innerText || '').replace(/\s+/g, ' ').trim();
  });
}

async function pageProbe(page) {
  return page.evaluate(() => {
    const main = document.getElementById('main-content');
    const text = (main?.innerText || document.body.innerText || '').replace(/\s+/g, ' ').trim();
    const forbidden = /无访问权限|无权|403|Forbidden/i.test(text);
    return { textHead: text.slice(0, 220), forbidden };
  });
}

/** 收集业务写/导出按钮权限态（含 display:none 的 v-hasPermi 节点） */
async function collectActionButtons(page, patterns) {
  return page.evaluate((pats) => {
    const re = new RegExp(pats.join('|'));
    const root = document.getElementById('main-content') || document.body;
    return [...root.querySelectorAll('button, .el-button, a.el-link, .el-link')]
      .map((b) => {
        const text = (b.textContent || '').replace(/\s+/g, ' ').trim();
        if (!text || text.length > 16) return null; // 排除 KPI/长文案块
        if (!re.test(text)) return null;
        // 排除「停售3已锁机」这类统计磁贴：须像操作按钮
        const tag = b.tagName.toLowerCase();
        const isBtn =
          tag === 'button' || b.classList.contains('el-button') || b.classList.contains('el-link');
        if (!isBtn) return null;
        const st = getComputedStyle(b);
        const disabled = !!(
          b.disabled ||
          b.getAttribute('disabled') != null ||
          b.classList.contains('is-disabled') ||
          b.getAttribute('aria-disabled') === 'true'
        );
        const visible =
          st.display !== 'none' &&
          st.visibility !== 'hidden' &&
          st.opacity !== '0' &&
          (b.offsetParent !== null || st.position === 'fixed');
        const pe = st.pointerEvents;
        const actionable = visible && !disabled && pe !== 'none';
        return {
          text: text.slice(0, 40),
          visible,
          disabled,
          pe,
          display: st.display,
          actionable
        };
      })
      .filter(Boolean);
  }, patterns);
}

function assertNoActionable(buttons, label) {
  const bad = buttons.filter((b) => b.actionable);
  return {
    ok: bad.length === 0,
    detail: JSON.stringify({ label, bad, all: buttons })
  };
}

function assertHasActionable(buttons, nameRe, label) {
  const hit = buttons.filter((b) => nameRe.test(b.text) && b.actionable);
  return {
    ok: hit.length > 0,
    detail: JSON.stringify({ label, hit, all: buttons })
  };
}

async function main() {
  const browser = await chromium.launch({ channel: CHANNEL, headless: !HEADED });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });

  try {
    // ===== Finance 13900000002 =====
    {
      const login = await loginAs(page, '13900000002');
      const e0 = await shot(page, 'F-00-login');
      record('F-00', '财务登录', login.ok ? 'PASS' : 'FAIL', login.url, e0);
      if (!login.ok) throw new Error('finance login failed');

      const side = await sidebarText(page);
      const noDevops = !/DevOps|运维中心|系统扫描/i.test(side);
      record('F-01', '财务侧栏无 DevOps', noDevops ? 'PASS' : 'FAIL', side.slice(0, 180), null);

      await page.goto(`${ADMIN}/finance`, { waitUntil: 'domcontentloaded' });
      await page.waitForTimeout(2000);
      const fin = await pageProbe(page);
      const finOk = !fin.forbidden && /财务|毛利|报表|账单/.test(fin.textHead);
      record(
        'F-02',
        '财务页可进',
        finOk ? 'PASS' : 'FAIL',
        fin.textHead,
        await shot(page, 'F-02-finance')
      );

      await page.goto(`${ADMIN}/warehouse?tab=purchase`, { waitUntil: 'domcontentloaded' });
      await page.waitForTimeout(2800);
      const wh = await page.evaluate(() => {
        const text = (document.getElementById('main-content')?.innerText || '').replace(
          /\s+/g,
          ' '
        );
        const row = [...document.querySelectorAll('.el-table__body tr')].find((r) =>
          (r.innerText || '').includes('PO-UAT-B06')
        );
        const rowText = (row?.innerText || '').replace(/\s+/g, ' ');
        const hasApprove =
          !!row &&
          [...row.querySelectorAll('button, .el-button, .el-link')].some((b) =>
            /^(通过|驳回)$/.test((b.textContent || '').trim())
          );
        return {
          forbidden: /无访问权限|无权|403/.test(text),
          hasPo: !!row,
          rowText: rowText.slice(0, 200),
          hasApprove,
          head: text.slice(0, 160)
        };
      });
      record(
        'F-03',
        '财务可看采购待审单',
        !wh.forbidden && wh.hasPo ? 'PASS' : 'FAIL',
        JSON.stringify(wh),
        await shot(page, 'F-03-purchase')
      );
      // Finance is assignee for PO-UAT-B06 node 2 → should see approve buttons
      if (wh.hasPo) {
        record(
          'F-04',
          '财务对本节点可见通过/驳回',
          wh.hasApprove ? 'PASS' : 'FAIL',
          wh.rowText,
          null
        );
      } else {
        record('F-04', '财务对本节点可见通过/驳回', 'SKIP', '无 PO-UAT-B06', null);
      }

      // 按钮权限：财务有 procurement:edit → 新建采购单可点；本节点通过/驳回可点
      const finBtns = await collectActionButtons(page, [
        '新建采购单',
        '通过',
        '驳回',
        '采购收货',
        '导出'
      ]);
      const fCreate = assertHasActionable(finBtns, /^新建采购单$/, 'finance-create-po');
      record('F-06', '财务「新建采购单」可点', fCreate.ok ? 'PASS' : 'FAIL', fCreate.detail, null);
      const fApprove = assertHasActionable(finBtns, /^(通过|驳回)$/, 'finance-approve');
      record('F-07', '财务「通过/驳回」可点', fApprove.ok ? 'PASS' : 'FAIL', fApprove.detail, null);

      await page.goto(`${ADMIN}/devops`, { waitUntil: 'domcontentloaded' });
      await page.waitForTimeout(1800);
      const devops = await pageProbe(page);
      const devopsBlocked =
        page.url().includes('forbidden') ||
        devops.forbidden ||
        !/Sonar|扫描任务|运维/.test(devops.textHead);
      record(
        'F-05',
        '财务进 DevOps 被拦',
        devopsBlocked ? 'PASS' : 'FAIL',
        `${page.url()} | ${devops.textHead}`,
        await shot(page, 'F-05-devops')
      );
    }

    // ===== Replenisher 13900000004 =====
    {
      const login = await loginAs(page, '13900000004');
      const e0 = await shot(page, 'R-00-login');
      record('R-00', '补货员登录', login.ok ? 'PASS' : 'FAIL', login.url, e0);
      if (!login.ok) throw new Error('replenisher login failed');

      const homeOk = /stock-health|replenishment|warehouse/i.test(login.url);
      record('R-01', '补货员落页非无权 dashboard', homeOk ? 'PASS' : 'FAIL', login.url, null);

      const side = await sidebarText(page);
      const sideOk = /补货|库存|仓库/.test(side) && !/DevOps|角色管理|菜单管理/.test(side);
      record('R-02', '补货员侧栏裁剪', sideOk ? 'PASS' : 'FAIL', side.slice(0, 180), null);

      await page.goto(`${ADMIN}/replenishment`, { waitUntil: 'domcontentloaded' });
      await page.waitForTimeout(2200);
      const rep = await pageProbe(page);
      record(
        'R-03',
        '补货调度可进',
        !rep.forbidden ? 'PASS' : 'FAIL',
        rep.textHead,
        await shot(page, 'R-03-replen')
      );

      const repBtns = await collectActionButtons(page, [
        '规划补货路线',
        '一键规划补货',
        '导出',
        '签到',
        '完成'
      ]);
      const rPlan = assertHasActionable(repBtns, /^规划补货路线$/, 'replen-plan');
      record('R-05', '补货员「规划补货路线」可点', rPlan.ok ? 'PASS' : 'FAIL', rPlan.detail, null);

      await page.goto(`${ADMIN}/warehouse?tab=purchase`, { waitUntil: 'domcontentloaded' });
      await page.waitForTimeout(2500);
      const rWh = await pageProbe(page);
      if (!rWh.forbidden && !page.url().includes('forbidden')) {
        const rPoBtns = await collectActionButtons(page, [
          '新建采购单',
          '通过',
          '驳回',
          '采购收货'
        ]);
        // 补货员无 procurement:edit → 写按钮不可点
        const rNoWrite = assertNoActionable(rPoBtns, 'replen-purchase-write');
        record(
          'R-06',
          '补货员采购页无写按钮',
          rNoWrite.ok ? 'PASS' : 'FAIL',
          rNoWrite.detail,
          await shot(page, 'R-06-purchase-btns')
        );
      } else {
        record(
          'R-06',
          '补货员采购页无写按钮',
          'PASS',
          `采购页不可进（更严）：${page.url()}`,
          await shot(page, 'R-06-purchase-btns')
        );
      }

      await page.goto(`${ADMIN}/orders`, { waitUntil: 'domcontentloaded' });
      await page.waitForTimeout(1800);
      const orders = await pageProbe(page);
      const ordersBlocked =
        page.url().includes('forbidden') ||
        orders.forbidden ||
        !/订单号|订单列表|共 \d+ 条/.test(orders.textHead);
      record(
        'R-04',
        '补货员订单页无权',
        ordersBlocked ? 'PASS' : 'FAIL',
        `${page.url()} | ${orders.textHead}`,
        await shot(page, 'R-04-orders')
      );
    }

    // ===== Viewer 13900000005 =====
    {
      const login = await loginAs(page, '13900000005');
      const e0 = await shot(page, 'V-00-login');
      record('V-00', '只读登录', login.ok ? 'PASS' : 'FAIL', login.url, e0);
      if (!login.ok) throw new Error('viewer login failed');

      await page.goto(`${ADMIN}/devices`, { waitUntil: 'domcontentloaded' });
      await page.waitForTimeout(2500);
      const deviceBtns = await collectActionButtons(page, [
        '^新建设备$',
        '^批量',
        '^导出',
        '^编辑$',
        '^删除$',
        '^停售$',
        '^启用$',
        '^恢复销售$'
      ]);
      const vNoDeviceWrite = assertNoActionable(deviceBtns, 'viewer-devices');
      record(
        'V-01',
        '设备页写/导出按钮不可点',
        vNoDeviceWrite.ok ? 'PASS' : 'FAIL',
        vNoDeviceWrite.detail,
        await shot(page, 'V-01-devices')
      );

      // Force-click 新建设备 if present in DOM — dialog must not open (IMP-050)
      const forced = await page.evaluate(() => {
        const btn = [...document.querySelectorAll('button, .el-button')].find((b) =>
          /新建设备/.test((b.textContent || '').trim())
        );
        if (!btn) return { clicked: false };
        btn.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
        return { clicked: true, text: (btn.textContent || '').trim() };
      });
      await page.waitForTimeout(800);
      const dialogsAfter = await page.evaluate(
        () =>
          [...document.querySelectorAll('.el-overlay')].filter((o) => {
            const st = getComputedStyle(o);
            return (
              st.display !== 'none' && st.visibility !== 'hidden' && o.querySelector('.el-dialog')
            );
          }).length
      );
      const forceOk = !forced.clicked || dialogsAfter === 0;
      record(
        'V-02',
        '强制点新建不弹窗',
        forceOk ? 'PASS' : 'FAIL',
        JSON.stringify({ forced, dialogsAfter }),
        await shot(page, 'V-02-force-create')
      );

      await page.goto(`${ADMIN}/orders`, { waitUntil: 'domcontentloaded' });
      await page.waitForTimeout(2200);
      const orderBtns = await collectActionButtons(page, ['导出', '退款', '取消', '人工', '创建']);
      const vNoOrderWrite = assertNoActionable(orderBtns, 'viewer-orders');
      record(
        'V-04',
        '订单页写/导出按钮不可点',
        vNoOrderWrite.ok ? 'PASS' : 'FAIL',
        vNoOrderWrite.detail,
        await shot(page, 'V-04-orders')
      );

      await page.goto(`${ADMIN}/fund-bills`, { waitUntil: 'domcontentloaded' });
      await page.waitForTimeout(2200);
      const fundBtns = await collectActionButtons(page, ['导出', '新建', '创建', '编辑']);
      const vNoFundWrite = assertNoActionable(fundBtns, 'viewer-fund');
      record(
        'V-05',
        '资金账单写/导出按钮不可点',
        vNoFundWrite.ok ? 'PASS' : 'FAIL',
        vNoFundWrite.detail,
        await shot(page, 'V-05-fund')
      );

      await page.goto(`${ADMIN}/warehouse?tab=purchase`, { waitUntil: 'domcontentloaded' });
      await page.waitForTimeout(2000);
      const vWhBlocked = page.url().includes('forbidden') || (await pageProbe(page)).forbidden;
      if (vWhBlocked) {
        record(
          'V-06',
          '只读无仓库写入口',
          'PASS',
          `采购页无权：${page.url()}`,
          await shot(page, 'V-06-warehouse')
        );
      } else {
        const vPoBtns = await collectActionButtons(page, [
          '新建采购单',
          '通过',
          '驳回',
          '采购收货',
          '导出'
        ]);
        const vNoPo = assertNoActionable(vPoBtns, 'viewer-purchase');
        record(
          'V-06',
          '只读无仓库写入口',
          vNoPo.ok ? 'PASS' : 'FAIL',
          vNoPo.detail,
          await shot(page, 'V-06-warehouse')
        );
      }

      await page.goto(`${ADMIN}/devops`, { waitUntil: 'domcontentloaded' });
      await page.waitForTimeout(1800);
      const devops = await pageProbe(page);
      const devopsBlocked =
        page.url().includes('forbidden') ||
        devops.forbidden ||
        !/Sonar|扫描任务|运维/.test(devops.textHead);
      record(
        'V-03',
        '只读进 DevOps 被拦',
        devopsBlocked ? 'PASS' : 'FAIL',
        `${page.url()} | ${devops.textHead}`,
        await shot(page, 'V-03-devops')
      );
    }
  } catch (e) {
    console.error(e);
    record('P4-ERR', '运行异常', 'FAIL', e.message, null);
  } finally {
    await browser.close();
  }

  const summary = { pass, fail, total: pass + fail };
  fs.writeFileSync(path.join(OUT, 'report.json'), JSON.stringify({ summary, results }, null, 2));
  console.log('\n=== P4 ROLE REGRESSION UAT ===');
  console.log(JSON.stringify(summary));
  process.exit(fail > 0 ? 1 : 0);
}

main();
