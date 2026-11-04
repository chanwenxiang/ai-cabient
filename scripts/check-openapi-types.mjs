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
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { runAsync } from './lib/async-spawn.mjs';
import { OPENAPI_ALIAS_GROUPS, OPENAPI_INDEX_REEXPORTS } from './openapi-alias-groups.mjs';

const __dirname = dirname(fileURLToPath(import.meta.url));
const root = resolve(__dirname, '..');
const generatedDir = process.env.OPENAPI_GENERATED_DIR
  ? resolve(root, process.env.OPENAPI_GENERATED_DIR)
  : join(root, 'packages', 'shared-types', 'src', 'generated');
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

/**
 * 🔴 双重编码哨兵（2026-11-04 批次 S 实测自伤）：那次 regen 把 openapi.ts 里 460 个中文
 * 全写成「UTF-8 字节被当 latin1 解码后再存成 UTF-8」的乱码（`点位费用配置` → `ç¹ä½Mè´¹…`），
 * 形状门禁与类型校验都看不出问题，只有 CI 起 HEAD 的 jar 抽真 spec 做 `git diff` 才红。
 * 这类乱码的特征是 «U+00C2–U+00F3 紧跟 U+0080–U+00BF»；正常的法文/德文变音符号后面
 * 不会跟 C1 控制字符，所以不会误报。用 `OPENAPI_GENERATED_DIR` 指向沙箱副本可复现。
 */
const MOJIBAKE = /[\u00c2-\u00f3][\u0080-\u00bf]/g;
for (const file of ['openapi.ts', ...OPENAPI_ALIAS_GROUPS.map((g) => g.file)]) {
  const filePath = join(generatedDir, file);
  if (!existsSync(filePath)) continue;
  const hits = readFileSync(filePath, 'utf8').match(MOJIBAKE) || [];
  if (hits.length) {
    fail(
      `${file} 有 ${hits.length} 处疑似双重编码乱码（样例 ${JSON.stringify(hits[0])}）：` +
        'regen 的输入 spec 被控制台码页污染。正解：起当前 trade 后 ' +
        'OPENAPI_IGNORE_CACHE=1 pnpm gen:api-types，别用 .tmp 里的旧 spec 反灌。'
    );
  }
}

const indexSrc = readFileSync(indexTs, 'utf8');
for (const name of OPENAPI_INDEX_REEXPORTS) {
  if (!indexSrc.includes(name)) {
    fail(`shared-types index.ts must re-export ${name}`);
  }
}

const explicitOpenApiFile = process.env.OPENAPI_FILE
  ? resolve(root, process.env.OPENAPI_FILE)
  : null;
const shouldRegen =
  process.env.OPENAPI_CHECK_REGEN === '1' || process.env.OPENAPI_CHECK_REGEN === 'true';

if (shouldRegen) {
  console.log('[check-openapi-types] regenerating from OpenAPI…');
  const env = { ...process.env };
  if (explicitOpenApiFile) {
    if (!existsSync(explicitOpenApiFile)) {
      fail(`OPENAPI_FILE 不存在：${explicitOpenApiFile}`);
    }
    env.OPENAPI_FILE = explicitOpenApiFile;
  } else {
    // 🔴 不要回落到 .tmp/live-openapi.json 缓存：该缓存可能远早于当前服务代码，
    // 用陈旧 spec 重生成会得到与 HEAD 完全一致的产物 ⇒ 「端点漏了却报 OK」的假绿。
    // 2026-09-23 实测：新增 /ops/admin/data/capabilities 后，带 09-22 的缓存重生成报 OK，
    // 现抓同一服务则立刻翻红。检查的意义就是「对着**当前**服务校验」，故这里强制现抓。
    delete env.OPENAPI_FILE;
    env.OPENAPI_IGNORE_CACHE = '1';
    console.log('[check-openapi-types] 忽略 .tmp 缓存，现抓 OPENAPI_URL 校验');
  }
  // 异步 spawn（lessons #292）：spawnSync 在 WorkBuddy 下 EBUSY 会把「没生成」判成失败。
  const gen = await runAsync(process.execPath, [join(root, 'scripts', 'gen-openapi-types.mjs')], {
    cwd: root,
    env,
    inheritStdio: true
  });
  if (gen.status !== 0)
    fail(`gen:api-types failed (exit ${gen.status}, ${gen.spawnError || 'no spawn error'})`);
  const diff = await runAsync(
    'git',
    ['diff', '--exit-code', '--', 'packages/shared-types/src/generated/'],
    { cwd: root, inheritStdio: true }
  );
  if (diff.status !== 0) {
    fail('generated OpenAPI types are stale; run pnpm gen:api-types and commit');
  }
} else {
  console.log('[check-openapi-types] structural OK (set OPENAPI_CHECK_REGEN=1 to regenerate+diff)');
}

console.log('[check-openapi-types] OK');
