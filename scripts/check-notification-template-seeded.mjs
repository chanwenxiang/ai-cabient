#!/usr/bin/env node
/**
 * 通知模板种库对账门禁（R18-3，四端审计 2026-11-12）。
 *
 * 判据：NotificationService 渲染站内信按 templateCode 查 notification_template，
 * 模板不存在 ⇒ log.debug 后静默 return——兜账承诺的「消费者触达」实际为 0，且无告警
 * （dispute_created 正是缺种 2 个月无人发现的实证）。本门禁把两侧对账：
 * ① 抽取全部 notifyConsumer/notifyMerchant/send 站点的 templateCode 实参
 *   （字符串字面量直取；标识符查同文件 local const 与全仓 static final String）；
 * ② 解析全部迁移 SQL 里 INSERT INTO notification_template 的元组首字段（template_code）；
 * ③ 代码引用 − 已种 ⇒ 差集必须为空。
 *
 * 动态站点（实参是变量/方法返回值）机器无法静态求值 ⇒ 记数并钉在 DYNAMIC_SITES 白名单，
 * 新增动态站点会红，逼人来登记它的取值集合。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const errors = [];

function fail(msg) {
  errors.push(msg);
}

function stripComments(src) {
  return src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/.*$/gm, '$1');
}

function walk(dir, ext, acc = []) {
  if (!fs.existsSync(dir)) return acc;
  for (const ent of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, ent.name);
    if (ent.isDirectory()) {
      if (ent.name === 'node_modules' || ent.name === 'target') continue;
      walk(p, ext, acc);
    } else if (ent.name.endsWith(ext)) acc.push(p);
  }
  return acc;
}

// ---------- ① 代码侧：notify*(…, "code", …) / send(…, "code", …) ----------

const javaRoots = [
  'services/trade-service/src/main/java',
  'services/user-service/src/main/java',
  'services/payment-service/src/main/java',
  'services/order-service/src/main/java',
  'services/device-service/src/main/java',
  'common'
];
const javaFiles = [];
for (const rel of javaRoots) {
  const abs = path.join(root, rel);
  if (fs.existsSync(abs)) walk(abs, '.java', javaFiles);
}

const globalConsts = new Map();
const bodies = new Map();
for (const f of javaFiles) {
  const src = stripComments(fs.readFileSync(f, 'utf8'));
  bodies.set(f, src);
  for (const m of src.matchAll(/\bstatic\s+final\s+String\s+([A-Z][A-Z0-9_]*)\s*=\s*"([^"]+)"/g)) {
    if (!globalConsts.has(m[1])) globalConsts.set(m[1], m[2]);
  }
}

function resolveCode(token, file) {
  const lit = /^"([^"]+)"$/.exec(token.trim());
  if (lit) return lit[1];
  const name = token.trim();
  if (!/^[A-Z][A-Z0-9_]*$/.test(name)) return null;
  const local = new RegExp(`\\bString\\s+${name}\\s*=\\s*"([^"]+)"`).exec(bodies.get(file) ?? '');
  if (local) return local[1];
  return globalConsts.get(name) ?? null;
}

/** 取调用实参第 n 个（1 起）：浅括号计数切参，容忍嵌套 Map.of(...)。 */
function nthArg(src, openIdx, n) {
  let depth = 0;
  let argIdx = 1;
  let start = -1;
  for (let i = openIdx; i < src.length; i++) {
    const c = src[i];
    if (c === '(') {
      depth++;
      if (depth === 1) start = i + 1;
    } else if (c === ')') {
      depth--;
      if (depth === 0 && argIdx === n) return src.slice(start, i).trim();
    } else if (c === ',' && depth === 1) {
      if (argIdx === n) return src.slice(start, i).trim();
      argIdx++;
      start = i + 1;
    }
  }
  return null;
}

const sites = []; // { code, where }
const dynamicSites = [];
// 动态站点白名单：实参无法静态求值的调用点，人工登记取值集合（新增必登记）。
const DYNAMIC_SITE_SETS = {};
for (const f of javaFiles) {
  const src = bodies.get(f);
  const rel = path.relative(root, f);
  const collect = (callRe, argN, label) => {
    for (const m of src.matchAll(callRe)) {
      const arg = nthArg(src, m.index + m[0].length - 1, argN);
      if (arg == null) continue;
      const code = resolveCode(arg, f);
      if (code == null) {
        if (!DYNAMIC_SITE_SETS[rel]) dynamicSites.push(`${rel}: ${label} 第 ${argN} 参未解析 = ${arg}`);
        else for (const c of DYNAMIC_SITE_SETS[rel]) sites.push({ code: c, where: `${rel}（动态集合）` });
      } else {
        sites.push({ code, where: rel });
      }
    }
  };
  collect(/\bnotificationService\.notifyConsumer\(/g, 2, 'notifyConsumer');
  collect(/\bnotificationService\.notifyMerchant\(/g, 2, 'notifyMerchant');
  collect(/\bnotificationService\.send\(/g, 4, 'send');
}

// ---------- ② 迁移侧：INSERT INTO notification_template 元组首字段 ----------

const migDir = 'services/trade-service/src/main/resources/db/migration';
const seeded = new Set();
const seedWhere = new Map();
for (const f of walk(path.join(root, migDir), '.sql')) {
  const src = fs.readFileSync(f, 'utf8');
  for (const m of src.matchAll(/INSERT\s+INTO\s+notification_template\b([\s\S]*?);/gi)) {
    const stmt = m[1];
    const vIdx = stmt.toUpperCase().lastIndexOf('VALUES');
    if (vIdx < 0) continue;
    const tuples = stmt.slice(vIdx + 6);
    // 按顶层括号拆元组；每元组第一个带引号 token 即 template_code
    let depth = 0;
    let cur = '';
    for (const c of tuples) {
      if (c === '(') {
        depth++;
        cur = '';
      } else if (c === ')') {
        depth--;
        const t = /'([a-z0-9_]+)'/i.exec(cur);
        if (t && depth === 0) {
          seeded.add(t[1].toLowerCase());
          if (!seedWhere.has(t[1].toLowerCase())) seedWhere.set(t[1].toLowerCase(), path.relative(root, f));
        }
      } else if (depth > 0) {
        cur += c;
      }
    }
  }
}
if (seeded.size === 0) fail('迁移里解析不到任何 notification_template 种子（写法变了，请同步本门禁）');

// ---------- ③ 对账 ----------

const missing = [...new Set(sites.filter((s) => !seeded.has(s.code)).map((s) => s.code))];
for (const code of missing) {
  const where = sites.filter((s) => s.code === code).map((s) => s.where);
  fail(
    `代码引用但 notification_template 未种库：${code}（${where.join(' / ')}）⇒ ` +
      `NotificationService 模板未命中仅 debug 日志静默丢弃，消费者触达为 0（R18-3 同型）。` +
      `请新增迁移 INSERT 该模板后再提交`
  );
}

if (dynamicSites.length > 0) {
  fail(
    '未登记的动态 templateCode 站点（实参机器求不出值）——' +
      '请把它的取值集合加进 DYNAMIC_SITE_SETS 再提交：\n' +
      dynamicSites.map((s) => `  - ${s}`).join('\n')
  );
}

if (errors.length) {
  console.error('check-notification-template-seeded: FAIL');
  for (const e of errors) console.error(`  ✗ ${e}`);
  process.exit(1);
}
console.log(
  `check-notification-template-seeded: PASS（代码引用 ${new Set(sites.map((s) => s.code)).size} 个模板码 ⊆ ` +
    `迁移已种 ${seeded.size} 个；动态站点全部已登记）`
);
