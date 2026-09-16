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
import { spawnSync } from 'node:child_process';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

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

let failed = 0;
for (const name of names) {
  const cmd = pkg.scripts[name];
  if (!cmd) {
    console.log(`✗ ${name} —— package.json 未定义（CI 会在此 ERR_PNPM_RECURSIVE_EXEC_FIRST_FAIL）`);
    failed++;
    continue;
  }
  const result = spawnSync(cmd, { cwd: root, shell: true, encoding: 'utf8' });
  if (result.status === 0) {
    console.log(`✓ ${name}`);
  } else {
    const tail = `${result.stdout || ''}${result.stderr || ''}`
      .trim()
      .split('\n')
      .slice(-3)
      .join(' / ');
    console.log(`✗ ${name} exit=${result.status} :: ${tail}`);
    failed++;
  }
}

console.log(`\n[run-audit-gates] 聚合链 ${names.length} 个门禁，失败 ${failed} 个`);
process.exit(failed === 0 ? 0 : 1);
