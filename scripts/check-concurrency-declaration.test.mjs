#!/usr/bin/env node
/**
 * S13 diff 级并发声明门禁（check-concurrency-declaration）的正负夹具。
 * 直测 judge() 纯判定：写方法无策略=红 / 三策略（条件UPDATE/乐观锁/显式锁）或锚点=绿 /
 * 非写方法不检查。
 */
import { judge, parseDiffHunks } from './check-concurrency-declaration.mjs';

let pass = 0;
let fail = 0;
function check(name, cond, detail = '') {
  if (cond) {
    pass += 1;
    console.log(`  ✓ ${name}`);
  } else {
    fail += 1;
    console.error(`  ✗ ${name}${detail ? ' —— ' + detail : ''}`);
  }
}

// 写方法模板：含领域词 + 写操作
const WRITE_METHOD = `    public void updateStock(String deviceId) {
        inventoryMapper.save(lot);
    }`;

// 1) 写方法无策略 → 红
{
  const files = [
    {
      file: 'X.java',
      content: `class X {\n${WRITE_METHOD}\n}`,
      changedRanges: [{ start: 2, end: 4 }]
    }
  ];
  const r = judge(files);
  check('写方法无策略=红', r.problems.length === 1, r.problems.join('|'));
}

// 2) 写方法有 CONCURRENCY 锚点 → 绿
{
  const content = `class X {\n    public void updateStock(String deviceId) {\n        // CONCURRENCY: 条件UPDATE\n        inventoryMapper.save(lot);\n    }\n}`;
  const r = judge([{ file: 'X.java', content, changedRanges: [{ start: 2, end: 5 }] }]);
  check('CONCURRENCY 锚点=绿', r.problems.length === 0, r.problems.join('|'));
}

// 3) 写方法有原子方法（条件 UPDATE 策略）→ 绿
{
  const content = `class X {\n    public void updateStock(String deviceId) {\n        lotRepository.deductQuantityAtomic(lotId, 1);\n    }\n}`;
  const r = judge([{ file: 'X.java', content, changedRanges: [{ start: 2, end: 4 }] }]);
  check('原子方法（条件UPDATE）=绿', r.problems.length === 0, r.problems.join('|'));
}

// 4) 写方法有显式锁 → 绿
{
  const content = `class X {\n    public void updateStock(String deviceId) {\n        if (!distributedLockService.tryLock(lockKey, 60, 5)) return;\n        inventoryMapper.save(lot);\n    }\n}`;
  const r = judge([{ file: 'X.java', content, changedRanges: [{ start: 2, end: 5 }] }]);
  check('显式锁=绿', r.problems.length === 0, r.problems.join('|'));
}

// 5) 写方法有乐观锁 version → 绿
{
  const content = `class X {\n    public void updateStock(String deviceId) {\n        int rows = inventoryMapper.updateById(entity); // version = version + 1\n    }\n}`;
  const r = judge([{ file: 'X.java', content, changedRanges: [{ start: 2, end: 4 }] }]);
  check('乐观锁 version=绿', r.problems.length === 0, r.problems.join('|'));
}

// 6) 非写方法（无领域词）→ 不检查，绿
{
  const content = `class X {\n    public void renderPage(String deviceId) {\n        view.render(deviceId);\n    }\n}`;
  const r = judge([{ file: 'X.java', content, changedRanges: [{ start: 2, end: 4 }] }]);
  check('非写方法不检查=绿', r.problems.length === 0 && r.checked === 0, r.problems.join('|'));
}

// 7) 无变更范围 → 绿
{
  const r = judge([{ file: 'X.java', content: 'class X {}', changedRanges: [] }]);
  check('无变更范围=绿', r.problems.length === 0, r.problems.join('|'));
}

// 8) parseDiffHunks 解析 @@ 行
{
  const ranges = parseDiffHunks('@@ -1,3 +5,7 @@\n context\n-old\n+new\n');
  check(
    'parseDiffHunks 正确',
    ranges.length === 1 && ranges[0].start === 5 && ranges[0].end === 11,
    JSON.stringify(ranges)
  );
}

console.log(`\n[check-concurrency-declaration.test] ${pass} 通过 / ${fail} 失败`);
if (fail > 0) process.exit(1);
