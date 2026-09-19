#!/usr/bin/env node
/**
 * P0 告警升级链门禁（O3）—— 「升级入口接线 / 默认关 / 不猜收件人 / 判业务码 / 类型拼写」对不上就红。
 *
 * 背景（09-19）
 * -------------
 * O3 要补的是「聊天渠道没人收到时，打短信/电话叫值班人」。这类新增最容易出的**不是**功能不работа，
 * 而是三种**静默失效**（本项目最忌的「信号在骗读者」）：
 *
 *  1. **只接了半条路径**：`send()` 会升级、`trySend()` 不会（或反之）⇒ 「有的告警会升级、有的不会」，
 *     而且两条路径的日志看起来都正常。
 *  2. **没人收到时悄悄打给一个写死的号码**：值班表解析失败就回退到某个默认号 ⇒ 半夜打错人，
 *     而"打过电话了"这个信号是**绿的**。
 *  3. **拿 HTTP 200 当已送达**：短信/外呼网关在「余额不足、号码黑名单、模板未报备」时常常回
 *     200 + 业务码非 0 ⇒ 升级链自认为成功、值班人从没收到。
 *
 * 规则
 * ----
 *   E1 入口接线：`send` 与 `trySend` **两条**路径都必须触发升级（只接一条 ⇒ 红）。
 *   E2 默认关：总开关 seed 值必须是 `FALSE`，且读取时必须带 `false` 兜底
 *      （配置行缺失也不得变成默认开）。
 *   E3 不猜收件人：解析不出值班人的分支必须直接返回 `NO_ONCALL`，**且该分支内不得有任何投递调用**。
 *   E4 判业务码：`deliveryError` 必须为 SMS/PHONE 声明看 `code`（HTTP 200 + 业务码非 0 要判失败）。
 *   E5 渠道绑定配置键：两级升级的 URL 必须来自各自 `ops.alert.escalation_*_webhook`，
 *      且**不得**出现硬编码 http(s) 地址。
 *   E6 解析下限 + 类型真实性（防恒真判据）：默认类型白名单要解析出 ≥ {@link MIN_ESCALATION_TYPES} 个，
 *      且**每个类型都必须作为字面量出现在 services 的 Java 源码里** —— 拼错一个字母 =
 *      该类型永远不升级，且没有任何报错。
 *   E7 命名空间：升级相关键必须保持 `ops.alert.` 前缀 ⇒ 自动继承
 *      `check-ops-alert-channels` 的 R5（seed）/R6（运营台可见）。
 *   E8 不得豁免：升级键**不允许**被塞进 `INTERNAL_ALERT_KEYS` 绕过 R6。
 *   E9 值班表解析 fail-closed：`OnCallRoster` 里非数组/解析异常必须落到空表（`EMPTY`）。
 *
 * 负向对照（逐条真注入漂移 → 必红 → 还原 → 逐字节回绿）见
 * `docs/evidence/2026-09-19-o3-escalation/scripts/ab-drift-ops-escalation.mjs`。
 *
 *   node scripts/check-ops-alert-escalation.mjs
 */
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-ops-alert-escalation]';

const SERVICE_DIR = join(root, 'services/trade-service/src/main/java/com/aicabinet/trade/service');
const DISPATCHER = join(SERVICE_DIR, 'OpsAlertDispatcher.java');
const CONFIG_SERVICE = join(SERVICE_DIR, 'SystemConfigService.java');
const ROSTER = join(SERVICE_DIR, 'OnCallRoster.java');
const ALERT_VIEW = join(root, 'clients/admin-vue/src/views/system/AlertRuleView.vue');
const CHANNELS_GATE = join(root, 'scripts/check-ops-alert-channels.mjs');
/** 类型字面量的搜索面：所有服务端 Java 源码。 */
const SERVICE_ROOTS = join(root, 'services');

/** 默认类型白名单至少要解析出这么多（低于此值说明结构已变，判据失去判别力）。 */
const MIN_ESCALATION_TYPES = 3;
/** 升级键必须使用的命名空间前缀。 */
const ALERT_KEY_PREFIX = 'ops.alert.';

const fail = (msg) => {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
};

const rel = (p) => relative(root, p).replace(/\\/g, '/');

for (const f of [DISPATCHER, CONFIG_SERVICE, ROSTER, ALERT_VIEW, CHANNELS_GATE]) {
  if (!existsSync(f)) fail(`缺少被校验文件 ${rel(f)}（路径可能已变）`);
}

const dispatcher = readFileSync(DISPATCHER, 'utf8');
const configService = readFileSync(CONFIG_SERVICE, 'utf8');
const roster = readFileSync(ROSTER, 'utf8');
const alertView = readFileSync(ALERT_VIEW, 'utf8');
const channelsGate = readFileSync(CHANNELS_GATE, 'utf8');

const problems = [];

/**
 * 抽出某个方法的整块源码（从签名匹配处起到下一个顶层成员为止）。
 * ⚠️ 不按行号/固定行数切：那种锚点会随无关改动漂移。
 */
function methodBody(source, signature) {
  const m = source.match(signature);
  if (!m) return null;
  const rest = source.slice(m.index + m[0].length);
  const next = rest.search(/\n {4}(?:public|private|protected|static|final|record|enum|@|\/\*\*)/);
  return next < 0 ? rest : rest.slice(0, next);
}

// ---------- E1 入口接线：两条路径都要触发 ----------
const sendBody = methodBody(
  dispatcher,
  /public void send\(String type, String title, String message,\s*\n\s*Map<String, Object> extra, String\.\.\. extraUrls\) \{/
);
const trySendBody = methodBody(
  dispatcher,
  /public boolean trySend\(String type, String title, String message,\s*\n\s*Map<String, Object> extra, String\.\.\. extraUrls\) \{/
);
if (!sendBody) {
  problems.push('OpsAlertDispatcher 里找不到 5 参 send(...) —— 结构可能已变，E1 失去判别力');
} else if (!/\bescalateIfNeeded\(/.test(sendBody)) {
  problems.push('send(...) 未触发升级链 ⇒ 走 send 的那批告警永远不会升级（且日志看不出异常）');
}
if (!trySendBody) {
  problems.push('OpsAlertDispatcher 里找不到 trySend(...) —— 结构可能已变，E1 失去判别力');
} else if (!/\bescalateIfNeeded\(/.test(trySendBody)) {
  problems.push('trySend(...) 未触发升级链 ⇒ 走 trySend 的那批告警永远不会升级');
}

// ---------- E2 总开关默认关（两端都要 fail-closed） ----------
const enabledSeed = configService.match(
  /upsertIfAbsent\(\s*OPS_ALERT_ESCALATION_ENABLED\s*,\s*([^,]+),/
);
if (!enabledSeed) {
  problems.push('OPS_ALERT_ESCALATION_ENABLED 未在 upsertIfAbsent 里 seed ⇒ 运营台看不到、改不了');
} else {
  const seedValue = enabledSeed[1].trim();
  if (!/^FALSE$/.test(seedValue)) {
    problems.push(
      `OPS_ALERT_ESCALATION_ENABLED 的 seed 值是 ${seedValue}，期望 FALSE ⇒ 新装环境会默认开启升级链（半夜打电话）`
    );
  }
}
const enabledRead = dispatcher.match(
  /getBoolean\(\s*SystemConfigService\.OPS_ALERT_ESCALATION_ENABLED\s*,\s*(true|false)\s*\)/
);
if (!enabledRead) {
  problems.push(
    '未按 `getBoolean(OPS_ALERT_ESCALATION_ENABLED, false)` 读取开关 ⇒ 配置行缺失时默认值不明（必须显式兜底 false）'
  );
} else if (enabledRead[1] !== 'false') {
  problems.push('总开关的读取兜底值是 true ⇒ 配置行缺失即默认开启升级链');
}

// ---------- E3 不猜收件人 ----------
const escalateBody = methodBody(dispatcher, /EscalationOutcome escalateIfNeeded\(/);
if (!escalateBody) {
  problems.push('找不到 escalateIfNeeded —— 结构可能已变，E3/E5 失去判别力');
} else {
  const noOnCall = escalateBody.match(/if\s*\(onCall == null\)\s*\{([\s\S]*?)\n {8}\}/);
  if (!noOnCall) {
    problems.push('escalateIfNeeded 里找不到「值班人缺失」分支 ⇒ 无法确认它是否 fail-closed');
  } else {
    const branch = noOnCall[1];
    if (!/return\s+EscalationOutcome\.NO_ONCALL\s*;/.test(branch)) {
      problems.push('「无值班人」分支没有返回 NO_ONCALL');
    }
    if (/\b(tryPost|escalateTo|postJson)\s*\(/.test(branch)) {
      problems.push(
        '「无值班人」分支里出现了投递调用 ⇒ 值班表解析失败时会打给一个猜出来的收件人（fail-open）'
      );
    }
  }
}

// ---------- E4 判业务码 ----------
const deliveryErrorBody = methodBody(dispatcher, /static String deliveryError\(/);
if (!deliveryErrorBody) {
  problems.push('找不到 deliveryError —— 结构可能已变，E4 失去判别力');
} else if (!/case\s+ESCALATION_SMS,\s*ESCALATION_PHONE\s*->\s*"code"/.test(deliveryErrorBody)) {
  problems.push(
    'deliveryError 未为 SMS/PHONE 声明看 `code` 业务码 ⇒ 网关回 HTTP 200 + 业务码非 0（余额不足/黑名单）会被当成"已送达"，升级链自认为成功、值班人从没收到'
  );
}

// ---------- E5 渠道绑定配置键 + 无硬编码地址 ----------
const smsCall =
  /escalateTo\(\s*ESCALATION_SMS\s*,\s*SystemConfigService\.OPS_ALERT_ESCALATION_SMS_WEBHOOK\s*,/;
const phoneCall =
  /escalateTo\(\s*ESCALATION_PHONE\s*,\s*SystemConfigService\.OPS_ALERT_ESCALATION_PHONE_WEBHOOK\s*,/;
if (!escalateBody) {
  // 已在 E3 报过
} else {
  if (!smsCall.test(escalateBody)) {
    problems.push(
      '一级升级未绑定 OPS_ALERT_ESCALATION_SMS_WEBHOOK ⇒ 短信地址来源不明（可能写死或复用了聊天渠道）'
    );
  }
  if (!phoneCall.test(escalateBody)) {
    problems.push('二级升级未绑定 OPS_ALERT_ESCALATION_PHONE_WEBHOOK ⇒ 电话地址来源不明');
  }
}
if (/(["'])https?:\/\/[^"']*\1/.test(dispatcher)) {
  problems.push('OpsAlertDispatcher 里出现硬编码 http(s) 地址 ⇒ 渠道地址必须来自系统参数');
}

// ---------- E6 解析下限 + 类型真实性 ----------
const typesSeed = configService.match(
  /upsertIfAbsent\(\s*OPS_ALERT_ESCALATION_TYPES\s*,\s*([\s\S]*?),\s*\n\s*"/
);
if (!typesSeed) {
  problems.push('OPS_ALERT_ESCALATION_TYPES 未 seed ⇒ 升级白名单无法在运营台配置');
} else {
  // ⚠️ 默认值常写成**多个字符串拼接**、且**一个引号内含逗号**（"A,B," + "C"）。
  // 所以：先把所有字面量并起来，再按逗号切 —— 别按「一个引号一个类型」解析（那样会解析出 0 个）。
  const configuredTypes = [...typesSeed[1].matchAll(/"([^"]*)"/g)]
    .map((m) => m[1])
    .join('')
    .split(',')
    .map((s) => s.trim())
    .filter((s) => /^[A-Z][A-Z0-9_]{2,}$/.test(s));
  if (configuredTypes.length < MIN_ESCALATION_TYPES) {
    fail(
      `只从默认类型白名单解析出 ${configuredTypes.length} 个类型（期望 ≥ ${MIN_ESCALATION_TYPES}）：结构可能已变，E6 失去判别力`
    );
  }
  // 类型真实性：拼错一个字母 ⇒ 该类型永远不升级，且没有任何报错。
  const serviceSources = [];
  const walk = (dir) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const full = join(dir, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (entry.name.endsWith('.java')) serviceSources.push(readFileSync(full, 'utf8'));
    }
  };
  walk(SERVICE_ROOTS);
  for (const type of configuredTypes) {
    // 只认**带引号的字面量**：宽松匹配（前后缀包含）会让「拼错一个字母」照样通过，
    // 而那正是这条规则要抓的东西。
    const emitted = serviceSources.some((src) => src.includes(`"${type}"`));
    if (!emitted) {
      problems.push(
        `默认升级类型「${type}」在 services 的 Java 源码里找不到带引号的字面量 ⇒ 可能是错字（该类型永远不会触发升级，且不报错）`
      );
    }
  }
}

// ---------- E7 命名空间元判据 ----------
const escalationKeys = [
  ...configService.matchAll(/"((?:ops\.alert\.)?[\w.]*(?:escalation|oncall)[\w.]*)"/g)
]
  .map((m) => m[1])
  .filter((k) => k.includes('.'));
if (!escalationKeys.length) {
  fail('解析不到任何升级相关配置键：结构可能已变，E7 失去判别力');
}
for (const key of escalationKeys) {
  if (!key.startsWith(ALERT_KEY_PREFIX)) {
    problems.push(
      `升级配置键「${key}」不在 ${ALERT_KEY_PREFIX} 命名空间内 ⇒ 会被 check-ops-alert-channels 的 R5/R6 漏掉（既不 seed 也不在运营台露出）`
    );
  }
  // 再直接确认一次运营台露出：万一将来某个键被加进 INTERNAL_ALERT_KEYS，R6 就不再守它（E8 也会红，
  // 但这条让本门禁自己就能独立断定"改不了就等于没有"）。
  if (!alertView.includes(`'${key}'`)) {
    problems.push(`升级配置键「${key}」未出现在 AlertRuleView.vue 的分组里 ⇒ 运营台看不到、改不了`);
  }
}

// ---------- E8 不得豁免 ----------
const internalMatch = channelsGate.match(/const INTERNAL_ALERT_KEYS = new Set\(\[([\s\S]*?)\]\)/);
if (!internalMatch) {
  problems.push(
    'check-ops-alert-channels.mjs 里找不到 INTERNAL_ALERT_KEYS ⇒ 无法确认升级键没有被豁免出运营台'
  );
} else {
  for (const key of escalationKeys) {
    if (internalMatch[1].includes(key)) {
      problems.push(
        `升级配置键「${key}」被放进 INTERNAL_ALERT_KEYS ⇒ 绕过"运营台必须可见"这道守卫`
      );
    }
  }
}

// ---------- E9 值班表解析 fail-closed ----------
const rosterParse = methodBody(roster, /static OnCallRoster parse\(/);
if (!rosterParse) {
  problems.push('OnCallRoster 里找不到 parse( —— 结构可能已变，E9 失去判别力');
} else {
  if (!/return\s+EMPTY\s*;/.test(rosterParse)) {
    problems.push('OnCallRoster.parse 没有任何「退化为空表」的分支 ⇒ 解析失败可能抛异常或放行');
  }
  if (!/!root\.isArray\(\)/.test(rosterParse)) {
    problems.push('OnCallRoster.parse 未校验「必须是 JSON 数组」⇒ 对象/字符串配置会被静默接受');
  }
}

if (problems.length) {
  console.error(`${TAG} FAIL: 发现 ${problems.length} 处升级链缺陷：`);
  for (const p of problems) console.error(`  - ${p}`);
  process.exit(1);
}

console.log(
  `${TAG} OK: send/trySend 两条路径均触发升级；总开关 seed=FALSE 且读取兜底 false；` +
    '无值班人时 fail-closed 不猜收件人；SMS/PHONE 判业务码 code；两级渠道绑定各自配置键且无硬编码地址；' +
    `默认白名单类型全部可在源码中找到出处；升级键保持在 ${ALERT_KEY_PREFIX} 命名空间且未被豁免`
);
