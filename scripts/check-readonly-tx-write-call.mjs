#!/usr/bin/env node
/**
 * 门禁：只读事务里不得调用建档/写入方法（2026-11-20 追踪号 046be09544ac）。
 *
 * 实炸：PointsRedeemService.summary/pointsLog 标 `@Transactional(readOnly = true)`，
 * 却在无会员行时 `orElseGet(() -> memberService.createMember(userId))`；
 * createMember 持锁 `SELECT … FOR UPDATE`，PG 直接拒绝
 * 「cannot execute SELECT FOR UPDATE in a read-only transaction」，积分页两接口一起 500。
 *
 * 判据：任意 `@Transactional(readOnly = true)` 方法体内出现 `createMember(` 即红。
 * 先脱敏注释；按下一个方法结束括号截断，避免跨方法误判。
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const SCAN_DIR = join(ROOT, 'services');
const TAG = '[check-readonly-tx-write-call]';

function* walk(dir) {
  if (!existsSync(dir)) return;
  for (const name of readdirSync(dir)) {
    if (name === 'target' || name === 'node_modules') continue;
    const p = join(dir, name);
    if (statSync(p).isDirectory()) yield* walk(p);
    else if (name.endsWith('.java')) yield p;
  }
}

export function readOnlyWriteProblems(src) {
  const clean = src
    .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/\/\/[^\n]*/g, (m) => m.replace(/[^\n]/g, ' '));
  const problems = [];
  const re = /@Transactional\s*\(\s*readOnly\s*=\s*true\s*\)/g;
  for (const m of clean.matchAll(re)) {
    const end = clean.indexOf('\n    }', m.index);
    const body = clean.slice(m.index, end < 0 ? clean.length : end);
    if (/\bcreateMember\s*\(/.test(body)) {
      const line = clean.slice(0, m.index).split('\n').length;
      problems.push(
        `第 ${line} 行：只读事务方法体调用 createMember(...)（会 FOR UPDATE，PG 必炸）`
      );
    }
  }
  return problems;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const problems = [];
  let files = 0;
  for (const p of walk(SCAN_DIR)) {
    files++;
    for (const msg of readOnlyWriteProblems(readFileSync(p, 'utf8'))) {
      problems.push(`${relative(ROOT, p)}: ${msg}`);
    }
  }
  if (files < 100) {
    console.error(`${TAG} FAIL：只扫到 ${files} 个 Java 文件，扫描面塌陷`);
    process.exit(1);
  }
  if (problems.length) {
    console.error(`${TAG} FAIL：`);
    for (const p of problems) console.error(`  - ${p}`);
    process.exit(1);
  }
  console.log(`${TAG} OK：${files} 个 Java 文件无「只读事务内建档」`);
}
