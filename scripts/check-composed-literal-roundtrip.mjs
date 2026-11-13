#!/usr/bin/env node
/**
 * 门禁（S3-3a）：组合字符串字面量「写读同源」清单——R22-1（充值退款 reason 写侧改
 * `#orderId` 拼接、读侧还在按空格解析 ⇒ 对账查单器静默跳过）的族级控制。
 *
 * 形态：全仓 Java 主源码里 `split("lit")` / `lastIndexOf("lit")`（reader）与
 * `String.join("lit")`（writer）的字符串字面量分隔符**全量登记**于
 * scripts/composed-literal-registry.json（file+sep+kind 计数）。
 *   - classification=external：外部格式分隔符（CSV/行/路径/协议），写侧不在仓内或读侧为外部服务；
 *   - classification=DISPLAY_ONLY：拼接结果仅作报错/日志/模板/回显文案，仓内无解析读侧
 *     （无写读往返，R22-1 风险不成立——2026-11-13 同源确认轮确立）；
 *   - classification=TBD：自定义组合字面量，写读同源待确认——显式欠账，maxTbd 棘轮只减。
 * 新增命中不登记即红；登记条目与实际计数失配即红（双向棘轮）。
 *
 * 为什么不做「无 writer 即红」的硬判据：外部协议解析（CSV/设备报文）天然无仓内 writer，
 * 硬扫会把人训练成加白名单（§八 Q3）——登记制让每个解析点显式可见。
 *
 *   node scripts/check-composed-literal-roundtrip.mjs
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-composed-literal-roundtrip]';
const REGISTRY_PATH = join('scripts', 'composed-literal-registry.json');
const RE = /\.(split|lastIndexOf)\("((?:[^"\\]|\\.)*)"\)|String\.join\("((?:[^"\\]|\\.)*)"/g;

export function extractEntries(src, rel) {
  const out = [];
  for (const m of src.matchAll(RE)) {
    const kind = m[3] !== undefined ? 'writer' : 'reader';
    const sep = m[3] !== undefined ? m[3] : m[2];
    out.push({ file: rel, sep, kind });
  }
  return out;
}

/** 聚合 file+sep+kind → count */
export function aggregate(entries) {
  const map = new Map();
  for (const e of entries) {
    const k = `${e.file}|${e.sep}|${e.kind}`;
    map.set(k, (map.get(k) || 0) + 1);
  }
  return map;
}

export function evaluate(actualMap, registry, maxTbd) {
  const problems = [];
  const regMap = new Map(registry.entries.map((e) => [`${e.file}|${e.sep}|${e.kind}`, e]));
  for (const [key, count] of actualMap) {
    const [file, sep, kind] = key.split('|');
    const e = regMap.get(key);
    if (!e) {
      problems.push(
        `组合字面量未登记：${file} 的 ${kind}「${sep}」×${count}——新解析/拼接点必须在 composed-literal-registry.json 登记（R22-1 家族：写读两侧必须同源）`
      );
      continue;
    }
    if (count !== e.count) {
      problems.push(
        `${file} 的 ${kind}「${sep}」命中 ${count} 与登记 ${e.count} 不符` +
          (count > e.count ? '（新增未登记）' : '（旧点已删，条目应同步销账）')
      );
    }
  }
  for (const [key, e] of regMap) {
    if (!actualMap.has(key)) {
      problems.push(`清单陈旧：${e.file} 的 ${e.kind}「${e.sep}」已无命中，条目必须删除`);
    }
  }
  const tbd = registry.entries.filter((e) => e.classification === 'TBD').length;
  if (tbd > maxTbd) {
    problems.push(
      `TBD 棘轮击穿：${tbd} > maxTbd ${maxTbd}（新登记须分类为 external 或销一条 TBD）`
    );
  }
  return { problems, stats: { actual: actualMap.size, tbd } };
}

function walkJava(dir, out = []) {
  if (!existsSync(dir)) return out;
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    const st = statSync(p);
    if (st.isDirectory()) {
      if (['node_modules', 'target', 'static'].includes(name)) continue;
      walkJava(p, out);
    } else if (name.endsWith('.java')) out.push(p);
  }
  return out;
}

function main() {
  const regPath = join(ROOT, REGISTRY_PATH);
  if (!existsSync(regPath)) fail(`清单缺失 ${REGISTRY_PATH}`);
  const registry = JSON.parse(readFileSync(regPath, 'utf8'));
  const maxTbd = typeof registry.maxTbd === 'number' ? registry.maxTbd : 0;

  const files = walkJava(join(ROOT, 'services')).filter((p) => /src[\\/]main[\\/]/.test(p));
  if (files.length === 0) fail('主源码扫空（判据退化=假绿）');
  const all = [];
  for (const f of files) {
    all.push(...extractEntries(readFileSync(f, 'utf8'), relative(ROOT, f).replace(/\\/g, '/')));
  }
  const actualMap = aggregate(all);
  if (actualMap.size === 0) fail('命中数=0（提取器坏了，零命中=假绿）');

  const { problems, stats } = evaluate(actualMap, registry, maxTbd);
  if (problems.length > 0) {
    for (const p of problems) console.error(`${TAG} FAIL ${p}`);
    console.error(`${TAG} 共 ${problems.length} 项（S3-3a 组合字面量清单）`);
    process.exit(1);
  }
  console.log(
    `${TAG} OK：${files.length} 个 .java，分隔符命中点 ${actualMap.size} 组全登记（TBD ${stats.tbd}/${maxTbd} 待同源确认）`
  );
}

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  main();
}
