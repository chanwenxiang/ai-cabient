#!/usr/bin/env node
/**
 * `check-mp-scoped-hash.mjs` 的负向夹具（MA-280 / S8③）。
 *
 *   node scripts/check-mp-scoped-hash.test.mjs
 */
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { BUILD_HOOK_TARGETS, missingBuildHooks } from './check-mp-scoped-hash.mjs';

const TAG = '[check-mp-scoped-hash.test]';
let checks = 0;
function assert(cond, message) {
  checks++;
  if (!cond) throw new Error(`${TAG} 断言失败：${message}`);
}
const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const GATE = resolve(ROOT, 'scripts/check-mp-scoped-hash.mjs');

const goodHook =
  'uni build -p mp-weixin && node ../../scripts/check-mp-scoped-hash.mjs dist/build/mp-weixin';

function runGate(args, env = {}) {
  return execFileSync(process.execPath, [GATE, ...args], {
    cwd: ROOT,
    encoding: 'utf8',
    env: { ...process.env, ...env },
    stdio: ['ignore', 'pipe', 'pipe']
  });
}

// 1) 两侧都内嵌产物级检查 ⇒ 无缺失
{
  const missing = missingBuildHooks(() => ({ scripts: { 'build:mp-weixin': goodHook } }));
  assert(missing.length === 0, `接线齐全却报缺失：${missing.join(',')}`);
  assert(BUILD_HOOK_TARGETS.length === 2, '必须同时盯住 consumer 与 merchant 两侧');
}

// 2) 少一侧 / 脚本被改成不带产物路径 ⇒ 必须报缺失（这是 MA-280 的护栏）
{
  const missing = missingBuildHooks((rel) => ({
    scripts: {
      'build:mp-weixin': rel.includes('merchant') ? 'uni build -p mp-weixin' : goodHook
    }
  }));
  assert(
    missing.length === 1 && missing[0].includes('merchant'),
    `只删商户侧应报 1 条：${missing}`
  );
  const noPath = missingBuildHooks(() => ({
    scripts: { 'build:mp-weixin': 'node ../../scripts/check-mp-scoped-hash.mjs' }
  }));
  assert(noPath.length === 2, `没带 dist/build/mp-weixin 路径应两侧都报：${noPath}`);
}

// 3) 真仓面：两个 package.json 的 build:mp-weixin 都必须内嵌检查
{
  const missing = missingBuildHooks((rel) => JSON.parse(readFileSync(resolve(ROOT, rel), 'utf8')));
  assert(missing.length === 0, `真仓面接线缺失：${missing.join(',')}`);
}

// 4) CI 干净检出（无产物）：不再默默 SKIP，必须输出「已验接线」
{
  const out = runGate([], { MP_SCOPED_HASH_ROOTS: '__no_such_artifact_dir__' });
  assert(/已验 consumer\/merchant/.test(out), `无产物时没有验接线：${out}`);
  assert(!/SKIP/.test(out), `无产物时不许再 SKIP 冒充判过：${out}`);
}

// 5) 负向：显式传了产物路径却不存在 ⇒ 必须红（构建失败不许判绿）
{
  let red = false;
  try {
    runGate(['__no_such_artifact_dir__'], {});
  } catch (e) {
    red = true;
    assert(/构建没产出|不得判绿/.test(String(e.stderr || '')), `报错文案不对：${e.stderr}`);
  }
  assert(red, '显式路径不存在时竟然 exit 0');
}

console.log(`${TAG} OK：${checks} 项断言全过`);
