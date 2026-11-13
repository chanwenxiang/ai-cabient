#!/usr/bin/env node
/**
 * `check-ledger-immutability.mjs` 的负向用例（S8④ 自证）。
 *
 *   node scripts/check-ledger-immutability.test.mjs
 */
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { evaluate, stripComments, extractSetOf } from './check-ledger-immutability.mjs';

const TAG = '[check-ledger-immutability.test]';
let checks = 0;
function assert(cond, message) {
  checks++;
  if (!cond) throw new Error(`${TAG} 断言失败：${message}`);
}
const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const REAL = readFileSync(
  resolve(
    ROOT,
    'services/trade-service/src/main/java/com/aicabinet/trade/service/AdminDataManageService.java'
  ),
  'utf8'
);
const MASKED = stripComments(REAL);

// 1) 真实源码 ⇒ 零问题
{
  const p = evaluate(MASKED);
  assert(p.length === 0, `真实源码应绿：${p.join('|')}`);
}

// 2) 推导式保护被换成手写清单 ⇒ 红（pg_constraint 在 SQL 里出现两次，注入要全删）
{
  const fake = MASKED.replace('WITH RECURSIVE', '// 手写清单')
    .split('pg_constraint')
    .join('NOTHING');
  const p = evaluate(fake);
  assert(p.length >= 2, `替换推导须红：${p.join('|')}`);
  assert(
    p.some((x) => x.includes('推导')),
    `红因要点明推导被替换：${p.join('|')}`
  );
}

// 3) FUNDS_LEDGER_TABLES 少了资金表 ⇒ 红
{
  const fake = MASKED.replace('"recharge_order",', '');
  const p = evaluate(fake);
  assert(
    p.some((x) => x.includes('少了基线资金表') && x.includes('recharge_order')),
    `删资金表须红：${p.join('|')}`
  );
}

// 4) FUNDS_LEDGER_TABLES 多了未评审的表 ⇒ 红
{
  const fake = MASKED.replace(
    'FUNDS_LEDGER_TABLES = Set.of(',
    'FUNDS_LEDGER_TABLES = Set.of("mystery_ledger",'
  );
  const p = evaluate(fake);
  assert(
    p.some((x) => x.includes('多了基线之外')),
    `加表不评审须红：${p.join('|')}`
  );
}

// 5) 后缀保护被拆 ⇒ 红
{
  const fake = MASKED.replace('"_split",', '');
  const p = evaluate(fake);
  assert(
    p.some((x) => x.includes('少了基线后缀') && x.includes('_split')),
    `拆后缀须红：${p.join('|')}`
  );
}

// 6) deleteRow 放行闸被拿掉 ⇒ 红
{
  const fake = MASKED.replace('if (deleteProtectedTables().contains(table)) {', 'if (false) {');
  const p = evaluate(fake);
  assert(
    p.some((x) => x.includes('放行闸')),
    `拿掉闸须红：${p.join('|')}`
  );
}

// 7) DATA_DELETE 审计丢失 before ⇒ 红
{
  const fake = MASKED.replace('before=', '');
  const p = evaluate(fake);
  assert(
    p.some((x) => x.includes('DATA_DELETE') && x.includes('before')),
    `审计缺 before 须红：${p.join('|')}`
  );
}

// 8) 提取器自检：Set.of 元素解析正确
{
  const t = extractSetOf(MASKED, 'FUNDS_LEDGER_TABLES');
  assert(t.length === 5 && t[0] === 'recharge_order', `Set.of 抽取：${JSON.stringify(t)}`);
  const s = extractSetOf(MASKED, 'FUNDS_LEDGER_SUFFIXES');
  assert(s.length === 6, `后缀抽取：${JSON.stringify(s)}`);
}

console.log(`${TAG} OK：${checks} 项断言全过`);
