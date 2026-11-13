#!/usr/bin/env node
/**
 * `check-fix-batch-coverage.mjs` 的负向用例（S8④ 自证）。
 *
 *   node scripts/check-fix-batch-coverage.test.mjs
 */
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { evaluateBatch, evaluateRegistry, isCoveredBy } from './check-fix-batch-coverage.mjs';

const TAG = '[check-fix-batch-coverage.test]';
let checks = 0;
function assert(cond, message) {
  checks++;
  if (!cond) throw new Error(`${TAG} 断言失败：${message}`);
}
const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');

const BATCH = {
  commit: 'abc12345',
  report: 'docs/AUDIT_X.md',
  totalItems: 2,
  items: [{ id: 'X-1', files: ['services/a/A.java'] }],
  notAdopted: [{ id: 'X-2', why: '产品口径不同' }]
};

// 1) 登记文件 ∈ commit diff ⇒ 绿
{
  const p = evaluateBatch(BATCH, ['services/a/A.java', 'docs/other.md'], () => true);
  assert(p.length === 0, `正常登记应绿：${p.join('|')}`);
}

// 2) A26 本体：提交信息声称改了，diff 里没有 ⇒ 红
{
  const p = evaluateBatch(BATCH, ['services/b/B.java'], () => true);
  assert(p.length === 1 && p[0].includes('A26'), `A26 形态须红：${p.join('|')}`);
}

// 3) 登记的文件仓内不存在 ⇒ 锚点红
{
  const p = evaluateBatch(BATCH, ['services/a/A.java'], () => false);
  assert(p.length === 1 && p[0].includes('不存在'), `幽灵文件须红：${p.join('|')}`);
}

// 4) 覆盖对账不平：totalItems 与 items+notAdopted 不等 ⇒ 红
{
  const p = evaluateBatch({ ...BATCH, totalItems: 3 }, ['services/a/A.java'], () => true);
  assert(p.length === 1 && p[0].includes('对账不平'), `漏登记条目须红：${p.join('|')}`);
}

// 5) 跨批条目重复 ⇒ 红；cutoff 后未登记的 fix 提交 ⇒ 红
{
  const reg = {
    coverageCutoff: '0000000',
    batches: [BATCH, { ...BATCH, commit: 'def67890' }],
    unregisteredAllowlist: []
  };
  const dup = evaluateRegistry(reg, []);
  assert(
    dup.length === 2 && dup.every((p) => p.includes('重复')),
    `每条重复都报（2 条）: ${dup.join('|')}`
  );

  const reg2 = { ...reg, batches: [BATCH] };
  const missing = evaluateRegistry(reg2, [{ hash: 'fff00011', subject: 'fix(trade): 某修复' }]);
  assert(
    missing.length === 1 && missing[0].includes('未登记'),
    `未登记 fix 提交须红：${missing.join('|')}`
  );
}

// 6) 未登记豁免（带 why）⇒ 绿
{
  const reg = {
    coverageCutoff: '0000000',
    batches: [BATCH],
    unregisteredAllowlist: [{ commit: 'fff00011', why: '门禁自身提交，非修复批' }]
  };
  const p = evaluateRegistry(reg, [{ hash: 'fff00011', subject: 'fix(gate): 门禁判据' }]);
  assert(p.length === 0, `豁免后应绿：${p.join('|')}`);
}

// 6b) 缩写宽度不一致也必须对上（2026-11-13 CI 真实故障的回归钉）
//     注册表存的是各自机器上 `git log --%h` 的缩写（7/8/9 位不等），CI 全量对象库里
//     同一条命令输出 9 位 ⇒ 旧的 Set.has() 精确比把 25 条登记全体判成「未登记」。
{
  const FULL = 'abcdef1234567890abcdef1234567890abcdef12';
  const reg = {
    coverageCutoff: '0000000',
    batches: [{ ...BATCH, commit: 'abcdef12' }], // 登记表里只有 8 位
    unregisteredAllowlist: [{ commit: 'ffff000', why: '7 位缩写豁免' }]
  };
  assert(
    evaluateRegistry(reg, [{ hash: FULL, subject: 'fix(trade): 前缀匹配' }]).length === 0,
    '登记 8 位 / 运行时 40 位 ⇒ 必须判为已登记'
  );
  assert(
    evaluateRegistry(reg, [{ hash: 'ffff000123456789', subject: 'fix(gate): 豁免前缀' }]).length ===
      0,
    '豁免表 7 位前缀 ⇒ 必须判为已豁免'
  );
  const stillMissing = evaluateRegistry(reg, [
    { hash: '9999888877776666555544443333222211110000', subject: 'fix(trade): 真未登记' }
  ]);
  assert(
    stillMissing.length === 1 && stillMissing[0].includes('未登记'),
    `前缀匹配不得放松判据：无关 commit 仍须红（${stillMissing.join('|')}）`
  );
  assert(isCoveredBy('abc', ['abcdef12']) === false, '短于 7 位的键不得算命中（防误配）');
}

// 7) 真实仓面：空登记表 + cutoff 必须可被 rev-parse 解析（由 main 校验），此处验表结构
{
  const registry = JSON.parse(
    readFileSync(resolve(ROOT, 'scripts/fix-batch-registry.json'), 'utf8')
  );
  assert(Array.isArray(registry.batches), 'batches 必须是数组');
  assert(
    /^[0-9a-f]{7,40}$/.test(registry.coverageCutoff),
    `coverageCutoff 必须是真实短哈希：${registry.coverageCutoff}`
  );
  assert(Array.isArray(registry.unregisteredAllowlist), 'unregisteredAllowlist 必须是数组');
}

console.log(`${TAG} OK：${checks} 项断言全过`);
