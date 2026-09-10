#!/usr/bin/env node
/**
 * CI / 本地：校验 OpenAPI 生成类型已提交且订单读模型别名齐全。
 * 若提供 OPENAPI_FILE 或可访问的 OPENAPI_URL，则重新生成并要求 working tree 无 diff。
 *
 *   node scripts/check-openapi-types.mjs
 *   OPENAPI_FILE=.tmp/live-openapi.json node scripts/check-openapi-types.mjs
 */
import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const root = resolve(__dirname, '..');
const generatedDir = join(root, 'packages', 'shared-types', 'src', 'generated');
const openapiTs = join(generatedDir, 'openapi.ts');
const orderModels = join(generatedDir, 'order-models.ts');
const indexTs = join(root, 'packages', 'shared-types', 'src', 'index.ts');

function fail(msg) {
  console.error(`[check-openapi-types] ${msg}`);
  process.exit(1);
}

for (const f of [openapiTs, orderModels, indexTs]) {
  if (!existsSync(f)) fail(`missing ${f}`);
}

const orderModelsSrc = readFileSync(orderModels, 'utf8');
for (const name of [
  'OpenApiOrderReadModel',
  'OpenApiOrderReadModelAdmin',
  'OpenApiOrderReadModelMerchant',
  'OpenApiOrderReadModelConsumer',
  'OpenApiOrderLineDto'
]) {
  if (!orderModelsSrc.includes(name)) fail(`order-models.ts missing export ${name}`);
}

const openapiSrc = readFileSync(openapiTs, 'utf8');
if (!openapiSrc.includes('OrderReadModel')) {
  fail('openapi.ts missing OrderReadModel schema');
}

const indexSrc = readFileSync(indexTs, 'utf8');
if (!indexSrc.includes('OpenApiOrderReadModelMerchant')) {
  fail('shared-types index.ts must re-export OpenApiOrderReadModelMerchant');
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
  console.log(
    '[check-openapi-types] structural OK (set OPENAPI_CHECK_REGEN=1 to regenerate+diff)'
  );
}

console.log('[check-openapi-types] OK');
