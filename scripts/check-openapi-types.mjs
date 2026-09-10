#!/usr/bin/env node
/**
 * CI / 本地：校验 OpenAPI 生成类型已提交且别名齐全。
 * 若提供 OPENAPI_FILE 或可访问的 OPENAPI_URL，则重新生成并要求 working tree 无 diff。
 *
 *   node scripts/check-openapi-types.mjs
 *   OPENAPI_FILE=.tmp/live-openapi.json node scripts/check-openapi-types.mjs
 *
 * 别名清单与 gen 共用 scripts/openapi-alias-groups.mjs。
 */
import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { OPENAPI_ALIAS_GROUPS, OPENAPI_INDEX_REEXPORTS } from './openapi-alias-groups.mjs';

const __dirname = dirname(fileURLToPath(import.meta.url));
const root = resolve(__dirname, '..');
const generatedDir = join(root, 'packages', 'shared-types', 'src', 'generated');
const openapiTs = join(generatedDir, 'openapi.ts');
const indexTs = join(root, 'packages', 'shared-types', 'src', 'index.ts');

function fail(msg) {
  console.error(`[check-openapi-types] ${msg}`);
  process.exit(1);
}

if (!existsSync(openapiTs)) fail(`missing ${openapiTs}`);
if (!existsSync(indexTs)) fail(`missing ${indexTs}`);

for (const group of OPENAPI_ALIAS_GROUPS) {
  const filePath = join(generatedDir, group.file);
  if (!existsSync(filePath)) fail(`missing ${filePath}`);
  const src = readFileSync(filePath, 'utf8');
  for (const item of group.exports) {
    if (!src.includes(item.name)) {
      fail(`${group.file} missing export ${item.name}`);
    }
  }
}

const openapiSrc = readFileSync(openapiTs, 'utf8');
if (!openapiSrc.includes('OrderReadModel')) {
  fail('openapi.ts missing OrderReadModel schema');
}

const indexSrc = readFileSync(indexTs, 'utf8');
for (const name of OPENAPI_INDEX_REEXPORTS) {
  if (!indexSrc.includes(name)) {
    fail(`shared-types index.ts must re-export ${name}`);
  }
}

const openApiFile = process.env.OPENAPI_FILE
  ? resolve(root, process.env.OPENAPI_FILE)
  : join(root, '.tmp', 'live-openapi.json');
const shouldRegen =
  process.env.OPENAPI_CHECK_REGEN === '1' || process.env.OPENAPI_CHECK_REGEN === 'true';

if (shouldRegen) {
  console.log('[check-openapi-types] regenerating from OpenAPI…');
  const env = { ...process.env };
  if (existsSync(openApiFile)) {
    env.OPENAPI_FILE = openApiFile;
  }
  const gen = spawnSync('node', [join(root, 'scripts', 'gen-openapi-types.mjs')], {
    cwd: root,
    env,
    stdio: 'inherit',
    shell: true
  });
  if (gen.status !== 0) fail('gen:api-types failed');
  const diff = spawnSync(
    'git',
    ['diff', '--exit-code', '--', 'packages/shared-types/src/generated/'],
    { cwd: root, stdio: 'inherit', shell: true }
  );
  if (diff.status !== 0) {
    fail('generated OpenAPI types are stale; run pnpm gen:api-types and commit');
  }
} else {
  console.log('[check-openapi-types] structural OK (set OPENAPI_CHECK_REGEN=1 to regenerate+diff)');
}

console.log('[check-openapi-types] OK');
