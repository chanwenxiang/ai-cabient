#!/usr/bin/env node
/**
 * S14 ② 表名声明集门禁（check-script-table-set）的负向/正向夹具。
 * 直测 judge() 纯判定（与 CLI 取数解耦），覆盖：
 *   时间线自建自用 / 已 DROP 复用（硬引用与软引用）/ 未知表名 fail-closed /
 *   清库脚本引用 / 声明集对账双向 / ACK 豁免头留痕。
 */
import { judge } from './check-script-table-set.mjs';

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

// 1) 正常时间线：V1 建 a，V2 建 b 并引用 a → 绿
{
  const migs = [
    { file: 'V1__a.sql', version: '1', created: ['a'], dropped: [], hard: [], soft: [] },
    { file: 'V2__b.sql', version: '2', created: ['b'], dropped: [], hard: ['a', 'b'], soft: [] }
  ];
  const r = judge(migs, [], new Set(['a', 'b']), ['a', 'b']);
  check('正常时间线绿', r.problems.length === 0, r.problems.join('|'));
}

// 2) 已 DROP 表在后续迁移被硬引用 → 红（MA-042 家族）
{
  const migs = [
    { file: 'V1__a.sql', version: '1', created: ['a'], dropped: ['a'], hard: [], soft: [] },
    { file: 'V2__use.sql', version: '2', created: [], dropped: [], hard: ['a'], soft: [] }
  ];
  const r = judge(migs, [], new Set(), ['b']);
  check(
    '已 DROP 表被后续迁移硬引用=红',
    r.problems.some((p) => p.includes('已 DROP 的表') && p.includes('V2__use.sql')),
    r.problems.join('|')
  );
}

// 3) 未知表名（从未 CREATE）→ fail-closed 红
{
  const migs = [
    { file: 'V1__a.sql', version: '1', created: ['a'], dropped: [], hard: [], soft: [] },
    { file: 'V2__use.sql', version: '2', created: [], dropped: [], hard: ['ghost'], soft: [] }
  ];
  const r = judge(migs, [], new Set(['a']), ['a']);
  check(
    '未知表名=红（fail-closed）',
    r.problems.some((p) => p.includes('未知表')),
    r.problems.join('|')
  );
}

// 4) 同文件自建自用 → 绿（createdInFile 豁免）
{
  const migs = [
    { file: 'V1__a.sql', version: '1', created: ['a'], dropped: [], hard: ['a'], soft: [] }
  ];
  const r = judge(migs, [], new Set(['a']), ['a']);
  check('同文件自建自用=绿', r.problems.length === 0, r.problems.join('|'));
}

// 5) 清库脚本引用已 DROP 表 → 红
{
  const migs = [
    { file: 'V1__a.sql', version: '1', created: ['a'], dropped: ['a'], hard: [], soft: [] }
  ];
  const cleanups = [{ file: 'scripts/clean.ps1', hard: ['a'], content: '' }];
  const r = judge(migs, cleanups, new Set(), []);
  check(
    '清库脚本引用已 DROP 表=红',
    r.problems.some((p) => p.includes('清库脚本') && p.includes('已 DROP')),
    r.problems.join('|')
  );
}

// 6) 清库脚本引用活表 → 绿
{
  const migs = [
    { file: 'V1__a.sql', version: '1', created: ['a'], dropped: [], hard: [], soft: [] }
  ];
  const cleanups = [{ file: 'scripts/clean.ps1', hard: ['a'], content: '' }];
  const r = judge(migs, cleanups, new Set(['a']), ['a']);
  check('清库脚本引用活表=绿', r.problems.length === 0, r.problems.join('|'));
}

// 7) 声明集对账双向漂移 → 红
{
  const migs = [
    { file: 'V1__a.sql', version: '1', created: ['a'], dropped: [], hard: [], soft: [] }
  ];
  // 注册表少了 a（迁移存活但注册表未登记）
  const r1 = judge(migs, [], new Set(['a']), []);
  check(
    '注册表漏活表=红',
    r1.problems.some((p) => p.includes('retention-registry 未登记')),
    r1.problems.join('|')
  );
  // 注册表多了 ghost（登记了但迁移不存活）
  const r2 = judge(migs, [], new Set(['a']), ['a', 'ghost']);
  check(
    '注册表幽灵表=红',
    r2.problems.some((p) => p.includes('不存活')),
    r2.problems.join('|')
  );
}

// 8) ACK 豁免头 → 留痕不红
{
  const migs = [
    {
      file: 'V1__a.sql',
      version: '1',
      created: ['a'],
      dropped: ['a'],
      hard: [],
      soft: []
    },
    {
      file: 'V2__use.sql',
      version: '2',
      created: [],
      dropped: [],
      hard: ['a'],
      soft: [],
      content: '-- SCRIPT_TABLE_SET_EXEMPT: 历史遗留，已评审'
    }
  ];
  const r = judge(migs, [], new Set(), []);
  check(
    'ACK 豁免头=留痕不红',
    r.problems.length === 0 && r.acknowledged.length === 1,
    `problems=${r.problems.length} ack=${r.acknowledged.length}`
  );
}

// 9) 软引用：已 DROP 复用=红；未知软引用不罚
{
  const migs = [
    { file: 'V1__a.sql', version: '1', created: ['a'], dropped: ['a'], hard: [], soft: [] },
    { file: 'V2__use.sql', version: '2', created: [], dropped: [], hard: [], soft: ['a', 'ghost'] }
  ];
  const r = judge(migs, [], new Set(), []);
  check(
    '软引用已 DROP=红 / 未知软引用不罚',
    r.problems.some((p) => p.includes('FROM/JOIN') && p.includes('已 DROP')) &&
      !r.problems.some((p) => p.includes('ghost')),
    r.problems.join('|')
  );
}

// 10) DROP→重建序列：终态存活，后续引用不罚（状态机，非集合差）
{
  const migs = [
    { file: 'V1__a.sql', version: '1', created: ['a'], dropped: [], hard: [], soft: [] },
    { file: 'V2__drop.sql', version: '2', created: [], dropped: ['a'], hard: [], soft: [] },
    { file: 'V3__recreate.sql', version: '3', created: ['a'], dropped: [], hard: [], soft: [] },
    { file: 'V4__use.sql', version: '4', created: [], dropped: [], hard: ['a'], soft: [] }
  ];
  const r = judge(migs, [], new Set(['a']), ['a']);
  check('DROP→重建终态存活，后续引用不罚', r.problems.length === 0, r.problems.join('|'));
}

// 11) DROP→重建但引用点在重建之前 = 死引用（红）
{
  const migs = [
    { file: 'V1__a.sql', version: '1', created: ['a'], dropped: [], hard: [], soft: [] },
    { file: 'V2__drop.sql', version: '2', created: [], dropped: ['a'], hard: [], soft: [] },
    { file: 'V3__use.sql', version: '3', created: [], dropped: [], hard: ['a'], soft: [] },
    { file: 'V4__recreate.sql', version: '4', created: ['a'], dropped: [], hard: [], soft: [] }
  ];
  const r = judge(migs, [], new Set(['a']), ['a']);
  check(
    '引用点在重建之前=死引用红',
    r.problems.some((p) => p.includes('V3__use.sql') && p.includes('已 DROP')),
    r.problems.join('|')
  );
}

console.log(`\n[check-script-table-set.test] ${pass} 通过 / ${fail} 失败`);
if (fail > 0) process.exit(1);
