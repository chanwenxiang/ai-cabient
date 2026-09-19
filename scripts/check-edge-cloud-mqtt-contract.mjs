#!/usr/bin/env node
/**
 * O4 协议治理：MQTT 契约三方对账（proto ⟷ Java 常量 ⟷ edge Kotlin 硬编码）。
 *
 *   node scripts/check-edge-cloud-mqtt-contract.mjs
 *
 * 为什么需要它：仓库里存在**三份**对同一套 MQTT 协议的描述 ——
 *   ① `proto/cabinet.proto`（文档化契约，不参与代码生成）
 *   ② `CabinetConstants` / `MqttTopics`（服务端权威）
 *   ③ `edge/android-app/.../MqttDeviceClient.kt`（端侧硬编码 topic 与 payload.type）
 * 改其中任意一处而忘掉其余，都不会有任何编译/运行报错，只会**静默失联**。
 * 本脚本把三者拉平对账；另外拦住「声明了却零调用」的死 topic。
 *
 * 🔴 判据取向：只做**机械可推导**的比较（oneof 字段名 upper_snake 后逐字等于常量值、
 *    topic 形状按前缀+`{deviceId}`+后缀重组），**不维护任何手工映射表** ——
 *    手工映射表本身就会成为第四处漂移源。
 * 🔴 每个解析结果都断言非空：解析器一旦失效（正则打滑）必须**报错**，
 *    否则空集合互相比对会恒绿（门禁失效形态③）。
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');

const F_TOPICS = join(
  root,
  'services/common/common-core/src/main/java/com/aicabinet/common/mqtt/MqttTopics.java'
);
const F_CONSTS = join(
  root,
  'services/common/common-core/src/main/java/com/aicabinet/common/constants/CabinetConstants.java'
);
const F_KT = join(
  root,
  'edge/android-app/app/src/main/java/com/aicabinet/edge/mqtt/MqttDeviceClient.kt'
);
const F_PROTO = join(root, 'proto/cabinet.proto');

const errors = [];
const notes = [];

function err(m) {
  errors.push(m);
}
function read(p) {
  try {
    return readFileSync(p, 'utf8');
  } catch (e) {
    err(`读不到 ${relative(root, p)}: ${e.message}`);
    return '';
  }
}

/** 去注释：Java/Kotlin 的 `//`、`/* *​/`。不去注释 ⇒ 注释里的样例会被当成真实声明。 */
function stripComments(src) {
  return src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/[^\n]*/g, '$1');
}

/** proto 同样要剥注释：`// AlertEvent alert = 13;` 若被当成真字段，删掉声明也能恒绿（形态③）。 */
function stripProtoComments(src) {
  return stripComments(src);
}

function sortedArr(s) {
  return [...s].sort();
}
function eqSets(a, b) {
  return sortedArr(a).join(',') === sortedArr(b).join(',');
}
function fmt(s) {
  return sortedArr(s).join(' | ') || '(空)';
}

// ── 解析 ①：Java 常量 ────────────────────────────────────────────────────────
const constsSrc = stripComments(read(F_CONSTS));
const cmds = new Set();
const events = new Set();
for (const m of constsSrc.matchAll(/static\s+final\s+String\s+(MQTT_CMD_\w+)\s*=\s*"([^"]*)"/g)) {
  cmds.add(m[2]);
}
for (const m of constsSrc.matchAll(
  /static\s+final\s+String\s+(MQTT_EVENT_TYPE_\w+)\s*=\s*"([^"]*)"/g
)) {
  events.add(m[2]);
}
if (cmds.size === 0)
  err('解析 CabinetConstants 得到 0 个 MQTT_CMD_*：正则或文件结构已变，判据会恒绿');
if (events.size === 0) err('解析 CabinetConstants 得到 0 个 MQTT_EVENT_TYPE_*');

// ── 解析 ②：Java topic 形状 ──────────────────────────────────────────────────
const topicsSrc = stripComments(read(F_TOPICS));
const prefixM = topicsSrc.match(/static\s+final\s+String\s+CABINET\s*=\s*"([^"]*)"/);
if (!prefixM) err('解析 MqttTopics 得不到 CABINET 前缀常量');
const prefix = prefixM ? prefixM[1] : '';
const TOPIC_PLACEHOLDER = '{deviceId}';

/** name -> shape；shape 里设备号写作 {deviceId} */
const javaTopicShapes = new Map();
for (const m of topicsSrc.matchAll(
  /static\s+String\s+(\w+)\s*\(\s*String\s+\w+\s*\)\s*\{\s*return\s+CABINET\s*\+\s*\w+\s*\+\s*"([^"]*)"\s*;/g
)) {
  javaTopicShapes.set(m[1], `${prefix}${TOPIC_PLACEHOLDER}${m[2]}`);
}
// 直接赋字面量的 topic 常量（通配符形态）
for (const m of topicsSrc.matchAll(/static\s+final\s+String\s+(\w+)\s*=\s*"([^"]+)"/g)) {
  if (m[1] === 'CABINET') continue;
  javaTopicShapes.set(m[1], m[2]);
}
if (javaTopicShapes.size === 0) err('解析 MqttTopics 得到 0 个 topic 形状');

// ── 解析 ③：proto 的 oneof 字段 ─────────────────────────────────────────────
const protoSrc = stripProtoComments(read(F_PROTO));

/** 在 src 中取 `message <name> {` 的**配对花括号内**内容 */
function messageBody(src, name) {
  const start = src.search(new RegExp(`message\\s+${name}\\s*\\{`));
  if (start < 0) {
    err(`proto 里找不到 message ${name}`);
    return '';
  }
  const open = src.indexOf('{', start);
  let depth = 0;
  for (let i = open; i < src.length; i++) {
    if (src[i] === '{') depth++;
    else if (src[i] === '}') {
      depth--;
      if (depth === 0) return src.slice(open + 1, i);
    }
  }
  err(`proto 里 message ${name} 花括号不配对`);
  return '';
}

/** 取 oneof payload 的字段名，upper_snake 后返回 */
function oneofFields(src, name) {
  const body = messageBody(src, name);
  const idx = body.search(/oneof\s+\w+\s*\{/);
  if (idx < 0) {
    err(`proto 的 message ${name} 里找不到 oneof`);
    return new Set();
  }
  const open = body.indexOf('{', idx);
  let depth = 0;
  let inner = '';
  for (let i = open; i < body.length; i++) {
    if (body[i] === '{') depth++;
    else if (body[i] === '}') {
      depth--;
      if (depth === 0) {
        inner = body.slice(open + 1, i);
        break;
      }
    }
  }
  const out = new Set();
  for (const m of inner.matchAll(/(\w+)\s+(\w+)\s*=\s*\d+\s*;/g)) {
    out.add(m[2].toUpperCase());
  }
  if (out.size === 0) err(`proto 的 message ${name} oneof 解析到 0 个字段`);
  return out;
}

const protoCmd = oneofFields(protoSrc, 'DeviceCommand');
const protoEvent = oneofFields(protoSrc, 'DeviceEvent');

// ── 解析 ④：edge Kotlin 硬编码 ──────────────────────────────────────────────
const ktSrc = stripComments(read(F_KT));
const ktTopics = new Set();
for (const m of ktSrc.matchAll(/"cabinet\/\$deviceId(\/[^"]*)"/g)) {
  ktTopics.add(`${prefix}${TOPIC_PLACEHOLDER}${m[1]}`);
}
const ktOutTypes = new Set();
for (const m of ktSrc.matchAll(/"type"\s+to\s+"([A-Z_]+)"/g)) {
  ktOutTypes.add(m[1]);
}
const ktInTypes = new Set();
for (const m of ktSrc.matchAll(/node\["type"\]\s*==\s*"([A-Z_]+)"/g)) {
  ktInTypes.add(m[1]);
}
if (ktTopics.size === 0) err('解析 MqttDeviceClient.kt 得到 0 个硬编码 topic');
if (ktOutTypes.size === 0) err('解析 MqttDeviceClient.kt 得到 0 个上行 payload.type');

// ── 对账 ────────────────────────────────────────────────────────────────────
// C1 下行命令：proto oneof ∪ upper == MQTT_CMD_*
if (!eqSets(protoCmd, cmds)) {
  err(
    `下行命令集合不一致\n     proto oneof : ${fmt(protoCmd)}\n     MQTT_CMD_*  : ${fmt(cmds)}\n` +
      `     ⇒ proto 缺 ${fmt(new Set([...cmds].filter((x) => !protoCmd.has(x))))}；` +
      `proto 多 ${fmt(new Set([...protoCmd].filter((x) => !cmds.has(x))))}`
  );
}
// C2 上行事件：proto oneof ∪ upper == MQTT_EVENT_TYPE_*
if (!eqSets(protoEvent, events)) {
  err(
    `上行事件集合不一致\n     proto oneof        : ${fmt(protoEvent)}\n     MQTT_EVENT_TYPE_*  : ${fmt(events)}\n` +
      `     ⇒ proto 缺 ${fmt(new Set([...events].filter((x) => !protoEvent.has(x))))}；` +
      `proto 多 ${fmt(new Set([...protoEvent].filter((x) => !events.has(x))))}`
  );
}
// C3 edge 上行 type ⊆ 事件常量
for (const t of ktOutTypes) {
  if (!events.has(t))
    err(`edge 上行的 payload.type="${t}" 不在 MQTT_EVENT_TYPE_* 里（${fmt(events)}）`);
}
// C4 edge 下行的 type ⊆ 命令常量
for (const t of ktInTypes) {
  if (!cmds.has(t)) err(`edge 下行的 payload.type="${t}" 不在 MQTT_CMD_* 里（${fmt(cmds)}）`);
}
// C5 edge 使用的 topic 必须都是 MqttTopics 声明过的形状
const javaShapes = new Set(javaTopicShapes.values());
for (const t of ktTopics) {
  if (!javaShapes.has(t)) {
    err(`edge 硬编码 topic "${t}" 在 MqttTopics 里没有对应声明（已声明：${fmt(javaShapes)}）`);
  }
}

// C6 死 topic 声明：MqttTopics 每个成员必须在**别处**被引用（去掉自身声明行）
const prodSrcs = [];
(function walk(dir) {
  let entries = [];
  try {
    entries = readdirSync(dir);
  } catch {
    return;
  }
  for (const e of entries) {
    const p = join(dir, e);
    let st;
    try {
      st = statSync(p);
    } catch {
      continue;
    }
    if (st.isDirectory()) {
      if (e === 'target' || e === 'node_modules' || e === 'build') continue;
      walk(p);
    } else if (/\.(java|kt)$/.test(e)) {
      prodSrcs.push(stripComments(readFileSync(p, 'utf8')));
    }
  }
})(join(root, 'services'));
(function walk(dir) {
  let entries = [];
  try {
    entries = readdirSync(dir);
  } catch {
    return;
  }
  for (const e of entries) {
    const p = join(dir, e);
    let st;
    try {
      st = statSync(p);
    } catch {
      continue;
    }
    if (st.isDirectory()) {
      if (e === 'target' || e === 'node_modules' || e === 'build') continue;
      walk(p);
    } else if (/\.(java|kt)$/.test(e)) {
      prodSrcs.push(stripComments(readFileSync(p, 'utf8')));
    }
  }
})(join(root, 'edge'));

const haystack = prodSrcs.join('\n');
for (const name of javaTopicShapes.keys()) {
  // 计数 = 全仓出现次数 − 本文件里的声明/定义处
  const total = (haystack.match(new RegExp(`\\b${name}\\b`, 'g')) || []).length;
  const inDeclFile = (stripComments(read(F_TOPICS)).match(new RegExp(`\\b${name}\\b`, 'g')) || [])
    .length;
  if (total - inDeclFile <= 0) {
    err(
      `MqttTopics.${name} 是**死声明**：全仓生产代码零引用。` +
        `要么补上使用方，要么连同 proto/门禁一起删掉（勿留「看起来像协议」的空壳）`
    );
  }
}

// ── 解析 ⑤：内部 HTTP 端点（device/simulator 客户端 → trade-service 服务端）────────
// 这是 Javadoc 里承诺过、却一直没人实现的第二半契约：客户端 `.uri("/internal/...")`
// 与服务端 `@RequestMapping` + `@PostMapping` 拼出来的路径对不上 = **运行期静默 404**，
// 编译期毫无提示。
const TRADE_API_DIR = join(root, 'services/trade-service/src/main/java/com/aicabinet/trade/api');
const INTERNAL_CLIENTS = [
  'services/device-service/src/main/java/com/aicabinet/device/client/TradeServiceClient.java',
  'edge/device-simulator/src/main/java/com/aicabinet/simulator/DeviceSimulator.java'
];

/** 路径变量名归一：`{deviceId}` / `{sessionId}` → `{}`（两端变量名允许不同） */
const normPath = (p) => p.replace(/\{[^}]*\}/g, '{}').replace(/\/+$/, '');

/** 服务端：类级 @RequestMapping + 方法级 @XxxMapping 组合出的全部内部路径 */
function serverInternalPaths() {
  const out = new Set();
  let files = [];
  try {
    files = readdirSync(TRADE_API_DIR).filter((f) => /Controller\.java$/.test(f));
  } catch (e) {
    err(`读不到 trade-service api 目录：${e.message}`);
    return out;
  }
  for (const f of files) {
    const src = stripComments(readFileSync(join(TRADE_API_DIR, f), 'utf8'));
    // 类级：@RequestMapping("X") 紧接 public class
    const cls = src.match(/@RequestMapping\("([^"]*)"\)\s*public\s+class/);
    if (!cls || !cls[1].startsWith('/internal/')) continue;
    const base = cls[1];
    // 方法级路径**可以省略**：`@PostMapping`（无参数）= 直接挂在类级前缀上。
    // 第一版要求必须有 `("...")`，于是把 `/internal/v1/ops-alerts` 误报成漂移（假红）。
    for (const m of src.matchAll(/@(?:Post|Get|Put|Delete|Patch)Mapping\b(?:\("([^"]*)"\))?/g)) {
      out.add(normPath(base + (m[1] || '')));
    }
  }
  if (out.size === 0) err('在 trade-service 里解析到 0 个 /internal/* 端点，锚点可能已失效');
  return out;
}

const serverPaths = serverInternalPaths();
for (const rel of INTERNAL_CLIENTS) {
  const src = stripComments(read(rel));
  for (const m of src.matchAll(/\.uri\("([^"]*)"\)/g)) {
    const p = m[1];
    if (!p.startsWith('/internal/')) continue;
    if (!serverPaths.has(normPath(p))) {
      err(
        `${rel}: 调用了内部端点 ${p}，但 trade-service 里**没有**对应映射` +
          `（拼不出来的服务端路径 = 运行期静默 404）`
      );
    }
  }
}
notes.push(
  `trade-service 内部端点 ${serverPaths.size} 个；已对账 ${INTERNAL_CLIENTS.length} 个客户端文件`
);

// ── 汇总 ────────────────────────────────────────────────────────────────────
notes.push(`proto 下行命令 ${protoCmd.size} 个、上行事件 ${protoEvent.size} 个`);
notes.push(
  `MqttTopics 声明 topic ${javaTopicShapes.size} 个；edge 硬编码 topic ${ktTopics.size} 个`
);
notes.push(`edge 上行 type ${fmt(ktOutTypes)}；下行 type ${fmt(ktInTypes)}`);

for (const n of notes) console.log(`[check-edge-cloud-mqtt-contract] ${n}`);
if (errors.length) {
  for (const e of errors) console.error(`[check-edge-cloud-mqtt-contract] FAIL ${e}`);
  console.error(`[check-edge-cloud-mqtt-contract] ${errors.length} 处协议漂移`);
  process.exit(1);
}
console.log('[check-edge-cloud-mqtt-contract] OK：proto / Java 常量 / edge Kotlin 三方一致');
