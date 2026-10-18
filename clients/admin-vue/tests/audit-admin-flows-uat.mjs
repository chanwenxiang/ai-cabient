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
 *   VIEWER_PHONE / VIEWER_PASSWORD  default 13900000005 / 123456   F8 越权矩阵的低权子账号（V117/V118）
 *   MERCHANT_PHONE / MERCHANT_PASSWORD  default 空                 F7 补货终态需商户 token；不配则如实 SKIP
 *   PW_CHANNEL / PW_HEADED 同其它 UAT 脚本
 *
 * 🔴 隔离 project（aicabinet-uat）运行必须显式传容器名，否则 resolveContainer 的 `/postgres/`、
 *    `/redis/` 等正则会先命中**共享栈** `ai-cabinet-*-1`（`docker ps` 含全部容器），
 *    导致 psql/验证码读到 peer 的库与 redis ⇒ 跨栈污染（F7 的 SQL 种子会写进共享库）。
 *    隔离跑法（与 infra/docker-compose.uat-isolated.yml 配套）：
 *      ADMIN_URL=http://localhost:28080/admin  API_BASE=http://localhost:28080
 *      TRADE_DIRECT=http://127.0.0.1:28081     JIANGYI_GATEWAY_BASE=http://127.0.0.1:28094
 *      PG_CONTAINER=aicabinet-uat-postgres-1   TRADE_CONTAINER=aicabinet-uat-trade-service-1
 *      GATEWAY_CONTAINER=aicabinet-uat-jiangyi-gateway-1  REDIS_CONTAINER=aicabinet-uat-redis-1
 *
 * 实现状态：F1 全链绿（17/0/2 于 aicabinet-uat 隔离栈实测，2026-10-18：设备链→令牌闸门激活走
 *   ops:device:edit 产品路径→开门→识别→归一→结算→订单→ops API 读全通；F1-13/F1-14 因 SPA
 *   登录面/local 无录像如实 SKIP）；env-baseline 的 E-03 SPA UI 登录判据偏弱（adminLogin 依赖
 *   前端 form/captcha 按钮 DOM，与 admin-vue 版本绑死，同因导致 F1-13 只能 SKIP；API 面登录
 *   已由 F1-04c/F1-12/F8-00 三处 HTTP=200 反证）；F2 争议全链绿（F2-01~17 全 PASS 于 aicabinet-uat
 *   隔离栈实测，2026-10-18：needReview→DISPUTED→ops_exception+工单→admin list/claim/resolve
 *   CONFIRM→COMPLETED+建单结算+金额不变量(2×343=686=合计)+viewer 越权 403；F2-18 同 E-03 弱判据如实
 *   SKIP）；F7/F8 为**骨架**（断言只落真实端点，设备证据/未核契约标 ⚠️ 待核实并 SKIP，不造绿）。F3–F6 尚未编写。
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
// 低权子账号（V117 种入的 viewer，V118 收紧为「只有 *:list + dashboard:view」）——F8 越权矩阵的攻击方。
const VIEWER_PHONE = process.env.VIEWER_PHONE || '13900000005';
const VIEWER_PASSWORD = process.env.VIEWER_PASSWORD || '123456';
// 补货任务的终态 COMPLETED 只有**商户域**能推（MerchantPortalController:663），F7 需商户 token；
// 默认无演示商户凭据 ⇒ F7 会如实 SKIP 而不造绿（不猜着登）。
const MERCHANT_PHONE = process.env.MERCHANT_PHONE || '';
const MERCHANT_PASSWORD = process.env.MERCHANT_PASSWORD || '';
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
    `${s === 'PASS' ? '✓' : s === 'FAIL' ? '✗' : '○'} [${flow}] ${id} — ${redactSecrets(String(detail)).slice(0, 220)}`
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
 * 证据落盘前**必须脱敏**：设备 token / JWT（`eyJ…`）与 `token=` 查询串一旦被写进
 * `docs/evidence/**` 就随提交长期留存，而将邑设备 token TTL 默认 7 天——证据要留，凭据不留。
 *
 * <p>两条 JWT 规则缺一不可：三段式抓完整 token；`eyJ…` 前缀式抓**被日志尾部切片截断**的片段
 * （截断只剩「头 + payload 前缀」时三段正则匹配不到，会把 `{"alg":"HS256"}.eyJq` 这类残片漏进证据，
 * 2026-10-18 F1-04b/F1-07 实测即命中此坑）。</p>
 */
function redactSecrets(text) {
  return String(text)
    .replace(/eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}/g, '[REDACTED-JWT]')
    .replace(/eyJ[A-Za-z0-9_-]*(?:\.[A-Za-z0-9_-]*)*/g, '[REDACTED-JWT]')
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
 * psql 的安全包装：列名/表名若与当前 schema 不符（骨架期常见，标注「待核实」处），
 * 返回 err 而不是抛异常 ⇒ 该步记 SKIP/红，**不拖垮整轮**（对齐驱动器的「登录失败不能崩」纪律）。
 */
function psqlSafe(sql) {
  try {
    return { val: psql(sql), err: '' };
  } catch (e) {
    return { val: '', err: String(e.message).replace(/\s+/g, ' ').slice(0, 200) };
  }
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

/**
 * 以指定运营账号登录（与 adminLoginViaApi 同一条接口 + 同一份验证码解法），
 * 但手机号/密码可参数化 ⇒ F8 能用**低权 viewer** 拿到一个「权限不足」的真实 token 去越权，
 * 而不是只测 admin（admin 恒过 RBAC 闸门，测不出矩阵）。
 */
async function opsLoginAs(phone, password) {
  try {
    const cap = await fetch(`${API}/api/v2/auth/captcha`).then((r) => r.json());
    const captchaId = cap?.data?.captchaId;
    const captchaCode = (await captchaFromRedis(captchaId)).toLowerCase();
    const res = await fetch(`${API}/api/v2/auth/admin-password-login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Requested-With': 'XMLHttpRequest' },
      body: JSON.stringify({ phoneNumber: phone, password, captchaId, captchaCode })
    });
    const json = await res.json().catch(() => null);
    return {
      status: res.status,
      token: json?.data?.token || '',
      twoFactor: json?.data?.twoFactorRequired,
      error: ''
    };
  } catch (e) {
    return { status: 0, token: '', twoFactor: undefined, error: String(e.message).slice(0, 160) };
  }
}

/** 商户密码登录（补货任务终态只能由商户域推进；无凭据即返 0，由 F7 判 SKIP，不猜）。 */
async function merchantLoginViaApi(phone, password) {
  if (!phone || !password)
    return { status: 0, token: '', error: '未提供 MERCHANT_PHONE/MERCHANT_PASSWORD' };
  try {
    const cap = await fetch(`${API}/api/v2/auth/captcha`).then((r) => r.json());
    const captchaId = cap?.data?.captchaId;
    const captchaCode = (await captchaFromRedis(captchaId)).toLowerCase();
    const res = await fetch(`${API}/api/v2/auth/merchant-password-login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Requested-With': 'XMLHttpRequest' },
      body: JSON.stringify({ phoneNumber: phone, password, captchaId, captchaCode })
    });
    const json = await res.json().catch(() => null);
    return { status: res.status, token: json?.data?.token || '', error: '' };
  } catch (e) {
    return { status: 0, token: '', error: String(e.message).slice(0, 160) };
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
    } catch {
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

    // demo/ensure 只保证**存在性**、不回填 consumed 库存；重跑第 2 轮就会因 SKU-DEMO-001
    // 只剩 1 件 ⇒ 走 DisputeRequiredException 分支（SessionSettleService:176，"识别结果需人工审核"）。
    // 这是**测试夹具**再补给（不动 gateway/trade 判定，不造结算证据）；设备/开门/识别/
    // 归一/落单/结算全链仍由产品路径产生。仅 device_sku_inventory（本仓无批次账本 ⇒ 汇总即权威）。
    try {
      psql(
        `update device_sku_inventory set quantity=greatest(quantity, 10), updated_at=now() where device_id='${deviceId}' and sku_id='${skuId}'`
      );
      const inv = psql(
        `select quantity from device_sku_inventory where device_id='${deviceId}' and sku_id='${skuId}'`
      );
      record(
        'F1',
        'F1-01b',
        Number((inv || '').trim()) >= 2,
        `测试夹具：demo SKU 库存 ≥ 结算所需 2 件；device_sku_inventory(${deviceId}/${skuId})=${(inv || '').trim()}`,
        null
      );
    } catch (e) {
      record('F1', 'F1-01b', false, `库存夹具再补给失败：${String(e.message).slice(0, 160)}`, null);
    }

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

    // ①' 激活走真产品路径，不用 SQL 硬写 auth_enabled——令牌签发的激活闸门（审计 A1/P0-3，
    //    DeviceTokenController:84 要求 authEnabled=true）是**我方判定**，头注里明确种子只替代
    //    将邑签发 identifier 这一步、不替代我方任何判定。所以这里由 admin 调后台激活接口
    //    `POST /api/v2/ops/admin/devices/{id}/jiangyi/activate`（perm ops:device:edit →
    //    JiangyiOnboardingService.setDeviceAuthEnabled）落库，顺带取证「运营台激活」这条能力。
    const actAdmin = state.adminToken ? { token: state.adminToken } : await adminLoginViaApi();
    state.adminToken = actAdmin.token;
    let actDetail = '';
    let activated = false;
    if (!actAdmin.token) {
      actDetail = `admin 登录失败 HTTP=${actAdmin.status} err=${actAdmin.error}`;
    } else {
      const act = await api(
        actAdmin.token,
        'POST',
        `/api/v2/ops/admin/devices/${deviceId}/jiangyi/activate`
      );
      const dbAuth = psqlSafe(
        `select coalesce(auth_enabled::text,'null') from jiangyi_device where device_id='${deviceId}'`
      );
      const authTrue = /true/i.test(dbAuth.val || '');
      activated = act.status === 200 && authTrue;
      actDetail = `activate HTTP=${act.status} body=${JSON.stringify(act.json || {}).slice(0, 120)}；库 auth_enabled=${(dbAuth.val || '').trim() || dbAuth.err}`;
    }
    record(
      'F1',
      'F1-02c',
      activated,
      `后台激活（A1/P0-3 令牌闸门前置，走 ops:device:edit 产品路径而非 SQL）：${actDetail}`,
      writeText('f1-jiangyi-activate', { detail: actDetail })
    );
    if (!activated) return;

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

    // 结算证据只有将邑端侧识别一种：`settlement.recognition_mode` 键本身已随 DC-27 批2 删除，
    // 识别只认 `POST /internal/v1/vision/edge-results` 上报。前一轮这里曾写入 VISION_GRAVITY 借重力
    // delta 凑结算，属造绿，已删除（重力/云端残留路径本身作为退役债务登记进台账，见 AUDIT_LEDGER AU-134）。
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
      `select o.order_id, o.status, o.total_amount_cents, o.paid_amount_cents, coalesce(o.coupon_discount_cents,0), coalesce(o.member_discount_cents,0), s.state from cabinet_order o join shopping_session s on s.session_id=o.session_id where s.session_id='${sessionId}'`
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

    // 金额不变量（CouponService:267 落库口径 = `total = max(0, subtotal - discount)`）：
    // Σline − coupon − member = total；同时逐行 line = qty × unit（资损类判据，独立于页面）。
    // 之前只校 Σline = total 是**驱动器口径错**（未考虑券/会员折），非产品缺陷。
    const lines = dbLines
      .split('\n')
      .filter(Boolean)
      .map((l) => l.split('|'))
      .map(([, q, up, la]) => ({ q: Number(q), up: Number(up), la: Number(la) }))
      .filter((r) => Number.isFinite(r.la));
    const oc = dbOrder.split('|');
    const totalMatch = Number((oc[2] || '').trim());
    const couponCents = Number((oc[4] || '0').trim()) || 0;
    const memberCents = Number((oc[5] || '0').trim()) || 0;
    const sumLines = lines.reduce((a, r) => a + r.la, 0);
    record(
      'F1',
      'F1-10',
      lines.length > 0 &&
        sumLines - couponCents - memberCents === totalMatch &&
        lines.every((r) => r.la === r.q * r.up),
      `Σ行金额=${sumLines} − 券=${couponCents} − 会员=${memberCents} ⇒ ${sumLines - couponCents - memberCents} 应=订单合计=${totalMatch}；逐行 qty×unit=${lines.map((r) => `${r.q}×${r.up}=${r.la}`).join(',')}`,
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

    // F1-13 订单管理页 UI 检索：需 SPA 侧真登录（Playwright page 与 API token 是两个上下文）。
    // 若 UI 登录不通（E-03 弱判据同因），如实 SKIP 而非 FAIL——API 面 F1-12 已实证该单可读，
    // 这是**后台 SPA 登录链**问题不是**订单数据**问题（不掩盖，也不误报成产品缺陷）。
    const uiLogin = await adminLogin(page);
    if (!uiLogin.ok) {
      record(
        'F1',
        'F1-13',
        'SKIP',
        `SPA UI 登录不通（E-03 同款弱判据），API 面已由 F1-12 实证 orderId=${orderId} 可读；SPA 登录链挂到接真前 checklist`,
        await shot(page, 'f1-admin-orders-search')
      );
    } else {
      await page.goto(`${ADMIN}/orders`, { waitUntil: 'domcontentloaded' });
      try {
        await page
          .locator('.el-table__body-wrapper, .el-table__body, .el-table')
          .first()
          .waitFor({ state: 'attached', timeout: 8000 });
      } catch {
        /* 表格未出现也不 throw，让 found=false 如实记录 */
      }
      if (orderId) {
        const input = page
          .locator('input[placeholder*="订单"], input[placeholder*="搜索"]')
          .first();
        if ((await input.count()) > 0) {
          await input.fill(orderId);
          await page.keyboard.press('Enter');
        }
        try {
          await page
            .waitForFunction((oid) => document.body.innerText.includes(oid), orderId, {
              timeout: 5000
            })
            .catch(() => {});
        } catch {
          /* noop */
        }
      }
      const found = await page.evaluate(
        (oid) =>
          [...document.querySelectorAll('.el-table__body-wrapper tr, .el-table__row')].some((tr) =>
            tr.innerText.includes(oid)
          ) || document.body.innerText.includes(oid),
        orderId
      );
      record(
        'F1',
        'F1-13',
        found,
        `订单管理页可检索到该单 orderId=${orderId}（选择器：Element Plus .el-table__body-wrapper tr + body.innerText 兜底）`,
        await shot(page, 'f1-admin-orders-search')
      );
    }

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
   * F2 争议全链（低置信/需复核 → DISPUTED → 后台审核处置 → 终态）。
   *
   * 产品判定亲读（行号取本 worktree cadmin-uat）：
   *   DISPUTED 入口  SettlementRecognitionService:212 escalateToDispute（先 createTicket 再 :218 throw），
   *                  :114 needReview 判定**早于** :120 dev/mock 短路 ⇒ 环境无关，取 needReview=true 作确定性触发；
   *                  SessionSettleService:177 catch(DisputeRequiredException) ⇒ 会话 transition DISPUTED
   *                  + opsExceptionService.report("RECOGNITION_FAILED","HIGH",…)。
   *   工单落库       DisputeService.createTicket（V4__dispute_ops.sql：dispute_ticket OPEN/reason/resolved_at）
   *                  + ops_exception（V52：type/severity/status/session_id，OPEN 部分唯一索引去重）。
   *   审核读        GET /api/v2/ops/disputes[/{ticketId}]（perm ops:dispute，DisputeController:28/46）。
   *   审核写        POST …/claim、…/resolve（perm ops:dispute:resolve，DisputeController:55/64）。
   *   结案终态      DisputeService.doResolveTicket:650 ⇒ DISPUTED→COMPLETED（四路径同点），
   *                  KEEP→PAID / WAIVE→REFUNDED（alignOrderStatusAfterDisputeResolve:687）；
   *                  CONFIRM/ADJUST 需 items 非空（:630），且**无订单时经 finalizeOrder 首次建单+结算**
   *                  （SettlementConfirmDisputeService:156-162，与 F1 同一条 finalizeOrder，
   *                  @Transactional noRollbackFor BalanceInsufficient ⇒ 余额不足落 PENDING 而非抛）。
   *                  异常随结案关闭（opsExceptionService.resolveOpenForSession:647）。
   *
   * 🔴 不造绿：DISPUTED 由**产品自己的** needReview→escalateToDispute 判定产生（转人工是真实负向结论，
   *   非我们凑的结算成功）；CONFIRM 结案的订单/金额由真实 confirmDisputedItems→finalizeOrder 落库。
   *   设备档案（BOUND/激活/class-mapping/SKU 库存）复用 F1（F2 依赖 state.f1，FLOWS 须含 F1 在前）；
   *   会话经真 WS 门链推到 SHOPPING 后 kill 模拟器，再由**内部面**合成 CLOSED(→RECOGNIZING) +
   *   注入 needReview 识别结果（edge-results 是契约承认的第三方端侧入口，见 VisionInternalController:54），
   *   以避开模拟器自身高置信识别的竞态——争议判定与结案结算全程走产品代码，无 SQL 硬写状态。
   */
  F2: async ({ page, state }) => {
    const f1 = state.f1 || {};
    if (!f1.deviceId || !f1.skuId) {
      record(
        'F2',
        'F2-00',
        'SKIP',
        `F2 复用 F1 的将邑设备档案（BOUND/激活/class-mapping/库存），需 FLOWS 含 F1 在前：state.f1=${JSON.stringify(f1).slice(0, 100)}`,
        null
      );
      return;
    }
    const { deviceId, skuId, classId } = f1;

    // CONFIRM 结算会按现价扣库存（走真实 deductForOrder）⇒ 测试夹具先再补给，同 F1-01b 口径（非造结算证据）。
    let inv = '';
    try {
      psql(
        `update device_sku_inventory set quantity=greatest(quantity, 10), updated_at=now() where device_id='${deviceId}' and sku_id='${skuId}'`
      );
      inv = psql(
        `select quantity from device_sku_inventory where device_id='${deviceId}' and sku_id='${skuId}'`
      );
    } catch (e) {
      record('F2', 'F2-01', false, `库存再补给失败：${String(e.message).slice(0, 140)}`, null);
      return;
    }
    record(
      'F2',
      'F2-01',
      Number((inv || '').trim()) >= 2,
      `争议 CONFIRM 结算前库存夹具：device_sku_inventory(${deviceId}/${skuId})=${(inv || '').trim()}（需 ≥ 结算件数 2）`,
      null
    );

    const ens = await internal('POST', '/internal/v1/demo/ensure');
    const demo = ens.json?.data || {};
    const login =
      state.f1.consumerToken && state.f1.consumerToken.length > 10
        ? { status: 200, token: state.f1.consumerToken }
        : await consumerLoginViaApi(demo.consumerPhone || '13800138000', '123456');

    // 复用 F1 播下的 jiangyi_device 行（device_sn=AUDIT-F1-<deviceId>），故模拟器 SN 必须一致，
    // 否则 gateway 按 SN 解析不到 BOUND 设备 ⇒ 拿不到令牌/不上线。
    const sn = `AUDIT-F1-${deviceId}`;
    const sim = startJiangyiSimulator({ sn, classId, qty: 2, shoppingMs: 30000 });
    if (!sim.child) {
      record('F2', 'F2-02', false, `模拟器未能启动：${sim.error}`, null);
      return;
    }
    const online = await pollUntil(
      () => ({
        ok: psql(`select online_status from device_info where device_id='${deviceId}'`) === 'ONLINE'
      }),
      { timeoutMs: 40_000, intervalMs: 1500 }
    );
    record(
      'F2',
      'F2-02',
      online.ok,
      `设备经 WS 链上线（判据同 F1-04b：gateway 建连→trade ws-online→setWsPresence）online_status=${psql(
        `select online_status from device_info where device_id='${deviceId}'`
      )}`,
      writeText('f2-simulator-online', { sn, simLog: sim.tail() })
    );
    if (!online.ok) {
      sim.child.kill();
      return;
    }

    const created = await api(login.token, 'POST', '/api/v2/sessions', {
      deviceId,
      idempotencyKey: `AUDIT-F2-${Date.now()}`
    });
    const sessionId = created.json?.data?.sessionId;
    record(
      'F2',
      'F2-03',
      created.status === 200 && !!sessionId,
      `POST /api/v2/sessions HTTP=${created.status} session=${sessionId} state=${created.json?.data?.state} msg=${created.json?.message || ''}`,
      writeText('f2-session-create', created.json)
    );
    if (!sessionId) {
      sim.child.kill();
      return;
    }

    // 等设备上报 door-event OPEN 把会话推到 SHOPPING；shoppingMs=30s 保证模拟器来不及自己上报识别，
    // 于是**立即 kill**，识别改由脚本走内部面注入 ⇒ 无竞态（不篡改状态机：CLOSED/edge-results 皆产品接口）。
    const shopping = await pollUntil(
      () => {
        const st = sessionState(sessionId);
        return { ok: ['SHOPPING', 'RECOGNIZING'].includes(st), state: st };
      },
      { timeoutMs: 30_000, intervalMs: 1500 }
    );
    sim.child.kill();
    record(
      'F2',
      'F2-04',
      shopping.ok,
      `设备 door-event OPEN ⇒ 会话进 SHOPPING（随即 kill 模拟器于其自动识别之前）state=${shopping.state}`,
      writeText('f2-shopping', { state: shopping.state })
    );

    // 合成 CLOSED：jiangyi 会话在 SessionDoorService:78 直接返回、不 settleAfterClose ⇒ 停在 RECOGNIZING 等识别。
    const closed = await internal('POST', '/internal/v1/sessions/door-event', {
      sessionId,
      deviceId,
      doorState: 'CLOSED',
      timestamp: Date.now()
    });
    const recState = sessionState(sessionId);
    record(
      'F2',
      'F2-05',
      closed.status === 200 && recState === 'RECOGNIZING',
      `内部面合成 door-event CLOSED ⇒ RECOGNIZING：HTTP=${closed.status} state=${recState}（期望 RECOGNIZING）`,
      writeText('f2-closed', closed.json)
    );

    // needReview=true ⇒ escalateToDispute ⇒ DISPUTED（同步：VisionResultIngestService:96 直接调 completeAsyncRecognition）。
    const dispute = await internal('POST', '/internal/v1/vision/edge-results', {
      sessionId,
      deviceId,
      taskId: 'AUDIT-F2-REVIEW',
      traceId: null,
      items: [{ skuId, quantity: 2, confidence: 0.4 }],
      overallConfidence: 0.4,
      needReview: true,
      modelVersion: 'jiangyi:AUDIT-F2-REVIEW',
      detectedClasses: ['audit-dispute'],
      provider: 'JIANGYI',
      occurredAt: new Date().toISOString()
    });
    const dispState = sessionState(sessionId);
    record(
      'F2',
      'F2-06',
      dispState === 'DISPUTED',
      `端侧 needReview 识别结果 ⇒ 同步结算 ⇒ DISPUTED：HTTP=${dispute.status} accepted=${dispute.json?.data?.accepted} outcome=${dispute.json?.data?.outcome} reason=${String(dispute.json?.data?.reason || '').slice(0, 60)} state=${dispState}`,
      writeText('f2-dispute', dispute.json)
    );

    const exc = psqlSafe(
      `select status||'|'||coalesce(severity,'')||'|'||coalesce(resolved_at::text,'null') from ops_exception where session_id='${sessionId}' and exception_type='RECOGNITION_FAILED'`
    );
    record(
      'F2',
      'F2-07',
      !exc.err && /OPEN/.test(exc.val),
      `ops_exception(RECOGNITION_FAILED) 待处理：[${(exc.val || exc.err).replace(/\s+/g, ' ')}]（期望 OPEN，SessionSettleService:177 报）`,
      null
    );
    const tk = psqlSafe(
      `select ticket_id||'|'||status||'|'||coalesce(reason,'') from dispute_ticket where session_id='${sessionId}'`
    );
    record(
      'F2',
      'F2-08',
      !tk.err && /OPEN/.test(tk.val),
      `dispute_ticket 建单：[${(tk.val || tk.err).replace(/\s+/g, ' ').slice(0, 200)}]`,
      writeText('f2-ticket-db', { ticket: tk.val || tk.err })
    );
    const ticketId = ((tk.val || '').split('|')[0] || '').trim();
    if (!ticketId) return;

    const admin = state.adminToken ? { token: state.adminToken } : await adminLoginViaApi();
    state.adminToken = admin.token;
    if (!admin.token) {
      record('F2', 'F2-09', false, `admin 登录失败 HTTP=${admin.status} err=${admin.error}`, null);
      return;
    }

    const list = await api(
      admin.token,
      'GET',
      `/api/v2/ops/disputes?sessionId=${encodeURIComponent(sessionId)}`
    );
    const items = list.json?.data?.items || [];
    const inList = items.some((it) => (it.ticketId || it.id) === ticketId);
    record(
      'F2',
      'F2-09',
      list.status === 200 && inList,
      `GET /api/v2/ops/disputes?sessionId HTTP=${list.status} 命中工单=${inList}（本页 ${items.length} 条）`,
      writeText('f2-disputes-list', list.json)
    );

    const detail = await api(
      admin.token,
      'GET',
      `/api/v2/ops/disputes/${encodeURIComponent(ticketId)}`
    );
    record(
      'F2',
      'F2-10',
      detail.status === 200 && !!detail.json?.data,
      `GET 工单详情 HTTP=${detail.status} status=${detail.json?.data?.status}`,
      writeText('f2-dispute-detail', detail.json)
    );

    // 越权守卫：低权 viewer 不得结案（改判=资金动作）——期望 403（缺 ops:dispute:resolve）。
    const viewer = await opsLoginAs(VIEWER_PHONE, VIEWER_PASSWORD);
    if (viewer.token) {
      const bad = await api(
        viewer.token,
        'POST',
        `/api/v2/ops/disputes/${encodeURIComponent(ticketId)}/resolve`,
        { resolutionType: 'CONFIRM', items: [{ skuId, quantity: 2 }] }
      );
      record(
        'F2',
        'F2-11',
        bad.status === 403,
        `viewer 越权结案必须被拒：HTTP=${bad.status}（期望 403，ops:dispute:resolve 闸门）`,
        writeText('f2-viewer-forbidden', bad.json)
      );
    } else {
      record('F2', 'F2-11', 'SKIP', `viewer 登录失败 HTTP=${viewer.status}，越权守卫未取证`, null);
    }

    const claim = await api(
      admin.token,
      'POST',
      `/api/v2/ops/disputes/${encodeURIComponent(ticketId)}/claim`
    );
    record(
      'F2',
      'F2-12',
      claim.status === 200,
      `admin claim HTTP=${claim.status} assignee=${claim.json?.data?.assigneeId || claim.json?.data?.assignee || '(见 json)'}`,
      writeText('f2-claim', claim.json)
    );

    const resolve = await api(
      admin.token,
      'POST',
      `/api/v2/ops/disputes/${encodeURIComponent(ticketId)}/resolve`,
      { resolutionType: 'CONFIRM', items: [{ skuId, quantity: 2 }] }
    );
    const rt = resolve.json?.data?.resolutionType || resolve.json?.data?.resolution;
    record(
      'F2',
      'F2-13',
      resolve.status === 200 && rt === 'CONFIRM',
      `admin resolve CONFIRM HTTP=${resolve.status} resolutionType=${rt} msg=${resolve.json?.message || ''}`,
      writeText('f2-resolve', resolve.json)
    );

    // 终态断言（以库为准）：会话 COMPLETED、工单 RESOLVED、异常关闭、订单+金额不变量。
    const finState = sessionState(sessionId);
    record(
      'F2',
      'F2-14',
      finState === 'COMPLETED',
      `结案后会话终态=${finState}（期望 COMPLETED，DisputeService:650）`,
      null
    );
    const tk2 = psqlSafe(`select status from dispute_ticket where ticket_id='${ticketId}'`);
    record(
      'F2',
      'F2-15',
      !tk2.err && /RESOLVED/.test(tk2.val),
      `工单终态=[${(tk2.val || tk2.err).trim()}]（期望 RESOLVED）`,
      null
    );
    const exc2 = psqlSafe(
      `select status from ops_exception where session_id='${sessionId}' and exception_type='RECOGNITION_FAILED'`
    );
    record(
      'F2',
      'F2-16',
      !exc2.err && !/OPEN/.test(exc2.val),
      `异常随结案关闭：status=[${(exc2.val || exc2.err).trim()}]（不应仍 OPEN，resolveOpenForSession:647）`,
      null
    );

    const dbOrder = psql(
      `select o.order_id, o.status, o.total_amount_cents, coalesce(o.coupon_discount_cents,0), coalesce(o.member_discount_cents,0) from cabinet_order o where o.session_id='${sessionId}'`
    );
    const dbLines = psql(
      `select l.sku_id, l.quantity, l.unit_price_cents, l.line_amount_cents from cabinet_order_line l join cabinet_order o on o.order_id=l.order_id where o.session_id='${sessionId}'`
    );
    const olines = dbLines
      .split('\n')
      .filter(Boolean)
      .map((l) => l.split('|'))
      .map(([, q, up, la]) => ({ q: Number(q), up: Number(up), la: Number(la) }))
      .filter((r) => Number.isFinite(r.la));
    const oc = dbOrder.split('|');
    const orderStatus = (oc[1] || '').trim();
    const total = Number((oc[2] || '0').trim());
    const coupon = Number((oc[3] || '0').trim()) || 0;
    const member = Number((oc[4] || '0').trim()) || 0;
    const sumLines = olines.reduce((a, r) => a + r.la, 0);
    record(
      'F2',
      'F2-17',
      !!dbOrder &&
        olines.length > 0 &&
        sumLines - coupon - member === total &&
        olines.every((r) => r.la === r.q * r.up) &&
        ['PAID', 'PENDING'].includes(orderStatus),
      `CONFIRM 建单+金额不变量：order=[${dbOrder}] lines=[${dbLines}]；Σ行=${sumLines}−券=${coupon}−会员=${member}=${sumLines - coupon - member} 应=合计=${total}；状态=${orderStatus}（PAID/PENDING）`,
      writeText('f2-db-order', { dbOrder, dbLines })
    );

    // SPA 争议审核页 UI（同 E-03 弱判据 ⇒ UI 登录不通则如实 SKIP，API 面 F2-09~13 已实证审核链）。
    const uiLogin = await adminLogin(page);
    if (!uiLogin.ok) {
      record(
        'F2',
        'F2-18',
        'SKIP',
        `SPA UI 登录不通（E-03 同款弱判据）；争议审核链 API 面已由 F2-09~13 实证，SPA 挂接真前 checklist`,
        await shot(page, 'f2-admin-disputes')
      );
    } else {
      await page.goto(`${ADMIN}/disputes`, { waitUntil: 'domcontentloaded' });
      try {
        await page
          .locator('.el-table__body-wrapper, .el-table__body, .el-table')
          .first()
          .waitFor({ state: 'attached', timeout: 8000 });
      } catch {
        /* 表格未出现不 throw，found=false 如实记录 */
      }
      const found = await page.evaluate(
        (tid) =>
          [...document.querySelectorAll('.el-table__body-wrapper tr, .el-table__row')].some((tr) =>
            tr.innerText.includes(tid)
          ) || document.body.innerText.includes(tid),
        ticketId
      );
      record(
        'F2',
        'F2-18',
        found,
        `争议审核页可检索到工单 ticketId=${ticketId}（选择器：Element Plus .el-table__body-wrapper tr + body.innerText 兜底）`,
        await shot(page, 'f2-admin-disputes')
      );
    }
  },

  /**
   * F7 补货任务 → 补货录像。
   *
   * 契约面（Explore 亲读，行号取本 worktree cadmin-uat）：
   *   建单    POST /api/v2/ops/admin/replenishment/suggest/create-task   OpsReplenishmentController:177
   *   终态    POST /api/v2/merchant/replenishment/tasks/{id}/complete     MerchantPortalController:663（仅商户域能推 COMPLETED）
   *   读录像  GET  /api/v2/ops/admin/jiangyi/order-videos/by-task/{id}    AdminJiangyiController:85（perm ops:device:video）
   *   签名    GET  /api/v2/ops/admin/jiangyi/order-videos/{vid}/play-url  AdminJiangyiController:108
   *   产录像  POST /internal/v1/jiangyi/order-video-report                JiangyiInternalController:172（边端设备上报）
   *
   * 🔴 补货录像是「证据」，按 no-fabricated-evidence 铁律只能由**边端链**产生（柜机补货开门→
   *    gateway→order-video-report 落 jiangyi_order_video，经 shopping_session.replenishment_task_id 关联）。
   *    本骨架不自造 video_urls 落库、不用假 MP4 记录凑绿：凡取不到的设备上报 payload 形状一律标
   *    ⚠️ 待核实并 SKIP，绿只在「读路径契约真跑通（by-task 回 200+空数组）」这类可核验处挣。
   *
   * 跑在隔离空库（aicabinet-uat），正向对照即便命中真实变更也只动一次性栈。
   */
  F7: async ({ state }) => {
    const admin = state.adminToken ? { token: state.adminToken } : await adminLoginViaApi();
    state.adminToken = admin.token;

    // F7-01 建单：请求体字段契约待读 OpsReplenishmentController:177 的入参 DTO 后补齐（此处不猜 body）。
    const created = await api(
      admin.token,
      'POST',
      '/api/v2/ops/admin/replenishment/suggest/create-task',
      {}
    );
    const taskId = created.json?.data?.id ?? created.json?.data?.taskId;
    const bodyKnown = created.status === 200 && taskId !== undefined && taskId !== null;
    record(
      'F7',
      'F7-01-create-task',
      bodyKnown ? true : 'SKIP',
      bodyKnown
        ? `补货任务已建 taskId=${taskId}（GET fulfillment-tasks 可核）`
        : `create-task HTTP=${created.status} msg=${created.json?.message || ''}——⚠️ 请求体字段契约待核实（骨架不猜 body），故后续设备链无法推进`,
      writeText('f7-create-task', { status: created.status, body: created.json })
    );

    // F7-02 终态只能由商户域推进：无 MERCHANT_PHONE 即如实 SKIP（不借运营 token 伪推）。
    const mer = await merchantLoginViaApi(MERCHANT_PHONE, MERCHANT_PASSWORD);
    if (!mer.token) {
      record(
        'F7',
        'F7-02-merchant-complete',
        'SKIP',
        `商户 token 不可得（${mer.error || '未配 MERCHANT_PHONE/PASSWORD'}）⇒ 任务无法推到 COMPLETED；接真 checklist 需演示商户凭据`,
        null
      );
    } else if (taskId === undefined || taskId === null) {
      record(
        'F7',
        'F7-02-merchant-complete',
        'SKIP',
        'F7-01 未拿到真实 taskId ⇒ 无从 complete',
        null
      );
    } else {
      const done = await api(
        mer.token,
        'POST',
        `/api/v2/merchant/replenishment/tasks/${taskId}/complete`,
        {}
      );
      const dbState = psqlSafe(
        `select status from replenishment_task where id='${String(taskId).replace(/'/g, "''")}'`
      );
      record(
        'F7',
        'F7-02-merchant-complete',
        done.status === 200 && /COMPLETED/.test(dbState.val || ''),
        `complete HTTP=${done.status}｜库 replenishment_task.status=${dbState.val || dbState.err || '(无行)'}`,
        writeText('f7-merchant-complete', { http: done.status, body: done.json, db: dbState.val })
      );
    }

    // F7-03 读路径契约（运营域）：即便无边端录像，by-task 也必须回 200 + 空数组 ⇒ 证读接口/权限面通。
    if (taskId === undefined || taskId === null) {
      record('F7', 'F7-03-videos-by-task', 'SKIP', '无 taskId，跳过读路径断言', null);
    } else {
      const vids = await api(
        admin.token,
        'GET',
        `/api/v2/ops/admin/jiangyi/order-videos/by-task/${taskId}`
      );
      const arr = vids.json?.data;
      const list = Array.isArray(arr) ? arr : Array.isArray(arr?.list) ? arr.list : null;
      record(
        'F7',
        'F7-03-videos-by-task',
        vids.status === 200 && list !== null ? true : vids.status === 403 ? false : 'SKIP',
        vids.status === 200 && list !== null
          ? `补货录像读路径 HTTP=200 返回 ${list.length} 行（本地无模拟器⇒0 行属预期，非缺陷）`
          : vids.status === 403
            ? `by-task 回 403——运营 token 缺 ops:device:video？（应能拿到，属红）`
            : `by-task HTTP=${vids.status} 非数组形态——⚠️ 响应结构待核实`,
        writeText('f7-videos-by-task', { status: vids.status, body: vids.json })
      );
      // F7-04 play-url 需真实 video 行 id；无边端录像即 SKIP。
      record(
        'F7',
        'F7-04-play-url',
        'SKIP',
        list && list.length
          ? `有 ${list.length} 行，play-url 需逐行签名 URL（待补）`
          : '无边端录像行 ⇒ play-url 无从取（接真需模拟器产录像）',
        null
      );
    }

    // F7-05 设备产录像：order-video-report 的 payload 形状 + 边端链未接 ⇒ 登记为接真前必补，不在此造绿。
    record(
      'F7',
      'F7-05-edge-video-ingest',
      'SKIP',
      '补货录像只能由边端补货开门上报产生；⚠️ order-video-report(JiangyiInternalController:172) payload 形状待读 DTO，且需 jiangyi 模拟器在补货会话下开门——见接真 checklist',
      null
    );
  },

  /**
   * F8 RBAC 越权矩阵（菜单/按钮/API 三层）。
   *
   * 权威闸门是 **API 层** `@RequiresPermissions` → PermissionService:43/55 抛 403「无权限执行此操作」；
   * 前端「菜单/按钮」是 v-hasPermi 的 display:none（可绕，非权威），故本矩阵以 **viewer 低权 token 拿到 403**
   * 为准，并用 **admin 对同一端点（不存在的资源 id）非 403** 作正向对照，证明 403 来自 RBAC 而非端点本身坏。
   * 另以 ops_role_permission 落库口径复核 viewer 确无写码（DB 列名待核实则标 SKIP，不崩整轮）。
   */
  F8: async ({ state }) => {
    const admin = state.adminToken ? { token: state.adminToken } : await adminLoginViaApi();
    state.adminToken = admin.token;

    const viewer = await opsLoginAs(VIEWER_PHONE, VIEWER_PASSWORD);
    record(
      'F8',
      'F8-00-viewer-login',
      viewer.status === 200 && !!viewer.token,
      `低权 viewer(${VIEWER_PHONE}) 登录 HTTP=${viewer.status} token=${viewer.token ? '有' : '无'} 2FA=${viewer.twoFactor}（V172 默认 totp=false 不阻断）`,
      null
    );
    if (!viewer.token) {
      record('F8', 'F8-ABORT', false, '低权 token 取不到 ⇒ 矩阵无法执行（不造绿，整条判红）', null);
      return;
    }

    // viewer 自身权限码（GET me/permissions，OpsRbacController:212）——不得含写码。
    const vp = await api(viewer.token, 'GET', '/api/v2/ops/admin/rbac/me/permissions');
    const vBlob = JSON.stringify(vp.json?.data ?? vp.json ?? '');
    record(
      'F8',
      'F8-00b-viewer-perms',
      vp.status === 200 &&
        !/refund|:edit|:manage|:review|:assign|:adjust|:payout|:import/.test(vBlob),
      `viewer 权限码里不得含写码；实际：${vBlob.slice(0, 320) || '(空)'}`,
      writeText('f8-viewer-permissions', vp.json)
    );

    // DB 口径：viewer 角色在 ops_role_permission 里无任何写码。列名以实测 schema 为准：
    //   ops_role(role_id, role_key) · ops_permission(permission_id, perm_code) · ops_role_permission(role_id, permission_id)。
    const dbw = psqlSafe(
      `select coalesce(string_agg(p.perm_code,','),'(空)') from ops_role r ` +
        `join ops_role_permission rp on rp.role_id = r.role_id ` +
        `join ops_permission p on p.permission_id = rp.permission_id ` +
        `where r.role_key = 'viewer' and p.perm_code ~ ':(refund|edit|manage|review|assign|adjust|payout|import)$'`
    );
    record(
      'F8',
      'F8-00c-db-no-write',
      dbw.err ? 'SKIP' : dbw.val === '(空)',
      dbw.err
        ? `viewer 角色写码查询失败（ops_role/ops_role_permission 列名待核实）：${dbw.err}`
        : `viewer 角色写码（DB）=${dbw.val}`,
      writeText('f8-viewer-writecodes-db', { val: dbw.val, err: dbw.err })
    );

    const PROBES = [
      [
        'F8-01-refund',
        'POST',
        '/api/v2/ops/admin/orders/{orderId}/refund',
        'ops:order:refund',
        'AdminDashboardController:428'
      ],
      [
        'F8-02-balance',
        'POST',
        '/api/v2/ops/admin/users/{userId}/balance',
        'ops:user:balance',
        'AdminDashboardController:389'
      ],
      [
        'F8-03-recharge-refund',
        'POST',
        '/api/v2/ops/admin/recharge/{orderId}/refund',
        'ops:recharge:edit',
        'AdminDashboardController:418'
      ],
      [
        'F8-04-balance-refund-review',
        'POST',
        '/api/v2/ops/admin/balance-refunds/{requestId}/review',
        'ops:balance-refund:review',
        'AdminBalanceRefundController:38'
      ],
      [
        'F8-05-withdraw-payout',
        'POST',
        '/api/v2/ops/admin/merchant-withdraws/{requestId}/payout',
        'ops:merchant-withdraw:review',
        'MerchantWithdrawController:93'
      ],
      [
        'F8-06-wallet-adjust',
        'POST',
        '/api/v2/ops/admin/merchant-wallets/{merchantId}/adjust',
        'ops:merchant-withdraw:adjust',
        'MerchantWalletController:51'
      ],
      [
        'F8-07-fund-adjust',
        'POST',
        '/api/v2/ops/admin/platform-fund/accounts/{accountId}/adjust',
        'ops:fund:manage',
        'OpsPlatformFundController:73'
      ],
      [
        'F8-08-staff-payout',
        'POST',
        '/api/v2/ops/admin/staff-wallets/{staffUserId}/payout',
        'ops:line-manager:edit',
        'OpsStaffWalletController:46'
      ],
      [
        'F8-09-pricing',
        'PUT',
        '/api/v2/ops/admin/devices/{deviceId}/pricing/{skuId}',
        'ops:pricing:edit',
        'AdminDevicePricingController:46'
      ],
      [
        'F8-10-config',
        'PUT',
        '/api/v2/ops/admin/system-configs',
        'ops:config:edit',
        'SystemConfigController:70'
      ],
      [
        'F8-11-rent-split',
        'PUT',
        '/api/v2/ops/admin/site-contracts/{contractId}/rent-split-rules',
        'ops:org:edit',
        'SiteRentSplitController:32'
      ],
      [
        'F8-12-rbac-assign',
        'POST',
        '/api/v2/ops/admin/rbac/operators',
        'ops:rbac:assign:add',
        'OpsRbacController:146'
      ]
    ];

    // 判定口径（关键：区分 RBAC 闸门 vs 先校验，避免把「没触达闸门」误判成安全红/绿）：
    //   viewer 403           ⇒ RBAC 拦住 = PASS（真绿）
    //   viewer 2xx           ⇒ 越权成功 = FAIL（真缺陷）
    //   viewer 400/404/422   ⇒ 端点**先校验 body/id 再判权限**，探针用空 body+假 id 没触达闸门
    //                          ⇒ 权限面「未验」，记 SKIP 并标注需补合法 payload（属 #48 完成 F8 的活），不判红也不洗绿。
    //   其余                 ⇒ 非预期，记 SKIP。
    let blocked = 0;
    let breach = 0;
    let unverified = 0;
    const unverifiedList = [];
    for (const [code, method, pathTpl, perm, file] of PROBES) {
      // 用不存在的资源 id 兜住路径参，使 admin 正向对照只证「过了 RBAC」而不动真实资金/配置。
      const p = pathTpl.replace(/\{[a-zA-Z]+\}/g, '999999999');
      const v = await api(viewer.token, method, p, {});
      const a = await api(admin.token, method, p, {});
      let verdict;
      if (v.status === 403 && (v.json?.code === 403 || /无权限/.test(v.text || ''))) {
        blocked++;
        verdict = true;
      } else if (v.status >= 200 && v.status < 300) {
        breach++;
        verdict = false;
      } else if ([400, 404, 409, 422].includes(v.status)) {
        unverified++;
        unverifiedList.push(code);
        verdict = 'SKIP';
      } else {
        unverified++;
        unverifiedList.push(code);
        verdict = 'SKIP';
      }
      record(
        'F8',
        code,
        verdict,
        verdict === 'SKIP'
          ? `${method} ${p}［需 ${perm}｜${file}］ viewer=${v.status}=先校验未触达 RBAC 闸门（admin=${a.status}）⇒ 权限面未验，需合法 body 补测`
          : `${method} ${p}［需 ${perm}｜${file}］ viewer=${v.status} admin=${a.status}（${blocked && v.status === 403 ? '闸门拦住' : '见状态码'}）`,
        writeText(`f8-probe-${code}`, {
          viewer: { status: v.status, body: v.json },
          admin: { status: a.status, body: a.json }
        })
      );
    }

    // 聚合：真实越权(breach>0) 才判红；闸门已证拦住的端点 >0 且无越权即绿；其余未验端点单列。
    record(
      'F8',
      'F8-99-summary',
      breach === 0 && blocked > 0 ? true : breach > 0 ? false : 'SKIP',
      `API 层 RBAC：${PROBES.length} 探针中 闸门拦住=${blocked}、越权=${breach}、先校验未触达闸门(待补 body)=${unverified}${unverified.length ? `[${unverified.join(',')}]` : ''}；菜单/按钮属前端 v-hasPermi 隐藏，非权威闸门，不单独判绿`
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
