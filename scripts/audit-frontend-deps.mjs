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

let report;
try {
  report = JSON.parse(r.stdout || '{}');
} catch {
  console.error('[audit-frontend] failed to parse pnpm audit JSON');
  console.error((r.stderr || r.stdout || '').slice(-800));
  process.exit(1);
}

const vulns = report.vulnerabilities || {};
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

if (actionable.length) {
  console.error('[audit-frontend] unprotected vulnerabilities:');
  for (const a of actionable) {
    console.error(`  - ${a.name} (${a.severity}) ${a.ids.join(' ') || '(no GHSA id)'}`);
  }
  process.exit(1);
}

console.log('[audit-frontend] OK (prod; uni-locked GHSA ignored)');
process.exit(0);
