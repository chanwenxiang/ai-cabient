#!/usr/bin/env node
/**
 * 本机等价执行聚合门禁链：`pnpm check:audit-gates` 的逃生通道。
 *
 * 为什么需要它：本机 `pnpm` 包装器（corepack shim）指向不存在的路径，跑不了；
 * 于是历史上一直是「逐个 `node scripts/xxx.mjs` 手动跑」。那样**看不出聚合链本身是断的** ——
 * 实测曾发生：新门禁写进了 `check:audit-gates`，却忘了在 package.json 定义它自己的 script，
 * 本机逐个跑全绿，CI 却在 `ERR_PNPM_RECURSIVE_EXEC_FIRST_FAIL` 处直接红，
 * 且断点之后的门禁在 CI 从未执行过。
 *
 * 本脚本按 `check:audit-gates` 的字面顺序解析 `pnpm <name> && …`，逐个用其
 * package.json 命令执行，并对「引用了未定义的 script」立即报错 —— 与 CI 语义一致。
 *
 *   node scripts/run-audit-gates.mjs
 */
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { runAsync } from './lib/async-spawn.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const pkg = JSON.parse(readFileSync(resolve(root, 'package.json'), 'utf8'));
const chain = pkg.scripts?.['check:audit-gates'];
if (!chain) {
  console.error('[run-audit-gates] package.json 里没有 check:audit-gates');
  process.exit(1);
}

const names = [...chain.matchAll(/pnpm\s+([A-Za-z0-9:_-]+)/g)].map((m) => m[1]);
if (names.length === 0) {
  console.error('[run-audit-gates] 未能解析出任何 pnpm 引用');
  process.exit(1);
}

/**
 * 异步执行一条门禁命令。
 *
 * 🔴 走共用的异步 spawn（`scripts/lib/async-spawn.mjs`）：WorkBuddy 环境下
 * `spawnSync` 无论 `shell:true/false`、无论是否间隔重试，一律 `status=null` +
 * `EBUSY`（`execSync` 同样抛）⇒ 聚合链 44 个门禁**全部**打印 `exit=null`，
 * 看起来像全红，实际一个都没跑（假红，方向与假绿同样危险）。
 *
 * 这里仍需 `shell: true`：门禁命令来自 package.json，是 `node scripts/x.mjs && …`
 * 这样的**命令行串**（含 `&&`），必须交给 shell 解析。
 */
function runCommand(cmd, cwd) {
  return runAsync(cmd, [], { cwd, inheritStdio: false, shell: true });
}

let failed = 0;
for (const name of names) {
  const cmd = pkg.scripts[name];
  if (!cmd) {
    console.log(`✗ ${name} —— package.json 未定义（CI 会在此 ERR_PNPM_RECURSIVE_EXEC_FIRST_FAIL）`);
    failed++;
    continue;
  }
  const result = await runCommand(cmd, root);
  if (result.status === 0) {
    console.log(`✓ ${name}`);
  } else {
    const tail = `${result.stdout || ''}${result.stderr || ''}`
      .trim()
      .split('\n')
      .slice(-3)
      .join(' / ');
    const why = result.spawnError ? ` (spawn failed: ${result.spawnError})` : '';
    console.log(`✗ ${name} exit=${result.status}${why} :: ${tail}`);
    failed++;
  }
}

console.log(`\n[run-audit-gates] 聚合链 ${names.length} 个门禁，失败 ${failed} 个`);
process.exit(failed === 0 ? 0 : 1);
