/**
 * 本地快速校验：空头 MIGRATION_REVIEWED 应失败。
 * Run: node scripts/check-migration-reviewed-gate.test.mjs
 */
import { writeFileSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
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

const bare = `-- MIGRATION_REVIEWED: yes
-- empty bypass attempt
ALTER TABLE shopping_session ALTER COLUMN state TYPE varchar(64);
`;

const ok = `-- MIGRATION_REVIEWED: yes
-- TABLES: shopping_session (~1k)
-- LOCK_RISK: medium
-- NOTES: staging lock wait < 1s
ALTER TABLE shopping_session ALTER COLUMN state TYPE varchar(64);
`;

function withTempSql(body, run) {
  const name = `V9999__tmp_migration_reviewed_gate_${Date.now()}.sql`;
  const abs = join(migrationDir, name);
  writeFileSync(abs, body, 'utf8');
  try {
    return run();
  } finally {
    try {
      rmSync(abs);
    } catch {
      /* ignore */
    }
  }
}

function runCheck() {
  return spawnSync('node', [join(root, 'scripts', 'check-migration-safety.mjs')], {
    cwd: root,
    encoding: 'utf8'
  });
}

const bareResult = withTempSql(bare, runCheck);
if (bareResult.status === 0) {
  console.error('FAIL: bare MIGRATION_REVIEWED should have failed');
  process.exit(1);
}
if (!/requires LOCK_RISK|requires non-empty NOTES|requires TABLES/i.test(bareResult.stderr || '')) {
  console.error('FAIL: expected metadata errors, got:\n', bareResult.stderr);
  process.exit(1);
}

const okResult = withTempSql(ok, runCheck);
if (okResult.status !== 0) {
  console.error('FAIL: complete reviewed header should pass\n', okResult.stderr);
  process.exit(1);
}

console.log('[check-migration-reviewed-gate] OK');
