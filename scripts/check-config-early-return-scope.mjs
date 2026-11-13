#!/usr/bin/env node
/**
 * 门禁：配置开关只许关它自己的行为——数字零值配置早退不得连带关闭「独立的副作用腿」（S6）。
 *
 * 缺陷本体（R22-6，2026-11-12 修，23c57e09）：
 *   UnpaidOrderService.autoCancelExpired 原来 `if (hours <= 0) return 0;` 之后**再**跑
 *   alertStaleChargePendingOps()——字典把 auto_cancel_hours=0 定义为「关闭自动关单」，
 *   但 CHARGE_PENDING（渠道扣款结果未知）的唯一自动巡检出口搭了这班车一起被关。
 *   修法形态（正样本，本门禁据此钉绿）：独立腿挪到早退**之前**（先巡检、后短路关单腿）。
 *
 * 判据（方法体级，fail-closed）：
 *   方法体内出现「数字零值早退」（if 条件含 `<= 0` 且分支里有 return），且早退变量
 *   来自配置读取（`.get*(` 接 config 接收者 / CONFIG_KEY 常量），且早退**之后**的代码
 *   还有 alert/notify/report/dispatch/resolve/createOps/createTicket 类副作用调用 ⇒ 红。
 *   - 布尔开关整体门（`if (!enabled) return; 干活`）不命中：那是「0=关」的合法形态；
 *   - `send*` 刻意不进副作用词表：`if (limit<=0) return; sendX()` 多为合法整体门，
 *     误报面大（本档 §八 Q3）——要扩词表先补正反夹具；
 *   - 命中项必须逐条入 allowlist（带 why）或对齐 R22-6 修法（挪早退前）；
 *     allowlist 里「对应命中已消失」的陈旧条目同样红（双向棘轮）。
 *
 * 坑位（铁律 87，实现按此写并用 .test.mjs 钉住）：
 *   ① 方法体抽取必须排除控制关键字（if/for/while/switch/catch/synchronized/try）与
 *      `new`（匿名类体）、lambda（`-> {`）——否则控制块/调用点会被当成方法体；
 *   ② 注释与字符串先剥掉：注释里贴反例形态不得假红。
 *
 *   node scripts/check-config-early-return-scope.mjs
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-config-early-return-scope]';
const ALLOWLIST_PATH = join('scripts', 'config-early-return-allowlist.json');

// 副作用词表（v1）：R22-6 家族的「独立行为腿」。send* 有意排除（见头注）。
export const SIDE_EFFECT_RE =
  /\b(?:alert|notify|report|dispatch|resolve|createOps|createTicket|record\w*(?:Alert|Exception|Ops))\w*\s*\(/g;

const CTRL_KEYWORDS = new Set([
  'if',
  'for',
  'while',
  'switch',
  'catch',
  'synchronized',
  'try',
  'do',
  'else',
  'return',
  'new',
  'throw',
  'assert',
  'finally'
]);
const CONFIG_KEY = /\b[A-Z][A-Z0-9_]{4,}\b/;
const CONFIG_CALL =
  /\.(get(?:Int|Long|Double|Hours|Minutes|Seconds|Days|Value|String|Property)\w*)\s*\(/;

/** 去注释与字符串字面量：判据只看真代码（与 check-outbound-client-factory 同款实现）。 */
export function stripCommentsAndStrings(src) {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/(^|[^:])\/\/[^\n]*/g, '$1 ')
    .replace(/"(?:[^"\\]|\\.)*"/g, '""');
}

/**
 * 方法体抽取（泛化版）：标识符(参数配平) [throws …] {…}。
 * 排除控制关键字 / `new`（匿名类）/ lambda（`) ->` 或 `) -> {`）/ 调用语句（`)` 后跟 `;`）。
 * 这是铁律 87 「extractBracedBodies 把调用点当方法体」坑的按构造规避版。
 */
export function extractMethodBodies(masked) {
  const bodies = [];
  const re = /([A-Za-z_$][\w$]*)\s*\(/g;
  let m;
  while ((m = re.exec(masked))) {
    const name = m[1];
    if (CTRL_KEYWORDS.has(name)) continue;
    const before = masked.slice(Math.max(0, m.index - 40), m.index).replace(/\s+/g, ' ');
    if (/(?:^|\s)new$/.test(before)) continue;
    let depth = 0;
    let j = m.index + m[0].length - 1;
    let closed = -1;
    for (; j < masked.length; j++) {
      const c = masked[j];
      if (c === '(') depth++;
      else if (c === ')') {
        depth--;
        if (depth === 0) {
          closed = j;
          break;
        }
      } else if (c === ';' || c === '{') break;
    }
    if (closed < 0) continue;
    let k = closed + 1;
    while (k < masked.length && /\s/.test(masked[k])) k++;
    // lambda：`) -> {` / `) -> 表达式`
    if (masked.slice(k, k + 2) === '->') continue;
    // throws 子句：跳到体开括号（限 80 字符内，防误吞）
    const rest = masked.slice(k, k + 80);
    const throwM = rest.match(/^throws\s+[\w.$,\s]+?\{/);
    if (throwM) k += throwM[0].length - 1;
    if (masked[k] !== '{') continue;
    let bd = 0;
    let e = k;
    for (; e < masked.length; e++) {
      if (masked[e] === '{') bd++;
      else if (masked[e] === '}') {
        bd--;
        if (bd === 0) break;
      }
    }
    if (bd !== 0) continue;
    bodies.push({ name, src: masked.slice(k, e + 1) });
    re.lastIndex = e; // 已吞掉整个体，防体内重复抽取
  }
  return bodies;
}

/**
 * 单方法体扫描：数字零值配置早退 + 早退后仍有副作用调用 ⇒ 命中。
 * 返回 [{ guard, call }]，guard 是 if 条件原文（allowlist 的匹配键）。
 */
export function scanMethodBody(body) {
  const hits = [];
  for (const m of body.matchAll(/if\s*\(([^(){};]{1,200}?<=\s*0L?)\s*\)/g)) {
    const cond = m[1].trim();
    const ifStart = m.index;
    let guardEnd = ifStart + m[0].length;
    // 分支体：块（配平）或单句（到分号）
    let bd = 0;
    let e = guardEnd;
    let sawBrace = false;
    for (; e < body.length; e++) {
      const c = body[e];
      if (/\s/.test(c) && !sawBrace) continue;
      if (c === '{') {
        sawBrace = true;
        bd++;
      } else if (c === '}') {
        bd--;
        if (bd === 0) {
          e++;
          break;
        }
      } else if (c === ';' && !sawBrace) {
        e++;
        break;
      } else if (!sawBrace) {
        continue; // `{` 前的空白/换行
      }
    }
    guardEnd = sawBrace || e < body.length ? e : guardEnd;
    // else 链消费：`else {…}` / `else if (…) {…}` / `else 单句` 不算被短路（条件不满足时照跑）
    let cur = guardEnd;
    for (;;) {
      const rest = body.slice(cur);
      const elseM = rest.match(/^\s*else\b/);
      if (!elseM) break;
      cur += elseM[0].length;
      const afterElse = body.slice(cur);
      const elseIf = afterElse.match(/^\s*if\s*\([^(){};]{0,200}?\)/);
      if (elseIf) cur += elseIf[0].length;
      const seg = body.slice(cur);
      if (/^\s*\{/.test(seg)) {
        const bs = cur + seg.match(/^\s*\{/)[0].length - 1;
        let d = 0;
        let q = bs;
        for (; q < body.length; q++) {
          if (body[q] === '{') d++;
          else if (body[q] === '}') {
            d--;
            if (d === 0) {
              q++;
              break;
            }
          }
        }
        cur = q;
      } else {
        const semi = body.indexOf(';', cur);
        cur = semi >= 0 ? semi + 1 : cur;
      }
      guardEnd = cur;
    }
    const guardText = body.slice(ifStart, guardEnd);
    if (!/\breturn\b/.test(guardText)) continue; // 不早退的不在 S6 范围
    // 配置来源：条件里内联配置读取，或守卫变量在方法体内从「config 接收者」读取赋值。
    // 🔴 裸全大写常量不算配置证据——STATUS_IN_PROGRESS 这类业务常量会假阳
    //    （ReplenishmentTimeoutScheduler.expireOne 的 rows<=0 是 CAS 行数判据，实测误报）。
    const varName = (cond.match(/([a-z]\w*)\s*<=\s*0/) || [])[1] || '';
    const inline = CONFIG_CALL.test(cond) && (CONFIG_KEY.test(cond) || /config/i.test(cond));
    const assignRe = new RegExp(
      `\\b${varName}\\b\\s*=\\s*[^;]{0,200}?\\b\\w*(?:[Cc]onfig|Properties|Props|Settings)\\w*\\s*\\.\\s*(?:get|take|resolve)\\w*\\s*\\(`
    );
    const assigned = Boolean(varName) && (inline || assignRe.test(body));
    if (!assigned) continue;
    const tail = body.slice(guardEnd);
    SIDE_EFFECT_RE.lastIndex = 0;
    const side = tail.match(SIDE_EFFECT_RE);
    if (side) hits.push({ guard: cond, call: side[0].trim() });
  }
  return hits;
}

/**
 * 汇总判定（双向棘轮）：命中必须被 allowlist 条目覆盖；allowlist 出现
 * 「已无对应命中」的陈旧条目同样红。匹配键 = file 全等 && 命中 guard 含条目 guard 子串。
 * @param {{file:string, guard:string, call:string}[]} findings
 * @param {{file:string, guard:string, why:string}[]} entries
 */
export function evaluate(findings, entries) {
  const errors = [];
  const matched = new Set();
  for (const f of findings) {
    const i = entries.findIndex((e) => e.file === f.file && f.guard.includes(e.guard));
    if (i >= 0) {
      matched.add(i);
      continue;
    }
    errors.push(
      `${f.file}: 数字零值配置早退「if (${f.guard})」之后仍有副作用调用 ${f.call}` +
        `——早退会把独立行为腿一起短路（R22-6 形态）；挪到早退前，或带理由登记 allowlist`
    );
  }
  entries.forEach((e, i) => {
    if (!matched.has(i)) {
      errors.push(
        `allowlist 陈旧条目：${e.file}「${e.guard}」已无对应命中（双向棘轮：修掉了就删条目）`
      );
    }
  });
  return errors;
}

function walkJava(dir, out = []) {
  if (!existsSync(dir)) return out;
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    const st = statSync(full);
    if (st.isDirectory()) {
      if (name === 'node_modules' || name === 'target' || name === 'static') continue;
      walkJava(full, out);
    } else if (name.endsWith('.java')) out.push(full);
  }
  return out;
}

async function main() {
  const servicesDir = join(ROOT, 'services');
  if (!existsSync(servicesDir)) {
    console.error(`${TAG} FAIL：services/ 不存在（扫描根缺失，不判通过）`);
    process.exit(1);
  }
  const allowlistFile = join(ROOT, ALLOWLIST_PATH);
  if (!existsSync(allowlistFile)) {
    console.error(`${TAG} FAIL：allowlist 缺失 ${ALLOWLIST_PATH}`);
    process.exit(1);
  }
  const registry = JSON.parse(readFileSync(allowlistFile, 'utf8'));
  const entries = registry.entries || [];
  const maxEntries = typeof registry.maxEntries === 'number' ? registry.maxEntries : entries.length;

  const javaFiles = walkJava(servicesDir).filter((p) => /(^|[\\/])src[\\/]main[\\/]/.test(p));
  if (javaFiles.length === 0) {
    console.error(`${TAG} FAIL：没有扫到任何 .java（判据退化会让零命中变假绿）`);
    process.exit(1);
  }

  if (entries.length > maxEntries) {
    console.error(
      `${TAG} FAIL：allowlist 条目 ${entries.length} > 棘轮基线 ${maxEntries}` +
        `（新增豁免必须先消掉一条旧豁免或证明旧条目失效——棘轮只许收紧）`
    );
    process.exit(1);
  }

  const findings = [];
  let methodCount = 0;
  for (const abs of javaFiles) {
    const rel = relative(ROOT, abs).replace(/\\/g, '/');
    const bodies = extractMethodBodies(stripCommentsAndStrings(readFileSync(abs, 'utf8')));
    methodCount += bodies.length;
    for (const b of bodies) {
      for (const h of scanMethodBody(b.src)) findings.push({ file: rel, ...h });
    }
  }
  if (methodCount === 0) {
    console.error(`${TAG} FAIL：方法体抽取数=0（抽取器坏了，零命中是假绿）`);
    process.exit(1);
  }

  const errors = evaluate(findings, entries);
  if (errors.length > 0) {
    for (const e of errors) console.error(`${TAG} FAIL ${e}`);
    console.error(
      `${TAG} 共 ${errors.length} 项违规；修法参照 R22-6（23c57e09）：独立行为腿挪到配置早退之前，` +
        `或带理由登记 ${ALLOWLIST_PATH}`
    );
    process.exit(1);
  }
  console.log(
    `${TAG} OK：扫 ${javaFiles.length} 个 .java / ${methodCount} 个方法体，` +
      `零值配置早退无搭车副作用（allowlist ${entries.length}/${maxEntries}）`
  );
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  main();
}
