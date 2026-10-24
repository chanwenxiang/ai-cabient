#!/usr/bin/env node
/**
 * 定时任务整实体回写门禁：**`*Scheduler` / `*Job` 里禁止对「读出来的实体」整实体 save**
 * （棘轮，只减不增）。
 *
 * 缺陷类（lessons #243，已真实复现）：定时任务把实体 `select` 出来、改几个字段、再
 * `save` / `saveOrUpdate` / `updateById` 整行回写。期间 HTTP 写路径（或另一个分片）已经
 * 改过同一行的其它列 —— 整实体回写会用任务手里的旧快照覆盖掉那些列（丢更新），
 * 且写放大到全列。正确形态是**列级条件更新**：
 *   `update ... set status = ? where id = ? and status = ?`（期望状态一并进 where）。
 *
 * 判据：
 *   1. 扫 `services/**\/src/main/java` 里类名以 `Scheduler` / `Job` 结尾的类；
 *   2. 方法体内的 `xxx.save(arg)` / `insert(arg)` 只有 arg **可证明是新建实体**时才放行
 *      （`new X(...)`，或同方法内最近一次赋值 RHS 是 `new X(...)` / `X.builder()…build()`）；
 *      `saveOrUpdate` / `updateById` / `saveBatch` / `updateBatchById` 一律视为整实体更新；
 *   3. 每处必须命中 `scripts/scheduler-entity-save-allowlist.json` 棘轮清单：
 *      未登记 / 同方法多一处 / 已修未下调 / 条目失效 / 理由为空 都红；
 *   4. 锚点：解析到的 Scheduler/Job 类 < 20 即红（解析器退化会让零命中变成假绿）。
 *
 * 只针对**读改写**：新建日志行（`new MemberPointsLog()` 后 `save`）不是本缺陷类，
 * 不登记、不拦 —— 否则棘轮会装进永远修不掉的假条目。
 *
 *   node scripts/check-scheduler-entity-save.mjs
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-scheduler-entity-save]';
const ALLOWLIST = join('scripts', 'scheduler-entity-save-allowlist.json');
const MIN_SCHEDULER_CLASSES = 20;
const ALWAYS_WHOLE_ENTITY = new Set(['saveOrUpdate', 'updateById', 'saveBatch', 'updateBatchById']);

/** 注释掩成空格（保留换行与列）；字符串原样保留。 */
export function stripJavaComments(source) {
  let out = '';
  let state = 'code';
  for (let i = 0; i < source.length; i++) {
    const c = source[i];
    const n = source[i + 1];
    if (state === 'code') {
      if (c === '/' && n === '/') {
        out += '  ';
        i++;
        state = 'line';
      } else if (c === '/' && n === '*') {
        out += '  ';
        i++;
        state = 'block';
      } else if (c === '"') {
        out += '"';
        state = 'str';
      } else if (c === "'") {
        out += "'";
        state = 'char';
      } else {
        out += c;
      }
      continue;
    }
    if (state === 'line') {
      if (c === '\n') {
        out += '\n';
        state = 'code';
      } else {
        out += ' ';
      }
      continue;
    }
    if (state === 'block') {
      if (c === '*' && n === '/') {
        out += '  ';
        i++;
        state = 'code';
      } else {
        out += c === '\n' ? '\n' : ' ';
      }
      continue;
    }
    if (c === '\\' && i + 1 < source.length) {
      out += c + source[++i];
      continue;
    }
    out += c;
    if ((state === 'str' && c === '"') || (state === 'char' && c === "'")) {
      state = 'code';
    }
  }
  return out;
}

function walkJavaFiles(dir, out = []) {
  for (const entry of readdirSync(dir)) {
    if (entry === 'target' || entry === 'node_modules') continue;
    const full = join(dir, entry);
    const st = statSync(full);
    if (st.isDirectory()) walkJavaFiles(full, out);
    else if (entry.endsWith('.java')) out.push(full);
  }
  return out;
}

function lineOf(src, index) {
  let line = 1;
  for (let i = 0; i < index; i++) if (src[i] === '\n') line++;
  return line;
}

function findMatching(src, open) {
  const pair = src[open] === '(' ? ')' : '}';
  let depth = 0;
  let inStr = false;
  for (let i = open; i < src.length; i++) {
    const c = src[i];
    if (inStr) {
      if (c === '\\') {
        i++;
        continue;
      }
      if (c === '"') inStr = false;
      continue;
    }
    if (c === '"') {
      inStr = true;
      continue;
    }
    if (c === src[open]) depth++;
    else if (c === pair) {
      depth--;
      if (depth === 0) return i;
    }
  }
  return -1;
}

export function parseClass(source) {
  const src = stripJavaComments(source);
  const decl = /\b(?:class|record)\s+([A-Za-z_$][\w$]*)/.exec(src);
  if (!decl) return null;
  const methods = [];
  const re =
    /^[ \t]*(?:(?:public|private|protected|static|final|synchronized|abstract|default|native)\s+)+([A-Za-z_$][\w$.]*(?:\s*<[^;{}()]*>)?(?:\s*\[\s*\])?)\s+([A-Za-z_$][\w$]*)\s*\(/gm;
  let m;
  while ((m = re.exec(src))) {
    const openParen = m.index + m[0].length - 1;
    const closeParen = findMatching(src, openParen);
    if (closeParen < 0) continue;
    let i = closeParen + 1;
    while (i < src.length && src[i] !== '{' && src[i] !== ';') i++;
    if (src[i] !== '{') continue;
    const end = findMatching(src, i);
    if (end < 0) continue;
    methods.push({ name: m[2], returnType: m[1], start: i, end, line: lineOf(src, m.index) });
    re.lastIndex = end + 1;
  }
  return { className: decl[1], masked: src, methods };
}

/** 同方法内、调用点之前对该标识符的最近一次赋值 RHS（没有则 null）。 */
export function lastAssignmentBefore(body, name, callIndex) {
  const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const re = new RegExp(`(?:^|[;{}\\s])${escaped}\\s*=\\s*([^;]+);`, 'g');
  let last = null;
  let m;
  while ((m = re.exec(body))) {
    if (m.index >= callIndex) break;
    last = m[1];
  }
  return last;
}

/** 参数是否可证明是「本方法新建的实体」。 */
export function isFreshEntity(body, rawArgs, callIndex) {
  const args = String(rawArgs || '').trim();
  if (/^new\s+/.test(args)) return true;
  if (!/^[A-Za-z_$][\w$]*$/.test(args)) return false;
  const rhs = lastAssignmentBefore(body, args, callIndex);
  if (!rhs) return false;
  return /^new\s+/.test(rhs.trim()) || rhs.includes('.builder(');
}

export function analyze(root) {
  const servicesDir = join(root, 'services');
  const files = existsSync(servicesDir) ? walkJavaFiles(servicesDir) : [];
  const hits = [];
  let schedulerClasses = 0;

  for (const file of files) {
    const parsed = parseClass(readFileSync(file, 'utf8'));
    if (!parsed) continue;
    if (!/(?:Scheduler|Job)$/.test(parsed.className)) continue;
    schedulerClasses++;
    const { masked, methods, className } = parsed;
    for (const method of methods) {
      const body = masked.slice(method.start, method.end + 1);
      const bodyStart = method.start;
      const callRe = /\.\s*(save|saveOrUpdate|updateById|saveBatch|updateBatchById|insert)\s*\(/g;
      let m;
      while ((m = callRe.exec(body))) {
        const open = bodyStart + m.index + m[0].length - 1;
        const close = findMatching(masked, open);
        if (close < 0) continue;
        const args = masked.slice(open + 1, close);
        const call = m[1];
        const wholeEntity = ALWAYS_WHOLE_ENTITY.has(call) || !isFreshEntity(body, args, m.index);
        if (!wholeEntity) continue;
        const rel = relative(root, file).split('\\').join('/');
        hits.push({
          file,
          rel,
          className,
          method: method.name,
          call,
          key: `${rel}#${method.name}`,
          line: lineOf(masked, open),
          args: args.trim().replace(/\s+/g, ' ')
        });
      }
    }
  }
  return { files: files.length, schedulerClasses, hits };
}

export function checkProject(root, options = {}) {
  const allowlistPath = join(root, options.allowlist ?? ALLOWLIST);
  const minClasses = options.minClasses ?? MIN_SCHEDULER_CLASSES;
  const { files, schedulerClasses, hits } = analyze(root);
  const findings = [];

  let allowlist = {};
  if (existsSync(allowlistPath)) {
    try {
      allowlist = JSON.parse(readFileSync(allowlistPath, 'utf8'));
    } catch (error) {
      findings.push(`${ALLOWLIST} 不是合法 JSON：${error.message}`);
    }
  } else {
    findings.push(`${ALLOWLIST} 不存在 —— 棘轮清单必须随代码一起提交`);
  }

  const actualByKey = new Map();
  for (const hit of hits) {
    const list = actualByKey.get(hit.key) ?? [];
    list.push(hit);
    actualByKey.set(hit.key, list);
  }

  for (const [key, entry] of Object.entries(allowlist)) {
    const reason = entry && typeof entry === 'object' ? entry.reason : '';
    const count = entry && typeof entry === 'object' ? entry.count : undefined;
    if (!Number.isInteger(count) || count < 1) {
      findings.push(`清单条目 ${key}：count 必须是 ≥1 的整数`);
      continue;
    }
    if (!String(reason || '').trim()) {
      findings.push(`清单条目 ${key}：缺少理由（写清这处读改了哪些列、为何还不能改列级更新）`);
      continue;
    }
    const actual = actualByKey.get(key) ?? [];
    if (actual.length === 0) {
      findings.push(`清单条目 ${key} 已失效（该方法里没有整实体回写了）—— 已改好就下调/删除`);
    } else if (actual.length > count) {
      findings.push(
        `${key}：登记 ${count} 处，实际 ${actual.length} 处（${actual.map((h) => h.line).join(', ')}）` +
          `—— 同一方法又新增了整实体回写`
      );
    } else if (actual.length < count) {
      findings.push(
        `${key}：登记 ${count} 处，实际只剩 ${actual.length} 处 —— 已改好请把 count 下调到 ${actual.length}`
      );
    }
  }

  for (const [key, entries] of actualByKey) {
    if (Object.prototype.hasOwnProperty.call(allowlist, key)) continue;
    findings.push(
      `${entries[0].rel}:${entries[0].line} (${entries[0].className}#${entries[0].method}) ` +
        `${entries[0].call}(${entries[0].args}) 整实体回写：改为列级条件更新` +
        `（update set 列 where id=? and 期望状态=?），确实暂时改不了才登记进 ${ALLOWLIST}（带理由）`
    );
  }

  if (schedulerClasses < minClasses) {
    findings.push(
      `锚点失效：只解析到 ${schedulerClasses} 个 Scheduler/Job 类（下限 ${minClasses}）—— 解析器退化时零命中是假绿`
    );
  }

  return {
    ok: findings.length === 0,
    files,
    schedulerClasses,
    hits,
    findings,
    allowlistPath,
    allowlistCount: Object.keys(allowlist).length
  };
}

function main() {
  const result = checkProject(ROOT);
  if (!result.ok) {
    console.error(`${TAG} FAIL: ${result.findings.length} 处定时任务整实体回写问题：`);
    for (const f of result.findings) console.error(`  - ${f}`);
    process.exit(1);
  }
  console.log(
    `${TAG} OK：${result.files} 个 main 源文件、${result.schedulerClasses} 个 Scheduler/Job 类，` +
      `${result.hits.length} 处读改写全部在棘轮清单内（${result.allowlistCount} 条，只减不增）`
  );
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main();
}
