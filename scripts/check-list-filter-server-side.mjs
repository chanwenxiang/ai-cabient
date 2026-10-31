#!/usr/bin/env node
/**
 * 门禁：**列表取数（`fetchPage`）里禁止对「本页行」做客户端 `.filter(`**（棘轮）。
 *
 * 缺陷类（已真实复现两次）：
 *   - MA-007（R2 `/sessions` 类型筛选）：`rows = rows.filter(...)` + `total` 换成页内过滤后条数
 *     ⇒ 跨页数据被吃掉（已完成+消费 显 13、真值 17），导出同源受害；
 *   - MA-011（R3 `/device-ops` 关键词）：同一写法，只在第 2 页的事件搜不到。
 * 正确形态：筛选参数进 query 由后端过滤（必要时给后端加参数），`total` 一律用服务端值。
 *
 * 判据：
 *   1. 扫 `clients/admin-vue/src/views/**\/*.vue` 里 `fetchPage: async (…) => { … }` 的方法体；
 *   2. 方法体内出现 `X.filter(` ，且 X ∈ {data.items, items, rows, list} 或由 `data.items` 赋值的变量
 *      ⇒ 记一处违规；
 *   3. 每处必须命中 `scripts/list-filter-server-side-allowlist.json`（棘轮）：未登记⇒红；
 *      登记数>实际数⇒红（改好要下调，防清单腐烂）；理由为空⇒红；
 *   4. 锚点：解析到的 `fetchPage` 方法数 < 15 即红（解析器退化会让「零命中」变假绿）。
 *
 * 只减不增：新增白名单必须写明「为什么这处是安全的」。
 *
 *   node scripts/check-list-filter-server-side.mjs
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-list-filter-server-side]';
const ALLOWLIST = join('scripts', 'list-filter-server-side-allowlist.json');
const SCAN_DIR = join('clients', 'admin-vue', 'src', 'views');
const MIN_FETCH_PAGE = 15;
const ROW_VARS = new Set(['items', 'rows', 'list']);

/** 从 `fetchPage:` 起截取到配平的 `},`（粗粒度但足够：视图里 fetchPage 都是对象属性箭头函数）。 */
export function extractFetchPageBlocks(source) {
  const blocks = [];
  const re = /fetchPage\s*:\s*async\s*\([^)]*\)\s*=>\s*\{/g;
  let m;
  while ((m = re.exec(source))) {
    const start = m.index + m[0].length - 1; // 指向 '{'
    let depth = 0;
    let i = start;
    for (; i < source.length; i++) {
      const ch = source[i];
      if (ch === '{') depth++;
      else if (ch === '}') {
        depth--;
        if (depth === 0) break;
      }
    }
    blocks.push({ start, end: i + 1, body: source.slice(start, i + 1) });
  }
  return blocks;
}

/** 找出块内对「本页行」的客户端过滤；返回 [{ line, snippet }]。整行先验模式可省掉误报。 */
export function findClientRowFilters(body) {
  const hits = [];
  const lines = body.split('\n');
  lines.forEach((line, idx) => {
    // 形如 `rows = rows.filter(...)`、`list = [...list].filter(`、`const targets = items.filter(`
    const m = /(?:^|[^.\w])([A-Za-z_$][\w$]*)\s*=\s*[^;=]*\.filter\s*\(/.exec(line);
    if (!m) return;
    const receiver = /([A-Za-z_$][\w$]*)\.filter\s*\(/.exec(line);
    const name = receiver ? receiver[1] : m[1];
    if (!ROW_VARS.has(name)) return;
    hits.push({ line: idx + 1, snippet: line.trim().slice(0, 160) });
  });
  return hits;
}

function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (name.endsWith('.vue')) out.push(p);
  }
  return out;
}

function main() {
  const files = walk(join(ROOT, SCAN_DIR));
  const found = [];
  let blocks = 0;
  for (const file of files) {
    const src = readFileSync(file, 'utf8');
    for (const b of extractFetchPageBlocks(src)) {
      blocks++;
      const hits = findClientRowFilters(b.body);
      if (!hits.length) continue;
      const before = src.slice(0, b.start);
      const baseLine = before.split('\n').length;
      for (const h of hits) {
        found.push({
          file: relative(ROOT, file).replace(/\\/g, '/'),
          line: baseLine + h.line,
          snippet: h.snippet
        });
      }
    }
  }
  if (blocks < MIN_FETCH_PAGE) {
    console.error(
      `${TAG} FAIL: 只解析到 ${blocks} 个 fetchPage（锚点 ${MIN_FETCH_PAGE}）⇒ 解析器退化，零命中不可信`
    );
    process.exit(1);
  }

  const allow = existsSync(join(ROOT, ALLOWLIST))
    ? JSON.parse(readFileSync(join(ROOT, ALLOWLIST), 'utf8'))
    : [];
  if (!Array.isArray(allow)) {
    console.error(`${TAG} FAIL: ${ALLOWLIST} 必须是数组`);
    process.exit(1);
  }
  const allowByKey = new Map(allow.map((a) => [`${a.file}#${a.count ?? 1}`, a]));
  const byFile = new Map();
  for (const f of found) byFile.set(f.file, (byFile.get(f.file) ?? 0) + 1);

  const problems = [];
  for (const [file, count] of byFile) {
    const entry = allow.find((a) => a.file === file);
    if (!entry) {
      problems.push(
        `${file}：取数里对当前页做了 .filter( ${count} 处，且未登记白名单\n` +
          found
            .filter((f) => f.file === file)
            .map((f) => `      L${f.line}: ${f.snippet}`)
            .join('\n')
      );
      continue;
    }
    if (!entry.reason || !entry.reason.trim()) problems.push(`${file}：白名单缺 reason`);
    if ((entry.count ?? 1) !== count) {
      problems.push(
        `${file}：白名单登记 ${entry.count ?? 1} 处，实际 ${count} 处（棘轮：改少要下调，改多说明又加了一处）`
      );
    }
  }
  for (const entry of allow) {
    if (!byFile.has(entry.file)) {
      problems.push(`${entry.file}：白名单已无对应违规（已修好请删除该条，防清单腐烂）`);
    }
  }
  void allowByKey;

  if (problems.length) {
    console.error(
      `${TAG} FAIL: 取数里的客户端行过滤（跨页漏数据 + total 谎报，见 MA-007 / MA-011）：\n  - ` +
        problems.join('\n  - ')
    );
    process.exit(1);
  }
  console.log(
    `${TAG} OK（fetchPage ${blocks} 个；客户端行过滤 ${found.length} 处，全部在白名单内：${allow.map((a) => a.file).join(', ') || '无'}）`
  );
}

if (import.meta.url === pathToFileURL(process.argv[1] || '').href) main();
