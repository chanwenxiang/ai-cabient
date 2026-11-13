#!/usr/bin/env node
/** `check-open-timeout-contract.mjs` 负向用例（S8③ 销账）：纯函数用例（门禁自带 main 守卫，导入安全）。
 *   node scripts/check-open-timeout-contract.test.mjs */
import { parseBackendSeconds, parseFrontendSeconds } from './check-open-timeout-contract.mjs';

const TAG = '[check-open-timeout-contract.test]';
let checks = 0;
function assert(cond, message) {
  checks++;
  if (!cond) throw new Error(`${TAG} 断言失败：${message}`);
}

// 1) 后端秒数解析：${ENV:默认值} 形态（门禁正则只认这个）
{
  const v = parseBackendSeconds('    opening-seconds: ${SESSION_OPENING_EXPIRE_SECONDS:60}');
  assert(v === 60, `后端默认值应解析 60，实际 ${v}`);
  assert(parseBackendSeconds('opening-seconds: 60') === null, '裸数值形态应不匹配（fail-closed）');
}

// 2) 前端秒数解析
{
  const v = parseFrontendSeconds('const OPENING_EXPIRE_SECONDS = 10;');
  assert(v === 10, `前端解析应出 10，实际 ${v}`);
}

// 3) 真实仓面：门禁本体绿（自带 main 守卫，spawn 全量）
{
  const { runAsync } = await import('./lib/async-spawn.mjs');
  const { join, dirname, resolve } = await import('node:path');
  const { fileURLToPath } = await import('node:url');
  const r = await runAsync(process.execPath, [
    resolve(dirname(fileURLToPath(import.meta.url)), 'check-open-timeout-contract.mjs')
  ]);
  assert(r.status === 0, `真实树应绿：${(r.stderr || '').slice(-300)}`);
}

console.log(`${TAG} OK：${checks} 项断言全过`);
