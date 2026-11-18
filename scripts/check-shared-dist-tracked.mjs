#!/usr/bin/env node
/**
 * 门禁：packages/<pkg>/dist 的「无跟踪产物」必须是**显式 gitignore**，不能靠静默跳过（F8）。
 *
 * check-shared-dist-fresh 对 `tracked.length === 0` 直接 continue：
 * shared-types 的 dist 确实被 .gitignore（构建期生成）所以跳过是对的，
 * 但任何人误删某个已跟踪包的 dist 后，门禁也会用同一条 continue 报绿——
 * 「不跟踪」与「显式不跟踪」不是一回事。
 */
import { existsSync, readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gitAsync } from './lib/async-spawn.mjs';

const ROOT = process.env.GATE_ROOT
  ? resolve(process.env.GATE_ROOT)
  : resolve(dirname(fileURLToPath(import.meta.url)), '..');

/** 纯判据：pkgs = 本地有 dist 的包名；tracked = 有 git 跟踪产物的包；ignored = 显式忽略的包。 */
export function evaluate(pkgs, tracked, ignored) {
  const errors = [];
  if (pkgs.length < 3) {
    errors.push(`R0 只识别到 ${pkgs.length} 个本地 dist 包（<3）——扫描面塌了`);
  }
  let covered = 0;
  for (const pkg of pkgs) {
    if (tracked.has(pkg)) {
      covered++;
      continue;
    }
    if (ignored.has(pkg)) continue;
    errors.push(
      `${pkg}: dist 存在于工作树但 git 未跟踪，且未在 .gitignore 显式忽略 —— ` +
        `check-shared-dist-fresh 会静默跳过，产物与源是否一致无人保证`
    );
  }
  if (covered === 0) {
    errors.push('R0 没有任何包的 dist 被 git 跟踪——逐字节比对形同虚设');
  }
  return errors;
}

async function main() {
  const packagesDir = join(ROOT, 'packages');
  const pkgs = existsSync(packagesDir)
    ? readdirSync(packagesDir, { withFileTypes: true })
        .filter((d) => d.isDirectory() && existsSync(join(packagesDir, d.name, 'dist')))
        .map((d) => d.name)
    : [];
  const tracked = new Set();
  const ignored = new Set();
  for (const pkg of pkgs) {
    const rel = `packages/${pkg}/dist/`;
    const listed = await gitAsync(['ls-files', '--', rel], { cwd: ROOT });
    if (!listed.ok) {
      console.error(`[check-shared-dist-tracked] git ls-files 失败：${listed.out}`);
      process.exit(1);
    }
    if (listed.out.split(/\r?\n/).filter(Boolean).length) tracked.add(pkg);
    const check = await gitAsync(['check-ignore', '-q', rel], { cwd: ROOT });
    if (check.ok) ignored.add(pkg);
  }
  const errors = evaluate(pkgs, tracked, ignored);
  if (errors.length) {
    console.error(`[check-shared-dist-tracked] ${errors.length} 项失败：`);
    for (const e of errors) console.error(`  - ${e}`);
    process.exitCode = 1;
  } else {
    console.log(
      `[check-shared-dist-tracked] OK：${tracked.size} 个 dist 已跟踪纳入逐字节比对，` +
        `${ignored.size} 个显式 gitignore 跳过`
    );
  }
}

const invokedDirectly =
  process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (invokedDirectly) await main();
