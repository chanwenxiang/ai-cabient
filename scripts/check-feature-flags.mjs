#!/usr/bin/env node
/**
 * 功能开关注册表一致性门禁 —— 「所有功能都能在运营台开关」这句话的守卫。
 *
 * 背景：系统配置（`system_config`）早就支持运行期读写，但**没有权威清单**，于是长出三类缺陷：
 *   ① 代码里读、却没 seed ⇒ 运营台根本看不到该键 ⇒ **假可配置**（实测踩到 3 个）；
 *   ② seed 了、却没有任何消费者 ⇒ 运营台能关但关了个空气 ⇒ **死开关**（实测 1 个）；
 *   ③ 键名以字面量散落各处 ⇒ 改一处忘一处，清单与代码必然漂移。
 * 本门禁把 `services/trade-service/src/main/resources/ops/feature-flags.json`（注册表）当成
 * 单一权威清单，并用**双向**校验把它钉在源码上。
 *
 * 六条规则：
 *   R0 结构：注册表必须能解析，条目数 ≥ 40，键唯一，字段齐全，type/group 合法。
 *      （锚点下限 + 结构校验，防止某天正则/字段改名后「解析出 0 条」被读成绿。）
 *   R1 seed → 注册表：`SystemConfigService.ensureDefaults()` 里每个 `upsertIfAbsent` 的键都必须登记。
 *   R2 注册表 → 有消费者：每个键都要能在源码里找到使用它的证据，
 *      否则它就是「能关但关了个空气」的死开关。
 *   R3 读取点 → 注册表：每个能静态解析出键的读取点都必须登记在册（反制 ①）。
 *   R4 动态键站点：接收者是变量、静态解析不出键的调用点必须**逐表达式**登记在册；
 *      多一处、少一处、换个表达式都要红。刻意**不用行号**做锚点 —— 行号会随插入而静默漂移，
 *      那是「信号在骗读者」（本项目已踩过 `ci.yml:297` 漂到 147 行之外）。
 *   R5 注册表 → seed：每个登记在册的键都必须被 seed，保证默认值存在。
 *
 * 🔴 豁免（R2 的 `deprecated` 条目）**必须自带可验证锚点**：声明它被废弃的代码注释必须真实存在。
 *    没有锚点的豁免会退化成「随便标个 deprecated 就绕过门禁」。
 *
 * 已知边界（刻意声明，避免读成「全覆盖」）：R3 只覆盖静态可解析的读取点；
 * 经反射/动态拼接键名的读取不在覆盖范围内 —— 这类站点会被 R4 的计数差异逼出来复核。
 *
 *   node scripts/check-feature-flags.mjs
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-feature-flags]';

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

const REGISTRY_REL = 'services/trade-service/src/main/resources/ops/feature-flags.json';
const SCS_REL =
  'services/trade-service/src/main/java/com/aicabinet/trade/service/SystemConfigService.java';

const registryPath = join(root, REGISTRY_REL);
if (!existsSync(registryPath)) fail(`找不到注册表 ${REGISTRY_REL}`);
if (!existsSync(join(root, SCS_REL))) fail(`找不到 ${SCS_REL}`);

const registry = JSON.parse(readFileSync(registryPath, 'utf8'));
const scs = readFileSync(join(root, SCS_REL), 'utf8');
const problems = [];

// ── 共用解析（与 SystemConfigService 的实际写法对齐） ──────────────────────
const constMap = new Map();
for (const m of scs.matchAll(
  /public static final String ([A-Z0-9_]+)\s*=\s*"((?:[^"\\]|\\.)*)"\s*;/g
)) {
  constMap.set(m[1], m[2]);
}

/** 括号深度感知的参数切分：默认值/描述里含逗号，纯正则会被骗。 */
function extractCallArgs(text, openParenIdx) {
  const args = [];
  let depth = 0;
  let inStr = false;
  let esc = false;
  let cur = '';
  for (let i = openParenIdx; i < text.length; i++) {
    const ch = text[i];
    if (inStr) {
      cur += ch;
      if (esc) esc = false;
      else if (ch === '\\') esc = true;
      else if (ch === '"') inStr = false;
      continue;
    }
    if (ch === '"') {
      inStr = true;
      cur += ch;
      continue;
    }
    if (ch === '(') {
      depth++;
      if (depth === 1) continue;
    }
    if (ch === ')') {
      depth--;
      if (depth === 0) {
        args.push(cur.trim());
        return args;
      }
    }
    if (ch === ',' && depth === 1) {
      args.push(cur.trim());
      cur = '';
      continue;
    }
    cur += ch;
  }
  return null;
}

const unquote = (s) => {
  if (s == null) return null;
  const m = /^"([\s\S]*)"$/.exec(s.trim());
  return m ? m[1] : null;
};

/**
 * 解析 Java **字面量拼接**："a," + "b" → "a,b"。
 *
 * 🔴 为什么必须有它（2026-09-20，CI 实测）：`unquote` 的 /^"([\s\S]*)"$/ 是**贪婪**的，
 * 遇到 `"a," + "b"` 会把整段吞掉首尾引号、返回**源码片段**（内含真实换行）。
 * 于是判据拿到的是源码文本而不是值 —— 而源码文本的换行是 CRLF 还是 LF **取决于磁盘**：
 * 本机 core.autocrlf=true 把 services 下的 .java 检出为 CRLF，恰好与注册表 JSON 里手抄的
 * 同一片段（含 CRLF 转义）**一致 ⇒ 假绿**；CI（blob 为 LF）两者差一个 CR ⇒ **红**。
 * 解析成真值后，比较结果与行尾彻底无关。
 *
 * 只处理**全部由字面量与 + 组成**的表达式；单个字面量、或含常量引用时返回 null（交给原逻辑）。
 */
function resolveConcat(expr) {
  const s = String(expr).trim();
  let i = 0;
  let out = '';
  let parts = 0;
  while (i < s.length) {
    while (i < s.length && /\s/.test(s[i])) i++;
    if (i >= s.length) break;
    if (s[i] === '+') {
      i++;
      continue;
    }
    if (s[i] !== '"') return null;
    let j = i + 1;
    while (j < s.length && s[j] !== '"') j += s[j] === '\\' ? 2 : 1;
    if (j >= s.length) return null;
    out += s.slice(i + 1, j);
    parts++;
    i = j + 1;
  }
  return parts > 1 ? out : null;
}

function resolveExpr(expr, localMap) {
  if (expr == null) return null;
  let t = expr.trim();
  const sv = /^String\.valueOf\((.+)\)$/.exec(t);
  if (sv) t = sv[1].trim();
  // 必须先试拼接：`unquote` 的贪婪正则会把 `"a," + "b"` 整段吞成源码片段。
  const concat = resolveConcat(t);
  if (concat !== null) return concat;
  const lit = unquote(t);
  if (lit !== null) return lit;
  let name = t;
  if (name.startsWith('SystemConfigService.')) name = name.slice('SystemConfigService.'.length);
  if (constMap.has(name)) return constMap.get(name);
  if (localMap && localMap.has(name)) return resolveExpr(localMap.get(name), localMap);
  return resolveConstRef(t);
}

// ── 扫描 main java ────────────────────────────────────────────────────────
function walkJava(dir, out = []) {
  for (const e of readdirSync(dir)) {
    const full = join(dir, e);
    if (statSync(full).isDirectory()) {
      if (['target', 'node_modules', '.git'].includes(e)) continue;
      walkJava(full, out);
    } else if (e.endsWith('.java')) {
      out.push(full);
    }
  }
  return out;
}

const servicesDir = join(root, 'services');
const mainJava = walkJava(servicesDir)
  .map((p) => ({ abs: p, rel: relative(root, p).replace(/\\/g, '/') }))
  .filter((p) => !p.rel.includes('/src/test/'));

if (mainJava.length === 0) fail('services/ 下没扫到任何 main java，门禁已失效');

/**
 * 解析扫到的 java 里「另一个类的常量」引用，如
 * `com.aicabinet.common.constants.CabinetConstants.MIN_BALANCE_CENTS` → `2000`。
 * 刻意不写死具体类名 —— 换成别的常量、别的类仍然能解出来。
 */
function resolveConstRef(expr) {
  const m = /^([a-z][A-Za-z0-9_.]*\.)?([A-Z][A-Za-z0-9_]*)\.([A-Z0-9_]+)$/.exec(
    String(expr).trim()
  );
  if (!m) return null;
  const pkgPath = m[1] ? m[1].replace(/\.$/, '').replace(/\./g, '/') : '';
  const suffix = pkgPath ? `${pkgPath}/${m[2]}.java` : `${m[2]}.java`;
  const target = mainJava.find((p) => p.rel.endsWith(suffix));
  if (!target) return null;
  const text = readFileSync(target.abs, 'utf8');
  const hit = new RegExp(`static final (?:int|long|String) ${m[3]}\\s*=\\s*([^;]+);`).exec(text);
  if (!hit) return null;
  const raw = hit[1].trim().replace(/_/g, '');
  // 与 resolveExpr 同因（§11.42）：`unquote` 的贪婪正则会把 `"a," + "b"` 整段吞成**源码片段**
  // （内含真实换行，而行尾取决于磁盘）⇒ 必须先试拼接。当前注册表里没有 `Class.CONST` 形式的
  // 默认值（该分支尚不可达），此处属**预防性**补齐——同类缺陷不留第二处。
  const concat = resolveConcat(raw);
  if (concat !== null) return concat;
  const lit = unquote(raw);
  if (lit !== null) return lit;
  return /^-?\d+$/.test(raw) ? raw : null;
}

// ── R0：结构 ──────────────────────────────────────────────────────────────
const ALLOWED_TYPES = new Set(['BOOLEAN', 'NUMBER', 'TEXT', 'TEXTAREA', 'SELECT']);
const flags = Array.isArray(registry.flags) ? registry.flags : null;
if (!flags) fail('注册表缺 flags 数组');
if (flags.length < 40) {
  fail(`注册表只有 ${flags.length} 个条目（下限 40）—— 解析或字段改名可能已让它退化，不能读成绿`);
}
const groups = Array.isArray(registry.groups) ? registry.groups : [];
if (groups.length === 0) fail('注册表缺 groups 数组');

const seenKeys = new Set();
for (const f of flags) {
  for (const field of ['key', 'group', 'type', 'default', 'description']) {
    if (typeof f[field] !== 'string') {
      problems.push(`条目 ${JSON.stringify(f.key ?? f)} 缺字段 ${field}`);
    }
  }
  if (typeof f.key !== 'string' || !/^[a-z][a-z0-9_]*(\.[a-z0-9_]+)+$/.test(f.key)) {
    problems.push(`键名形状不合法: ${JSON.stringify(f.key)}`);
  }
  if (seenKeys.has(f.key)) problems.push(`键重复: ${f.key}`);
  seenKeys.add(f.key);
  if (!ALLOWED_TYPES.has(f.type)) problems.push(`${f.key} 的 type 非法: ${JSON.stringify(f.type)}`);
  if (!groups.includes(f.group)) problems.push(`${f.key} 的 group「${f.group}」不在 groups 列表里`);
  if (f.type === 'SELECT') {
    if (!Array.isArray(f.options) || f.options.length < 2) {
      problems.push(`${f.key} 是 SELECT 但 options 少于 2 项`);
    } else if (!f.options.some((o) => o && o.value === f.default)) {
      // 默认值必须是可选项之一，否则运营台会显示一个「选不中」的默认态
      problems.push(
        `${f.key} 的默认值 ${JSON.stringify(f.default)} 不在 options 里（` +
          JSON.stringify(f.options.map((o) => o && o.value)) +
          '）'
      );
    }
  }
  if (f.type === 'BOOLEAN' && !['true', 'false'].includes(f.default)) {
    problems.push(`${f.key} 是 BOOLEAN 但默认值不是 true/false: ${JSON.stringify(f.default)}`);
  }
  if (f.type === 'NUMBER' && !/^-?\d+$/.test(f.default)) {
    problems.push(`${f.key} 是 NUMBER 但默认值不是整数: ${JSON.stringify(f.default)}`);
  }
}

// ── R1：seed → 注册表 ─────────────────────────────────────────────────────
const seeded = new Map();
{
  const re = /upsertIfAbsent\s*\(/g;
  let m;
  while ((m = re.exec(scs)) !== null) {
    const args = extractCallArgs(scs, m.index + m[0].length - 1);
    if (!args || args.length < 2) continue;
    const key = resolveExpr(args[0], null);
    if (!key || !key.includes('.')) continue;
    seeded.set(key, resolveExpr(args[1], null) ?? args[1].trim());
  }
}
if (seeded.size < 40) {
  fail(`从 ensureDefaults() 只解析出 ${seeded.size} 个 seed（下限 40）—— 解析锚点可能已被重写`);
}
const missingInRegistry = [...seeded.keys()].filter((k) => !seenKeys.has(k));
if (missingInRegistry.length) {
  problems.push(
    `R1 seed 了但注册表里没有（运营台看不到这张表就是假清单）：\n    - ` +
      missingInRegistry.join('\n    - ')
  );
}

// ── R5：注册表 → seed ─────────────────────────────────────────────────────
const notSeeded = flags.filter((f) => !seeded.has(f.key)).map((f) => f.key);
if (notSeeded.length) {
  problems.push(
    `R5 注册表里有但没 seed（默认值不存在，新环境开关会「无值」）：\n    - ` +
      notSeeded.join('\n    - ')
  );
}
for (const f of flags) {
  if (!seeded.has(f.key)) continue;
  if (seeded.get(f.key) !== f.default) {
    problems.push(
      `R5 ${f.key} 的默认值与 seed 不一致：注册表=${JSON.stringify(f.default)} seed=${JSON.stringify(
        seeded.get(f.key)
      )}`
    );
  }
}

// ── R2：注册表 → 有消费者 ─────────────────────────────────────────────────
// 豁免锚点：注明「这个键为什么可以没有消费者」的代码注释必须真实存在。
const DEPRECATED_ANCHORS = new Map([
  [
    'ops.log_retention.points_months',
    {
      file: 'services/trade-service/src/main/java/com/aicabinet/trade/service/GrowthLogArchiveScheduler.java',
      anchor: '积分流水为账本组成部分，不在此删除',
      why: '积分流水是账本组成部分，禁止 DELETE，故该保留月数开关刻意不接消费者'
    }
  ]
]);

// 反向核对：注册表里标 deprecated 的条目必须都在锚点表内（防止「随手标 deprecated 绕过 R2」）
const deprecatedInRegistry = flags.filter((f) => f.deprecated).map((f) => f.key);
for (const key of deprecatedInRegistry) {
  if (!DEPRECATED_ANCHORS.has(key)) {
    problems.push(`R2 ${key} 标了 deprecated 但门禁里没有对应的可验证锚点，豁免不成立`);
  }
}
for (const [key, spec] of DEPRECATED_ANCHORS) {
  const flag = flags.find((f) => f.key === key);
  if (!flag) {
    problems.push(`R2 锚点表里的 ${key} 已不在注册表，请同步清理`);
    continue;
  }
  if (!flag.deprecated || !flag.deprecatedNote) {
    problems.push(`R2 ${key} 在锚点表里被豁免，但注册表未标 deprecated / 缺 deprecatedNote`);
  }
  const anchorPath = join(root, spec.file);
  if (!existsSync(anchorPath)) {
    problems.push(`R2 ${key} 的锚点文件不存在: ${spec.file}`);
  } else if (!readFileSync(anchorPath, 'utf8').includes(spec.anchor)) {
    problems.push(
      `R2 ${key} 的豁免锚点已消失（${spec.file} 里找不到「${spec.anchor}」）—— 豁免退化成假绿，请复核`
    );
  }
}

const scsRel = SCS_REL;
const otherJava = mainJava.filter((p) => p.rel !== scsRel).map((p) => readFileSync(p.abs, 'utf8'));
if (otherJava.length === 0) fail('除 SystemConfigService.java 外没有其它 main java，R2 无法判定');

// SystemConfigService 自身也是合法消费者（如 consumerPublicConfig() / usesGravityFusion()）。
// 但必须先把「常量声明」和「seed 的 upsert/refresh 调用」剥掉 —— 否则「定义了自己」会被误读成「有人用它」，
// 那正是本门禁要抓的恒真形态。
const scsConsumerText = scs
  .replace(/public static final String [A-Z0-9_]+\s*=\s*"(?:[^"\\]|\\.)*"\s*;/g, '')
  .replace(/upsertIfAbsent\s*\([\s\S]*?\);/g, '')
  .replace(/refreshDescriptionIfPresent\s*\([\s\S]*?\);/g, '');

const consumerless = [];
for (const f of flags) {
  if (f.deprecated) continue;
  const asLiteral = `"${f.key}"`;
  const names = [...constMap.entries()].filter(([, v]) => v === f.key).map(([n]) => n);
  const qualifiers = names.map((n) => `SystemConfigService.${n}`);
  const hit =
    otherJava.some((t) => t.includes(asLiteral)) ||
    (qualifiers.length > 0 && otherJava.some((t) => qualifiers.some((q) => t.includes(q)))) ||
    names.some((n) => new RegExp(`\\b${n}\\b`).test(scsConsumerText));
  if (!hit) consumerless.push(f.key);
}
if (consumerless.length) {
  problems.push(
    `R2 注册表里有键但源码里找不到消费者（能关却关了个空气 = 死开关；` +
      `若确为刻意废弃，请在注册表标 deprecated 并在门禁 DEPRECATED_ANCHORS 里写明锚点）：\n    - ` +
      consumerless.join('\n    - ')
  );
}

// ── R3 / R4：读取点 ───────────────────────────────────────────────────────
const READ =
  /\b(systemConfigService|self)\s*\.\s*(getValue|getInt|getBoolean|getDouble|getLong)\s*\(/g;
const readKeys = new Set();
const dynamicByFile = new Map();
let readSites = 0;

for (const { abs, rel } of mainJava) {
  const text = readFileSync(abs, 'utf8');
  const localMap = new Map();
  for (const m of text.matchAll(
    /(?:public|private|protected)?\s*static final String ([A-Z0-9_]+)\s*=\s*([^;]+);/g
  )) {
    localMap.set(m[1], m[2].trim());
  }
  READ.lastIndex = 0;
  let m;
  while ((m = READ.exec(text)) !== null) {
    readSites++;
    // SystemConfigService 自身是转发层：getInt/getBoolean/getDouble 里的 `self.getValue(key, …)`
    // 的 key 来自调用方，不在注册表直接覆盖范围；但仍按 R4 登记计数。
    const args = extractCallArgs(text, m.index + m[0].length - 1);
    const key = args && args.length >= 1 ? resolveExpr(args[0], localMap) : null;
    if (!key || !key.includes('.')) {
      if (!dynamicByFile.has(rel)) dynamicByFile.set(rel, []);
      dynamicByFile.get(rel).push((args?.[0] ?? '?').replace(/\s+/g, ' '));
      continue;
    }
    readKeys.add(key);
  }
}

if (readSites < 40) {
  fail(`只扫到 ${readSites} 个读取调用点（下限 40）—— 接收者锚点可能已被重写，判定失效`);
}

const unregisteredReads = [...readKeys].filter((k) => !seenKeys.has(k));
if (unregisteredReads.length) {
  problems.push(
    `R3 源码里读了但注册表里没有（运营台看不到 = 假可配置）：\n    - ` +
      unregisteredReads.join('\n    - ')
  );
}

// 动态键站点：按「文件 → 表达式多重集」精确比对（刻意不用行号）
const EXPECTED_DYNAMIC_SITES = new Map([
  [
    'services/trade-service/src/main/java/com/aicabinet/trade/service/OpsAlertDispatcher.java',
    {
      exprs: ['channel.configKey()', 'channel.configKey()', 'configKey'],
      why: '渠道键来自 CHANNELS 里的 SystemConfigService.OPS_ALERT_* 常量，均已登记在册'
    }
  ],
  [
    scsRel,
    {
      exprs: ['key', 'key', 'key'],
      why: '配置服务自身的 getInt/getBoolean/getDouble 转发 self.getValue(key, …)，key 由调用方传入'
    }
  ]
]);

const norm = (arr) => [...arr].sort();
for (const [file, spec] of EXPECTED_DYNAMIC_SITES) {
  const actual = dynamicByFile.get(file);
  if (!actual) {
    problems.push(`R4 登记的动态键站点 ${file} 已不存在（请同步清理门禁登记）`);
    continue;
  }
  if (norm(actual).join('|') !== norm(spec.exprs).join('|')) {
    problems.push(
      `R4 ${file} 的动态键站点变了：\n      实际=${JSON.stringify(norm(actual))}\n      登记=${JSON.stringify(
        norm(spec.exprs)
      )}\n      （${spec.why}）`
    );
  }
}
for (const file of dynamicByFile.keys()) {
  if (!EXPECTED_DYNAMIC_SITES.has(file)) {
    problems.push(
      `R4 出现未登记的动态键站点 ${file} :: ${JSON.stringify(dynamicByFile.get(file))}\n` +
        `      请确认其键来源已全部登记在注册表，然后把它加入 EXPECTED_DYNAMIC_SITES`
    );
  }
}

if (problems.length) {
  fail(`\n  ${problems.join('\n  ')}\n`);
}

console.log(
  `${TAG} OK：注册表 ${flags.length} 个开关 / ${groups.length} 个分组，seed ${seeded.size} 个键全部登记，` +
    `读取点 ${readSites} 处（可解析键 ${readKeys.size} 个）全部登记，` +
    `${EXPECTED_DYNAMIC_SITES.size} 个文件的动态键站点已登记，` +
    `${DEPRECATED_ANCHORS.size} 个废弃豁免的代码锚点均存在`
);
