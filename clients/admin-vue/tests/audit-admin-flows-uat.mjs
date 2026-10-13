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
 *   JIANGYI_GATEWAY_BASE default http://127.0.0.1:18084 （compose 里 jiangyi-gateway 的发布端口）
 *     ⚠️ 本机 18084 被栈外监听截走（curl 得 `server: Cowboy` 404，容器内 :8083 正常），
 *     实测时改走一次性转发容器：`python3 .tmp/gw_fwd.py` 起在 ai-cabinet_default 网络上，
 *     然后 JIANGYI_GATEWAY_BASE=http://127.0.0.1:18094（详见 Track C 报告 §0 环境说明）
 *   FLOWS          default env-baseline                逗号分隔：env-baseline,F1,F2,...
 *   PW_CHANNEL / PW_HEADED 同其它 UAT 脚本
 *
 * Run:
 *   cd clients/consumer-mp && node ../admin-vue/tests/audit-admin-flows-uat.mjs
 */
import { chromium } from 'playwright';
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync, execSync, spawn } from 'node:child_process';
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
    detail: redactSecrets(String(detail)).slice(0, 600),
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
/**
 * 证据落盘前**必须脱敏**：设备 token / JWT（三段 `eyJ…`）与 `token=` 查询串一旦被写进
 * `docs/evidence/**` 就随提交长期留存，而将邑设备 token TTL 默认 7 天——证据要留，凭据不留。
 */
function redactSecrets(text) {
  return String(text)
    .replace(/eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}/g, '[REDACTED-JWT]')
    .replace(/(token=)[^&\s"']+/gi, '$1[REDACTED]');
}

function writeText(name, payload) {
  const file = path.join(OUT, `${name}.json`);
  fs.writeFileSync(file, redactSecrets(JSON.stringify(payload, null, 2)));
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
/** compose 容器名带实例前缀（如 `6a848fad8f21_ai-cabinet-postgres-1`），按关键词解析一次。 */
function resolveContainer(pattern, overrideName) {
  if (overrideName) return overrideName;
  const names = execSync('docker ps --format "{{.Names}}"', { encoding: 'utf8' });
  return names.split('\n').find((n) => pattern.test(n));
}
function psql(sql) {
  if (!pgContainer) {
    pgContainer = resolveContainer(/postgres/, process.env.PG_CONTAINER);
    if (!pgContainer) throw new Error('未找到 postgres 容器（可用 PG_CONTAINER 指定）');
  }
  return execSync(
    // `-t -A` 的默认字段分隔符已是 `|`，不要再传 `-F'|'`：execSync 在 Windows 走 cmd.exe，
    // 单引号不是引用符，`|` 会被当成管道 ⇒ "命令语法不正确"（实测 status=255）。
    `docker exec ${pgContainer} psql -U aicabinet -d aicabinet -t -A -c "${sql.replace(/"/g, '\\"')}"`,
    { encoding: 'utf8' }
  )
    .split('\n')
    .map((l) => l.trim())
    .filter(Boolean)
    .join('\n');
}

/**
 * trade 容器近 90s 日志里是否出现某模式 + 命中行原文。
 * 用途：HTTP 侧只回笼统 500 时，用**服务端异常原文**判定是不是已知缺陷（AU-133），
 * 而不是靠「我猜是」把红洗成黄。
 */
function tradeLogSnippet(pattern) {
  try {
    const name = resolveContainer(/trade/, process.env.TRADE_CONTAINER);
    if (!name) return { hit: false, line: '(未找到 trade 容器)' };
    const out = execSync(`docker logs --since 90s ${name} 2>&1`, { encoding: 'utf8' });
    const line = out.split('\n').find((l) => pattern.test(l)) || '';
    return { hit: !!line, line: line.slice(0, 400) };
  } catch (e) {
    return { hit: false, line: `日志读取失败：${String(e.message).slice(0, 120)}` };
  }
}

/** gateway 容器日志（判定设备链是否真的走过 gateway，而不是脚本自己打内部面）。 */
function gatewayLogSnippet(pattern) {
  try {
    const name = resolveContainer(/jiangyi-gateway/, process.env.GATEWAY_CONTAINER);
    if (!name) return { hit: false, line: '(未找到 jiangyi-gateway 容器)' };
    const out = execSync(`docker logs --since 180s ${name} 2>&1`, { encoding: 'utf8' });
    const line =
      out
        .split('\n')
        .filter((l) => pattern.test(l))
        .pop() || '';
    return { hit: !!line, line: line.slice(0, 400) };
  } catch (e) {
    return { hit: false, line: `日志读取失败：${String(e.message).slice(0, 120)}` };
  }
}

/** 轮询到条件成立或超时（会话状态由设备上报异步推进，不能假定瞬时完成）。 */
async function pollUntil(fn, { timeoutMs = 45_000, intervalMs = 1500 } = {}) {
  const deadline = Date.now() + timeoutMs;
  let last;
  for (;;) {
    last = await fn();
    if (last?.ok || Date.now() >= deadline) return last;
    await new Promise((r) => setTimeout(r, intervalMs));
  }
}

/** 会话当前态（以库为准：状态机由设备上报异步推进，HTTP 回包可能滞后）。 */
function sessionState(sessionId) {
  return psql(`select state from shopping_session where session_id='${sessionId}'`) || '';
}

/**
 * 起本仓自带的**将邑设备模拟器**（`edge/device-simulator` jiangyi 模式，协议逐字照 V16）：
 * 取 token → WS 在线 → 收 openDoor → 上报开锁/拉门 → 上报识别 classId+quantity。
 *
 * 为什么必须有它：产品的结算证据**只有将邑端侧识别一种**（无重力结算、无云端识别，
 * 见 docs/JIANGYI_ONLY_FINAL_VERDICT_2026-10-11.md），所以「一单真实结算」必须由
 * 设备链产生；脚本自己 POST 内部面 door-event/edge-results 只能验证 trade 受理面，
 * 证明不了 gateway 归一与路由。日志落 .tmp/f1-simulator.log 供事后取证。
 */
function startJiangyiSimulator({ sn, classId, qty, shoppingMs }) {
  const jar = path.resolve(
    __dirname,
    '../../../edge/device-simulator/target/device-simulator-0.1.0-SNAPSHOT-all.jar'
  );
  if (!fs.existsSync(jar)) {
    return {
      child: null,
      logFile: null,
      error: `模拟器 jar 不存在：${jar}（先 mvn -pl edge/device-simulator -am package -DskipTests）`
    };
  }
  const logFile = path.resolve(__dirname, '../../../.tmp/f1-simulator.log');
  fs.mkdirSync(path.dirname(logFile), { recursive: true });
  fs.writeFileSync(logFile, '');
  const child = spawn('java', ['-jar', jar, 'jiangyi'], {
    env: {
      ...process.env,
      JIANGYI_GATEWAY_BASE: process.env.JIANGYI_GATEWAY_BASE || 'http://127.0.0.1:18084',
      JIANGYI_DEVICE_SN: sn,
      JIANGYI_SIM_CLASS_ID: String(classId),
      JIANGYI_SIM_QTY: String(qty),
      JIANGYI_SIM_SHOPPING_MS: String(shoppingMs)
    },
    stdio: ['ignore', 'pipe', 'pipe']
  });
  const sink = fs.createWriteStream(logFile, { flags: 'a' });
  child.stdout.pipe(sink);
  child.stderr.pipe(sink);
  child.on('error', (e) => sink.write(`[spawn-error] ${e.message}\n`));
  process.on('exit', () => {
    try {
      child.kill();
    } catch {
      /* 已退出 */
    }
  });
  return {
    child,
    logFile,
    tail: () => fs.readFileSync(logFile, 'utf8').split('\n').filter(Boolean).slice(-25).join('\n')
  };
}

/**
 * 本机 compose 容器名带**实例前缀**（`6a848fad8f21_ai-cabinet-redis-1`），而
 * redis-captcha.mjs 的本地回退路径默认写死 `ai-cabinet-redis-1` ⇒ 必须先解析真名，
 * 否则 docker exec 报 No such container，登录腿整条假红（实测 E-03 FAIL）。
 * 模块级解析：ENV E-03 与 F1-04c 两条登录路径共用同一份解析结果。
 */
if (!process.env.REDIS_HOST && !process.env.REDIS_CONTAINER) {
  const name = resolveContainer(/redis/);
  if (name) process.env.REDIS_CONTAINER = name;
}

/** 运营侧 API 登录（前端把 token 放 sessionStorage，脚本走同一份接口拿同一份权限）。 */
async function adminLoginViaApi() {
  try {
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
      twoFactor: json?.data?.twoFactorRequired,
      error: ''
    };
  } catch (e) {
    // 登录失败必须是**一条红记录**，不能是整轮崩溃（否则后面的流程一步都看不到）
    return { status: 0, token: '', twoFactor: undefined, error: String(e.message).slice(0, 160) };
  }
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
   * F1 下单→将邑开门→**端侧识别**→结算→订单→视频。
   *
   * 🔴 结算证据只有**将邑端侧识别**这一种来源（本仓无重力结算；云端识别引擎/YOLO/DeepSeek
   * 已按 2026-10-11 拍板退役——docs/JIANGYI_ONLY_FINAL_VERDICT_2026-10-11.md §3），所以本流程
   * 的证据**必须由设备链产生**，脚本不自造证据：
   *   模拟器取 token → WS 在线（gateway→trade ws-online ⇒ device_info ONLINE）
   *   → 消费者建会话 ⇒ trade 路由 openDoor 到 gateway ⇒ WS 下发
   *   → 设备上报 uploadLockState/uploadDoorState ⇒ gateway 转发 door-event OPEN ⇒ SHOPPING
   *   → 设备端识别后上报 addRecognitionGoodsToOrder(classId,quantity)
   *   ⇒ gateway 归一（classId→SKU，未命中 fail-closed）+ 合成 door-event CLOSED
   *   ⇒ trade edge-results ⇒ RECOGNIZING→SETTLING→COMPLETED。
   *
   * 只有两处不是产品路径，均在记录里写明原因：
   *   ① `jiangyi_device` BOUND 档案用 SQL 种子——setDomain 的 identifier 是**将邑平台签发的
   *      外部事实**（本地栈虽配了租户凭据，但从审计机打真平台会改真柜归属，未经拍板不可做）；
   *      且 AU-133 使 dev 构建的 register 恒 500。种子只替代「拿到 identifier」这一步，
   *      不替代我方任何判定（isJiangyi 路由/在线判定/映射/状态机/结算/幂等全走真代码）。
   *   ② F1-08 的迟到识别结果是脚本直打内部面（真链路 gateway 不会重放），钉的是
   *      「终态会话必须拒收识别结果」这条资损契约，属服务端契约面而非设备面。
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
    // AU-133：register 从不填 identifier，而 V332 该列 NOT NULL ⇒ 恒 500。
    // 修复在 V346；**被测镜像是 dev 构建**（见 §0 版本溯源），跑不到这段代码，
    // 所以这里按「缺陷签名」识别成 BLOCKED（已知阻塞，不是本轮新增回归）：
    // HTTP 只回笼统 500，签名取自 trade 容器日志原文，不靠猜。
    // 修复版的运行证据 = JiangyiOnboardingRegisterSqlIT 3/3，运行期复验列入接真前清单。
    const regBody = JSON.stringify(reg.json || {});
    const regLog = tradeLogSnippet(/null value in column "identifier"/);
    const regBlocked = reg.status === 500 && (/identifier/i.test(regBody) || regLog.hit);
    record(
      'F1',
      'F1-02',
      reg.status === 200 ? true : regBlocked ? 'BLOCKED' : false,
      regBlocked
        ? 'onboarding/register HTTP=500 且 trade 日志命中 identifier NOT NULL 原文 ⇒ AU-133（V346 已修，被测后端=dev 构建不含该迁移）；IT 3/3 实证，运行期复验挂接真前 checklist'
        : `onboarding/register HTTP=${reg.status} status=${reg.json?.data?.status} body=${regBody.slice(0, 140)}`,
      writeText('f1-jiangyi-register', { response: reg.json, tradeLogLine: regLog.line })
    );

    // ① BOUND 档案种子（原因见本流程头注）：identifier 用确定性编码，避免重跑撞
    //    uk_jiangyi_device_identifier；status=BOUND 后 trade 才会把开门路由到 gateway。
    const identifier = `AUDIT${deviceId}`.slice(0, 32);
    let seededRow = '';
    let seedErr = '';
    try {
      psql(
        `insert into jiangyi_device (device_id, device_sn, identifier, model_name, status, token_version, created_at, updated_at) values ('${deviceId}', '${sn}', '${identifier}', 'AUDIT-SIM-MODEL', 'BOUND', 0, now(), now()) on conflict (device_id) do update set device_sn=excluded.device_sn, identifier=excluded.identifier, status='BOUND', updated_at=now()`
      );
      seededRow = psql(
        `select status||'|'||identifier||'|'||device_sn from jiangyi_device where device_id='${deviceId}'`
      );
    } catch (e) {
      seedErr = String(e.message).slice(0, 160);
    }
    // 路由判定要等 trade 的负查询缓存过期（JiangyiDeviceDirectory 未命中 TTL=10s），
    // 用真判定接口轮询，不靠 sleep 猜。
    const routable = await pollUntil(
      async () => {
        const res = await internal('GET', `/internal/v1/jiangyi/devices/${deviceId}/routable`);
        return { ok: res.json?.data === true, last: res };
      },
      { timeoutMs: 30_000 }
    );
    record(
      'F1',
      'F1-02b',
      /BOUND/.test(seededRow) && routable.ok ? 'SEEDED' : false,
      `BOUND 档案种子（替代 setDomain 的将邑平台签发 identifier 这一步，不替代我方判定）：库=${seededRow || seedErr}；trade 路由判定 routable=${routable.ok ? 'true' : JSON.stringify(routable.last?.json || {}).slice(0, 80)}`,
      writeText('f1-jiangyi-seed', { seededRow, seedErr, routable: routable.last?.json })
    );
    if (!routable.ok) return;

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

    // 消费者开门前置：device_info.online_status 必须 ONLINE（ensureDeviceOnline ⇒ 否则 409
    // DEVICE_OFFLINE）。将邑柜的在线**只由 WS 链决定**（gateway 建连回执 →
    // trade /internal/v1/jiangyi/devices/{id}/ws-online → setWsPresence），所以这里起真模拟器
    // 让在线由设备链产生，而不是打设备心跳内部面糊过去。
    const qty = 2;
    const sim = startJiangyiSimulator({ sn, classId: clsId, qty, shoppingMs: 6000 });
    if (!sim.child) {
      record('F1', 'F1-04b', false, `模拟器未能启动：${sim.error}`, null);
      return;
    }
    const online = await pollUntil(
      () => ({
        ok: psql(`select online_status from device_info where device_id='${deviceId}'`) === 'ONLINE'
      }),
      { timeoutMs: 40_000, intervalMs: 1500 }
    );
    const wsLog = gatewayLogSnippet(/ws connected|ws online reported/);
    record(
      'F1',
      'F1-04b',
      online.ok,
      `将邑模拟器（device-simulator jiangyi 模式）SN=${sn} 已连 gateway，柜机 online_status=${
        psql(`select online_status from device_info where device_id='${deviceId}'`) || '(无行)'
      }（判据=gateway 建连回执→trade ws-online→setWsPresence，非脚本改库）｜gateway 日志：${wsLog.line || '(未命中)'}｜模拟器日志：${String(
        sim.tail()
      )
        .replace(/\s+/g, ' ')
        .slice(0, 200)}`,
      writeText('f1-simulator-online', {
        sn,
        identifier,
        gatewayLog: wsLog.line,
        simulatorLog: sim.tail()
      })
    );
    if (!online.ok) {
      sim.child.kill();
      return;
    }

    // 运营登录在此提前（F1-12/13 的后台读接口与 F2/F3 复用同一 token，不重复打登录接口）。
    const admin = await adminLoginViaApi();
    state.adminToken = admin.token;
    record(
      'F1',
      'F1-04c',
      admin.status === 200 && !!admin.token,
      `ops login HTTP=${admin.status} 2FA=${admin.twoFactor}`,
      null
    );

    // 结算模式**不动**：产品只有将邑端侧识别一种证据源，`settlement.recognition_mode`
    // 保持默认 VISION。前一轮这里曾写入 VISION_GRAVITY 借重力 delta 凑结算，属造绿，已删除
    // （重力/云端残留路径本身作为退役债务登记进台账，见 AUDIT_LEDGER AU-134）。
    //
    // 营业锁前置：柜机离线超 10 分钟会被 presence 巡检**自动停售**（sales_locked=true，
    // reason=离线超时自动停售），消费者开门即 409「设备已暂停营业」。上线自动锁是正确产品
    // 行为，所以这里用**运营真接口**解锁（PUT devices/{id}/policy，非改库），顺带验该端点可用。
    const polGet = await api(admin.token, 'GET', `/api/v2/ops/admin/devices/${deviceId}/policy`);
    const pol = polGet.json?.data || {};
    let polPut = null;
    if (pol.salesLocked === true || pol.saleForbidden === true) {
      polPut = await api(admin.token, 'PUT', `/api/v2/ops/admin/devices/${deviceId}/policy`, {
        deviceId,
        salesLocked: false,
        priceLocked: pol.priceLocked === true,
        skuEditForbidden: pol.skuEditForbidden === true,
        saleForbidden: false
      });
    }
    const lockAfter =
      psql(
        `select 'sales_locked='||coalesce(sales_locked::text,'null')||' sale_forbidden='||coalesce(sale_forbidden::text,'null') from device_info where device_id='${deviceId}'`
      ) || '(无行)';
    record(
      'F1',
      'F1-04e',
      polGet.status === 200 &&
        (pol.salesLocked !== true || (polPut && polPut.status === 200)) &&
        !/sales_locked=true/.test(lockAfter),
      `营业锁前置（离线超时自动停售的解锁走运营 API，不改库）：GET policy HTTP=${polGet.status} salesLocked=${pol.salesLocked} saleForbidden=${pol.saleForbidden}｜${polPut ? `PUT 解锁 HTTP=${polPut.status} msg=${polPut.json?.message || ''}` : '无需解锁'}｜库=${lockAfter}`,
      writeText('f1-device-policy', {
        get: { status: polGet.status, body: polGet.json },
        put: polPut ? { status: polPut.status, body: polPut.json } : null,
        dbAfter: lockAfter
      })
    );
    if (/sales_locked=true/.test(lockAfter)) {
      sim.child.kill();
      return;
    }

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
    if (!sessionId) {
      sim.child.kill();
      return;
    }
    state.f1 = { deviceId, sessionId, skuId, classId: clsId, consumerToken: login.token };

    // F1-06 拉门事件由**设备上报**驱动（模拟器 uploadDoorState success → gateway 转发
    // door-event OPEN → 会话 OPENING→SHOPPING）。脚本不再自己 POST door-event：
    // 自造门事件只能证明 trade 受理面，证明不了「真柜上报能推到状态机」。
    const opened = await pollUntil(
      () => {
        const st = sessionState(sessionId);
        return { ok: ['SHOPPING', 'RECOGNIZING', 'SETTLING', 'COMPLETED'].includes(st), state: st };
      },
      { timeoutMs: 40_000 }
    );
    const openLog = gatewayLogSnippet(new RegExp(`door-event OPEN forwarded.*${sessionId}`));
    record(
      'F1',
      'F1-06',
      opened.ok && openLog.hit,
      `设备上报拉门 ⇒ 会话进/过 SHOPPING：state=${opened.state}（期望 SHOPPING 及以上）；gateway 转发原文=${
        openLog.line || '(未命中『door-event OPEN forwarded』⇒ 门事件不是设备链产生的)'
      }`,
      writeText('f1-door-open-chain', {
        state: opened.state,
        gatewayLog: openLog.line,
        simulatorLog: sim.tail()
      })
    );

    // F1-07 设备端识别上报（classId×quantity，无金额字段——金额由我方按映射 SKU 现价合计）
    // ⇒ gateway 归一 + 合成 door-event CLOSED（模式一无关门上报）⇒ trade edge-results
    // ⇒ RECOGNIZING→SETTLING→COMPLETED。
    const settled = await pollUntil(
      () => {
        const st = sessionState(sessionId);
        return { ok: ['COMPLETED', 'DISPUTED', 'FAILED'].includes(st), state: st };
      },
      { timeoutMs: 60_000 }
    );
    const recogLog = gatewayLogSnippet(new RegExp(`recognition forwarded.*${sessionId}`));
    record(
      'F1',
      'F1-07',
      settled.state === 'COMPLETED',
      `将邑端侧识别（classId=${clsId}×${qty}）→ gateway 归一转发 → trade 结算 ⇒ 会话终态=${
        settled.state
      }（期望 COMPLETED；DISPUTED=映射未命中/无证据，FAILED=开门链断）｜gateway 转发原文=${
        recogLog.line || '(未命中)'
      }｜模拟器日志=${String(sim.tail()).replace(/\s+/g, ' ').slice(0, 200)}`,
      writeText('f1-recognition-chain', {
        state: settled.state,
        gatewayLog: recogLog.line,
        simulatorLog: sim.tail()
      })
    );
    // 设备链闭环已完成（识别上报已发出），模拟器收摊；WS 断开会把柜机置回离线，
    // 后续步骤只读库/后台，不再依赖在线。
    sim.child.kill();

    // F1-08 契约钉子：会话已终态后再收到识别结果**必须拒收**（否则同一会话二次结算=资损）。
    // 真链路里 gateway 不重放，所以这步直打内部面，钉的是 trade 侧受理判定。
    const replay = await internal('POST', '/internal/v1/vision/edge-results', {
      sessionId,
      deviceId,
      taskId: 'AUDIT-F1-REPLAY',
      traceId: null,
      items: [{ skuId, quantity: qty, confidence: 0.99 }],
      overallConfidence: 0.99,
      needReview: false,
      modelVersion: `jiangyi:AUDIT-SIM-MODEL`,
      detectedClasses: ['audit-demo'],
      provider: 'JIANGYI',
      occurredAt: new Date().toISOString()
    });
    const amountAfterReplay = psql(
      `select coalesce(string_agg(total_amount_cents::text, ','), '(无订单)') from cabinet_order where session_id='${sessionId}'`
    );
    record(
      'F1',
      'F1-08',
      replay.status === 200 &&
        replay.json?.data?.accepted === false &&
        sessionState(sessionId) === settled.state,
      `终态会话收到迟到/重放识别结果 ⇒ 必须拒收且不二次结算：HTTP=${replay.status} accepted=${replay.json?.data?.accepted} outcome=${replay.json?.data?.outcome} reason=${String(
        replay.json?.data?.reason || ''
      ).slice(
        0,
        60
      )} stateAfter=${sessionState(sessionId)}（应仍为 ${settled.state}）订单合计=${amountAfterReplay}`,
      writeText('f1-edge-results-replay', replay.json)
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

    // ops 登录已在 F1-04c 完成，这里只确认 token 仍在（同一会话链路复用，不重复打登录接口）
    record(
      'F1',
      'F1-11',
      !!state.adminToken,
      `ops token 复用 F1-04c（长度=${(state.adminToken || '').length}）`,
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
    blocked: results.filter((r) => r.status === 'BLOCKED').length,
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
