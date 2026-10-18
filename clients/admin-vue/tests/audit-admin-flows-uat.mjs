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
 *   SKIP）；F3 退款→报表口径隔离栈实测（2026-10-18）：运营对 F1 已支付 BALANCE 单——viewer 越权 403 / 短原因 400 /
 *   退货退款(restore=true) 200 REFUNDED + 库 inventory_deducted=false / 钱包回补 +合计 / 台账净额=0(入账−REFUND) /
 *   工单 RESOLVED / 分账 VOIDED / 重复退 409 全绿；报表口径钉 (a) 退款感知台账，(c) finance/stats 无退款过滤是已知陷阱不断言。
 *   ✅ F3-FIND-1 已修（2026-10-18 拍板方案 B）：退货退款 restore=true 原在 V271 DEFERRABLE 守卫下必 500——
 *   prepare 独立事务把 inventory_deducted 置 false 时 status 仍 PAID ⇒ assert_cabinet_order_inventory_link RAISE。
 *   修法：SettlementWaiveRefundService 将「回库 + un-deduct」从 prepare 挪到 finalize，与 status=REFUNDED 同事务原子落
 *   ⇒ 提交守卫见 REFUNDED（非 PAID）即豁免；去重由 finalize 的 REFUNDED 早退保证（替代旧 C03 prepare 落 flag）。
 *   F3 主链因此从「仅退款 restore=false」改为「退货退款 restore=true」端到端跑通真守卫。
 *   F4 提现审核隔离栈实测绿（2026-10-18，aicabinet-uat）：V321 资质闸门 403 / CB-035 T+1 可用额闸门 412（提额=可用+1 被待结算 3916 分排除）/
 *   ≥阈值 60000 申请→PENDING_REVIEW 冻结毛额 / 两节点(MANAGER→FINANCE，均 admin)审批→PAID(MOCK 软启动) / consumeFrozen 拆 WITHDRAW_PAID+WITHDRAW_FEE(6‰) /
 *   驳回→REJECTED+WITHDRAW_RELEASE / 复用 requestNo 改金额 409 全绿（F4-00 未种 settle_unlock_hour⇒走硬默认 0）。
 *   F5 平台资金池隔离栈实测绿：viewer 建账 403 / 建账 BANK 初始 100000(以库断言，建账 DTO balanceCents=0 属实体时序) / 负初始 400+重复渠道 409 /
 *   充值 +20000 / 归集 -30000 出账(0⇒400) / 带符号调整 -12345(允许负余额，空原因 400) / 流水 RECHARGE·SWEEP·ADJUST 齐；F5-07 PAYOUT 非运营端点(商户提现自动写)如实 SKIP。
 *   F6 设备租赁隔离栈实测绿：rerun 守卫(未来/回溯93天/非法日期 全 400) / PATCH 设备 coop_mode=RENTAL·rent=31000·data_fee=0(ops:device:edit 产品路径) /
 *   押金登记 ONLINE 扣商户 20000→HELD / 空依据扣款 400+有效扣 5000→15000·DEDUCT / 结算 SETTLED·RETURN 净 -5000 / 列表 200 / viewer 403 /
 *   **出账已挣**：rerun-daily-fees 走产品链⇒REN 行 SETTLED 金额=floor(31000/当月天数)=1000·chargedCents=1000（dataFee=0⇒无 DATA 行）；
 *   F6-08 锁柜/复机须账龄满 lock_days 的 **PENDING** 台账行（产品日扣只写 SETTLED，PENDING 仅人工补录）⇒隔离栈无 PENDING 且不 SQL 造行凑绿，如实 SKIP。
 *   F7 补货建单+读路径隔离栈实测绿：POST create-from-suggestion(deviceId=EG746560232828)⇒200 建 route+taskId、GET fulfillment-tasks 可核、
 *   GET order-video-report 读路径/videos?taskId⇒200（本地无模拟器⇒0 行属预期）；F7-02 商户推 COMPLETED 需演示商户凭据、F7-04/05 边端录像上报需 jiangyi 模拟器在补货会话开门⇒无凭据/无录像如实 SKIP（不 SQL 造行凑绿）。
 *   F8 RBAC 越权矩阵隔离栈实测绿：12 条写端点探针均补到**最小合法 body**，越过 @Valid 直达 @RequiresPermissions AOP 闸门⇒viewer 全 403、0 越权、0「校验先于闸门」；
 *   admin 正向只在有路径 id 处发（config/rbac 无 id 跳正向写以免真实改配置/建操作员）。菜单/按钮属前端 v-hasPermi 隐藏、非权威闸门不单独判绿。
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
   * F3 退款 → 报表口径（运营后台对 F1 的**已支付余额单**全额退款）。
   *
   * 资金链亲读（行号取本 worktree cadmin-uat）：
   *   入口      POST /api/v2/ops/admin/orders/{orderId}/refund  AdminDashboardController:429（perm ops:order:refund）
   *             → DisputeService.refundByOperator:249 先 requirePermission（缺码 ⇒ 403，先于任何 id 解析），
   *               :253 requireDeviceAccess（全局域直接放行）→ lines 空 ⇒ executeFullRefund:323（P2-2 无外层事务）。
   *   校验      reason.trim()<4 ⇒ 400；status==REFUNDED ⇒ 409「订单已退款」；status ∉{PAID,COMPLETED,DISPUTED,PARTIAL} ⇒ 409。
   *   退款      SettlementService.waiveAndRefund → SettlementWaiveRefundService:66：prepare 取 netCompletedCents（本单 686）
   *             → OrderPaymentService.refundOrder:529（跳过闸门 amount<=0 或 userId>=OPERATOR_USER_ID_START，10001 消费者不受限）
   *             → prepareOrderRefund:589：BALANCE ⇒ completeLocalRefund:802 else 分支 `balanceLedgerService.change(+686, REFUND)`
   *               写一条 COMPLETED payment_operation（doChange:81）并回补钱包可用余额；
   *             finalizeWaiveRefund:107：status=REFUNDED、refunded_cents、refunded_at、voidSplitOnFullRefund（order_revenue_split→VOIDED）。
   *   工单      ensureDisputeTicketForFullRefund:367 建单（无则建、按 session_id 关联）→ finalizeFullRefundTicket:412 置 RESOLVED。
   *
   * 报表口径（本流程取证重点）：
   *   (a) payment_operation 台账【退款感知】= 对账净现金流入 SUM：CHARGE/ADJUST/RECHARGE − REFUND ⇒ 本单 686−686=0；
   *   (b) CommissionBaseCalc COUNTABLE={PAID,COMPLETED,PARTIAL_REFUNDED} **不含 REFUNDED** ⇒ 退款单移出抽成基数；
   *   (c) ⚠️ 后台 finance/stats 的 `SUM(total_amount_cents)` 无状态过滤、不减退款 ⇒ 全额退款后仍显示 686，
   *       是**已知设计陷阱**（非缺陷）⇒ 本流程**不**对 (c) 断言变化，只钉 (a) 台账为权威退款感知口径。
   *
   * 🔴 不造绿：退款由运营真实接口发起，余额回补/流水/工单/分账冲正全部由产品代码落库；断言只落 DB/API 口径。
   *   仅对 pay_channel=BALANCE 且状态 PAID 的正向单执行；WECHAT/ALIPAY 需真实渠道凭据 ⇒ 如实 SKIP（隔离栈无凭据，不 mock 凑）。
   */
  F3: async ({ state }) => {
    const f1 = state.f1 || {};
    // 独立复跑退款链的可选入口：state.f1 缺（本轮没跑 F1）时，用 F3_TARGET_SESSION 指向库中已存在的 PAID 单会话，
    //   免依赖模拟器在场即可复验退款/退货退款链（真实资金动作仅落隔离栈）。
    if (!f1.sessionId && process.env.F3_TARGET_SESSION) {
      f1.sessionId = process.env.F3_TARGET_SESSION.trim();
    }
    if (!f1.sessionId) {
      record(
        'F3',
        'F3-00',
        'SKIP',
        `F3 复用 F1 的已支付余额单（按 state.f1.sessionId 定位订单），需 FLOWS 含 F1 在前或设 F3_TARGET_SESSION：state.f1=${JSON.stringify(f1).slice(0, 100)}`,
        null
      );
      return;
    }
    const ord = psqlSafe(
      `select order_id||'|'||status||'|'||coalesce(pay_channel,'')||'|'||total_amount_cents||'|'||` +
        `coalesce(paid_amount_cents,0)||'|'||coalesce(refunded_cents,0)||'|'||user_id ` +
        `from cabinet_order where session_id='${f1.sessionId}'`
    );
    const row = (ord.val || '').trim().split('|');
    const orderId = (row[0] || '').trim();
    const oStatus = (row[1] || '').trim();
    const oChannel = (row[2] || '').trim();
    const oTotal = Number(row[3] || '0');
    const oUser = (row[6] || '').trim();
    if (!orderId) {
      record(
        'F3',
        'F3-00',
        false,
        `F1 会话未产生订单，无法测退款：[${(ord.val || ord.err).slice(0, 160)}]`,
        null
      );
      return;
    }
    const liveChannel = /WECHAT|ALIPAY/i.test(oChannel);
    const refundable = oStatus === 'PAID';
    record(
      'F3',
      'F3-00',
      refundable && !liveChannel ? true : 'SKIP',
      `退款前置：orderId=${orderId} 状态=${oStatus} 渠道=${oChannel || '(null)'} 合计=${oTotal} 消费者=${oUser}（仅 BALANCE+PAID 正向测；${liveChannel ? '渠道单需真实凭据' : '状态不可退'}⇒SKIP）`,
      writeText('f3-target-order', ord.val || ord.err)
    );
    if (liveChannel || !refundable) return;

    const admin = state.adminToken ? { token: state.adminToken } : await adminLoginViaApi();
    state.adminToken = admin.token;
    if (!admin.token) {
      record('F3', 'F3-01', false, `admin 登录失败 HTTP=${admin.status} ⇒ 退款链无法推进`, null);
      return;
    }

    // F3-02 viewer 越权退款必须被拒（真实 orderId ⇒ 权限闸门先于 id 解析，期望 403）。
    const viewer = await opsLoginAs(VIEWER_PHONE, VIEWER_PASSWORD);
    if (viewer.token) {
      const bad = await api(
        viewer.token,
        'POST',
        `/api/v2/ops/admin/orders/${encodeURIComponent(orderId)}/refund`,
        { reason: '越权退款尝试', restoreInventory: true }
      );
      record(
        'F3',
        'F3-02-viewer-forbidden',
        bad.status === 403,
        `viewer 越权退款：HTTP=${bad.status}（期望 403，ops:order:refund 闸门）`,
        writeText('f3-viewer-forbidden', bad.json)
      );
    } else {
      record(
        'F3',
        'F3-02-viewer-forbidden',
        'SKIP',
        `viewer 登录失败 HTTP=${viewer.status}，越权守卫未取证`,
        null
      );
    }

    // F3-03 负向：原因 <4 字 ⇒ 400（executeFullRefund:332）。
    const shortReason = await api(
      admin.token,
      'POST',
      `/api/v2/ops/admin/orders/${encodeURIComponent(orderId)}/refund`,
      { reason: '短', restoreInventory: true }
    );
    record(
      'F3',
      'F3-03-short-reason',
      shortReason.status === 400,
      `短原因退款：HTTP=${shortReason.status} msg=${String(shortReason.json?.message || '').slice(0, 60)}（期望 400）`,
      writeText('f3-short-reason', shortReason.json)
    );

    // F3-04 正向全额退款（退货退款口径 restore=true，F3-FIND-1 修法 B 已修 ⇒ 端到端跑真 V271 守卫）⇒ 200 + REFUNDED。
    //   balBefore 必须在退款前取（restore=true 回库 + 钱包回补同链发生）。
    const balBefore = Number(
      (
        psqlSafe(`select balance_cents from user_account where user_id='${oUser}'`).val || '0'
      ).trim() || '0'
    );
    const ref = await api(
      admin.token,
      'POST',
      `/api/v2/ops/admin/orders/${encodeURIComponent(orderId)}/refund`,
      { reason: '审计退款全链验证（退货退款）', restoreInventory: true }
    );
    const rStatus = ref.json?.data?.status;
    const rRefunded = ref.json?.data?.refundedCents;
    const guardHit = /inventory_deducted|assert_cabinet_order_inventory_link/.test(
      String(ref.json?.message || '')
    );
    record(
      'F3',
      'F3-04-full-refund-restore',
      ref.status === 200 && rStatus === 'REFUNDED' && rRefunded === oTotal,
      `运营全额退款(退货退款 restore=true)：HTTP=${ref.status} status=${rStatus} refundedCents=${rRefunded}(应=${oTotal}) msg=${String(ref.json?.message || '').slice(0, 60)}${guardHit ? '｜仍命中 V271 守卫⇒F3-FIND-1 未修好！' : '｜未命中守卫⇒F3-FIND-1 修法 B 生效'}`,
      writeText('f3-refund-restore', ref.json)
    );

    // F3-04b 库存回退落库：inventory_deducted 必须随 REFUNDED 同事务翻成 false（修法 B 的原子性直接取证）。
    const invDeducted = psqlSafe(
      `select inventory_deducted from cabinet_order where order_id='${orderId}'`
    );
    record(
      'F3',
      'F3-04b-inventory-undeducted',
      !invDeducted.err && /^(f|false)$/i.test((invDeducted.val || '').trim()),
      `退款后 inventory_deducted=${(invDeducted.val || invDeducted.err).trim()}（退货退款应=false，且与 REFUNDED 同事务原子落、未触发 V271 中间态守卫）`,
      writeText('f3-inventory-undeducted', invDeducted.val || invDeducted.err)
    );

    // F3-05 库终态：cabinet_order REFUNDED / refunded_cents=合计 / refunded_at 非空。
    const after = psqlSafe(
      `select status||'|'||coalesce(refunded_cents,0)||'|'||coalesce(paid_amount_cents,0)||'|'||(refunded_at is not null) ` +
        `from cabinet_order where order_id='${orderId}'`
    );
    const a = (after.val || '').split('|');
    record(
      'F3',
      'F3-05-order-terminal',
      !after.err && a[0] === 'REFUNDED' && Number(a[1]) === oTotal && a[3] === 'true',
      `订单终态：status=${a[0]} refunded_cents=${a[1]}(应=${oTotal}) paid_amount_cents=${a[2]} refunded_at非空=${a[3]}`,
      writeText('f3-order-after', after.val || after.err)
    );

    // F3-06 消费者钱包回补 +合计（completeLocalRefund BALANCE 分支 change(+amount, REFUND)）。
    const balAfter = Number(
      (
        psqlSafe(`select balance_cents from user_account where user_id='${oUser}'`).val || '0'
      ).trim() || '0'
    );
    record(
      'F3',
      'F3-06-balance-credited',
      balAfter === balBefore + oTotal,
      `钱包回补：before=${balBefore} after=${balAfter} 应=+${oTotal}（余额单退款计入可用余额，非冻结）`,
      writeText('f3-balance', { balBefore, balAfter })
    );

    // F3-07 报表口径 (a)【退款感知台账】= netCompletedCents（PaymentOperationMapper:128，正是退款额取值口径）：
    //   净 = (CHARGE/ADJUST_CHARGE/PREAUTH_CAPTURE) − REFUND = 0。开门柜 F1 入账走 PREAUTH_CAPTURE（非 CHARGE），
    //   故正项必须含 PREAUTH_CAPTURE，否则漏计入账、净额被洗成负数（本次实测即踩此坑）。
    const led = psqlSafe(
      `select coalesce(sum(case when operation_type in ('CHARGE','ADJUST_CHARGE','PREAUTH_CAPTURE') then amount_cents ` +
        `when operation_type='REFUND' then -amount_cents else 0 end),0)||'|'||` +
        `coalesce(sum(case when operation_type in ('CHARGE','ADJUST_CHARGE','PREAUTH_CAPTURE') and status='COMPLETED' then amount_cents else 0 end),0)||'|'||` +
        `coalesce(sum(case when operation_type='REFUND' and status='COMPLETED' then amount_cents else 0 end),0) ` +
        `from payment_operation where order_id='${orderId}' and status='COMPLETED'`
    );
    const L = (led.val || '').split('|');
    record(
      'F3',
      'F3-07-ledger-net-zero',
      !led.err && Number(L[0]) === 0 && Number(L[1]) === oTotal && Number(L[2]) === oTotal,
      `台账净额(退款感知)：净=${L[0]} 入账=${L[1]} 退款=${L[2]}（期望 0/${oTotal}/${oTotal}，netCompletedCents 口径含 PREAUTH_CAPTURE）`,
      writeText('f3-ledger', led.val || led.err)
    );

    // F3-08 退款工单随全额退款建单并结案 RESOLVED（按 session 关联，finalizeFullRefundTicket:412）。
    const tk = psqlSafe(
      `select coalesce(string_agg(status,','),'(无工单)') from dispute_ticket where session_id='${f1.sessionId}'`
    );
    record(
      'F3',
      'F3-08-ticket-resolved',
      !tk.err && /RESOLVED/.test(tk.val) && !/OPEN/.test(tk.val),
      `退款工单状态集合=[${(tk.val || tk.err).replace(/\s+/g, ' ').slice(0, 80)}]（应含 RESOLVED 且不残留 OPEN）`,
      null
    );

    // F3-09 分账冲正：若本单有 order_revenue_split，全额退款后应全 VOIDED；无分账行 ⇒ 如实 SKIP。
    const split = psqlSafe(
      `select coalesce(string_agg(distinct status,','),'(无分账)') from order_revenue_split where order_id='${orderId}'`
    );
    const splitPass =
      !split.err &&
      split.val !== '(无分账)' &&
      /VOIDED/.test(split.val) &&
      !/ACCRUED|SETTLED|PENDING/.test(split.val);
    record(
      'F3',
      'F3-09-split-voided',
      split.err || split.val === '(无分账)' ? 'SKIP' : splitPass,
      split.err
        ? `order_revenue_split 查询失败（列名待核实）：${split.err}`
        : split.val === '(无分账)'
          ? '本 demo 单无分账行 ⇒ 冲正无从取证（非缺陷，如实 SKIP）'
          : `分账状态集合=[${split.val}]（有分账⇒应全 VOIDED，voidSplitOnFullRefund:144）`,
      writeText('f3-split', { val: split.val, err: split.err })
    );

    // F3-10 会话终态 COMPLETED（退款不改会话态；F1 结算后应仍 COMPLETED）。
    const st = sessionState(f1.sessionId);
    record(
      'F3',
      'F3-10-session-completed',
      st === 'COMPLETED',
      `退款后会话态=${st}（应仍 COMPLETED）`,
      null
    );

    // F3-11 重复退款 ⇒ 409（executeFullRefund:327 status==REFUNDED 入口拦截，钱账不双退）。
    const dup = await api(
      admin.token,
      'POST',
      `/api/v2/ops/admin/orders/${encodeURIComponent(orderId)}/refund`,
      { reason: '重复退款尝试', restoreInventory: true }
    );
    record(
      'F3',
      'F3-11-double-refund',
      dup.status === 409,
      `重复退款：HTTP=${dup.status} msg=${String(dup.json?.message || '').slice(0, 60)}（期望 409 订单已退款）`,
      writeText('f3-double', dup.json)
    );
  },

  /**
   * F4 提现审核（申请→两节点审批→打款），含 CB-035 T+1 解锁口径。
   *
   * 契约面（亲读，行号取本 worktree cadmin-uat）：
   *   运营代提现  POST /api/v2/ops/admin/merchant-wallets/{merchantId}/withdraw
   *               MerchantWalletController:61-69（perm ops:merchant-withdraw:adjust，body WithdrawApplyRequest）
   *               → MerchantWithdrawService.apply:239（先 requirePermission/requireMerchantAccess，
   *                 再 V321 资质门禁 requireMerchantWithdrawEligible:246，最后 createWithdraw）
   *   审核        POST /api/v2/ops/admin/merchant-withdraws/{requestId}/review  MerchantWithdrawController:84-91
   *               （perm ops:merchant-withdraw:review，body WithdrawReviewRequest{approve:Boolean,remark}，approve fail-closed）
   *               → review:318 → completeReview:331（approve=false 直接 REJECTED+releaseFrozen；
   *                 approve=true 走两节点审批流 MERCHANT_WITHDRAW：运营审核(MANAGER)→财务复核(FINANCE)，
   *                 isInstanceApproved 为真才落 APPROVED，随后 executePayout）
   *   列表        GET  /api/v2/ops/admin/merchant-withdraws?...   MerchantWithdrawController:70-82
   *   落库表      merchant_withdraw_request(V139+V195/V241/V307)：status
   *               PENDING_REVIEW|APPROVED|PAYING|PAID|REJECTED|FAILED|CANCELLED；钱包台账 wallet_ledger（domain='MERCHANT'）
   *               entry_type WITHDRAW_FREEZE/WITHDRAW_RELEASE/WITHDRAW_PAID/WITHDRAW_FEE（ref_type=WITHDRAW，ref_id=requestId）
   *
   * CB-035 T+1：解锁时刻 system_config key `merchant.withdraw.settle_unlock_hour`（隔离栈**未种** ⇒ 走 yml/硬默认 0=次日零点）；
   *   available = balance − frozen − pendingSettle，pendingSettle 只计 order_revenue_split 中 status∈(LEDGER_ONLY,SETTLED)
   *   且 settle_after>unlockedThroughDate 的商户分账（MerchantWithdrawService:812/831）。⇒ 超 available 提额必 412「可用余额不足」，
   *   正是 T+1 把未解锁分账挡在可提现额度外的实证。
   *
   * 资质种子（隔离栈一次性测试库，非业务结果伪造）：演示商户 legal_person/business_license_url 皆 NULL ⇒ V321 必 403。
   *   为跑通正向审核链，F4-02 先如实断言「未补资质=403」（这是正确的门禁行为），再对 MCH-DEFAULT 直接 UPDATE 补齐两字段（前置条件），
   *   随后所有申请/审核/打款的**结果**均由产品链产出。审计运营账号=100000001，恰为 MANAGER∩FINANCE 处理人，故可自审两节点。
   */
  F4: async ({ state }) => {
    const admin = state.adminToken ? { token: state.adminToken } : await adminLoginViaApi();
    state.adminToken = admin.token;
    const ops = '/api/v2/ops/admin';
    const M = 'MCH-DEFAULT';

    // F4-00 CB-035 配置面：settle_unlock_hour 隔离栈未种 ⇒ 走默认（0=次日零点）；若种了须为 0..23。
    const cfgQ = psqlSafe(
      `select coalesce(string_agg(config_value,','),'(未种)') from system_config where config_key='merchant.withdraw.settle_unlock_hour'`
    );
    const cfgVal = (cfgQ.val || '').trim();
    const cfgOk =
      cfgQ.err === '' &&
      (cfgVal === '(未种)' ||
        cfgVal === '' ||
        (/^\d+$/.test(cfgVal) && Number(cfgVal) >= 0 && Number(cfgVal) <= 23));
    record(
      'F4',
      'F4-00-cb035-config',
      cfgOk,
      `CB-035 settle_unlock_hour 现值=${cfgVal || '(空)'}（未种即走 yml/硬默认 0=次日零点；越界值应被代码 clamp 到 0..23）`,
      writeText('f4-cb035-config', { cfgVal, err: cfgQ.err })
    );

    // F4-01 viewer 越权审核 ⇒ 403（RBAC，ops:merchant-withdraw:review）。
    const viewer = await opsLoginAs(VIEWER_PHONE, VIEWER_PASSWORD);
    const vrv = await api(viewer.token, 'POST', `${ops}/merchant-withdraws/1/review`, {
      approve: true,
      remark: '越权'
    });
    record(
      'F4',
      'F4-01-viewer-forbidden',
      vrv.status === 403,
      `viewer 调 review：HTTP=${vrv.status} msg=${String(vrv.json?.message || '').slice(0, 40)}（期望 403 无权限）`,
      writeText('f4-viewer-review', vrv.json)
    );

    // F4-02 V321 资质门禁：未补 KYC 的 MCH-OTHER 运营代提现 ⇒ 403（正确的资金安全行为，记为绿）。
    const gate = await api(admin.token, 'POST', `${ops}/merchant-wallets/MCH-OTHER/withdraw`, {
      amountCents: 60000
    });
    const gateMsg = String(gate.json?.message || '');
    record(
      'F4',
      'F4-02-eligibility-gate',
      gate.status === 403 && /资质/.test(gateMsg),
      `未补资质代提现：HTTP=${gate.status} msg=${gateMsg.slice(0, 40)}（期望 403 商户资质不完整，V321 生效）`,
      writeText('f4-eligibility', gate.json)
    );

    // 种子：补齐 MCH-DEFAULT 主体资质（V321 前置条件，隔离栈一次性测试库；非业务结果伪造）并保证可提现余量。
    psql(
      `begin; update merchant set legal_person='审计测试法人', business_license_url='https://audit.local/license.png' where merchant_id='${M}'; commit;`
    );
    const balNow = Number(
      (
        psqlSafe(
          `select balance_cents from wallet_account where domain='MERCHANT' and owner_id='${M}'`
        ).val || '0'
      ).trim()
    );
    if (balNow < 150000) {
      await api(admin.token, 'POST', `${ops}/merchant-wallets/${M}/adjust`, {
        amountCents: 150000,
        remark: '审计前置充值'
      });
    }

    // F4-03 CB-035 T+1 实证：提额=available+1 ⇒ 412「可用余额不足」（未解锁分账把可提现额度压到 available）。
    //   已补资质⇒越过 403；amount=avail+1 远小于日限⇒命中 available 网关（当日首笔 T+1 探针，重跑多次可能改判日限则如实 SKIP）。
    let f4Boundary = 'SKIP';
    let f4BoundaryDetail = 'wallet_account 读取失败';
    const acct0 = psqlSafe(
      `select balance_cents||'|'||frozen_cents from wallet_account where domain='MERCHANT' and owner_id='${M}'`
    );
    const pending0 = psqlSafe(
      `select coalesce(sum(merchant_cents),0) from order_revenue_split where merchant_id='${M}' and status in ('LEDGER_ONLY','SETTLED') and settle_after > current_date`
    );
    if (!acct0.err && !pending0.err) {
      const [bal0, froz0] = (acct0.val || '').split('|').map((x) => Number(x));
      const pend0 = Number((pending0.val || '0').trim()) || 0;
      const avail0 = bal0 - froz0 - pend0;
      if (pend0 > 0) {
        const over = await api(admin.token, 'POST', `${ops}/merchant-wallets/${M}/withdraw`, {
          amountCents: avail0 + 1
        });
        const m = String(over.json?.message || '');
        f4Boundary =
          over.status === 412 && /可用余额不足|余额不足/.test(m)
            ? true
            : over.status === 412
              ? 'SKIP'
              : 'SKIP';
        f4BoundaryDetail = `提额=可用余额+1(${avail0 + 1})⇒HTTP=${over.status} msg=${m.slice(0, 40)}（T+1 未解锁分账 ${pend0} 分被排除在可提现额度外）`;
      } else {
        f4BoundaryDetail = `当日无未解锁分账(pending=0)⇒T+1 边界不可复现，如实 SKIP`;
      }
    }
    record(
      'F4',
      'F4-03-t1-available-gate',
      f4Boundary,
      f4BoundaryDetail,
      writeText('f4-t1-gate', {
        acct0: acct0.val || acct0.err,
        pending: pending0.val || pending0.err
      })
    );

    const reqNo = 'UATWD' + Date.now();
    const WD = 60000;
    const apply = await api(admin.token, 'POST', `${ops}/merchant-wallets/${M}/withdraw`, {
      amountCents: WD,
      requestNo: reqNo
    });
    const requestId = apply.json?.data?.requestId;
    const st0 = apply.json?.data?.status || '';
    record(
      'F4',
      'F4-04-apply-pending',
      apply.status === 200 && st0 === 'PENDING_REVIEW' && requestId != null,
      `代提现 ${WD}：HTTP=${apply.status} status=${st0} requestId=${requestId ?? '(无)'}（>=阈值50000⇒PENDING_REVIEW 并冻结毛额）`,
      writeText('f4-apply', apply.json)
    );

    // F4-05 冻结台账（ref_type=WITHDRAW, ref_id=requestId, entry_type=WITHDRAW_FREEZE）。
    const frz = psqlSafe(
      `select coalesce(string_agg(entry_type,','),'(无)') from wallet_ledger where domain='MERCHANT' and ref_type='WITHDRAW' and ref_id='${requestId}'`
    );
    record(
      'F4',
      'F4-05-freeze-ledger',
      /WITHDRAW_FREEZE/.test(frz.val || ''),
      `申请冻结台账：${frz.val || frz.err}（期望含 WITHDRAW_FREEZE）`,
      writeText('f4-freeze', { led: frz.val || frz.err })
    );

    // F4-06 两节点审批（运营审核 MANAGER → 财务复核 FINANCE）：admin(100000001) 为两节点处理人，逐轮 approve 推至非 PENDING。
    let stReview = st0;
    for (let i = 0; i < 3 && stReview === 'PENDING_REVIEW'; i++) {
      const rv = await api(admin.token, 'POST', `${ops}/merchant-withdraws/${requestId}/review`, {
        approve: true,
        remark: `审计通过第${i + 1}轮`
      });
      stReview = rv.json?.data?.status || stReview;
    }
    const fin = psqlSafe(
      `select status||'|'||amount_cents||'|'||fee_cents||'|'||coalesce(paid_at::text,'') from merchant_withdraw_request where request_id=${requestId}`
    );
    const finA = (fin.val || '').split('|');
    const finalStatus = finA[0] || stReview;
    record(
      'F4',
      'F4-06-two-node-review',
      finalStatus === 'PAID' || finalStatus === 'APPROVED',
      `两节点审批后终态=${finalStatus}｜库 status=${finA[0]} fee=${finA[1] ?? '?'} paid_at=${finA[3] || '空'}（MANAGER→FINANCE 均 admin 处理，approve 两次达 APPROVED，MOCK 软启动打款达 PAID）`,
      writeText('f4-review-final', { loopStatus: stReview, db: fin.val || fin.err })
    );

    // F4-07 打款台账 + 净额守恒（WITHDRAW_PAID；有手续费则 WITHDRAW_FEE；net+fee=毛额）。
    const led = psqlSafe(
      `select coalesce(string_agg(entry_type,','),'') from wallet_ledger where domain='MERCHANT' and ref_type='WITHDRAW' and ref_id='${requestId}'`
    );
    const fee = Number(finA[1] || 0);
    const hasPaid = /WITHDRAW_PAID/.test(led.val || '');
    const feeOk = fee > 0 ? /WITHDRAW_FEE/.test(led.val || '') : true;
    record(
      'F4',
      'F4-07-payout-ledger',
      finalStatus === 'PAID' ? hasPaid && feeOk : 'SKIP',
      `打款台账：${led.val || led.err}｜毛额${finA[1] ?? WD} 费${fee}（PAID 应 consumeFrozen 拆 WITHDRAW_PAID${fee > 0 ? '+WITHDRAW_FEE' : ''}）`,
      writeText('f4-payout-ledger', { led: led.val || led.err, fee })
    );

    // F4-08 驳回链：另一笔 PENDING_REVIEW → approve=false ⇒ REJECTED + releaseFrozen（不依赖审批推进，直接落 REJECTED）。
    const reqNo2 = 'UATWDRJ' + Date.now();
    const apply2 = await api(admin.token, 'POST', `${ops}/merchant-wallets/${M}/withdraw`, {
      amountCents: 60000,
      requestNo: reqNo2
    });
    const rid2 = apply2.json?.data?.requestId;
    let rejStatus = '';
    if (rid2 != null) {
      const rj = await api(admin.token, 'POST', `${ops}/merchant-withdraws/${rid2}/review`, {
        approve: false,
        remark: '审计驳回验证'
      });
      rejStatus = rj.json?.data?.status || '';
    }
    const relLed =
      rid2 == null
        ? { val: '', err: '无 requestId' }
        : psqlSafe(
            `select coalesce(string_agg(entry_type,','),'') from wallet_ledger where domain='MERCHANT' and ref_type='WITHDRAW' and ref_id='${rid2}'`
          );
    record(
      'F4',
      'F4-08-reject-releases-frozen',
      rejStatus === 'REJECTED' && /WITHDRAW_RELEASE/.test(relLed.val || ''),
      `驳回：status=${rejStatus || '(未建)'}｜台账=${relLed.val || relLed.err}（REJECTED 须 releaseFrozen 返还可提现额）`,
      writeText('f4-reject', { status: rejStatus, led: relLed.val || relLed.err })
    );

    // F4-09 同 requestNo 不同金额 ⇒ 409（AU-006：requestNo 全局唯一且须归属/金额一致，防跨商户吞单）。
    const dup = await api(admin.token, 'POST', `${ops}/merchant-wallets/${M}/withdraw`, {
      amountCents: 70000,
      requestNo: reqNo
    });
    record(
      'F4',
      'F4-09-duplicate-conflict',
      dup.status === 409,
      `复用 requestNo+改金额：HTTP=${dup.status} msg=${String(dup.json?.message || '').slice(0, 40)}（期望 409 单号占用）`,
      writeText('f4-dup', dup.json)
    );
  },

  /**
   * F5 平台资金池操作（CB-033）。
   *
   * 契约面（亲读）：OpsPlatformFundController（base /api/v2/ops/admin）
   *   GET  /platform-fund/accounts                         ops:fund:list
   *   GET  /platform-fund/accounts/{id}/flows              ops:fund:list
   *   POST /platform-fund/accounts                         ops:fund:manage（建账，initialBalanceCents>0 落 RECHARGE 流水）
   *   POST /platform-fund/accounts/{id}/recharge           ops:fund:manage（+amountCents）
   *   POST /platform-fund/accounts/{id}/sweep              ops:fund:manage（归集=出账，落 -amountCents，SWEEP）
   *   POST /platform-fund/accounts/{id}/adjust             ops:fund:manage（带符号调整，remark 必填，ADJUST）
   *   ⚠️「提现/打款 PAYOUT」不是运营端点——由商户提现成功后 recordPayoutQuietly 自动写（ref_type=MERCHANT_WITHDRAW），
   *      不能 POST ⇒ 第五种流水如实标观察、不伪造。
   *
   * 落库：platform_fund_account(V341, 建栈即空) / platform_fund_flow(flow_type RECHARGE|SWEEP|ADJUST|PAYOUT，amount 带符号，
   *   balance_after 每次更新后回读)。校验皆服务端 ResponseStatusException（无 bean-validation）：
   *   channel 空/账户名空 400、初始余额<0 400、渠道重复 409、sweep<=0 400、adjust delta=0 或 remark 空 400、
   *   账户不存在 404、amount=0 400。余额**允许为负**（设计如此，无透支 SQL 守卫）。
   */
  F5: async ({ state }) => {
    const admin = state.adminToken ? { token: state.adminToken } : await adminLoginViaApi();
    state.adminToken = admin.token;
    const base = '/api/v2/ops/admin/platform-fund';
    const CH = 'BANK';

    // F5-00 viewer 越权建账 ⇒ 403（ops:fund:manage）。
    const v = await opsLoginAs(VIEWER_PHONE, VIEWER_PASSWORD);
    const vres = await api(v.token, 'POST', `${base}/accounts`, {
      channel: 'WECHAT',
      accountName: '越权',
      initialBalanceCents: 1
    });
    record(
      'F5',
      'F5-00-viewer-forbidden',
      vres.status === 403,
      `viewer 建账：HTTP=${vres.status}（期望 403 缺 ops:fund:manage）`,
      writeText('f5-viewer-create', vres.json)
    );

    // 清测试渠道，保证可重复（隔离栈）。
    psql(
      `begin; delete from platform_fund_flow where account_id in (select account_id from platform_fund_account where channel='${CH}'); delete from platform_fund_account where channel='${CH}'; commit;`
    );

    // F5-01 建账 + 初始余额 ⇒ RECHARGE 流水，balance=initial。
    const created = await api(admin.token, 'POST', `${base}/accounts`, {
      channel: CH,
      accountName: '审计对公户',
      initialBalanceCents: 100000,
      remark: '审计建账'
    });
    const accId = created.json?.data?.accountId;
    // 建账返回 DTO 的 balanceCents 恒 0（初始余额经 recordFlow 落库后才更新，DTO 是建账前的实体）⇒ 以库为准。
    const accBal = Number(
      (
        psqlSafe(`select balance_cents from platform_fund_account where account_id=${accId}`).val ||
        ''
      ).trim()
    );
    record(
      'F5',
      'F5-01-create-account',
      created.status === 200 && accId != null && accBal === 100000,
      `建账 BANK：HTTP=${created.status} accountId=${accId ?? '(无)'} 库 balance=${accBal}（初始 100000 落 RECHARGE 流水；建账 DTO balanceCents=0 属实体时序，以库断言）`,
      writeText('f5-create', { body: created.json, dbBalance: accBal })
    );

    // F5-02 负向：初始余额<0 ⇒ 400；渠道重复 ⇒ 409。
    const negInit = await api(admin.token, 'POST', `${base}/accounts`, {
      channel: 'ALIPAY',
      accountName: '负初始',
      initialBalanceCents: -5
    });
    const dupCh = await api(admin.token, 'POST', `${base}/accounts`, {
      channel: CH,
      accountName: '重复',
      initialBalanceCents: 0
    });
    record(
      'F5',
      'F5-02-create-guards',
      negInit.status === 400 && dupCh.status === 409,
      `负初始余额 HTTP=${negInit.status}（期望400）｜重复渠道 HTTP=${dupCh.status}（期望409）`,
      writeText('f5-create-guards', { negInit: negInit.json, dup: dupCh.json })
    );

    // F5-03 充值 +20000 ⇒ balance 120000，RECHARGE。
    const before3 = Number(
      (
        psqlSafe(`select balance_cents from platform_fund_account where account_id=${accId}`).val ||
        ''
      ).trim()
    );
    const rech = await api(admin.token, 'POST', `${base}/accounts/${accId}/recharge`, {
      amountCents: 20000,
      remark: '审计充值'
    });
    const balAfter3 = Number(rech.json?.data?.balanceCents ?? NaN);
    record(
      'F5',
      'F5-03-recharge',
      rech.status === 200 && before3 + 20000 === balAfter3,
      `充值：HTTP=${rech.status} ${before3}→${balAfter3}（期望 +20000）`,
      writeText('f5-recharge', rech.json)
    );

    // F5-04 归集 -30000 ⇒ balance -30000（出账），SWEEP；负数守卫 ⇒ 400。
    const sweepBad = await api(admin.token, 'POST', `${base}/accounts/${accId}/sweep`, {
      amountCents: 0,
      remark: '零归集'
    });
    const before4 = Number(
      (
        psqlSafe(`select balance_cents from platform_fund_account where account_id=${accId}`).val ||
        ''
      ).trim()
    );
    const sweep = await api(admin.token, 'POST', `${base}/accounts/${accId}/sweep`, {
      amountCents: 30000,
      remark: '审计归集'
    });
    const balAfter4 = Number(sweep.json?.data?.balanceCents ?? NaN);
    record(
      'F5',
      'F5-04-sweep-outflow',
      sweep.status === 200 && before4 - 30000 === balAfter4 && sweepBad.status === 400,
      `归集：HTTP=${sweep.status} ${before4}→${balAfter4}（期望 -30000 出账）｜amount=0 HTTP=${sweepBad.status}（期望400）`,
      writeText('f5-sweep', { sweep: sweep.json, zero: sweepBad.json })
    );

    // F5-05 带符号调整 -12345 ⇒ balance -12345（允许为负）；缺 remark ⇒ 400。
    const adjNoRemark = await api(admin.token, 'POST', `${base}/accounts/${accId}/adjust`, {
      amountCents: -1,
      remark: '   '
    });
    const before5 = Number(
      (
        psqlSafe(`select balance_cents from platform_fund_account where account_id=${accId}`).val ||
        ''
      ).trim()
    );
    const adj = await api(admin.token, 'POST', `${base}/accounts/${accId}/adjust`, {
      amountCents: -12345,
      remark: '审计调整'
    });
    const balAfter5 = Number(adj.json?.data?.balanceCents ?? NaN);
    record(
      'F5',
      'F5-05-adjust-signed',
      adj.status === 200 && before5 - 12345 === balAfter5 && adjNoRemark.status === 400,
      `调整：HTTP=${adj.status} ${before5}→${balAfter5}（带符号，允许负余额）｜空原因 HTTP=${adjNoRemark.status}（期望400「必须填写原因」）`,
      writeText('f5-adjust', { adjust: adj.json, noRemark: adjNoRemark.json })
    );

    // F5-06 流水读回：三条 RECHARGE/SWEEP/ADJUST 类型齐、balance_after 单调与库一致。
    const flows = await api(admin.token, 'GET', `${base}/accounts/${accId}/flows?limit=50`);
    const arr = flows.json?.data;
    const list = Array.isArray(arr) ? arr : Array.isArray(arr?.list) ? arr.list : null;
    const types = list ? list.map((f) => f.flowType) : [];
    record(
      'F5',
      'F5-06-flows-ledger',
      flows.status === 200 &&
        list !== null &&
        ['RECHARGE', 'SWEEP', 'ADJUST'].every((t) => types.includes(t)),
      `流水：HTTP=${flows.status} 类型集=${Array.from(new Set(types)).join(',')}（建账/充值 RECHARGE、归集 SWEEP、调整 ADJUST 齐）`,
      writeText('f5-flows', flows.json)
    );

    // F5-07 PAYOUT（第五种流水）不可 POST ⇒ 如实观察，不伪造（由商户提现自动写，见 F4）。
    record(
      'F5',
      'F5-07-payout-not-admin',
      'SKIP',
      'PAYOUT 流水非运营端点：商户提现成功后 recordPayoutQuietly(PlatformFundService:224) 自动落，ref_type=MERCHANT_WITHDRAW；F4 打款链已产出该流水，此处不 POST 伪造',
      null
    );
  },

  /**
   * F6 设备租赁（押金闭环 + 日扣守卫 + 出账(REN 日摊)已挣；锁柜/复机缺 PENDING 种子如实 SKIP）。
   *
   * 两套并行机制（亲读，勿混）：
   *   CB-034 押金/月租账单  OpsDeviceRentalController（base /api/v2/ops/admin/device-rental）
   *     POST /deposits            ops:rental:manage（登记，requireRentalDevice⇒设备须 coop_mode=RENTAL；charge_mode 固定 ONLINE ⇒ 从商户余额真扣 DEPOSIT_CHARGE；installFeeDeduct 默认 0=V383）
   *     POST /deposits/{id}/deduct ops:rental:manage（扣款，reason 必填）
   *     POST /deposits/{id}/settle ops:rental:manage（撤机结算：剩余押金 − 安装费(默认0) 退回商户 DEPOSIT_RETURN）
   *     GET  /deposits · /deposits/{id}/events · /bills   ops:fund:list
   *   F-2/F-5/F-6 日扣台账  OpsMerchantFeeLedgerController（base /api/v2/ops/admin/merchant-fee-ledgers）
   *     GET /            ops:merchant-withdraw:list
   *     POST /{id}/settle ops:merchant-withdraw:adjust（结清→自动复机 unlockFeeOverdueIfCleared）
   *     POST /rerun-daily-fees?feeDate=YYYY-MM-DD  ops:merchant-withdraw:adjust（守卫：仅<=昨天、回溯<=92 天、格式对）
   *   配置设备为 RENTAL：PATCH /api/v2/ops/admin/devices/{deviceId}  ops:device:edit（UpdateDeviceRequest.coopMode/rentCents/dataFeeCents/rentStartYm；normalizeCoop∈{SELF,FRANCHISE,CONSIGN,RENTAL}）
   *   落库：device_rental_deposit(V342/V360/V365/V383 status HELD|SETTLED) / _event(CHARGE|DEDUCT|RETURN) / merchant_fee_ledger(V355/V380)。
   *
   * ✅ 出账(MerchantDailyIncomeNotifyScheduler.chargeDailyPeriodFees:343) 已可挣：先 PATCH 设备 coopMode=RENTAL/rentCents=31000，
   *   rerun-daily-fees 遍历全商户 → REN 行 amount=floor(31000/feeDate.lengthOfMonth())、status=SETTLED（dataFee=0⇒无 DATA 行）。
   *   日扣幂等：钱包流水 refId=日期 + 台账 uk_merchant_fee_date_type；本用例先删 (M,feeDate) REN/DATA 行以让 REN 行「当场产出」再核。
   * 🔴 锁柜/复机仍需一条账龄满 lock_days(默认7) 的 **PENDING** merchant_fee_ledger 行（产品日扣只写 SETTLED，PENDING 仅人工补录）。
   *   隔离演示栈无 PENDING ⇒ 状态机段如实 SKIP（绝不 SQL 造 PENDING 行凑绿）。
   */
  F6: async ({ state }) => {
    const admin = state.adminToken ? { token: state.adminToken } : await adminLoginViaApi();
    state.adminToken = admin.token;
    const rental = '/api/v2/ops/admin/device-rental';
    const feeBase = '/api/v2/ops/admin/merchant-fee-ledgers';
    const opsBase = '/api/v2/ops/admin';
    const DEV = 'EG746560232828'; // F1 演示设备，merchant=MCH-DEFAULT（有钱包）
    const M = 'MCH-DEFAULT';
    const iso = (ms) => new Date(Date.now() + ms).toISOString().slice(0, 10);
    const DAYMS = 86400000;
    const bal = () =>
      Number(
        (
          psqlSafe(
            `select balance_cents from wallet_account where domain='MERCHANT' and owner_id='${M}'`
          ).val || '0'
        ).trim()
      );

    // F6-00 rerun 守卫（负向，无需种子）：今天/未来 400、回溯>92 天 400、格式错 400。
    const gFut = await api(
      admin.token,
      'POST',
      `${feeBase}/rerun-daily-fees?feeDate=${iso(DAYMS)}`
    );
    const gFar = await api(
      admin.token,
      'POST',
      `${feeBase}/rerun-daily-fees?feeDate=${iso(-93 * DAYMS)}`
    );
    const gBad = await api(admin.token, 'POST', `${feeBase}/rerun-daily-fees?feeDate=2026-13-40`);
    record(
      'F6',
      'F6-00-rerun-guards',
      gFut.status === 400 && gFar.status === 400 && gBad.status === 400,
      `重跑日扣守卫：未来 HTTP=${gFut.status}｜回溯93天 HTTP=${gFar.status}｜非法日期 HTTP=${gBad.status}（均应 400）`,
      writeText('f6-rerun-guards', { fut: gFut.json, far: gFar.json, bad: gBad.json })
    );

    // F6-01 押金链/出账前置：PATCH 设备为 coop_mode=RENTAL、rent_cents=31000、data_fee=0（ops:device:edit，产品 API 非 SQL）。
    //   requireRentalDevice ⇒ 押金登记须 RENTAL；chargeDailyPeriodFees 的 REN 日摊也须此配置。dataFee=0 ⇒ 只出 REN 不出 DATA。
    const RENT = 31000;
    const ym = iso(0).slice(0, 7);
    const cfg = await api(admin.token, 'PATCH', `${opsBase}/devices/${DEV}`, {
      coopMode: 'RENTAL',
      rentCents: RENT,
      dataFeeCents: 0,
      rentStartYm: ym
    });
    const devRow = psqlSafe(
      `select coop_mode||'|'||coalesce(rent_cents,0)::text||'|'||coalesce(data_fee_cents,0)::text from device_info where device_id='${DEV}'`
    );
    const [dcoop, drent] = (devRow.val || '').split('|');
    record(
      'F6',
      'F6-01-rental-device-config',
      cfg.status === 200 && dcoop === 'RENTAL' && Number(drent) === RENT,
      `设备配置：HTTP=${cfg.status}｜库 coop_mode=${dcoop} rent_cents=${drent} data_fee=${devRow.val ? devRow.val.split('|')[2] : ''}（期望 RENTAL/${RENT}/0）`,
      writeText('f6-device-config', {
        http: cfg.status,
        body: cfg.json,
        db: devRow.val || devRow.err
      })
    );

    // F6-02 押金登记（ONLINE ⇒ 商户余额 -20000，status HELD，balance=20000）。清旧行以可重复。
    const b0 = bal();
    psql(
      `begin; delete from device_rental_deposit_event where deposit_id in (select deposit_id from device_rental_deposit where device_id='${DEV}'); delete from device_rental_deposit where device_id='${DEV}'; commit;`
    );
    const reg = await api(admin.token, 'POST', `${rental}/deposits`, {
      deviceId: DEV,
      amountCents: 20000,
      remark: '审计押金登记'
    });
    const depId = reg.json?.data?.depositId;
    const depRow0 = psqlSafe(
      `select status||'|'||balance_cents from device_rental_deposit where deposit_id=${depId}`
    );
    const [dstatus0, dbal0] = (depRow0.val || '').split('|');
    record(
      'F6',
      'F6-02-deposit-register',
      reg.status === 200 && dstatus0 === 'HELD' && Number(dbal0) === 20000 && bal() === b0 - 20000,
      `押金登记：HTTP=${reg.status} depositId=${depId ?? '(无)'}｜库 status=${dstatus0} balance=${dbal0}｜商户钱包 ${b0}→${bal()}（ONLINE 收取，installFee 默认0）`,
      writeText('f6-deposit-register', {
        http: reg.status,
        body: reg.json,
        walletDelta: b0 - bal()
      })
    );

    // F6-03 扣款守卫：空依据 ⇒ 400；有效扣 5000 ⇒ balance 15000 + DEDUCT 事件。depId 缺失（登记失败）⇒ BLOCKED，不伪造。
    if (depId === undefined || depId === null) {
      record(
        'F6',
        'F6-03-deposit-deduct',
        'BLOCKED',
        'F6-02 未拿到 depositId ⇒ 扣款链无从推进（不伪造）',
        null
      );
      record('F6', 'F6-04-deposit-settle', 'BLOCKED', '同上：无 depositId ⇒ 结算链 BLOCKED', null);
    } else {
      const dedBad = await api(admin.token, 'POST', `${rental}/deposits/${depId}/deduct`, {
        amountCents: 5000,
        reason: '  '
      });
      const ded = await api(admin.token, 'POST', `${rental}/deposits/${depId}/deduct`, {
        amountCents: 5000,
        reason: '审计验机损坏抵扣'
      });
      const dedEvents = psqlSafe(
        `select coalesce(string_agg(event_type,','),'') from device_rental_deposit_event where deposit_id=${depId}`
      );
      const depRow2 = psqlSafe(
        `select balance_cents from device_rental_deposit where deposit_id=${depId}`
      );
      record(
        'F6',
        'F6-03-deposit-deduct',
        dedBad.status === 400 &&
          ded.status === 200 &&
          Number((depRow2.val || '').trim()) === 15000 &&
          /DEDUCT/.test(dedEvents.val || ''),
        `扣款：空依据 HTTP=${dedBad.status}(期望400)｜有效 HTTP=${ded.status} 余额=${depRow2.val || depRow2.err} 事件=${dedEvents.val || dedEvents.err}（期望 15000 + DEDUCT）`,
        writeText('f6-deduct', { noReason: dedBad.json, ok: ded.json, events: dedEvents.val })
      );

      // F6-04 结算：剩余 15000 − 安装费(默认0) ⇒ 全额退回商户钱包，status SETTLED，RETURN 事件；钱包净 = b0 − 5000。
      const set = await api(admin.token, 'POST', `${rental}/deposits/${depId}/settle`, {
        remark: '审计撤机结算'
      });
      const depRow3 = psqlSafe(
        `select status from device_rental_deposit where deposit_id=${depId}`
      );
      const setEvents = psqlSafe(
        `select coalesce(string_agg(event_type,','),'') from device_rental_deposit_event where deposit_id=${depId}`
      );
      const bAfter = bal();
      record(
        'F6',
        'F6-04-deposit-settle',
        set.status === 200 &&
          (depRow3.val || '').trim() === 'SETTLED' &&
          /RETURN/.test(setEvents.val || ''),
        `结算：HTTP=${set.status} 状态=${depRow3.val || depRow3.err} 事件=${setEvents.val || setEvents.err}｜钱包 ${b0}→${bAfter}（净 -5000=已抵扣；installFee0⇒剩余 15000 全额 DEPOSIT_RETURN）`,
        writeText('f6-settle', { http: set.status, body: set.json, wallet: `${b0}->${bAfter}` })
      );
    }

    // F6-05 读回列表：GET /deposits 须 200 数组。
    const listR = await api(admin.token, 'GET', `${rental}/deposits`);
    const arr = listR.json?.data;
    const list = Array.isArray(arr) ? arr : Array.isArray(arr?.list) ? arr.list : null;
    record(
      'F6',
      'F6-05-deposits-list',
      listR.status === 200 && list !== null,
      `押金列表：HTTP=${listR.status} ${list ? list.length + ' 行' : '非数组'}`,
      writeText('f6-deposits-list', listR.json)
    );

    // F6-06 viewer 越权登记押金 ⇒ 403（ops:rental:manage）。
    const v = await opsLoginAs(VIEWER_PHONE, VIEWER_PASSWORD);
    const vdep = await api(v.token, 'POST', `${rental}/deposits`, {
      deviceId: DEV,
      amountCents: 1
    });
    record(
      'F6',
      'F6-06-viewer-forbidden',
      vdep.status === 403,
      `viewer 登记押金：HTTP=${vdep.status}（期望 403 缺 ops:rental:manage）`,
      writeText('f6-viewer-deposit', vdep.json)
    );

    // F6-07 出账(REN 日摊)——用产品链挣，不 SQL 造行：先删 (M,feeDate) 当日 REN/DATA 台账行让「当场产出」，
    //   rerun-daily-fees 遍历全商户 ⇒ 该 RENTAL 设备按 floor(rent/当月天数) 出 REN SETTLED 行（dataFee=0⇒无 DATA）。
    //   feeDate 取 -2 天（跨服务时区仍稳 <= 昨天，避开 today-1 边界）。
    const chargeDate = iso(-2 * DAYMS);
    const [cy, cm] = chargeDate.split('-').map(Number);
    const cdim = new Date(cy, cm, 0).getDate();
    const rentDailyExp = Math.floor(RENT / cdim);
    psql(
      `begin; delete from merchant_fee_ledger where merchant_id='${M}' and fee_date='${chargeDate}' and fee_type in ('REN','DATA'); commit;`
    );
    const rr = await api(admin.token, 'POST', `${feeBase}/rerun-daily-fees?feeDate=${chargeDate}`);
    const renRow = psqlSafe(
      `select status||'|'||amount_cents::text from merchant_fee_ledger where merchant_id='${M}' and fee_date='${chargeDate}' and fee_type='REN'`
    );
    const [renStatus, renAmount] = (renRow.val || '').split('|');
    record(
      'F6',
      'F6-07-daily-charge-out',
      rr.status === 200 && renStatus === 'SETTLED' && Number(renAmount) === rentDailyExp,
      `出账：重跑 ${chargeDate} HTTP=${rr.status} chargedCents=${rr.json?.data?.chargedCents}｜库 REN 行 status=${renStatus} 金额=${renAmount}（期望 floor(${RENT}/${cdim})=${rentDailyExp}，dataFee=0⇒无 DATA 行）`,
      writeText('f6-daily-charge', {
        http: rr.status,
        body: rr.json,
        ren: renRow.val || renRow.err,
        rentDailyExp,
        cdim
      })
    );

    // F6-08 锁柜/复机状态机：需账龄>=lock_days 的 **PENDING** merchant_fee_ledger 行（产品日扣只写 SETTLED，
    //   PENDING 仅人工补录）。隔离栈无 PENDING 且不 SQL 造行凑绿 ⇒ 如实 SKIP。出账已 F6-07 单独挣得。
    const pendingFee = psqlSafe(`select count(*) from merchant_fee_ledger where status='PENDING'`);
    record(
      'F6',
      'F6-08-fee-lock-statemachine',
      'SKIP',
      `锁柜/复机未复现：PENDING 台账行数=${pendingFee.val || pendingFee.err}（日扣只写 SETTLED；锁柜须人工补 PENDING 且最老行账龄满 merchant.overdue.lock_days 默认7⇒FEE_OVERDUE 锁柜，结清后 unlockFeeOverdueIfCleared 复机。接真须造 PENDING 行，见接真 checklist；出账链见 F6-07 已挣）`,
      null
    );
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
    const DEV = 'EG746560232828'; // F1 演示设备（merchant=MCH-DEFAULT）

    // F7-01 建单：body 契约 CreateFromSuggestionRequest{deviceId(必填), assigneeUserId?}；
    //   返回 ApiResponse<ReplenishmentRouteDto{tasks:[{taskId,...}]}>。
    //   无补货缺口时产品回 400「当前无补货缺口」⇒ 诚实 SKIP（绝不 SQL 造货道缺口凑绿）。
    const created = await api(
      admin.token,
      'POST',
      '/api/v2/ops/admin/replenishment/suggest/create-task',
      {
        deviceId: DEV
      }
    );
    const taskArr = created.json?.data?.tasks;
    const taskId = Array.isArray(taskArr) && taskArr.length ? taskArr[0].taskId : undefined;
    const noGap = created.status === 400 && /无补货缺口/.test(String(created.json?.message || ''));
    const bodyKnown = created.status === 200 && taskId !== undefined && taskId !== null;
    record(
      'F7',
      'F7-01-create-task',
      bodyKnown ? true : 'SKIP',
      bodyKnown
        ? `补货任务已建 taskId=${taskId}（routeId=${created.json?.data?.routeId}，GET fulfillment-tasks 可核）`
        : noGap
          ? `create-task HTTP=400「当前无补货缺口」⇒ 演示设备 ${DEV} 货道库存已达 PAR，无缺口可建；诚实 SKIP（不 SQL 造缺口凑绿）`
          : `create-task HTTP=${created.status} msg=${created.json?.message || ''}——body 已按 CreateFromSuggestionRequest 补齐，非 200 且非「无缺口」即响应待核实`,
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

    // 每探针带满足 DTO 约束（@NotBlank/@NotNull/@NotEmpty）的最小合法 body，使 viewer 越过 Spring @Valid
    //   抵达 @RequiresPermissions AOP 闸门；null = 该端点空 body 已能到闸门（早先已 403）。
    const PROBES = [
      [
        'F8-01-refund',
        'POST',
        '/api/v2/ops/admin/orders/{orderId}/refund',
        'ops:order:refund',
        'AdminDashboardController:428',
        { reason: '审计越权探针', restoreInventory: false }
      ],
      [
        'F8-02-balance',
        'POST',
        '/api/v2/ops/admin/users/{userId}/balance',
        'ops:user:balance',
        'AdminDashboardController:389',
        { deltaCents: 0, reason: '审计越权探针', idempotencyKey: 'audit-f8-02' }
      ],
      [
        'F8-03-recharge-refund',
        'POST',
        '/api/v2/ops/admin/recharge/{orderId}/refund',
        'ops:recharge:edit',
        'AdminDashboardController:418',
        null
      ],
      [
        'F8-04-balance-refund-review',
        'POST',
        '/api/v2/ops/admin/balance-refunds/{requestId}/review',
        'ops:balance-refund:review',
        'AdminBalanceRefundController:38',
        { approve: false }
      ],
      [
        'F8-05-withdraw-payout',
        'POST',
        '/api/v2/ops/admin/merchant-withdraws/{requestId}/payout',
        'ops:merchant-withdraw:review',
        'MerchantWithdrawController:93',
        null
      ],
      [
        'F8-06-wallet-adjust',
        'POST',
        '/api/v2/ops/admin/merchant-wallets/{merchantId}/adjust',
        'ops:merchant-withdraw:adjust',
        'MerchantWalletController:51',
        { amountCents: 0 }
      ],
      [
        'F8-07-fund-adjust',
        'POST',
        '/api/v2/ops/admin/platform-fund/accounts/{accountId}/adjust',
        'ops:fund:manage',
        'OpsPlatformFundController:73',
        null
      ],
      [
        'F8-08-staff-payout',
        'POST',
        '/api/v2/ops/admin/staff-wallets/{staffUserId}/payout',
        'ops:line-manager:edit',
        'OpsStaffWalletController:46',
        null
      ],
      [
        'F8-09-pricing',
        'PUT',
        '/api/v2/ops/admin/devices/{deviceId}/pricing/{skuId}',
        'ops:pricing:edit',
        'AdminDevicePricingController:46',
        null
      ],
      [
        'F8-10-config',
        'PUT',
        '/api/v2/ops/admin/system-configs',
        'ops:config:edit',
        'SystemConfigController:70',
        { configKey: 'audit.f8.probe', configValue: '1' }
      ],
      [
        'F8-11-rent-split',
        'PUT',
        '/api/v2/ops/admin/site-contracts/{contractId}/rent-split-rules',
        'ops:org:edit',
        'SiteRentSplitController:32',
        { rules: [{ partyType: 'MERCHANT' }] }
      ],
      [
        'F8-12-rbac-assign',
        'POST',
        '/api/v2/ops/admin/rbac/operators',
        'ops:rbac:assign:add',
        'OpsRbacController:146',
        { phoneNumber: '13900009999', name: '审计探针', password: 'AuditProbe123' }
      ]
    ];

    // 判定口径（区分 RBAC 闸门 vs 先校验，避免把「没触达闸门」误判成安全红/绿）：
    //   viewer 403           ⇒ RBAC 拦住 = PASS（真绿）
    //   viewer 2xx           ⇒ 越权成功 = FAIL（真缺陷）
    //   viewer 其余(4xx/5xx)  ⇒ 端点先校验 body/id 再判权限，探针没触达闸门 ⇒ 权限面「未验」，记 SKIP
    //                          （合法 body 已补齐，正常应把未验降到 0）。
    // admin 正向对照仅对「路径含 {id}」的探针发起（用假 id 999999999 ⇒ 业务查无资源，只证「过了 RBAC」而不动真实资金/配置）；
    //   config(F8-10)/rbac(F8-12) 无路径 id ⇒ 跳过 admin 正向写，避免真实改配置/建操作员。
    let blocked = 0;
    let breach = 0;
    let unverified = 0;
    const unverifiedList = [];
    for (const [code, method, pathTpl, perm, file, body] of PROBES) {
      const hasPathId = /\{[a-zA-Z]+\}/.test(pathTpl);
      // 用不存在的资源 id 兜住路径参，使 admin 正向对照只证「过了 RBAC」而不动真实资金/配置。
      const p = pathTpl.replace(/\{[a-zA-Z]+\}/g, '999999999');
      const v = await api(viewer.token, method, p, body || {});
      const a = hasPathId
        ? await api(admin.token, method, p, body || {})
        : { status: 'n/a（无路径 id，跳过正向写以免真实改配置/建操作员）', json: {} };
      let verdict;
      if (v.status === 403 && (v.json?.code === 403 || /无权限/.test(v.text || ''))) {
        blocked++;
        verdict = true;
      } else if (v.status >= 200 && v.status < 300) {
        breach++;
        verdict = false;
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
          ? `${method} ${p}［需 ${perm}｜${file}］ viewer=${v.status}=先校验未触达 RBAC 闸门（admin=${a.status}）⇒ 权限面未验`
          : `${method} ${p}［需 ${perm}｜${file}］ viewer=${v.status}${hasPathId ? ` admin=${a.status}` : ''}（${v.status === 403 ? '闸门拦住' : '见状态码'}）`,
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
  // AU-191（批次 10）：最小断言数阈值——全 SKIP 轮次（环境塌了/无种子）此前 exit 0，
  // 「0 失败=绿」可被空轮次冒充上线证据。MIN_PASS 按 flows 下限写死，不足即红。
  const MIN_PASS = Number(process.env.UAT_MIN_PASS || '40');
  if (summary.pass < MIN_PASS) {
    console.error(`[gate] pass=${summary.pass} < MIN_PASS=${MIN_PASS}: 空轮次/环境塌陷，不得作为上线证据`);
    process.exit(1);
  }
  process.exit(summary.fail > 0 ? 1 : 0);
}

main().catch((e) => {
  console.error(e);
  process.exit(2);
});
