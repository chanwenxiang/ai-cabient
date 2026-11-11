#!/usr/bin/env node
/**
 * 前端 prod 依赖审计门禁。
 * uni-app 锁死的 vite/esbuild 传递漏洞列入 IGNORE_GHSAS（仅构建链，非运行时产物），
 * 其余 moderate+ 一律失败，防止新 CVE 静默进入。
 *
 *   node scripts/audit-frontend-deps.mjs
 *   pnpm audit:frontend
 */
import { spawnSync } from 'node:child_process';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');

/**
 * @dcloudio/uni-app-vite → vite@5.4.x / esbuild，无法单独大升而不拆 uni 工具链。
 * 升级 uni 主版本后再复核并删条目。
 */
const IGNORE_GHSAS = new Set([
  'GHSA-fx2h-pf6j-xcff', // vite high — uni 传递
  'GHSA-4w7w-66w2-5vf9', // vite moderate — uni 传递
  'GHSA-v6wh-96g9-6wx3', // vite moderate — uni 传递
  'GHSA-67mh-4wv8-2f99' // esbuild moderate — uni 传递
]);

const SEVERITY_RANK = { critical: 4, high: 3, moderate: 2, low: 1, info: 0 };
const FAIL_AT_OR_ABOVE = SEVERITY_RANK.moderate;

function collectGhsaIds(entry, out) {
  if (!entry) return;
  for (const via of entry.via || []) {
    if (typeof via === 'string') continue;
    const id = via.url?.match(/GHSA-[a-z0-9-]+/i)?.[0] || via.source;
    if (typeof id === 'string' && id.startsWith('GHSA-')) out.add(id);
    if (via.severity) entry._severities = entry._severities || new Set();
    if (via.severity) entry._severities.add(String(via.severity).toLowerCase());
  }
}

const r = spawnSync(
  'pnpm',
  ['audit', '--registry', 'https://registry.npmjs.org/', '--prod', '--json'],
  { cwd: root, encoding: 'utf8', shell: true, maxBuffer: 20 * 1024 * 1024 }
);

// R15-H2：spawn 自身失败必须 fail-closed。注意 pnpm audit 在「发现漏洞」时本就
// exit 1 且 stdout 仍带完整 JSON——那是正常路径，不能按 status 一刀切；
// 真正的假绿根因是 registry/网络故障时 stdout 为空，被下方 `|| '{}'` 吞成空报告
// → 零 actionable → exit 0（CVE 门禁在基础设施故障时静默绿）。
if (r.error) {
  console.error('[audit-frontend] pnpm audit failed to spawn:', r.error.message);
  process.exit(1);
}
if (!r.stdout || !r.stdout.trim()) {
  console.error(
    `[audit-frontend] pnpm audit produced no stdout (exit=${r.status}, registry/network failure?)`
  );
  console.error((r.stderr || '').slice(-800));
  process.exit(1);
}

let report;
try {
  report = JSON.parse(r.stdout);
} catch {
  console.error('[audit-frontend] failed to parse pnpm audit JSON');
  console.error((r.stderr || r.stdout || '').slice(-800));
  process.exit(1);
}

// R15-H2：结构守门——两种格式任缺其一 = 报告形态不符合预期（上游契约变更/截断），
// 宁可红不可绿。2026-11-11 实证：pnpm 9 的 `--json` 输出是 npm6 老格式（advisories），
// **没有** `vulnerabilities` 键 ⇒ 旧脚本 `report.vulnerabilities || {}` 恒空、
// actionable 恒空、自落地起从未真正拦过任何漏洞（24 条 advisory 全部漏网）。
const vulns = report.vulnerabilities ?? report.advisories;
if (!vulns || typeof vulns !== 'object' || Array.isArray(vulns)) {
  console.error(
    '[audit-frontend] pnpm audit JSON has neither `vulnerabilities` nor `advisories` — treating as failure'
  );
  process.exit(1);
}

const actionable = [];

for (const [name, entry] of Object.entries(vulns)) {
  const ids = new Set();
  collectGhsaIds(entry, ids);
  // pnpm 也可能把 id 放在顶层
  if (entry.github_advisory_id) ids.add(entry.github_advisory_id);

  const severities = new Set(
    [...(entry._severities || [])].concat(
      entry.severity ? [String(entry.severity).toLowerCase()] : []
    )
  );
  if (!severities.size && entry.via) {
    for (const via of entry.via) {
      if (via && typeof via === 'object' && via.severity) {
        severities.add(String(via.severity).toLowerCase());
      }
    }
  }

  const maxRank = Math.max(0, ...[...severities].map((s) => SEVERITY_RANK[s] || 0));
  if (maxRank < FAIL_AT_OR_ABOVE) continue;

  const allIgnored = ids.size > 0 && [...ids].every((id) => IGNORE_GHSAS.has(id));
  if (allIgnored) {
    console.log(`[audit-frontend] ignored (uni toolchain): ${name} ${[...ids].join(',')}`);
    continue;
  }

  actionable.push({
    name,
    severity: [...severities].sort((a, b) => (SEVERITY_RANK[b] || 0) - (SEVERITY_RANK[a] || 0))[0],
    ids: [...ids]
  });
}

// R15-H2：存量基线棘轮（模式同 admin barehex 基线：只减不增、新增必红）。
// 2026-11-11 实测快照：24 条 advisory（low2/moderate13/high11），门禁从未工作期间
// 积累的存量以构建链传递为主（vite/@vue/@intlify/follow-redirects/brace-expansion/
// minimatch/postcss/source-map-js/echarts）——全部固化进基线；今后任何**新增** GHSA
// 直接红，存量靠依赖升级逐条出清后从本列表删除。
const KNOWN_BASELINE_GHSAS = new Set([
  // vite（uni 传递，构建链）
  'GHSA-587h-7wmj-h5pm', // high
  'GHSA-rq7h-c2jc-7f22', // moderate
  'GHSA-r825-w8mm-5pxw', // moderate
  'GHSA-698v-4r3x-3vcv', // moderate
  // source-map-js（vite 传递）
  'GHSA-68fv-2mgg-jv7q', // high
  // follow-redirects（axios 传递）
  'GHSA-q3jh-wrc6-jgq8', // high
  'GHSA-mrwp-f3q8-3g9p', // high
  'GHSA-8r9p-f939-6h3c', // high
  // brace-expansion（minimatch 传递）
  'GHSA-qhr7-859c-m2p7', // high
  'GHSA-6j4f-fj2g-mc7p', // high
  'GHSA-q2hr-2g5m-vwhr', // moderate
  // @vue/server-renderer（vue 3.4 锁版）
  'GHSA-g2v6-rqmx-r4w6', // high
  // @intlify/*（vue-i18n 传递）
  'GHSA-83rm-6c8r-g728', // high
  // postcss-selector-parser（构建链）
  'GHSA-rj75-hqrm-r3gf', // moderate
  // minimatch（构建链）
  'GHSA-g3hv-75wg-m249', // moderate
  // echarts（运行时图表库，待升级出清）
  'GHSA-fgmj-fm8m-jvvx' // moderate
]);

const overBaseline = actionable.filter(
  (a) => !a.ids.length || !a.ids.every((id) => KNOWN_BASELINE_GHSAS.has(id))
);
if (overBaseline.length) {
  console.error('[audit-frontend] NEW vulnerabilities beyond baseline (fail-closed):');
  for (const a of overBaseline) {
    console.error(`  - ${a.name} (${a.severity}) ${a.ids.join(' ') || '(no GHSA id)'}`);
  }
  process.exit(1);
}

if (actionable.length) {
  console.log(
    `[audit-frontend] ${actionable.length} known-baseline vulnerability/advisory entries (ratchet; upgrade to shrink)`
  );
}

console.log('[audit-frontend] OK (prod; uni-locked GHSA ignored)');
process.exit(0);
