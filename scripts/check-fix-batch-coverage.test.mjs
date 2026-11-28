#!/usr/bin/env node
/**
 * `check-fix-batch-coverage.mjs` 的负向用例（S8④ 自证）。
 *
 *   node scripts/check-fix-batch-coverage.test.mjs
 */
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  evaluateBatch,
  evaluateRegistry,
  isCoveredBy,
  resolveCurrentPath
} from './check-fix-batch-coverage.mjs';

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

// 3b) 修复批删除钉住已下线功能的用例（铁律 62）——D 状态文件在 diff 里但仓内已无 ⇒ 绿
//     但豁免要求**条目自述删除语义**（MA-387）：只认 D 文件不认条目＝任意条目挂一个被删文件
//     就能绕过「文件必须在仓内存在」这道锚点。
{
  const batch = {
    ...BATCH,
    items: [
      {
        id: 'X-1（删死代码：下线旧学习链）',
        why: '旧链整链退场，连同钉住它的用例一并删除',
        files: ['services/a/A.java', 'services/a/AOldTest.java']
      }
    ]
  };
  const p = evaluateBatch(
    batch,
    ['services/a/A.java', 'services/a/AOldTest.java'],
    (rel) => rel === 'services/a/A.java',
    new Set(['services/a/AOldTest.java'])
  );
  assert(p.length === 0, `声明了删除语义的条目删 D 文件不应误报：${p.join('|')}`);
}

// 3d) 3b 的负向（MA-387）：登记 D 文件，但条目自述里没有删除语义 ⇒ 红
{
  const batch = {
    ...BATCH,
    items: [{ id: 'X-1', files: ['services/a/A.java', 'services/a/AOldTest.java'] }]
  };
  const p = evaluateBatch(
    batch,
    ['services/a/A.java', 'services/a/AOldTest.java'],
    (rel) => rel === 'services/a/A.java',
    new Set(['services/a/AOldTest.java'])
  );
  assert(
    p.length === 1 && p[0].includes('没有删除语义'),
    `条目无删除语义却挂 D 文件须红（否则幽灵文件检查被绕过）：${p.join('|')}`
  );
}

// 3c) 3b 的负向：声称删除但 diff 里没有（既非改也非删）⇒ 仍红
{
  const batch = {
    ...BATCH,
    items: [{ id: 'X-1', files: ['services/a/A.java', 'services/a/AOldTest.java'] }]
  };
  const p = evaluateBatch(
    batch,
    ['services/a/A.java'],
    (rel) => rel === 'services/a/A.java',
    new Set(['services/a/AOldTest.java'])
  );
  assert(p.length === 1 && p[0].includes('A26'), `删除文件不在 diff 仍须红：${p.join('|')}`);
}

// 3e) 未应用迁移纯重排：历史登记路径按精确例外映射到当前路径，未登记路径不得放行
{
  const exceptions = [{ from: 'V516__old.sql', to: 'V519__old.sql' }];
  assert(
    resolveCurrentPath('V516__old.sql', exceptions) === 'V519__old.sql',
    '精确登记的重排路径应映射到新路径'
  );
  assert(
    resolveCurrentPath('V517__other.sql', exceptions) === 'V517__other.sql',
    '未登记路径必须保持原样'
  );
  const batch = { ...BATCH, items: [{ id: 'X-1', files: ['V516__old.sql'] }] };
  const problems = evaluateBatch(
    batch,
    ['V516__old.sql'],
    (rel) => resolveCurrentPath(rel, exceptions) === 'V519__old.sql'
  );
  assert(problems.length === 0, `重排后的历史登记不应误报：${problems.join('|')}`);
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
