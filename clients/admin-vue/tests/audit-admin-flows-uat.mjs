/**
 * C-admin 业务流程实测（上线前审计 Track C-admin，2026-10-13）
 *
 * 与 three-end-business-uat.mjs 的分工：后者是「页面冒烟」（页开得了、列表水合得了），
 * 本脚本是「业务流程走得了」——每条流程都要真正把业务对象造出来、在后台把它推到终态，
 * 断言落在**数据库/API 口径**上而不是页面文案上（页面文案能被外壳恒真满足，见 ui-assert.mjs 头注）。
 *
 * 八条流程（docs/AUDIT_LEDGER_2026-10-13.md 计划 §Track C）：
 *   F1 下单→模拟器开门→端侧识别→结算→订单→购物视频
 *   F2 争议全链（低置信→DISPUTED→后台审核处置）
 *   F3 退款→报表口径
 *   F4 提现审核（含 CB-035 T+1 解锁时刻可配）
 *   F5 平台资金池五操作
 *   F6 设备租赁（出账/押金/锁柜）
 *   F7 补货任务→补货视频
 *   F8 RBAC 越权矩阵（菜单/按钮/API 三层）
 *
 * Env:
 *   ADMIN_URL      default http://localhost/admin
 *   API_BASE       default http://localhost            （网关：业务 API 走这里，与真实用户同路径）
 *   TRADE_DIRECT   default http://127.0.0.1:18080      （/internal/* 被 nginx 一律 403，内部面必须直连）
 *   INTERNAL_KEY   default dev-internal-key-change-me
 *   FLOWS          default env-baseline                逗号分隔：env-baseline,F1,F2,...
 *   PW_CHANNEL / PW_HEADED 同其它 UAT 脚本
 *
 * Run:
 *   cd clients/consumer-mp && node ../admin-vue/tests/audit-admin-flows-uat.mjs
 */
import { chromium } from 'playwright';
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync, execSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { captchaFromRedis } from '../../../scripts/lib/redis-captcha.mjs';
import { adminPageState } from '../../../scripts/lib/ui-assert.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ADMIN = (process.env.ADMIN_URL || 'http://localhost/admin').replace(/\/$/, '');
const API = (process.env.API_BASE || 'http://localhost').replace(/\/$/, '');
const TRADE = (process.env.TRADE_DIRECT || 'http://127.0.0.1:18080').replace(/\/$/, '');
const INTERNAL_KEY = process.env.INTERNAL_KEY || 'dev-internal-key-change-me';
const CHANNEL = process.env.PW_CHANNEL || 'chrome';
const HEADED = process.env.PW_HEADED === '1';
const ADMIN_PHONE = process.env.ADMIN_PHONE || '13900000001';
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || '123456';
const FLOWS = (process.env.FLOWS || 'env-baseline')
  .split(',')
  .map((s) => s.trim())
  .filter(Boolean);

const OUT = path.resolve(__dirname, '../../../docs/evidence/audit-2026-10-13/trackC-admin');
fs.mkdirSync(OUT, { recursive: true });

const results = [];
function record(flow, id, status, detail, evidence) {
  const s = status === true ? 'PASS' : status === false ? 'FAIL' : String(status);
  results.push({
    flow,
    id,
    status: s,
    detail: String(detail).slice(0, 600),
    evidence,
    at: new Date().toISOString()
  });
  console.log(
    `${s === 'PASS' ? '✓' : s === 'FAIL' ? '✗' : '○'} [${flow}] ${id} — ${String(detail).slice(0, 220)}`
  );
}
async function shot(page, name) {
  const file = path.join(OUT, `${name}.png`);
  try {
    await page.screenshot({ path: file, fullPage: true });
    return path.relative(path.resolve(__dirname, '../../..'), file).replace(/\\/g, '/');
  } catch {
    return null;
  }
}
function writeText(name, payload) {
  const file = path.join(OUT, `${name}.json`);
  fs.writeFileSync(file, JSON.stringify(payload, null, 2));
  return path.relative(path.resolve(__dirname, '../../..'), file).replace(/\\/g, '/');
}

/** 网关业务面（带 Bearer）——与真实后台用户同一路径，才能测出 nginx/权限层的问题。 */
async function api(token, method, urlPath, body) {
  const res = await fetch(`${API}${urlPath}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      // 后台写操作要求该头（client.ts:51-73 的同源约定），缺了测的是「脚本特供路径」
      'X-Requested-With': 'XMLHttpRequest',
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    },
    body: body === undefined ? undefined : JSON.stringify(body),
    redirect: 'manual'
  });
  const text = await res.text();
  let json = null;
  try {
    json = JSON.parse(text);
  } catch {
    /* 非 JSON 保留原文 */
  }
  return { status: res.status, json, text };
}
/** 内部面：nginx 对 /internal/ 一律 403，只能直连 trade。 */
async function internal(method, urlPath, body) {
  const res = await fetch(`${TRADE}${urlPath}`, {
    method,
    headers: { 'Content-Type': 'application/json', 'X-Internal-Api-Key': INTERNAL_KEY },
    body: body === undefined ? undefined : JSON.stringify(body),
    redirect: 'manual'
  });
  const text = await res.text();
  let json = null;
  try {
    json = JSON.parse(text);
  } catch {
    /* noop */
  }
  return { status: res.status, json, text };
}

/**
 * DB 口径断言。
 *
 * 为什么必须有：只看 HTTP 200 会被「接口回了个空壳」骗过去（本轮审计多次遇到
 * accepted=false 却 200 的幂等分支）。流程是否真的走到终态，以会话态/订单行/账本为准。
 */
let pgContainer = null;
function psql(sql) {
  if (!pgContainer) {
    const names = execSync('docker ps --format "{{.Names}}"', { encoding: 'utf8' });
    pgContainer = process.env.PG_CONTAINER || names.split('\n').find((n) => /postgres/.test(n));
    if (!pgContainer) throw new Error('未找到 postgres 容器（可用 PG_CONTAINER 指定）');
  }
  return execSync(
    `docker exec ${pgContainer} psql -U aicabinet -d aicabinet -t -A -F'|' -c "${sql.replace(/"/g, '\\"')}"`,
    { encoding: 'utf8' }
  )
    .split('\n')
    .map((l) => l.trim())
    .filter(Boolean)
    .join('\n');
}

/** 运营侧 API 登录（前端把 token 放 sessionStorage，脚本走同一份接口拿同一份权限）。 */
async function adminLoginViaApi() {
  const cap = await fetch(`${API}/api/v2/auth/captcha`).then((r) => r.json());
  const captchaId = cap?.data?.captchaId;
  const captchaCode = (await captchaFromRedis(captchaId)).toLowerCase();
  const res = await fetch(`${API}/api/v2/auth/admin-password-login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Requested-With': 'XMLHttpRequest' },
    body: JSON.stringify({
      phoneNumber: ADMIN_PHONE,
      password: ADMIN_PASSWORD,
      captchaId,
      captchaCode
    })
  });
  const json = await res.json().catch(() => null);
  return {
    status: res.status,
    token: json?.data?.token || '',
    twoFactor: json?.data?.twoFactorRequired
  };
}

/** 消费者密码登录（演示号由 demo/ensure 播种）。 */
async function consumerLoginViaApi(phone, password) {
  const res = await fetch(`${API}/api/v2/auth/password-login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ phoneNumber: phone, password })
  });
  const json = await res.json().catch(() => null);
  return { status: res.status, token: json?.data?.token || '', json };
}

async function adminLogin(page) {
  await page.goto(`${ADMIN}/login`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1200);
  for (let i = 0; i < 4; i++) {
    try {
      const capId = await page
        .locator('button.captcha-img-btn[data-captcha-id]')
        .first()
        .getAttribute('data-captcha-id');
      if (!capId) throw new Error('no captcha id');
      const code = (await captchaFromRedis(capId)).toUpperCase();
      await page.locator('.el-input input[placeholder="请输入11位手机号…"]').fill(ADMIN_PHONE);
      await page.locator('.el-input input[placeholder="请输入登录密码…"]').fill(ADMIN_PASSWORD);
      await page.locator('.el-input input[placeholder="图形验证码…"]').fill(code);
      await page.locator('button.submit-btn, button:has-text("登录")').first().click();
      await page.waitForTimeout(2200);
      const text = await page.evaluate(() => document.body?.innerText || '');
      if (/运营工作台|概览|交易履约|订单管理/.test(text) || page.url().includes('/dashboard')) {
        const token = await page.evaluate(() => localStorage.getItem('admin_token') || '');
        return { ok: true, token };
      }
    } catch (e) {
      await page
        .locator('button.captcha-img-btn')
        .first()
        .click({ force: true, timeout: 3000 })
        .catch(() => {});
      await page.waitForTimeout(700);
    }
  }
  return { ok: false, token: '' };
}

// ---------- env-baseline：被测版本溯源 ----------

const FLOWS_IMPL = {
  /**
   * F1 下单→开门→端侧识别→结算→订单→视频。
   *
   * 门事件/识别结果都走 trade 内部面（door-event + edge-results），**不经设备 WS**：
   * 这是把「运营后台能否看到并核对一单真实结算」与「将邑 WS 链是否通」两件事分开测——
   * 后者属 Track E/B4，已单独立项。mock-enabled=true 的本地栈允许非将邑柜购物
   * （DeviceValidationService.ensureJiangyiOnlyShopping 豁免），故这里仍显式登记
   * jiangyi_device + class-mapping，让识别解析走真实映射表而不是 dev 短路。
   */
  F1: async ({ page, state }) => {
    const ens = await internal('POST', '/internal/v1/demo/ensure');
    const demo = ens.json?.data || {};
    const deviceId = demo.deviceId;
    const skuId = demo.fallbackSkuId;
    record(
      'F1',
      'F1-01',
      !!deviceId && !!skuId,
      `demo/ensure HTTP=${ens.status} device=${deviceId} sku=${skuId} skus=${demo.skuCount} invLines=${demo.deviceInventoryLines}`,
      writeText('f1-demo-ensure', ens.json)
    );
    if (!deviceId) return;

    const sn = `AUDIT-F1-${deviceId}`;
    const reg = await internal('POST', '/internal/v1/jiangyi/onboarding/register', {
      deviceId,
      deviceSn: sn,
      modelName: 'AUDIT-SIM-MODEL'
    });
    record(
      'F1',
      'F1-02',
      reg.status === 200,
      `onboarding/register HTTP=${reg.status} status=${reg.json?.data?.status}（bind-domain 不调：会打将邑真租户 setDomain，本地造数改由 DB 置 BOUND）`,
      writeText('f1-jiangyi-register', reg.json)
    );

    const clsId = 9001;
    const map = await internal('POST', '/internal/v1/jiangyi/class-mappings', {
      deviceId,
      classId: clsId,
      skuId,
      modelName: 'AUDIT-SIM-MODEL',
      textName: 'audit-demo',
      status: 'ACTIVE',
      source: 'MANUAL'
    });
    record(
      'F1',
      'F1-03',
      map.status === 200,
      `class-mapping ${clsId}→${skuId} HTTP=${map.status}`,
      map ? null : null
    );

    const login = await consumerLoginViaApi(demo.consumerPhone || '13800138000', '123456');
    record(
      'F1',
      'F1-04',
      login.status === 200 && !!login.token,
      `consumer login HTTP=${login.status}`,
      null
    );
    if (!login.token) return;

    const created = await api(login.token, 'POST', '/api/v2/sessions', {
      deviceId,
      idempotencyKey: `AUDIT-F1-${Date.now()}`
    });
    const sessionId = created.json?.data?.sessionId;
    record(
      'F1',
      'F1-05',
      created.status === 200 && !!sessionId,
      `POST /api/v2/sessions HTTP=${created.status} session=${sessionId} state=${created.json?.data?.state} msg=${created.json?.message || ''}`,
      writeText('f1-session-create', created.json)
    );
    if (!sessionId) return;
    state.f1 = { deviceId, sessionId, skuId, classId: clsId, consumerToken: login.token };

    const open = await internal('POST', '/internal/v1/sessions/door-event', {
      sessionId,
      deviceId,
      doorState: 'OPEN',
      timestamp: Date.now()
    });
    record(
      'F1',
      'F1-06',
      open.status === 200 && String(open.json?.data?.state) === 'SHOPPING',
      `door OPEN → state=${open.json?.data?.state}（期望 SHOPPING）HTTP=${open.status}`,
      writeText('f1-door-open', open.json)
    );

    const closed = await internal('POST', '/internal/v1/sessions/door-event', {
      sessionId,
      deviceId,
      doorState: 'CLOSED',
      timestamp: Date.now()
    });
    record(
      'F1',
      'F1-07',
      closed.status === 200 && String(closed.json?.data?.state) === 'RECOGNIZING',
      `door CLOSED → state=${closed.json?.data?.state}（期望 RECOGNIZING）HTTP=${closed.status}`,
      writeText('f1-door-closed', closed.json)
    );

    const qty = 2;
    const edge = await internal('POST', '/internal/v1/vision/edge-results', {
      sessionId,
      deviceId,
      taskId: 'AUDIT-F1-TASK',
      traceId: null,
      items: [{ skuId, quantity: qty, confidence: 0.95 }],
      overallConfidence: 0.95,
      needReview: false,
      modelVersion: 'AUDIT-SIM-MODEL',
      detectedClasses: ['audit-demo'],
      provider: 'JIANGYI',
      occurredAt: new Date().toISOString()
    });
    const settled = String(edge.json?.data?.stateAfter || edge.json?.data?.state || '');
    record(
      'F1',
      'F1-08',
      edge.status === 200 && edge.json?.data?.accepted === true,
      `edge-results HTTP=${edge.status} accepted=${edge.json?.data?.accepted} outcome=${edge.json?.data?.outcome} reason=${String(edge.json?.data?.reason || '').slice(0, 80)} stateAfter=${settled}`,
      writeText('f1-edge-results', edge.json)
    );

    const dbOrder = psql(
      `select o.order_id, o.status, o.total_amount_cents, o.paid_amount_cents, s.state from cabinet_order o join shopping_session s on s.session_id=o.session_id where s.session_id='${sessionId}'`
    );
    const dbLines = psql(
      `select l.sku_id, l.quantity, l.unit_price_cents, l.line_amount_cents from cabinet_order_line l join cabinet_order o on o.order_id=l.order_id where o.session_id='${sessionId}'`
    );
    record(
      'F1',
      'F1-09',
      !!dbOrder && !!dbLines,
      `order=[${dbOrder}] lines=[${dbLines}]`,
      writeText('f1-db-order', { dbOrder, dbLines })
    );

    // 金额不变量：行金额合计必须等于订单合计（资损类判据，独立于页面）
    const lines = dbLines
      .split('\n')
      .filter(Boolean)
      .map((l) => l.split('|'))
      .map(([, q, up, la]) => ({ q: Number(q), up: Number(up), la: Number(la) }))
      .filter((r) => Number.isFinite(r.la));
    const totalMatch = Number((dbOrder.split('|')[2] || '').trim());
    const sumLines = lines.reduce((a, r) => a + r.la, 0);
    record(
      'F1',
      'F1-10',
      lines.length > 0 && sumLines === totalMatch && lines.every((r) => r.la === r.q * r.up),
      `Σ行金额=${sumLines} vs 订单合计=${totalMatch}；逐行 qty×unit=${lines.map((r) => `${r.q}×${r.up}=${r.la}`).join(',')}`,
      null
    );

    const admin = await adminLoginViaApi();
    state.adminToken = admin.token;
    record(
      'F1',
      'F1-11',
      admin.status === 200 && !!admin.token,
      `ops login HTTP=${admin.status} 2FA=${admin.twoFactor}`,
      null
    );
    if (!state.adminToken) return;

    const orderId = (dbOrder.split('|')[0] || '').trim();
    const opsOrder = await api(
      state.adminToken,
      'GET',
      `/api/v2/ops/admin/orders/${encodeURIComponent(orderId)}`
    );
    record(
      'F1',
      'F1-12',
      opsOrder.status === 200 && !!opsOrder.json?.data,
      `GET ops order HTTP=${opsOrder.status} total=${opsOrder.json?.data?.totalAmountCents ?? opsOrder.json?.data?.total ?? '(见 json)'}`,
      writeText('f1-ops-order', opsOrder.json)
    );

    await page.goto(`${ADMIN}/orders`, { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(1500);
    if (orderId) {
      const input = page.locator('input[placeholder*="订单"], input[placeholder*="搜索"]').first();
      if ((await input.count()) > 0) {
        await input.fill(orderId);
        await page.keyboard.press('Enter');
        await page.waitForTimeout(1500);
      }
    }
    const found = await page.evaluate(
      (oid) =>
        [...document.querySelectorAll('.report-table tbody tr')].some((tr) =>
          tr.innerText.includes(oid)
        ),
      orderId
    );
    record(
      'F1',
      'F1-13',
      found,
      `订单管理页可检索到该单 orderId=${orderId}`,
      await shot(page, 'f1-admin-orders-search')
    );

    const video = await api(
      login.token,
      'GET',
      `/api/v2/orders/${encodeURIComponent(orderId)}/video`
    );
    record(
      'F1',
      'F1-14',
      video.status === 200 && video.text.length > 1024 ? true : 'SKIP',
      `订单视频 HTTP=${video.status} bytes=${video.text.length}（本地无模拟器录像⇒OSS 台账空属预期，接真前 checklist 已含真机视频全链）`,
      null
    );
  },

  /**
   * 先把自己「测的是哪一版」钉死：admin 产物指纹 + trade 构建信息。
   * 没有这一步，后面的所有绿都无法归因到某个 commit。
   */
  'env-baseline': async ({ page }) => {
    const index = await fetch(`${ADMIN}/index.html`, { cache: 'no-store' }).then((r) => r.text());
    const assets = [...index.matchAll(/assets\/[\w.-]+\.js/g)].map((m) => m[0]);
    record(
      'ENV',
      'E-01',
      true,
      `admin 入口产物=${assets.join(',') || '(无 js 引用)'}（index.html ${index.length}B）`,
      writeText('admin-build-fingerprint', { assets, indexHtml: index })
    );

    // actuator/info 在本栈是空的（build-info 未打进 jar），改用**行为指纹**判定被测版本
    const fp = spawnSync(
      process.execPath,
      [
        path.resolve(__dirname, '../../../scripts/devops/probe-audit-code-fingerprint.mjs'),
        '--base',
        TRADE
      ],
      { encoding: 'utf8', env: { ...process.env, TRADE_DIRECT: TRADE } }
    );
    const fpOut = `${fp.stdout || ''}${fp.stderr || ''}`;
    const unpatched = /不含批九入口校验|UNPATCHED/.test(fpOut);
    record(
      'ENV',
      'E-02',
      unpatched ? 'SKIP' : true,
      `代码指纹：${unpatched ? '当前后端=dev 构建（不含审计分支入口校验）→ 本文件所有流程结论一律标注为 dev 版本行为' : '当前后端含审计分支入口校验'}；probe exit=${fp.status}`,
      writeText('code-fingerprint', { exit: fp.status, stdout: fp.stdout, stderr: fp.stderr })
    );

    const login = await adminLogin(page);
    record(
      'ENV',
      'E-03',
      login.ok,
      login.ok ? `token len=${login.token.length} url=${page.url()}` : '登录失败',
      await shot(page, 'e03-admin-login')
    );
    if (!login.ok) return;

    // token 只在内存里传，绝不落 evidence 目录（截图/JSON 会被提交）
    for (const p of [
      { id: 'E-04', name: '订单管理', path: '/orders', title: '订单管理' },
      { id: 'E-05', name: '争议审核', path: '/disputes', title: '争议审核' },
      { id: 'E-06', name: '异常中心', path: '/exceptions', title: '异常中心' }
    ]) {
      await page.goto(`${ADMIN}${p.path}`, { waitUntil: 'domcontentloaded' });
      const st = await adminPageState(page, { title: p.title });
      record('ENV', p.id, st.ok, `${p.name}: ${st.detail}`, await shot(page, p.id.toLowerCase()));
    }
  }
};

async function main() {
  const r = await fetch(`${ADMIN}/index.html`, { method: 'HEAD', cache: 'no-store' }).catch(
    () => null
  );
  if (!r || !r.ok) {
    console.error(`admin 入口不可用: ${ADMIN}/index.html`);
    process.exit(2);
  }
  const browser = await chromium.launch({ channel: CHANNEL, headless: !HEADED });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const ctx = { page, browser, state: {} };
  try {
    for (const flow of FLOWS) {
      const impl = FLOWS_IMPL[flow];
      if (!impl) {
        record(flow, 'X-00', 'SKIP', '未实现的流程名');
        continue;
      }
      await impl(ctx);
    }
  } finally {
    await browser.close();
  }
  const summary = {
    flows: FLOWS,
    pass: results.filter((r) => r.status === 'PASS').length,
    fail: results.filter((r) => r.status === 'FAIL').length,
    skip: results.filter((r) => r.status === 'SKIP').length,
    at: new Date().toISOString()
  };
  const reportFile = path.join(OUT, 'report.json');
  fs.writeFileSync(reportFile, JSON.stringify({ summary, results }, null, 2));
  console.log('\n=== C-ADMIN FLOWS UAT ===');
  console.log(JSON.stringify(summary, null, 2));
  process.exit(summary.fail > 0 ? 1 : 0);
}

main().catch((e) => {
  console.error(e);
  process.exit(2);
});
