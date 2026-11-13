#!/usr/bin/env node
/**
 * 门禁（S4③）：mapper XML 里的 `to_char(` / `date_trunc(` **必须带 AT TIME ZONE**，
 * 且时区钉 Asia/Shanghai。
 *
 * 背景（R22-4，23c57e09）：PG 会话时区为 UTC，mapper XML 裸 `to_char` 得到 UTC 日桶 ——
 * 上海 00:00–07:59 的成交记到前一天、月界整笔落上月（实测 4 笔跨日）。R22-4 修了
 * CabinetOrderLineMapper.xml 的 4 处；本门禁把「SQL 日桶必须显式转时区」钉成机械判据，
 * 新增任何 to_char/date_trunc 不带 AT TIME ZONE 即红。
 *
 * 判据（绝对规则，非棘轮——规则本身全量约束）：
 *   ① `to_char(` / `date_trunc(` 的调用区域（其后 400 字符窗口）必须出现
 *      `at time zone '<zone>'`；
 *   ② zone 必须是 Asia/Shanghai（值棘轮——业务日桶硬依赖，改区先登记理由）；
 *   ③ 例外（如参数是 `date` 列、无时区语义）必须逐条进 allowlist 带理由——
 *      门禁静态判不了列类型，「例外」必须显式记账而非静默放过。
 *
 *   node scripts/check-mapper-tz-timezone.mjs
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-mapper-tz-timezone]';
const ALLOWLIST_PATH = join('scripts', 'mapper-tz-allowlist.json');
const EXPECTED_ZONE = 'Asia/Shanghai';

/** 纯判定：单个 XML 文件里的违规。返回 [{line, func, reason}] */
export function evaluateXml(src, rel, allowlist) {
  const allowed = allowlist.some((e) => e.file === rel);
  const violations = [];
  const lines = src.split(/\r?\n/);
  const funcRe = /(to_char|date_trunc)\s*\(/g;
  const atTzRe = /at time zone\s+'([^']+)'/i;
  for (const m of src.matchAll(funcRe)) {
    const line = src.slice(0, m.index).split('\n').length;
    const after = src.slice(m.index, m.index + 400);
    const tz = after.match(atTzRe);
    if (!tz) {
      if (allowed) continue;
      violations.push({
        line,
        func: m[1],
        reason: `to_char/date_trunc 未带 AT TIME ZONE——PG 会话 UTC 下日桶整体错位（R22-4 家族）；非时区语义的例外须登记 allowlist 带理由`
      });
      continue;
    }
    if (tz[1] !== EXPECTED_ZONE) {
      violations.push({
        line,
        func: m[1],
        reason: `AT TIME ZONE=${tz[1]} ≠ ${EXPECTED_ZONE}（业务日桶硬依赖北京时区）`
      });
    }
  }
  return violations;
}

function walkXml(dir, out = []) {
  if (!existsSync(dir)) return out;
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    const st = statSync(full);
    if (st.isDirectory()) {
      if (['node_modules', 'target', 'static'].includes(name)) continue;
      walkXml(full, out);
    } else if (name.endsWith('.xml')) out.push(full);
  }
  return out;
}

function main() {
  const allowlistFile = join(ROOT, ALLOWLIST_PATH);
  if (!existsSync(allowlistFile)) {
    console.error(`${TAG} FAIL：allowlist 缺失 ${ALLOWLIST_PATH}`);
    process.exit(1);
  }
  const allowlist = JSON.parse(readFileSync(allowlistFile, 'utf8')).entries || [];

  const servicesDir = join(ROOT, 'services');
  const xmlFiles = walkXml(servicesDir).filter((p) => /src[\\/]main[\\/]/.test(p));
  if (xmlFiles.length === 0) {
    console.error(`${TAG} FAIL：没有扫到任何 XML（扫描根失效，零命中=假绿）`);
    process.exit(1);
  }
  const withFuncs = xmlFiles.filter((p) => /to_char|date_trunc/.test(readFileSync(p, 'utf8')));
  if (withFuncs.length === 0) {
    console.error(`${TAG} FAIL：没有任何含 to_char/date_trunc 的 XML（判据退化=假绿）`);
    process.exit(1);
  }

  const problems = [];
  for (const abs of withFuncs) {
    const rel = relative(ROOT, abs).replace(/\\/g, '/');
    const violations = evaluateXml(readFileSync(abs, 'utf8'), rel, allowlist);
    for (const v of violations) {
      problems.push(`${rel}:${v.line} ${v.func}( ${v.reason}`);
    }
  }
  for (const e of allowlist) {
    if (!xmlFiles.some((p) => relative(ROOT, p).replace(/\\/g, '/') === e.file)) {
      problems.push(`allowlist 陈旧：${e.file} 已不在扫描范围（XML 路径漂移，豁免应删除或更新）`);
    }
    if (!withFuncs.some((p) => relative(ROOT, p).replace(/\\/g, '/') === e.file)) {
      problems.push(`allowlist 陈旧：${e.file} 已无 to_char/date_trunc 命中，豁免条目应删除`);
    }
  }

  if (problems.length > 0) {
    for (const p of problems) console.error(`${TAG} FAIL ${p}`);
    console.error(
      `${TAG} 共 ${problems.length} 项：mapper XML 日桶必须显式 AT TIME ZONE '${EXPECTED_ZONE}'`
    );
    process.exit(1);
  }
  console.log(
    `${TAG} OK：${xmlFiles.length} 个 XML 中 ${withFuncs.length} 个含日桶函数全部显式转 ${EXPECTED_ZONE}` +
      (allowlist.length ? `（allowlist 豁免 ${allowlist.length}）` : '')
  );
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  main();
}
