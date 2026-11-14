#!/usr/bin/env node
/**
 * 门禁（S3-3b）：「元 → 分」内联换算的**增量棘轮**——前端 + 后端双段。
 *
 * 背景：S3 双侧同源族的单位方向缺口——`check-no-inline-money-math` 只治「分→元」（/100），
 * 「元→分」（*100）方向 R1-§5.2 点名 5 处无门禁。但 `* 100` 同时混杂**百分比换算**语义
 * （bps→%、rate→percent），静态判据无法区分（§八 Q3：误报面大的不做硬扫）——
 * 所以按**增量棘轮**：现量全部逐文件登记（scripts/yuan-to-cents-baseline.json），
 * 命中数与基线严格相等（多=新增未登记红，少=删旧没销基线也红）。
 *
 * 🔴 2026-11-14 升级：扫描面从**仅前端**扩到**后端 Java**——负向注入实证前端门禁对
 * `services/**.java` 里的裸 `* 100` 金额换算零覆盖（CouponService:858 /
 * PlatformFundService:352 私有 yuanToCents 均不可见）。双段各自登记基线：
 *   - 前端段（entries）：clients 各端 src，元→分走 `yuanToCents`（shared-uni 单源）
 *   - 后端段（javaEntries）：services 各服务 src/main/java，元→分走 `YuanFen.toCents`
 *     （trade/util 单源，拒 scale>2 静默精度损失——PlatformFundService 私有 double 版
 *     绕过它即丢失该保护）；百分比/时间等非金额换算也须登记基线（判据无法区分语义，登记即显式化）
 *
 * 🔴 2026-11-14 补形：命中式从 `\*\s*100\b` 改 `\*\s*100(?![0-9])`。旧式因 `\b` 在
 * `100` 与 `L` 之间不成立（两侧都是 \w），**漏掉 `yuan * 100L` 这个 Java 金额换算最典型写法**
 * （金额是 long，`100L` 比 `100` 更常见；DeviceSlotService:351 就是 `* 100L` 形态）。
 * 新式 `100L/100l/100.0` 全命中，`1000` 仍不命中。
 *
 *   node scripts/check-yuan-to-cents.mjs
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-yuan-to-cents]';
const BASELINE_PATH = join('scripts', 'yuan-to-cents-baseline.json');
const DIRS = [
  join('clients', 'admin-vue', 'src', 'views'),
  join('clients', 'admin-vue', 'src', 'components'),
  join('clients', 'admin-vue', 'src', 'composables'),
  join('clients', 'merchant-mp', 'src'),
  join('clients', 'consumer-mp', 'src')
];
const JAVA_BASES = ['trade-service', 'device-service', 'common', 'jiangyi-gateway'].map((svc) =>
  join('services', svc, 'src', 'main', 'java')
);
const PATTERN = /\*\s*100(?![0-9])/g;
const FRONTEND_ANCHOR = 400;
const JAVA_ANCHOR = 900;

export function countHits(src) {
  return (src.match(PATTERN) || []).length;
}

/** 纯判定：实际命中 vs 基线（与 check-bare-localdate 同款双向棘轮）。
 *  @param kind 'frontend'｜'java'——只影响报错文案指向的单源目标 */
export function evaluate(actual, baseline, kind = 'frontend') {
  const target =
    kind === 'java'
      ? 'YuanFen.toCents（trade/util 单源，拒 scale>2 静默精度损失）'
      : 'yuanToCents（shared）';
  const problems = [];
  const baseMap = new Map(baseline.map((e) => [e.file, e.count]));
  const actualMap = new Map(actual.map((e) => [e.file, e.count]));
  for (const [file, hit] of actualMap) {
    if (!baseMap.has(file)) {
      problems.push(
        `${file}: 新增 * 100 命中 ${hit} 处不在基线——元→分必须走 ${target}，百分比换算也须登记基线（判据无法区分语义，登记即显式化）`
      );
      continue;
    }
    if (hit !== baseMap.get(file)) {
      problems.push(
        `${file}: * 100 命中 ${hit} 与基线 ${baseMap.get(file)} 不符` +
          (hit > baseMap.get(file) ? '（新增未登记）' : '（旧命中被删，基线条目应同步销账）')
      );
    }
  }
  for (const [file] of baseMap) {
    if (!actualMap.has(file)) {
      problems.push(`基线陈旧：${file} 已无 * 100 命中，基线条目必须删除（棘轮只许收缩）`);
    }
  }
  return problems;
}

function walk(dir, exts, out = []) {
  if (!existsSync(dir)) return out;
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    const st = statSync(p);
    if (st.isDirectory()) {
      if (['node_modules', 'dist', 'dev', 'target', 'static'].includes(name)) continue;
      walk(p, exts, out);
    } else if (exts.some((e) => name.endsWith(e))) out.push(p);
  }
  return out;
}

function main() {
  const baselinePath = join(ROOT, BASELINE_PATH);
  if (!existsSync(baselinePath)) fail(`基线缺失 ${BASELINE_PATH}`);
  const baselineJson = JSON.parse(readFileSync(baselinePath, 'utf8'));
  const feBaseline = baselineJson.entries || [];
  const javaBaseline = baselineJson.javaEntries || [];

  // ── 前端段 ──
  const feFiles = DIRS.flatMap((d) => walk(join(ROOT, d), ['.vue', '.ts']));
  if (feFiles.length < FRONTEND_ANCHOR)
    fail(
      `前端扫描面退化：只扫到 ${feFiles.length} 个文件（锚点 ${FRONTEND_ANCHOR}，实测基线 412）`
    );
  const feActual = [];
  for (const f of feFiles) {
    const n = countHits(readFileSync(f, 'utf8'));
    if (n > 0) feActual.push({ file: relative(ROOT, f).replace(/\\/g, '/'), count: n });
  }
  const feProblems = evaluate(feActual, feBaseline, 'frontend');

  // ── 后端段 ──
  const javaFiles = JAVA_BASES.flatMap((d) => walk(join(ROOT, d), ['.java']));
  if (javaFiles.length < JAVA_ANCHOR)
    fail(
      `后端扫描面退化：只扫到 ${javaFiles.length} 个文件（锚点 ${JAVA_ANCHOR}，实测 976，不含 target）`
    );
  const javaActual = [];
  for (const f of javaFiles) {
    const n = countHits(readFileSync(f, 'utf8'));
    if (n > 0) javaActual.push({ file: relative(ROOT, f).replace(/\\/g, '/'), count: n });
  }
  const javaProblems = evaluate(javaActual, javaBaseline, 'java');

  const problems = [...feProblems, ...javaProblems];
  if (problems.length > 0) {
    for (const p of problems) console.error(`${TAG} FAIL ${p}`);
    console.error(`${TAG} 共 ${problems.length} 项（S3-3b 元→分增量棘轮）`);
    process.exit(1);
  }
  const feTotal = feActual.reduce((n, e) => n + e.count, 0);
  const javaTotal = javaActual.reduce((n, e) => n + e.count, 0);
  console.log(
    `${TAG} OK：前端 ${feFiles.length} 文件 * 100 命中 ${feTotal} 处（${feActual.length} 文件）+ ` +
      `后端 ${javaFiles.length} 文件命中 ${javaTotal} 处（${javaActual.length} 文件）全部与基线一致`
  );
}

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  main();
}
