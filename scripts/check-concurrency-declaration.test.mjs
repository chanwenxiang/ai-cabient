#!/usr/bin/env node
/**
 * S13 diff 级并发声明门禁（check-concurrency-declaration）的正负夹具。
 * 直测 judge() 纯判定：写方法无策略=红 / 三策略（条件UPDATE/乐观锁/显式锁）或锚点=绿 /
 * 非写方法不检查。
 */
import { isMethodSignature, judge, parseDiffHunks } from './check-concurrency-declaration.mjs';

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

// 9) 方法声明判据：链式调用/赋值 lambda 不得冒充方法起点
//    （2026-11-15 实测：只读取数方法被报成「写方法 orElseGet」，根因就是旧近似谓词把
//     `DeviceSkuInventory inv = existing.orElseGet(() -> {` 当成签名行）
{
  check(
    '赋值+lambda 行不是方法声明',
    isMethodSignature('        DeviceSkuInventory inv = existing.orElseGet(() -> {') === false
  );
  check(
    '链式续行不是方法声明',
    isMethodSignature('                .map(inv -> toDto(inv))') === false
  );
  check('裸调用不是方法声明', isMethodSignature('        save(entity)') === false);
  check(
    '真声明（泛型参数+集合形参）=方法声明',
    isMethodSignature(
      '    public List<DeviceInventoryDto> listInventory(java.util.Collection<String> deviceIds, boolean low) {'
    ) === true
  );
  check(
    '真声明（private static 泛型）=方法声明',
    isMethodSignature('    private static <T> T cast(Object o) {') === true
  );
}

// 10) 方法前导归属：hunk 落在 javadoc/注解上时，不能算到「上一个方法」头上
//     （旧实现只找「签名行 ≤ 变更行」，给只读方法加 javadoc 会把变更算进上面的写方法）
{
  const content = [
    'class X {',
    '    public void updateStock(String deviceId) {',
    '        inventoryMapper.save(lot);',
    '    }',
    '',
    '    /** 只读：不碰任何写 */',
    '    public void renderInventory(String deviceId) {',
    '        view.render(deviceId);',
    '    }',
    '}'
  ].join('\n');
  // 变更行 = javadoc 那一行（6）与签名行（7）
  const r = judge([{ file: 'X.java', content, changedRanges: [{ start: 6, end: 7 }] }]);
  check(
    'javadoc 前导归给下面的方法（不误判成上面的写方法）',
    r.problems.length === 0 && r.checked === 0,
    r.problems.join('|')
  );
}

// 11) 多行签名（参数换行）：hunk 落在体内时必须归属到**它自己**，不能算到上一个方法头上
//     （2026-11-23 实测形态：改 `finalizePayout(long requestId,\n PayoutResult result)`，
//      报成「写方法 markPaying 无并发策略」——真凶没被点名，有证据的方法反而背锅）
{
  const head = [
    'class X {',
    '    public void settle(long id) {',
    '        // CONCURRENCY: 显式锁 lockKey=wallet',
    '        walletMapper.updateById(row);',
    '    }',
    '',
    '    public void payout(long requestId,'
  ];
  const tailBad = [
    '                      Result result) {',
    '        walletMapper.updateById(row);',
    '    }',
    '}'
  ];
  const bad = judge([
    {
      file: 'X.java',
      content: [...head, ...tailBad].join('\n'),
      changedRanges: [{ start: 9, end: 9 }]
    }
  ]);
  check(
    '多行签名方法被正确归属（不误报成上一个方法）',
    bad.problems.length === 1 &&
      bad.problems[0].includes('payout') &&
      !bad.problems[0].includes('settle'),
    bad.problems.join('|')
  );
  const tailGood = [
    '                      Result result) {',
    '        // CONCURRENCY: 条件 UPDATE',
    '        walletMapper.updateById(row);',
    '    }',
    '}'
  ];
  const good = judge([
    {
      file: 'X.java',
      content: [...head, ...tailGood].join('\n'),
      changedRanges: [{ start: 9, end: 9 }]
    }
  ]);
  check('多行签名方法带锚点=绿', good.problems.length === 0, good.problems.join('|'));
}

console.log(`\n[check-concurrency-declaration.test] ${pass} 通过 / ${fail} 失败`);
if (fail > 0) process.exit(1);
