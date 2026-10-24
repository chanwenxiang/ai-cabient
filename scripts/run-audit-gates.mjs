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
    // 完整输出落盘（3 行尾巴不够诊断未捕获异常的根因行；2026-10-24 mp-defect-classes 实证）
    try {
      const { mkdirSync, writeFileSync } = await import('node:fs');
      // .tmp 是 gitignore 的 ⇒ 新 clone / 干净 CI runner 上根本不存在，ENOENT 会被下面的
      // catch 吞掉，恰好在最需要诊断日志的机器上静默丢日志（2026-10-24 复核发现）
      const logDir = resolve(root, '.tmp');
      mkdirSync(logDir, { recursive: true });
      // 文件名禁冒号：Windows NTFS 会把 `a:b.log` 当备用数据流（ADS）写进 `a`，
      // 普通 ls/cat 全看不见（2026-10-24 实证：诊断文件写成功却"不存在"）
      const logName = `gate-failure-${name.replace(/:/g, '_')}.log`;
      writeFileSync(
        resolve(logDir, logName),
        `exit=${result.status}\n--- stdout ---\n${result.stdout || ''}\n--- stderr ---\n${result.stderr || ''}`
      );
      console.log(`  （完整输出已存 .tmp/${logName}）`);
    } catch (e) {
      // 落盘失败不掩盖原失败，但必须留一行痕迹，否则「日志呢」无处可查
      console.log(`  （完整输出落盘失败：${e instanceof Error ? e.message : e}）`);
    }
    failed++;
  }
}

console.log(`\n[run-audit-gates] 聚合链 ${names.length} 个门禁，失败 ${failed} 个`);
process.exit(failed === 0 ? 0 : 1);
