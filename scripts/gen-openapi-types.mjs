#!/usr/bin/env node
/**
 * 从 trade-service OpenAPI 生成 TypeScript 类型（openapi-typescript）。
 *
 * 用法：
 *   pnpm gen:api-types
 *   OPENAPI_URL=http://127.0.0.1:8080/v3/api-docs pnpm gen:api-types
 *   OPENAPI_FILE=.tmp/live-openapi.json pnpm gen:api-types
 *   OPENAPI_IGNORE_CACHE=1 pnpm gen:api-types        # 忽略本地缓存，强制现抓
 *
 * 默认先读 OPENAPI_FILE / .tmp/live-openapi.json，没有再拉 OPENAPI_URL。
 * 别名组定义见 scripts/openapi-alias-groups.mjs（与 check 共用）。
 */
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, writeFileSync, readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { OPENAPI_ALIAS_GROUPS, renderAliasGroupFile } from './openapi-alias-groups.mjs';

const require = createRequire(import.meta.url);

/**
 * 🔴 编码防线：springdoc 的 description 是中文，若取 spec 的链路把 UTF-8 按 latin-1/cp936 解一遍，
 * 生成物里就会出现「高位引导字节 + 0x80–0xBF 连续字节」这类双重编码串（2026-11-04 批次 S 的 regen
 * 就这样把 36 行中文注释写成乱码；本地门禁比的是旧容器照不出来，直到 CI 重打 HEAD 的 jar 才红）。
 * 断言点放在取数处（缓存与现抓两条路），保证乱码 spec 不会先落盘再报错。
 * 正常中文是 CJK（U+4E00+），不会命中该模式。
 * 与 `check-openapi-types.mjs` 里的产物侧哨兵配对：那道拦「生成物已经写坏」，这道拦「输入就是坏的，
 * 别动仓库」——两条链都留着，删任一条都会放开另一半路径。
 */
const MOJIBAKE_RE = /[\u00C2-\u00F3][\u0080-\u00BF]/;

function findMojibake(text) {
  const hits = [];
  const lines = text.split('\n');
  for (let i = 0; i < lines.length; i++) {
    if (MOJIBAKE_RE.test(lines[i])) hits.push(`${i + 1}: ${lines[i].trim().slice(0, 72)}`);
  }
  return hits;
}

function assertNoMojibake(label, text) {
  const hits = findMojibake(text);
  if (!hits.length) return;
  console.error(
    `[gen-openapi-types] FAIL：${label} 检出 ${hits.length} 行双重编码乱码，拒绝落盘。\n` +
      hits
        .slice(0, 5)
        .map((h) => `  - ${h}`)
        .join('\n') +
      `\n  成因通常是取 spec 的 shell/管道按本地代码页解码。修法：用 curl/Invoke-WebRequest -OutFile 写原始字节，` +
      `或让本脚本自己 fetch（OPENAPI_URL=...），不要在 PowerShell 里把响应转成字符串再落盘。`
  );
  process.exit(1);
}

// ⚠️ 自检必须在任何取数/写盘之前短路——否则 `--selftest` 会顺手用缓存 spec 重写生成物（2026-11-04 踩过）
if (process.argv.includes('--selftest')) {
  const cases = [
    { text: '/** 保存点位费用配置（费用项随时可改） */', want: 0, why: '正常中文放行' },
    { text: '/** \u00e4\u00bf\u009d\u00e5\u00ad\u0098 */', want: 1, why: '双重编码必须拦' },
    {
      text: 'export type A = 1;\n/** \u00c3\u00a9\u00c3\u00a8 */',
      want: 1,
      why: '任意一行命中即拦'
    }
  ];
  let bad = 0;
  for (const c of cases) {
    const got = findMojibake(c.text).length > 0 ? 1 : 0;
    const ok = got === c.want;
    if (!ok) bad++;
    console.log(`  [${ok ? 'ok' : 'BAD'}] ${c.why} ⇒ ${got}`);
  }
  if (bad) {
    console.error('[gen-openapi-types --selftest] FAIL');
    process.exit(1);
  }
  console.log('[gen-openapi-types --selftest] 编码判据通过（未触发任何生成）');
  process.exit(0);
}

const __dirname = dirname(fileURLToPath(import.meta.url));
const root = resolve(__dirname, '..');
const outDir = join(root, 'packages', 'shared-types', 'src', 'generated');
const outFile = join(outDir, 'openapi.ts');
const defaultFile = join(root, '.tmp', 'live-openapi.json');
const openApiFile = process.env.OPENAPI_FILE
  ? resolve(root, process.env.OPENAPI_FILE)
  : defaultFile;
const openApiUrl =
  process.env.OPENAPI_URL ||
  process.env.OPENAPI_URL_FALLBACK ||
  'http://127.0.0.1:18080/v3/api-docs';
/** 强制现抓、忽略本地 spec 缓存（校验场景用：缓存可能远早于当前服务代码） */
const ignoreCache =
  process.env.OPENAPI_IGNORE_CACHE === '1' || process.env.OPENAPI_IGNORE_CACHE === 'true';

mkdirSync(outDir, { recursive: true });
mkdirSync(dirname(defaultFile), { recursive: true });

async function resolveSpecPath() {
  if (ignoreCache) {
    console.log('[gen-openapi-types] OPENAPI_IGNORE_CACHE=1 → 跳过本地缓存，直接现抓');
  } else if (existsSync(openApiFile)) {
    const raw = readFileSync(openApiFile, 'utf8');
    assertNoMojibake(`本地缓存 spec ${openApiFile}`, raw);
    const json = JSON.parse(raw);
    const pathCount = json?.paths ? Object.keys(json.paths).length : 0;
    if (pathCount === 0) {
      console.warn(`[gen-openapi-types] ${openApiFile} paths 为空，尝试拉取 ${openApiUrl}`);
    } else {
      console.log(`[gen-openapi-types] 使用本地 ${openApiFile} (${pathCount} paths)`);
      return openApiFile;
    }
  }

  console.log(`[gen-openapi-types] 拉取 ${openApiUrl}`);
  const ctrl = AbortSignal.timeout(60_000);
  let res;
  try {
    res = await fetch(openApiUrl, { signal: ctrl });
  } catch (err) {
    console.error(
      '[gen-openapi-types] 无法获取 OpenAPI。请先启动 trade-service，或准备 .tmp/live-openapi.json'
    );
    console.error(String(err?.message || err));
    process.exit(1);
  }
  if (!res.ok) {
    console.error(`[gen-openapi-types] HTTP ${res.status} from ${openApiUrl}`);
    process.exit(1);
  }
  const text = await res.text();
  assertNoMojibake(`现抓 spec ${openApiUrl}`, text);
  const json = JSON.parse(text);
  const pathCount = json?.paths ? Object.keys(json.paths).length : 0;
  if (pathCount === 0) {
    console.error('[gen-openapi-types] paths 为空：请用 trade :18080/:8080，不要用网关');
    process.exit(1);
  }
  writeFileSync(openApiFile, text, 'utf8');
  console.log(`[gen-openapi-types] 已保存 ${openApiFile} (${pathCount} paths)`);
  return openApiFile;
}

const specPath = await resolveSpecPath();
// Windows 路径含空格时：`pnpm exec … -o D:\ai-generated code\…` 经 shell 拆段会 ENOENT / mkdir D:\
// 直调 openapi-typescript CLI（argv 不经 cmd 再拆）
const openapiTsPkg = dirname(require.resolve('openapi-typescript/package.json'));
const openapiTsBin = join(openapiTsPkg, 'bin', 'cli.js');
const result = spawnSync(process.execPath, [openapiTsBin, specPath, '-o', outFile], {
  cwd: root,
  stdio: 'inherit',
  shell: false
});

if (result.status !== 0) {
  process.exit(result.status || 1);
}

const banner =
  '/**\n' +
  ' * AUTO-GENERATED by scripts/gen-openapi-types.mjs - DO NOT EDIT.\n' +
  ' * Source: trade-service /v3/api-docs (springdoc).\n' +
  ' * Regenerate: pnpm gen:api-types\n' +
  ' */\n';
const body = readFileSync(outFile, 'utf8');
if (!body.startsWith('/**\n * AUTO-GENERATED')) {
  writeFileSync(outFile, banner + body, 'utf8');
}
console.log(`[gen-openapi-types] 写入 ${outFile}`);

for (const group of OPENAPI_ALIAS_GROUPS) {
  const aliasFile = join(outDir, group.file);
  writeFileSync(aliasFile, renderAliasGroupFile(banner, group), 'utf8');
  console.log(`[gen-openapi-types] 写入 ${aliasFile}`);
}

// dist 不入库：生成后本地/CI 同步编译，供 package.json main/types 消费
const sharedTypesDir = join(root, 'packages', 'shared-types');
const tscJs = join(sharedTypesDir, 'node_modules', 'typescript', 'bin', 'tsc');
const tsc = spawnSync(process.execPath, [tscJs, '-p', 'tsconfig.json'], {
  cwd: sharedTypesDir,
  stdio: 'inherit',
  shell: false
});
if (tsc.status !== 0) {
  console.error('[gen-openapi-types] shared-types tsc 失败（src 已写入；请检查后重跑 build）');
  process.exit(tsc.status || 1);
}
console.log('[gen-openapi-types] 已同步 packages/shared-types/dist');
