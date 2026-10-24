#!/usr/bin/env node
/**
 * 内存分页门禁：**返回分页对象的方法里，不得对全量 List 做 `subList` 切片**（棘轮）。
 *
 * 缺陷类（lessons #47，已真实复现）：分页接口先把**全量**行查出来/算出来，再 `subList`
 * 切出当前页。数据量小时看不出问题，涨到几万行就是每次翻页都全表扫 + 全量 DTO 物化，
 * 单页延迟随总量线性上涨，且 `subList` 返回的是原 List 的视图，外层再包一层序列化时
 * 还持有整份数据。正确形态是 SQL/仓储层 `searchPage(offset, limit)` + 单独 `count`。
 *
 * 判据：
 *   1. 扫 `services/**\/src/main/java` 的所有方法；返回类型是 `PageResult<…>` 或
 *      `*PageDto` 的算「分页方法」；
 *   2. 分页方法体内每一处 `.subList(` 都算一处内存分页；
 *   3. 每处必须命中 `scripts/inmemory-paging-allowlist.json` 的**棘轮清单**：
 *      - 未登记 ⇒ 红（禁止新增）；
 *      - 登记数 > 实际数 ⇒ 红（已改好就下调/删除，防条目腐烂）；
 *      - 登记数 < 实际数 ⇒ 红（同一方法里又加了一处）；
 *      - 理由为空 ⇒ 红；
 *   4. 锚点：解析到的分页方法数 < 60 即红 —— 解析器退化会让「零命中」变成假绿。
 *
 * 清单只减不增。修复方向：把过滤/排序/分页下推到 Mapper 的 `searchPage` + `count`。
 * 非分页方法里的 `subList(0, n)`（Top-N 截断、调度批大小）不在此列 —— 它们的语义
 * 就是「只要前 n 条」，不会被数据量放大成翻页全扫。
 *
 *   node scripts/check-inmemory-paging.mjs
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-inmemory-paging]';
const ALLOWLIST = join('scripts', 'inmemory-paging-allowlist.json');
const MIN_PAGE_METHODS = 60;

/** 注释掩成空格（保留换行与列）；字符串原样保留，避免 `"…//…"` 吃掉后半行。 */
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

/** 从 `open`（必须是 `(` 或 `{`）起找配对的闭合符，跳过字符串。 */
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

/** 返回值是分页对象才算「分页方法」。 */
export function isPageReturnType(rawType) {
  const type = String(rawType || '').replace(/\s+/g, '');
  if (/^(?:[\w$]+\.)*PageResult<.+>$/.test(type)) return true;
  return /^[\w$.]*PageDto$/.test(type);
}

/**
 * 解析方法：返回 `{name, returnType, start, end}`（start/end 为方法体花括号区间）。
 * 只认可「修饰符 + 返回类型 + 方法名(` 同行」的声明 —— 本仓 100% 如此；拿不到的方法
 * 不会静默放行：清单条目对不上同样会红。
 */
export function parseMethods(source) {
  const src = stripJavaComments(source);
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
    methods.push({
      name: m[2],
      returnType: m[1],
      start: i,
      end,
      line: lineOf(src, m.index)
    });
    re.lastIndex = end + 1;
  }
  return { masked: src, methods };
}

export function analyze(root) {
  const servicesDir = join(root, 'services');
  const files = existsSync(servicesDir) ? walkJavaFiles(servicesDir) : [];
  const hits = [];
  let pageMethods = 0;

  for (const file of files) {
    const { masked, methods } = parseMethods(readFileSync(file, 'utf8'));
    for (const method of methods) {
      if (!isPageReturnType(method.returnType)) continue;
      pageMethods++;
      const body = masked.slice(method.start, method.end + 1);
      const subListRe = /\.subList\s*\(/g;
      let m;
      while ((m = subListRe.exec(body))) {
        hits.push({
          file,
          rel: relative(root, file).split('\\').join('/'),
          method: method.name,
          key: `${relative(root, file).split('\\').join('/')}#${method.name}`,
          line: lineOf(masked, method.start + m.index)
        });
      }
    }
  }
  return { files: files.length, pageMethods, hits };
}

export function checkProject(root, options = {}) {
  const allowlistPath = join(root, options.allowlist ?? ALLOWLIST);
  const minPageMethods = options.minPageMethods ?? MIN_PAGE_METHODS;
  const { files, pageMethods, hits } = analyze(root);
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

  for (const [key, entries] of Object.entries(allowlist)) {
    const reason = entries && typeof entries === 'object' ? entries.reason : '';
    const count = entries && typeof entries === 'object' ? entries.count : undefined;
    if (!Number.isInteger(count) || count < 1) {
      findings.push(`清单条目 ${key}：count 必须是 ≥1 的整数`);
      continue;
    }
    if (!String(reason || '').trim()) {
      findings.push(`清单条目 ${key}：缺少理由（写清为什么这处还不能下推 SQL）`);
      continue;
    }
    const actual = actualByKey.get(key) ?? [];
    if (actual.length === 0) {
      findings.push(
        `清单条目 ${key} 已失效（该方法里没有内存分页了）—— 已改好就下调/删除，禁止留烂条目`
      );
    } else if (actual.length > count) {
      findings.push(
        `${key}：登记 ${count} 处，实际 ${actual.length} 处（${actual
          .map((h) => h.line)
          .join(', ')}）—— 同一方法又新增了内存分页`
      );
    } else if (actual.length < count) {
      findings.push(
        `${key}：登记 ${count} 处，实际只剩 ${actual.length} 处 —— 已改好请把 count 下调到 ${actual.length}（棘轮只减不增）`
      );
    }
  }

  for (const [key, entries] of actualByKey) {
    if (Object.prototype.hasOwnProperty.call(allowlist, key)) continue;
    findings.push(
      `${entries[0].rel}:${entries[0].line} (${entries[0].method}) 新增内存分页：` +
        `对全量 List 做 subList。请把过滤/排序/分页下推到 Mapper 的 searchPage + count；` +
        `确实暂时改不了才登记进 ${ALLOWLIST}（带理由）`
    );
  }

  if (pageMethods < minPageMethods) {
    findings.push(
      `锚点失效：只解析到 ${pageMethods} 个返回分页对象的方法（下限 ${minPageMethods}）—— ` +
        `解析器退化时「零命中」是假绿`
    );
  }

  return {
    ok: findings.length === 0,
    files,
    pageMethods,
    hits,
    findings,
    allowlistPath,
    allowlistCount: Object.keys(allowlist).length
  };
}

function main() {
  const result = checkProject(ROOT);
  if (!result.ok) {
    console.error(`${TAG} FAIL: ${result.findings.length} 处内存分页问题：`);
    for (const f of result.findings) console.error(`  - ${f}`);
    process.exit(1);
  }
  console.log(
    `${TAG} OK：${result.files} 个 main 源文件、${result.pageMethods} 个分页方法，` +
      `${result.hits.length} 处内存分页全部在棘轮清单内（${result.allowlistCount} 条，只减不增）`
  );
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main();
}
