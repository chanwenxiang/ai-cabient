#!/usr/bin/env node
/**
 * 门禁：只读事务里不得调用建档/写入方法（2026-11-20 追踪号 046be09544ac）。
 *
 * 实炸：PointsRedeemService.summary/pointsLog 标 `@Transactional(readOnly = true)`，
 * 却在无会员行时 `orElseGet(() -> memberService.createMember(userId))`；
 * createMember 持锁 `SELECT … FOR UPDATE`，PG 直接拒绝
 * 「cannot execute SELECT FOR UPDATE in a read-only transaction」，积分页两接口一起 500。
 *
 * 判据（2026-11-22 MA-393 从单点 createMember 扩为写入族，只读事务遇写 PG 必炸）：
 *   Tier A  无歧义 MyBatis-Plus/BaseMapper/IService 写 API —— .insert/.insertBatch/
 *           .insertOrUpdate/.updateById/.deleteById/.saveOrUpdate/.saveBatch，出现即红
 *           （这些方法名只存在于落库层，零误报、无白名单）；
 *   Tier B  写入语义大驼峰方法族 create/save/insert/update/delete/remove/upsert/persist/
 *           store + 大驼峰（覆盖 service 层 createMember 型建档），减**显式在内存白名单**
 *           —— 2026-11-22 全仓 2019 文件校准：唯一真实命中族是月结导出 POI 建表
 *           （createSheet/createRow/createCell/createCellStyle/createFont）与 List.removeIf，
 *           均为在内存构建/操作。白名单只收实证名，未来新在内存 createX 在此登记并写 why。
 * 先脱敏注释；按下一个方法结束括号截断，避免跨方法误判。
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const SCAN_DIR = join(ROOT, 'services');
const TAG = '[check-readonly-tx-write-call]';

const MP_WRITE_DOT =
  /\.\s*(?:insert|insertBatch|insertOrUpdate|updateById|deleteById|saveOrUpdate|saveBatch)\s*\(/;
const WRITE_FAMILY =
  /\b(?:create|save|insert|update|delete|remove|upsert|persist|store)[A-Z]\w*\s*\(/g;
const IN_MEMORY_ALLOW =
  /^(?:createCell|createRow|createSheet|createCellStyle|createFont|removeIf)$/;

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
    if (MP_WRITE_DOT.test(body)) {
      const line = clean.slice(0, m.index).split('\n').length;
      problems.push(
        `第 ${line} 行：只读事务方法体调用 MyBatis-Plus 写 API（.insert/.updateById/…，PG 必炸）`
      );
      continue;
    }
    for (const fm of body.matchAll(WRITE_FAMILY)) {
      const name = fm[0].replace(/\s*\($/, '').trim();
      if (IN_MEMORY_ALLOW.test(name)) continue;
      const line = clean.slice(0, m.index + fm.index).split('\n').length;
      problems.push(
        `第 ${line} 行：只读事务方法体调用写入族 ${name}(...)（PG 必炸；若属在内存构建，请到 IN_MEMORY_ALLOW 白名单登记并写 why）`
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
