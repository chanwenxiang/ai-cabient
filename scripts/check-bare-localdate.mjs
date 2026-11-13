#!/usr/bin/env node
/**
 * 门禁（S4②）：裸 LocalDate.now() / ZoneId.systemDefault() 的**增量棘轮**。
 *
 * 背景：时区漂移家族（R19-17 / R21-2 / R22-10）。R19-17 拍板「存量 60 处裸
 * LocalDate.now() 不动：环境固化优先」——本门禁**不反悔该拍板**，只把增量封死：
 * 业务主源码里出现 `LocalDate.now()`（无参）或 `ZoneId.systemDefault()`，
 * 必须逐文件登记在 scripts/localdate-baseline.json，且**命中数与基线严格相等**
 * （多命中＝新增未登记即红；少命中＝有人删了旧的却没同步登记表，同样红）。
 *
 * 存量 convert 到 `LocalDate.now(ZoneId...)` / TimeWindows 单源是 S4③ 的活，届时
 * 基线按「convert 一个、删一个基线条目」的方式逐批收缩——棘轮本身允许基线条目被销。
 *
 * 判据只看主源码（src/main/java）：测试里的造数/断言不造成生产时区行为。
 * 注释与字符串先剥掉（与 check-config-early-return-scope 同款 strip）。
 *
 *   node scripts/check-bare-localdate.mjs
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-bare-localdate]';
const BASELINE_PATH = join('scripts', 'localdate-baseline.json');

const BARE_RE = /LocalDate\.now\(\s*\)/g;
const SYSTEM_DEFAULT_RE = /ZoneId\.systemDefault\(\)/g;

export const stripComments = (src) =>
  src
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/(^|[^:])\/\/[^\n]*/g, '$1 ')
    .replace(/"(?:[^"\\]|\\.)*"/g, '""'); // 字符串里的示例写法不计数（假红）

export function countInFile(masked, kind) {
  const re = kind === 'bare' ? BARE_RE : SYSTEM_DEFAULT_RE;
  return (masked.match(re) || []).length;
}

/**
 * 纯判定：实际命中 vs 基线。双向棘轮。
 * @param {{file:string, count:number}[]} actual 实际命中（按 kind 过滤后）
 * @param {{file:string, count:number}[]} baseline 基线条目
 * @param {string} kind 'bare' | 'systemDefault'
 */
export function evaluate(actual, baseline, kind) {
  const problems = [];
  const baselineMap = new Map(baseline.map((e) => [e.file, e.count]));
  const actualMap = new Map(actual.map((e) => [e.file, e.count]));
  for (const [file, hit] of actualMap) {
    if (!baselineMap.has(file)) {
      problems.push(
        `${file}: 出现 ${kind === 'bare' ? '裸 LocalDate.now()' : 'ZoneId.systemDefault()'} ${hit} 处，但不在基线——新增时区敏感调用必须登记（或改走 LocalDate.now(ZoneId...) / TimeWindows）`
      );
      continue;
    }
    if (hit !== baselineMap.get(file)) {
      problems.push(
        `${file}: ${kind === 'bare' ? '裸 LocalDate.now()' : 'ZoneId.systemDefault()'} 命中 ${hit} 与基线 ${baselineMap.get(file)} 不符` +
          (hit > baselineMap.get(file) ? '（新增未登记）' : '（旧调用被删，基线条目应同步销账）')
      );
    }
  }
  for (const [file, expected] of baselineMap) {
    if (!actualMap.has(file)) {
      problems.push(
        `基线陈旧：${file} 的 ${kind === 'bare' ? '裸 LocalDate.now()' : 'ZoneId.systemDefault()'} 已无命中（${expected} 处），基线条目必须删除`
      );
    }
  }
  return problems;
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

function main() {
  const baselinePath = join(ROOT, BASELINE_PATH);
  if (!existsSync(baselinePath)) {
    console.error(`${TAG} FAIL：基线缺失 ${BASELINE_PATH}`);
    process.exit(1);
  }
  const baseline = JSON.parse(readFileSync(baselinePath, 'utf8'));
  const servicesDir = join(ROOT, 'services');
  if (!existsSync(servicesDir)) {
    console.error(`${TAG} FAIL：services/ 不存在（扫描根缺失，不判通过）`);
    process.exit(1);
  }
  const javaFiles = walkJava(servicesDir).filter((p) => /(^|[\\/])src[\\/]main[\\/]/.test(p));
  if (javaFiles.length === 0) {
    console.error(`${TAG} FAIL：没有扫到任何 .java（零命中=假绿）`);
    process.exit(1);
  }

  const actual = { bare: [], systemDefault: [] };
  for (const abs of javaFiles) {
    const rel = relative(ROOT, abs).replace(/\\/g, '/');
    const masked = stripComments(readFileSync(abs, 'utf8'));
    const b = countInFile(masked, 'bare');
    const s = countInFile(masked, 'systemDefault');
    if (b) actual.bare.push({ file: rel, count: b });
    if (s) actual.systemDefault.push({ file: rel, count: s });
  }

  const problems = [
    ...evaluate(actual.bare, baseline.bareLocalDate || [], 'bare'),
    ...evaluate(actual.systemDefault, baseline.zoneIdSystemDefault || [], 'systemDefault')
  ];
  if (problems.length > 0) {
    for (const p of problems) console.error(`${TAG} FAIL ${p}`);
    console.error(
      `${TAG} 共 ${problems.length} 项：时区敏感调用必须逐文件登记、命中数与基线严格相等`
    );
    process.exit(1);
  }
  const bareTotal = actual.bare.reduce((n, e) => n + e.count, 0);
  const sdTotal = actual.systemDefault.reduce((n, e) => n + e.count, 0);
  console.log(
    `${TAG} OK：裸 LocalDate.now() ${bareTotal} 处（${actual.bare.length} 文件）` +
      ` + ZoneId.systemDefault() ${sdTotal} 处（${actual.systemDefault.length} 文件）全部与基线一致`
  );
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  main();
}
