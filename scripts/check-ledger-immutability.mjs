#!/usr/bin/env node
/**
 * 门禁（S5）：资金/账本表删除保护与删除审计的不可退化——「资金凭证表可被物理删」这族
 * （R22-5，23c57e09）已由 schema 推导式保护 + before 全值审计落地，本门禁把它们钉成机械判据。
 *
 * 落点唯一：AdminDataManageService（管理后台原始数据通道，资金表物理删除的唯一入口）。
 * 判据（剥注释后，全部 fail-closed）：
 *   ① 推导式保护存在：`deleteProtectedTables()` 必须含 schema 推导标记
 *      （WITH RECURSIVE + pg_constraint + confdeltype）——有人把推导换成手写清单即红；
 *   ② FUNDS_LEDGER_TABLES 命名集合必须与基线 5 表**逐字一致**（删一个/加一个都红，
 *      新增资金表要么改本门禁基线、要么走 isFundsLedgerTable 的后缀判定自动覆盖）；
 *   ③ FUNDS_LEDGER_SUFFIXES 必须与基线 6 后缀一致；
 *   ④ deleteRow 删除前必须查 `deleteProtectedTables().contains(table)` 放行闸，
 *      且 DATA_DELETE 审计串必须带 `before=` 全值（selectRowAsText 先行）。
 *
 * 推导式保护是「由 schema 推导、不手写清单」的范式（手写清单必漂）；本门禁只钉
 * 「推导没被替换掉 + 资金集合没被拆」，不改推导逻辑本身。
 *
 *   node scripts/check-ledger-immutability.mjs
 */
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-ledger-immutability]';
const SERVICE_REL =
  'services/trade-service/src/main/java/com/aicabinet/trade/service/AdminDataManageService.java';

export const stripComments = (src) =>
  src.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/(^|[^:])\/\/[^\n]*/g, '$1 ');

// 基线（R22-5 口径，改这里必须同时改代码并在评审里说明——两边都是显式 diff）
const SEED_FUNDS_TABLES = [
  'recharge_order',
  'balance_refund_request',
  'balance_refund_allocation',
  'line_commission_daily',
  'staff_commission_daily'
];
const SEED_SUFFIXES = ['_ledger', '_bill', '_split', '_clawback', '_points_log', '_operation'];

/** 从源码里抽取 Set.of("a", "b", ...) 的元素（剥注释+字符串后无注释干扰） */
export function extractSetOf(src, varName) {
  const m = src.match(new RegExp(`${varName}\\s*=\\s*Set\\.of\\(([^)]*)\\)`));
  if (!m) return null;
  return m[1]
    .split(',')
    .map((s) => (s.match(/"([^"]+)"/) || [])[1])
    .filter(Boolean);
}

/**
 * 纯判定：对（剥注释后的）服务源码执行全部判据。
 * @param {string} masked
 */
export function evaluate(masked) {
  const problems = [];

  // ① 推导式保护
  const methodIdx = masked.indexOf('deleteProtectedTables()');
  if (methodIdx < 0) {
    problems.push('deleteProtectedTables() 不存在——推导式保护被整体移除（资金表删除闸消失）');
    return problems;
  }
  const methodBody = masked.slice(methodIdx, methodIdx + 4000);
  for (const marker of ['WITH RECURSIVE', 'pg_constraint', 'confdeltype']) {
    if (!methodBody.includes(marker)) {
      problems.push(
        `deleteProtectedTables() 缺推导标记「${marker}」——保护集合被换成手写清单（手写必漂，资金表删除闸失效）`
      );
    }
  }

  // ② ③ 资金集合与后缀
  const tables = extractSetOf(masked, 'FUNDS_LEDGER_TABLES');
  if (tables == null) {
    problems.push('FUNDS_LEDGER_TABLES 定义丢失（Set.of 形态被改）');
  } else {
    const missing = SEED_FUNDS_TABLES.filter((t) => !tables.includes(t));
    const extra = tables.filter((t) => !SEED_FUNDS_TABLES.includes(t));
    if (missing.length)
      problems.push(
        `FUNDS_LEDGER_TABLES 少了基线资金表：${missing.join(', ')}（资金凭证表可能失守删除保护）`
      );
    if (extra.length)
      problems.push(
        `FUNDS_LEDGER_TABLES 多了基线之外的表：${extra.join(', ')}（新增须改本门禁基线并评审，别只改代码）`
      );
  }
  const suffixes = extractSetOf(masked, 'FUNDS_LEDGER_SUFFIXES');
  if (suffixes == null) {
    problems.push('FUNDS_LEDGER_SUFFIXES 定义丢失');
  } else {
    const missing = SEED_SUFFIXES.filter((s) => !suffixes.includes(s));
    if (missing.length)
      problems.push(
        `FUNDS_LEDGER_SUFFIXES 少了基线后缀：${missing.join(', ')}（语义后缀保护被拆）`
      );
  }

  // ④ deleteRow：放行闸 + before 审计
  const deleteIdx = masked.indexOf('public void deleteRow(');
  if (deleteIdx < 0) {
    problems.push('deleteRow 方法不存在（删除通道形态变化，判据需同步重审）');
  } else {
    const body = masked.slice(deleteIdx, deleteIdx + 3000);
    if (!/deleteProtectedTables\(\)\.contains\(table\)/.test(body)) {
      problems.push(
        'deleteRow 缺「deleteProtectedTables().contains(table)」放行闸——资金表删除未被保护集合拦住'
      );
    }
    if (!/beforeRow\s*=/.test(body)) {
      problems.push('deleteRow 缺 beforeRow 全值读取（selectRowAsText）——删除后无 before 可对账');
    }
    if (!/"DATA_DELETE"/.test(body) || !/before=/.test(body)) {
      problems.push('deleteRow 的 DATA_DELETE 审计串缺 before= 全值——只记 pk 无法重建被删资金数据');
    }
  }
  return problems;
}

function main() {
  const src = readFileSync(join(ROOT, SERVICE_REL), 'utf8');
  const masked = stripComments(src);
  const problems = evaluate(masked);
  if (problems.length > 0) {
    for (const p of problems) console.error(`${TAG} FAIL ${p}`);
    console.error(`${TAG} 共 ${problems.length} 项（S5 资金表删除保护不可退化）`);
    process.exit(1);
  }
  console.log(
    `${TAG} OK：${SERVICE_REL.split('/').pop()} 推导式保护 + ${SEED_FUNDS_TABLES.length} 表 + ${SEED_SUFFIXES.length} 后缀 + deleteRow 闸与 before 审计全部在位`
  );
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  main();
}
