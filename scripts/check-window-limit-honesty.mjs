#!/usr/bin/env node
/**
 * 门禁（S10）：取数窗口/截断的**诚实性**——「静默截断不回显」「窗口写死不交代」家族
 * （MA-002/009/010/014）的登记制控制。
 *
 * 设计拍板（2026-11-13，用户授权）：分页面 PageResult 已携带 items/page/size/total
 * =天然诚实豁免；本门禁管两段**非分页面**：
 *   a) **截断点**：mapper 里 `LIMIT N`（N≥2；LIMIT 1=单行锚点按设计豁免）——
 *     全量登记于 scripts/window-limit-registry.json（file|limit 计数）；
 *   b) **窗口字面量**：service 里 `minusDays(N)` / `minusMonths(N)`（不含 ZONE 参数化
 *     形态本身——登记的是「写死的天数」）。
 * 分类：BATCH/ARCHIVE（内部消费或 S7 保留域）、TOP_N（条数即口径）、PARAM_OVERRIDABLE
 * （参数可覆盖）、KPI_FIXED（文案即口径）、DATE_ARITHMETIC（日期边界计算，非取数窗口）、
 * TBD=待确认消费面的显式欠账（maxTbd 只减）。
 * 判据：①新命中不登记即红；②计数失配红（file|值 聚合，行漂移不误报）；③TBD 棘轮。
 * 为什么登记制不做「无 total 即红」硬扫：消费面语义（批处理 vs 用户列表）静态判不了，
 * 硬扫会训练人加白名单（§八 Q3）——登记让每个截断/窗口点显式可见，语义细化随修复批。
 *
 *   node scripts/check-window-limit-honesty.mjs
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-window-limit-honesty]';
const REGISTRY_PATH = join('scripts', 'window-limit-registry.json');

export function extractLimitSites(src, rel) {
  const out = [];
  src.split(/\r?\n/).forEach((line, i) => {
    const m = line.match(/LIMIT\s+([2-9][0-9]*|\$\{[^}]+\})/);
    if (m) out.push({ file: rel, line: i + 1, limit: m[1] });
  });
  return out;
}

export function extractWindowSites(src, rel) {
  const out = [];
  for (const m of src.matchAll(/minus(Days|Months)\((\d+)\)/g)) {
    out.push({ file: rel, unit: `${m[1]}(${m[2]})` });
  }
  return out;
}

export function aggregate(sites, keyFn) {
  const map = new Map();
  for (const s of sites) {
    const k = keyFn(s);
    map.set(k, (map.get(k) || 0) + 1);
  }
  return map;
}

/**
 * 纯判定：实际命中 × 登记表（两段同构）。
 * @param {Map<string,number>} actualMap 聚合命中
 * @param {{file:string,limit?:string,unit?:string,count:number,cls:string}[]} entries
 * @param {string} kind 'limit' | 'window'
 * @param {number} maxTbd
 * @param {Set<string>} tbdFiles TBD 文件集合（棘轮按条目算）
 */
export function evaluateSection(actualMap, entries, kind, maxTbd, tbdFiles) {
  const problems = [];
  const regMap = new Map(
    entries.map((e) => [`${e.file}|${kind === 'limit' ? e.limit : e.unit}`, e])
  );
  for (const [key, count] of actualMap) {
    const [file, val] = key.split('|');
    const e = regMap.get(key);
    if (!e) {
      problems.push(
        `${kind === 'limit' ? '截断点' : '窗口字面量'}未登记：${file}（${val}）×${count}——` +
          `先确认消费面（批处理/Top-N/参数可覆盖？）再按分类登记 window-limit-registry.json`
      );
      continue;
    }
    if (count !== e.count) {
      problems.push(
        `${file}（${val}）命中 ${count} 与登记 ${e.count} 不符` +
          (count > e.count ? '（新增未登记）' : '（旧点已删，条目应同步销账）')
      );
    }
  }
  for (const [key, e] of regMap) {
    if (!actualMap.has(key)) {
      problems.push(
        `登记陈旧：${e.file}（${kind === 'limit' ? e.limit : e.unit}）已无命中，条目应删除`
      );
    }
  }
  const tbdCount = entries.filter((e) => e.cls === 'TBD').length;
  if (tbdCount > maxTbd) {
    problems.push(`TBD 棘轮击穿（${kind}）：${tbdCount} > ${maxTbd}（新登记须给出语义分类）`);
  }
  return { problems, tbdCount };
}

function walk(dir, out = []) {
  if (!existsSync(dir)) return out;
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    const st = statSync(p);
    if (st.isDirectory()) {
      if (['node_modules', 'target', 'static'].includes(name)) continue;
      walk(p, out);
    } else if (name.endsWith('.java')) out.push(p);
  }
  return out;
}

function main() {
  const regPath = join(ROOT, REGISTRY_PATH);
  if (!existsSync(regPath)) fail(`登记表缺失 ${REGISTRY_PATH}`);
  const registry = JSON.parse(readFileSync(regPath, 'utf8'));

  const mapperFiles = walk(
    join(ROOT, 'services/trade-service/src/main/java/com/aicabinet/trade/mapper')
  );
  const svcFiles = walk(
    join(ROOT, 'services/trade-service/src/main/java/com/aicabinet/trade/service')
  );
  if (mapperFiles.length === 0 || svcFiles.length === 0) fail('扫描面退化（零命中=假绿）');

  const limitSites = [];
  for (const f of mapperFiles) {
    limitSites.push(
      ...extractLimitSites(readFileSync(f, 'utf8'), relative(ROOT, f).replace(/\\/g, '/'))
    );
  }
  const windowSites = [];
  for (const f of svcFiles) {
    windowSites.push(
      ...extractWindowSites(readFileSync(f, 'utf8'), relative(ROOT, f).replace(/\\/g, '/'))
    );
  }
  const limitMap = aggregate(limitSites, (s) => `${s.file}|${s.limit}`);
  const windowMap = aggregate(windowSites, (s) => `${s.file}|${s.unit}`);
  if (limitMap.size === 0 && windowMap.size === 0) fail('命中数=0（提取器坏了，零命中=假绿）');

  const maxTbd = typeof registry.maxTbd === 'number' ? registry.maxTbd : 0;
  const limitProblems = evaluateSection(limitMap, registry.limitEntries || [], 'limit', maxTbd);
  const windowProblems = evaluateSection(windowMap, registry.windowEntries || [], 'window', maxTbd);
  const problems = [...limitProblems.problems, ...windowProblems.problems];
  const tbdTotal = limitProblems.tbdCount + windowProblems.tbdCount;
  if (tbdTotal > maxTbd) {
    problems.push(`TBD 棘轮击穿（总量）：${tbdTotal} > maxTbd ${maxTbd}`);
  }

  if (problems.length > 0) {
    for (const p of problems) console.error(`${TAG} FAIL ${p}`);
    console.error(`${TAG} 共 ${problems.length} 项（S10 取数窗口诚实性）`);
    process.exit(1);
  }
  console.log(
    `${TAG} OK：截断点 ${limitMap.size} 组 + 窗口字面量 ${windowMap.size} 组全登记` +
      `（TBD ${tbdTotal}/${maxTbd}，分页面 PageResult 已带 total=豁免）`
  );
}

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  main();
}
