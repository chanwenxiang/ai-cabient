#!/usr/bin/env node
/**
 * 权限矩阵门禁：控制器的 `@RequiresPermissions` 码集必须能过服务层路径上的**每一道**校验。
 *
 * 判据方向（为什么只拦这一个方向）：
 *   切面是 OR（注解里任一码即放行），服务层是 AND（路径上每道 require* 都得满足）。
 *   ⇒ 若注解里存在某个码 X，而路径上某道校验不接受 X，则「只持有 X」的人切面放行、服务层 403：
 *     前端按钮可见（导航按 X 判权）但点下去必失败 = MA-070/105/149/111 同族的「幽灵按钮」。
 *   反方向（注解比服务更窄）是**刻意双门**的形态，不属于本门禁的缺陷类，只在矩阵里记 info。
 *
 * 修法只有两种，且必须二选一，不许沉默：
 *   A) 从注解里删掉那个幽灵码 —— 可证的行为等价：该码持有人本来就会被服务层拒，
 *      删掉后拒绝点从服务层前移到切面，端点的可达集合不变（前提：删完还剩 ≥1 个码，
 *      且剩下的码都能过全部校验）。
 *   B) 若删完就空了（注解只有一个幽灵码 = 刻意双门），或该端点确实要「两码都得有」，
 *      就登记进 scripts/permission-matrix-exemptions.json 并写清理由。
 *
 * 解析退化保护：注解总数 / 方法级绑定数 / 两侧都有的端点数 都有下限锚，
 * 解析器一旦改坏（绑错方法、正则吞行）会先把门禁弄红，而不是静默假绿。
 */
import { existsSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';

const TAG = '[check-permission-matrix]';
const ROOT = join(import.meta.dirname, '..');
// 可测性：合成树跑负向用例时用 env 换根（见 check-permission-matrix.test.mjs 的沙箱纪律）
const TRADE = process.env.PERM_MATRIX_ROOT
  ? resolve(process.env.PERM_MATRIX_ROOT)
  : join(ROOT, 'services', 'trade-service', 'src', 'main', 'java', 'com', 'aicabinet', 'trade');
const EXEMPT = process.env.PERM_MATRIX_EXEMPTIONS
  ? resolve(process.env.PERM_MATRIX_EXEMPTIONS)
  : join(ROOT, 'scripts', 'permission-matrix-exemptions.json');
const DUMP = process.argv.includes('--dump-matrix');
const JSON_OUT = process.argv.includes('--json');

// 锚点：2026-11-03 实测（629 注解 / 598 方法级绑定 / 196 两侧都有校验）。
// 取「实测值的一半」做下限——解析器退化到只认一半时立刻红，而不是悄悄少查一半。
// 🔴 bothSides 的锚曾经虚高过：CRLF 文件里按 `lines[k].length + 1` 累加行偏移会整体前移，
//    方法体切片落到错误位置 ⇒ 服务层校验读丢（196 vs 119）。见教训 #399。
const MIN_ANNOTATIONS = Number(process.env.PERM_MIN_ANNOTATIONS ?? 300);
const MIN_BOUND_METHODS = Number(process.env.PERM_MIN_BOUND ?? 280);
const MIN_BOTH_SIDES = Number(process.env.PERM_MIN_BOTH ?? 100);

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (name.endsWith('.java')) out.push(p);
  }
  return out;
}

function matchBrace(src, openIdx) {
  let depth = 0;
  for (let i = openIdx; i < src.length; i++) {
    const c = src[i];
    if (c === '{') depth++;
    else if (c === '}') {
      depth--;
      if (depth === 0) return i;
    }
  }
  return -1;
}

/** 只取字符串里的权限码 + 同文件常量引用（常量→值）。 */
function codesOf(text, consts) {
  const out = new Set();
  for (const m of text.matchAll(/"((?:merchant|ops):[^"]*)"/g)) out.add(m[1]);
  for (const m of text.matchAll(/\b([A-Z][A-Z0-9_]{3,})\b/g)) {
    const v = consts[m[1]];
    if (v && /^(merchant|ops):/.test(v)) out.add(v);
  }
  return out;
}

function constantsOf(src) {
  const consts = {};
  for (const m of src.matchAll(/static\s+final\s+String\s+([A-Z][A-Z0-9_]*)\s*=\s*"([^"]*)"/g)) {
    consts[m[1]] = m[2];
  }
  for (const m of src.matchAll(
    /static\s+final\s+String\s+([A-Z][A-Z0-9_]*)\s*=\s*([A-Z][A-Z0-9_]*)\s*;/g
  )) {
    if (consts[m[2]]) consts[m[1]] = consts[m[2]];
  }
  return consts;
}

/** 剥掉括号内的内容，避免 `@Operation(summary = "...")` 之类把注解块截断。 */
function annotationLines(src, methodStartLine) {
  const lines = src.split(/\r?\n/);
  const out = [];
  for (let i = methodStartLine - 1; i >= 0; i--) {
    const t = lines[i].trim();
    if (t === '' || t.startsWith('*') || t.startsWith('/*') || t.startsWith('//')) continue;
    if (t.startsWith('@')) {
      out.unshift(t);
      // 注解可能跨行：若括号不配平，继续往上吞
      const open = (t.match(/\(/g) || []).length;
      const close = (t.match(/\)/g) || []).length;
      if (open !== close) {
        // 找到该注解的起始行（以 @ 开头且与已收集部分配平）
        let depth = close - open;
        let j = i - 1;
        const extra = [];
        for (; j >= 0; j--) {
          const e = lines[j].trim();
          extra.unshift(e);
          depth += (e.match(/\)/g) || []).length - (e.match(/\(/g) || []).length;
          if (depth === 0 && e.startsWith('@')) break;
        }
        if (depth !== 0) break;
        out.unshift(...extra);
        i = j + 1;
      }
      continue;
    }
    break;
  }
  return out;
}

/**
 * 行首偏移表：文件可能是 CRLF，用 `lines[k].length + 1` 累加会整体前移 N 个字符，
 * 于是方法体切片落到错误位置 ⇒ 服务层校验被读成别的码（本门禁 2026-11-03 首版就这么造过假阳性）。
 */
function lineStartsOf(src) {
  const starts = [0];
  for (let i = 0; i < src.length; i++) {
    if (src[i] === '\n') starts.push(i + 1);
    else if (src[i] === '\r') {
      if (src[i + 1] === '\n') {
        starts.push(i + 2);
        i++;
      } else starts.push(i + 1);
    }
  }
  return starts;
}

/** 取第 lineIdx（0 基）行的方法体（含首尾花括号）；签名跨行也能拿对。 */
function methodBody(src, starts, lines, lineIdx) {
  const from = starts[lineIdx];
  if (from == null) return null;
  // 先配平参数表括号，再取第一个 `{`
  let i = from;
  let depth = 0;
  let seenParen = false;
  for (; i < src.length; i++) {
    const c = src[i];
    if (c === '(') {
      depth++;
      seenParen = true;
    } else if (c === ')') {
      depth--;
      if (depth === 0 && seenParen) {
        i++;
        break;
      }
    } else if (c === ';' || c === '{') break;
  }
  if (depth !== 0 || !seenParen) return null;
  const braceIdx = src.indexOf('{', i);
  if (braceIdx < 0) return null;
  // 跳过 `throws X;` 之类的接口声明（无体）
  const semi = src.indexOf(';', i);
  if (semi >= 0 && semi < braceIdx) return null;
  const end = matchBrace(src, braceIdx);
  return end < 0 ? null : src.slice(braceIdx, end + 1);
}

/** 解析方法上的全部 @RequiresPermissions：返回 [{codes, logical}] 组（多组之间是 AND）。 */
function permGroups(annText, consts) {
  const groups = [];
  for (const m of annText.matchAll(/@RequiresPermissions\s*\(([^)]*)\)/g)) {
    const inner = m[1];
    const codes = [...codesOf(inner, consts)];
    const logical = /Logical\.AND/.test(inner) ? 'AND' : 'OR';
    if (codes.length) groups.push({ codes, logical });
  }
  return groups;
}

/**
 * 切面放行的「最小满足者」集合。
 * 单组 OR ⇒ 每个码各自是一个满足者（这正是幽灵码的来源）；AND ⇒ 全组码一起。
 * 多组做笛卡尔积，超过 64 个满足者就放弃（记 and-mode，不猜）。
 */
function witnesses(groups) {
  let acc = [new Set()];
  for (const g of groups) {
    if (g.logical === 'AND') {
      // AND 组：所有满足者都必须带上整组码
      acc = acc.map((b) => {
        const s = new Set(b);
        for (const c of g.codes) s.add(c);
        return s;
      });
      continue;
    }
    const next = [];
    for (const base of acc) {
      for (const c of g.codes) next.push(new Set([...base, c]));
    }
    acc = next;
    if (acc.length > 64) return null;
  }
  return acc;
}

/**
 * 标出方法体里被 `if / else / for / while / switch` 包住的区间。
 *
 * 为什么必须分分支：`JiangyiShelfService.shelve` 的两道 require 分别包在
 * `if (priceCents != null)` / `if (quantity != null)` 里 = 合法的「按字段分权」，
 * 把它当恒需 AND 会造出假阳性（首版就把 `shelveLearnedProduct` 误判成幽灵按钮）。
 * 块类型按 `{` 前面紧邻的文法判定；`->` 与 try/finally 视为恒执行（保守少判，不制造假红）。
 */
function condRanges(body) {
  const ranges = [];
  const stack = [];
  let i = 0;
  while (i < body.length) {
    const c = body[i];
    if (c === '"' || c === "'") {
      i++;
      while (i < body.length && body[i] !== c) {
        if (body[i] === '\\') i++;
        i++;
      }
      i++;
      continue;
    }
    if (c === '/' && body[i + 1] === '/') {
      while (i < body.length && body[i] !== '\n') i++;
      continue;
    }
    if (c === '/' && body[i + 1] === '*') {
      i += 2;
      while (i < body.length && !(body[i] === '*' && body[i + 1] === '/')) i++;
      i += 2;
      continue;
    }
    if (c === '{') {
      // `if (...) {` 的关键词不在 `{` 紧邻处：先剥尾部空白，若以 `)` 结尾就回找配对 `(`，
      // 再看它前一个词；`else {` 直接命中。
      const head = body.slice(0, i).replace(/\s+$/, '');
      let cond = /(?:^|[^\w.])else$/.test(head);
      if (!cond && head.endsWith(')')) {
        let d = 0;
        let j = head.length - 1;
        for (; j >= 0; j--) {
          if (head[j] === ')') d++;
          else if (head[j] === '(') {
            d--;
            if (d === 0) break;
          }
        }
        const kw = body
          .slice(0, j)
          .replace(/\s+$/, '')
          .match(/(\w+)$/);
        cond = !!kw && ['if', 'for', 'while', 'switch'].includes(kw[1]);
      }
      stack.push({ start: i, cond });
      i++;
      continue;
    }
    if (c === '}') {
      const top = stack.pop();
      if (top && top.cond) ranges.push([top.start, i]);
      i++;
      continue;
    }
    i++;
  }
  return ranges;
}

function isConditional(ranges, pos) {
  return ranges.some(([s, e]) => pos > s && pos < e);
}

const files = walk(TRADE);
const MIN_JAVA_FILES = Number(process.env.PERM_MIN_JAVA_FILES ?? 300);
if (files.length < MIN_JAVA_FILES)
  fail(`只扫到 ${files.length} 个 .java（锚点 ${MIN_JAVA_FILES}）⇒ 目录或解析退化`);
const byClass = new Map();
for (const f of files)
  byClass.set(
    f
      .split(/[\\/]/)
      .pop()
      .replace(/\.java$/, ''),
    f
  );

const totalAnnotations = files.reduce(
  (n, f) => n + (readFileSync(f, 'utf8').match(/@RequiresPermissions\b/g) || []).length,
  0
);

/** 服务类 → 方法名 → 该方法的每道校验（码集数组） */
const svcCache = new Map();
function serviceChecks(className) {
  if (svcCache.has(className)) return svcCache.get(className);
  const f = byClass.get(className);
  if (!f) {
    svcCache.set(className, null);
    return null;
  }
  const src = readFileSync(f, 'utf8');
  const consts = constantsOf(src);
  const lines = src.split(/\r?\n/);
  const starts = lineStartsOf(src);
  const map = new Map();
  for (let i = 0; i < lines.length; i++) {
    const t = lines[i].trim();
    const sig = t.match(/^(?:public|protected)\s+[\w<>\[\],.?\s]+\s+(\w+)\s*\(/);
    if (!sig || t.startsWith('@')) continue;
    const body = methodBody(src, starts, lines, i);
    if (!body) continue;
    const ranges = condRanges(body);
    const calls = [];
    for (const m of body.matchAll(/require(?:Any)?Permission\s*\(([^;]*?)\)/g)) {
      const set = codesOf(m[1], consts);
      if (set.size) calls.push({ set, conditional: isConditional(ranges, m.index) });
    }
    if (calls.length) {
      const prev = map.get(sig[1]) || [];
      map.set(sig[1], [...prev, ...calls]);
    }
  }
  svcCache.set(className, map);
  return map;
}

const rows = [];
let boundMethods = 0;
let bothSides = 0;

for (const f of files.filter((p) => /[\\/]api[\\/]/.test(p)).sort()) {
  const src = readFileSync(f, 'utf8');
  const consts = constantsOf(src);
  const lines = src.split(/\r?\n/);
  const starts = lineStartsOf(src);
  const fields = {};
  for (const m of src.matchAll(/private\s+final\s+(\w*(?:Service|Api|Manager))\s+(\w+)\s*;/g)) {
    fields[m[2]] = m[1];
  }
  for (let i = 0; i < lines.length; i++) {
    const t = lines[i].trim();
    const sig = t.match(/^(?:public|protected)\s+[\w<>\[\],.?\s]+\s+(\w+)\s*\(/);
    if (!sig || t.startsWith('@')) continue;
    const ann = annotationLines(src, i).join(' ');
    if (!/@RequiresPermissions\b/.test(ann)) continue;
    boundMethods++;
    const groups = permGroups(ann, consts);
    const declared = new Set(groups.flatMap((g) => g.codes));
    const body = methodBody(src, starts, lines, i);
    if (!body) continue;
    const ctrlRanges = condRanges(body);
    const checks = [];
    for (const m of body.matchAll(/\b(\w+)\.(\w+)\s*\(/g)) {
      const type = fields[m[1]];
      if (!type) continue;
      const map = serviceChecks(type);
      if (!map) continue;
      const list = map.get(m[2]);
      if (!list) continue;
      const callSiteBranch = isConditional(ctrlRanges, m.index);
      for (const c of list) {
        checks.push({
          via: `${type}.${m[2]}`,
          set: c.set,
          conditional: callSiteBranch || c.conditional
        });
      }
    }
    for (const m of body.matchAll(/require(?:Any)?Permission\s*\(([^;]*?)\)/g)) {
      const set = codesOf(m[1], consts);
      if (set.size)
        checks.push({ via: '(控制器自身)', set, conditional: isConditional(ctrlRanges, m.index) });
    }
    const ep = `${relative(ROOT, f).replace(/\\/g, '/')}#${sig[1]}`;
    const hardChecks = checks.filter((c) => !c.conditional);
    const branchChecks = checks.filter((c) => c.conditional);
    if (!checks.length) {
      rows.push({ ep, declared: [...declared].sort(), checks: [], kind: 'no-service-check' });
      continue;
    }
    bothSides++;
    const ws = witnesses(groups);
    const bad = [];
    if (ws) {
      for (const w of ws) {
        const failing = hardChecks
          .filter((c) => ![...w].some((code) => c.set.has(code)))
          .map((c) => ({ via: c.via, accepts: [...c.set].sort() }));
        if (failing.length) bad.push({ witness: [...w].sort(), failing });
      }
    }
    rows.push({
      ep,
      declared: [...declared].sort(),
      groups: groups.map((g) => ({ codes: [...g.codes].sort(), logical: g.logical })),
      checks: hardChecks.map((c) => ({ via: c.via, accepts: [...c.set].sort() })),
      branchChecks: branchChecks.map((c) => ({ via: c.via, accepts: [...c.set].sort() })),
      bad,
      witnesses: ws ? ws.map((w) => [...w].sort()) : null,
      kind: ws ? (bad.length ? 'ghost-code' : 'ok') : 'too-many-groups'
    });
  }
}

const ghosts = rows.filter((r) => r.kind === 'ghost-code');
const exemptRaw = existsSync(EXEMPT) ? JSON.parse(readFileSync(EXEMPT, 'utf8')) : [];
if (!Array.isArray(exemptRaw)) fail(`${EXEMPT} 必须是数组`);
// 🔴 存量豁免只许减不许加（批次 Q 已把 12 条降到 1 条 = 拍板保留的双门）：新增幽灵码必须当场修（收窄注解=可证等价；动服务层=需拍板），
//    不许「登记一条豁免」了事。上限见 docs/module-audit/ISSUES.md 的权限矩阵专项。
const MAX_EXEMPTIONS = Number(process.env.PERM_MAX_EXEMPTIONS ?? 1);
if (exemptRaw.length > MAX_EXEMPTIONS) {
  fail(
    `豁免清单 ${exemptRaw.length} 条 > 上限 ${MAX_EXEMPTIONS} ⇒ 幽灵按钮只能修不能登记；` +
      `确需扩清单请连同用户拍板一起改本行，并在台账写清理由`
  );
}
for (const e of exemptRaw) {
  if (!e || typeof e !== 'object') fail(`${EXEMPT} 每项必须是对象`);
  for (const k of ['ep', 'family', 'reason', 'ask']) {
    if (!String(e[k] || '').trim())
      fail(`${EXEMPT} 条目 ${e.ep || '?'} 缺 ${k}（豁免必须写清「为什么现在是红的、要谁拍什么」）`);
  }
}
const exempt = new Map(exemptRaw.map((e) => [e.ep, e]));
const exemptSeen = new Set();
const problems = [];
for (const g of ghosts) {
  if (exempt.has(g.ep)) {
    exemptSeen.add(g.ep);
    continue;
  }
  const single = g.bad.filter((b) => b.witness.length === 1).map((b) => b.witness[0]);
  const survivors = g.declared.filter((c) => !single.includes(c));
  problems.push(
    `${g.ep}\n    注解: ${g.groups.map((x) => `${x.codes.join('|')}(${x.logical})`).join(' + ')}` +
      `\n    校验: ${g.checks.map((c) => `${c.via}={${c.accepts.join(' ')}}`).join(' , ')}` +
      `\n    放得进切面、过不了服务层的满足者: ${g.bad.map((b) => `{${b.witness.join(' ')}}→${b.failing.map((f) => f.via).join(',')}`).join(' | ')}` +
      `\n    删幽灵码后注解还剩: ${survivors.length ? survivors.join(' ') : '（空 ⇒ 只能登记豁免；删到空=把双门拆成单门=扩权）'}`
  );
}
const staleExemptions = [...exempt.keys()].filter((k) => !exemptSeen.has(k));
if (staleExemptions.length) {
  problems.push(
    `豁免清单腐烂（这些端点已不再冲突，请从 ${relative(ROOT, EXEMPT).replace(/\\/g, '/')} 删除）：\n    ` +
      staleExemptions.join('\n    ')
  );
}

if (totalAnnotations < MIN_ANNOTATIONS)
  fail(`@RequiresPermissions 总数 ${totalAnnotations} < 锚点 ${MIN_ANNOTATIONS} ⇒ 扫描退化`);
if (boundMethods < MIN_BOUND_METHODS)
  fail(`注解绑定到方法的数量 ${boundMethods} < 锚点 ${MIN_BOUND_METHODS} ⇒ 注解/签名绑定退化`);
if (bothSides < MIN_BOTH_SIDES)
  fail(`两侧都有校验的端点 ${bothSides} < 锚点 ${MIN_BOTH_SIDES} ⇒ 服务层解析退化`);

if (JSON_OUT) {
  console.log(
    JSON.stringify({
      totalAnnotations,
      boundMethods,
      bothSides,
      ghostCount: ghosts.length,
      problems: problems.length
    })
  );
} else if (DUMP) {
  writeFileSync(join(ROOT, '.tmp', 'permission-matrix.json'), JSON.stringify(rows, null, 2));
  console.log(
    `${TAG} 矩阵已写 .tmp/permission-matrix.json：${rows.length} 行（幽灵 ${ghosts.length}）`
  );
}

if (problems.length) {
  console.error(
    `${TAG} FAIL：${problems.length} 条「切面放行、服务层必 403」的端点（幽灵按钮族，MA-070/105/149 同族）\n\n` +
      problems.join('\n\n')
  );
  process.exit(1);
}
console.log(
  `${TAG} OK：注解 ${totalAnnotations} 处 / 方法级绑定 ${boundMethods} / 两侧都有校验 ${bothSides}，` +
    `幽灵码端点 ${ghosts.length}（豁免 ${exemptSeen.size}），无清单腐烂`
);
