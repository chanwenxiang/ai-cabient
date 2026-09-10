#!/usr/bin/env node
/**
 * CI / 本地：扫描「相对基线分支新增」的 Flyway 脚本，拦截高风险裸 DDL。
 *
 *   node scripts/check-migration-safety.mjs
 *   MIGRATION_BASE_REF=origin/main node scripts/check-migration-safety.mjs
 *
 * 规则见 docs/MIGRATION_SAFETY.md
 */
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const root = resolve(__dirname, '..');
const migrationDir = join(
  root,
  'services',
  'trade-service',
  'src',
  'main',
  'resources',
  'db',
  'migration'
);

const HOT_TABLES = [
  'cabinet_order',
  'cabinet_order_line',
  'shopping_session',
  'payment_operation',
  'device_info',
  'user_account',
  'user_info',
  'inventory_lot',
  'dispute_ticket'
];

function fail(msg) {
  console.error(`[check-migration-safety] ${msg}`);
  process.exit(1);
}

function git(args) {
  const r = spawnSync('git', args, { cwd: root, encoding: 'utf8' });
  if (r.status !== 0) {
    return { ok: false, out: (r.stdout || '') + (r.stderr || '') };
  }
  return { ok: true, out: (r.stdout || '').trim() };
}

const baseRef = process.env.MIGRATION_BASE_REF || 'origin/dev';
let diffList = git([
  'diff',
  '--name-only',
  '--diff-filter=A',
  `${baseRef}...HEAD`,
  '--',
  migrationDir
]);
if (!diffList.ok) {
  // 无 remote 基线时：相对 HEAD 已暂存/未提交的新增
  diffList = git(['diff', '--name-only', '--diff-filter=A', 'HEAD', '--', migrationDir]);
}
const untracked = git(['ls-files', '--others', '--exclude-standard', '--', migrationDir]);
const names = new Set([
  ...(diffList.ok && diffList.out ? diffList.out.split(/\r?\n/).filter(Boolean) : []),
  ...(untracked.ok && untracked.out ? untracked.out.split(/\r?\n/).filter(Boolean) : [])
]);
const files = [...names];
if (files.length === 0) {
  console.log('[check-migration-safety] no new Flyway scripts vs baseline; OK');
  process.exit(0);
}

function touchesHotTable(body) {
  return HOT_TABLES.some((t) => new RegExp(`\\b${t}\\b`, 'i').test(body));
}

const errors = [];
const warnings = [];

for (const rel of files) {
  const abs = join(root, rel);
  let body;
  try {
    body = readFileSync(abs, 'utf8');
  } catch {
    errors.push(`${rel}: cannot read`);
    continue;
  }
  const reviewed = /MIGRATION_REVIEWED\s*:\s*yes/i.test(body);
  const lockRisk = (body.match(/LOCK_RISK\s*:\s*(low|medium|high)/i) || [])[1] || '';
  const notes = (body.match(/NOTES\s*:\s*(.+)/i) || [])[1] || '';

  if (/DROP\s+COLUMN/i.test(body) && !reviewed) {
    errors.push(
      `${rel}: DROP COLUMN without "MIGRATION_REVIEWED: yes" header (see docs/MIGRATION_SAFETY.md)`
    );
  }
  // PostgreSQL: ALTER COLUMN ... TYPE / SET NOT NULL / DROP DEFAULT 等可触发重写或长锁
  const alterColumn =
    /ALTER\s+COLUMN/i.test(body) ||
    /ALTER\s+TABLE[\s\S]{0,400}?\b(TYPE|SET\s+NOT\s+NULL|DROP\s+NOT\s+NULL|SET\s+DEFAULT|DROP\s+DEFAULT)\b/i.test(
      body
    );
  if (alterColumn && touchesHotTable(body) && !reviewed) {
    errors.push(
      `${rel}: ALTER COLUMN (or TYPE/SET NOT NULL) on hot table without "MIGRATION_REVIEWED: yes"`
    );
  } else if (alterColumn && !reviewed) {
    warnings.push(`${rel}: ALTER COLUMN without MIGRATION_REVIEWED — confirm lock risk on staging`);
  }
  if (/LOCK_RISK\s*:\s*high/i.test(body) && !String(notes).trim()) {
    errors.push(`${rel}: LOCK_RISK high requires non-empty NOTES`);
  }

  const createIndex = /CREATE\s+(UNIQUE\s+)?INDEX(?!\s+CONCURRENTLY)/i.test(body);
  const dropIndex = /DROP\s+INDEX(?!\s+IF\s+EXISTS)(?!\s+CONCURRENTLY)/i.test(body);
  const touchesHot = touchesHotTable(body);
  if (createIndex && touchesHot && !/CONCURRENTLY/i.test(body)) {
    const msg = `${rel}: CREATE INDEX on hot table without CONCURRENTLY`;
    if (reviewed && lockRisk === 'high') warnings.push(msg);
    else errors.push(msg);
  }
  if (dropIndex && touchesHot) {
    warnings.push(`${rel}: DROP INDEX on hot table — prefer DROP INDEX CONCURRENTLY`);
  }

  console.log(`[check-migration-safety] reviewed=${reviewed || false} ${relative(root, abs)}`);
}

for (const w of warnings) {
  console.warn(`[check-migration-safety] WARN ${w}`);
}
if (errors.length) {
  for (const e of errors) console.error(`[check-migration-safety] FAIL ${e}`);
  fail(`${errors.length} migration safety issue(s)`);
}
console.log(`[check-migration-safety] OK (${files.length} new script(s))`);
