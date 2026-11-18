#!/usr/bin/env node
/** S8③ 配套夹具：排障文档/失败退出判据的 R1–R4 负向验证。 */
import assert from 'node:assert/strict';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = resolve(join(dirname(fileURLToPath(import.meta.url)), '..'));
const gate = await import(pathToFileURL(join(ROOT, 'scripts', 'check-doc-commands.mjs')).href);
const base = {
  'docs/TROUBLESHOOTING_GUIDE.md':
    'pg_restore -d aicabinet backup.dump\n# PITR: WAL restore_command',
  'scripts/devops/flyway-repair/ValidateCheck.java':
    'catch(Exception e){ print("VALIDATE=FAIL"); System.exit(1); }',
  'scripts/deploy-staging.ps1': 'check-env.ps1\ndocker compose up\nif (-not $up) { exit 1 }'
};
const read =
  (over = {}) =>
  (rel) =>
    over[rel] ?? base[rel] ?? '';

assert.deepEqual(gate.evaluate(read()), [], '基线应绿');
assert.ok(
  gate
    .evaluate(read({ 'docs/TROUBLESHOOTING_GUIDE.md': 'pg_restore -d ai_cabinet' }))
    .some((e) => e.includes('R1')),
  'R1 未判红'
);
assert.ok(
  gate
    .evaluate(read({ 'docs/TROUBLESHOOTING_GUIDE.md': 'pg_restore --time="x"' }))
    .some((e) => e.includes('R2')),
  'R2 未判红'
);
assert.ok(
  gate
    .evaluate(
      read({ 'scripts/devops/flyway-repair/ValidateCheck.java': 'print("VALIDATE=FAIL");' })
    )
    .some((e) => e.includes('R3')),
  'R3 未判红'
);
assert.ok(
  gate
    .evaluate(
      read({
        'scripts/deploy-staging.ps1': 'docker compose up\ncheck-env.ps1\nif (-not $up) { exit 1 }'
      })
    )
    .some((e) => e.includes('R4')),
  'R4 顺序未判红'
);
assert.ok(
  gate
    .evaluate(read({ 'scripts/deploy-staging.ps1': 'check-env.ps1\ndocker compose up' }))
    .some((e) => e.includes('R4')),
  'R4 超时未判红'
);

console.log('[check-doc-commands.test] OK：6 项断言全过（R1–R4 负向）');
