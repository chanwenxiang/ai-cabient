#!/usr/bin/env node
/**
 * 门禁：佣金/分成参数写侧必须过限幅（MA-069 / MA-090 / MA-100 同族防复发）。
 *
 * 缺陷类（R17 复核实锤）：线长 `commission_rate_bps`、机器账单 `share_bps`、线路成员
 * `commission_rate_bps/commission_per_unit_cents` 都是**裸 Map / 裸实体直落**，越界值被
 * `LineCommissionJob` 与账单生成按万分比乘进真实出账（bps=5000 ⇒ 5 倍 GMV 佣金）。
 * 修法统一走 `CommissionParamBounds`，本门禁保证「以后再写一处分成就必须先过闸」。
 *
 * 判据：扫 `services/<模块>/src/main/java` 下全部 .java 中对四个分位/万分比列的 setter 调用；
 * 实体类（domain/**，定义 setter 本身）豁免，其余每个**写侧文件**必须
 *   ① import `com.aicabinet.trade.support.CommissionParamBounds`，或
 *   ② 出现在 `scripts/commission-param-bounds-allowlist.json`（文件 + 命中数，棘轮双向：
 *      少命中也会红，防止闸被拆掉后门禁跟着失效）。
 * 锚点：命中写侧文件数 < 5 即红（正则退化会让零命中变假绿）。
 *
 *   node scripts/check-commission-param-bounds.mjs
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-commission-param-bounds]';
const ALLOWLIST = join('scripts', 'commission-param-bounds-allowlist.json');
const SETTER = /\bset(CommissionRateBps|CommissionFixedCents|ShareBps|CommissionPerUnitCents)\s*\(/;
const GUARD_IMPORT = 'com.aicabinet.trade.support.CommissionParamBounds';
const MIN_WRITERS = 5;

function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    const st = statSync(full);
    if (st.isDirectory()) {
      if (name === 'node_modules' || name === 'target' || name === 'static') continue;
      walk(full, out);
    } else if (name.endsWith('.java')) {
      out.push(full);
    }
  }
  return out;
}

const allow = existsSync(join(ROOT, ALLOWLIST))
  ? JSON.parse(readFileSync(join(ROOT, ALLOWLIST), 'utf8'))
  : {};

const writers = [];
for (const service of ['trade-service', 'device-service', 'common', 'jiangyi-gateway']) {
  const base = join(ROOT, 'services', service, 'src', 'main', 'java');
  if (!existsSync(base)) continue;
  for (const file of walk(base)) {
    const rel = relative(ROOT, file).replace(/\\/g, '/');
    const text = readFileSync(file, 'utf8');
    const lines = text.split(/\r?\n/);
    const hits = [];
    lines.forEach((line, i) => {
      if (SETTER.test(line) && !line.trim().startsWith('//')) hits.push(i + 1);
    });
    if (!hits.length) continue;
    // 实体类只是定义 setter 本身，不是「写侧」
    if (/\/domain\/[^/]+\.java$/.test(rel)) continue;
    writers.push({ rel, hits, guarded: text.includes(GUARD_IMPORT) });
  }
}

const failures = [];
if (writers.length < MIN_WRITERS) {
  failures.push(
    `锚点失效：只扫到 ${writers.length} 个写侧文件（预期 ≥ ${MIN_WRITERS}），正则或目录可能已退化`
  );
}
for (const w of writers) {
  if (w.guarded) continue;
  const entry = allow[w.rel];
  if (entry && entry.count === w.hits.length) continue;
  if (entry) {
    failures.push(
      `${w.rel}：白名单登记 ${entry.count} 处，实际 ${w.hits.length} 处（行 ${w.hits.join(',')}）——棘轮要求同步`
    );
  } else {
    failures.push(`${w.rel}：写佣金/分成列但未过 CommissionParamBounds（行 ${w.hits.join(',')}）`);
  }
}

const stale = Object.keys(allow).filter((k) => !writers.some((w) => w.rel === k));
for (const k of stale) failures.push(`白名单残留已不存在的写侧文件：${k}（请删除条目）`);

if (failures.length) {
  console.error(`${TAG} FAIL（${failures.length} 项）`);
  for (const f of failures) console.error('  ✗ ' + f);
  process.exit(1);
}
console.log(
  `${TAG} OK：${writers.length} 个写侧文件全部过限幅闸（${writers.filter((w) => w.guarded).length} 走 CommissionParamBounds，` +
    `${Object.keys(allow).length} 个自带闸的先例在白名单）`
);
