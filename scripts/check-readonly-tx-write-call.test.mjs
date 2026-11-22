#!/usr/bin/env node
/** `check-readonly-tx-write-call.mjs` 的负向夹具（追踪号 046be09544ac）。 */
import { readOnlyWriteProblems } from './check-readonly-tx-write-call.mjs';

const TAG = '[check-readonly-tx-write-call.test]';
let checks = 0;
function assert(cond, message) {
  checks++;
  if (!cond) throw new Error(`${TAG} 断言失败：${message}`);
}

const bad = `
@Service class X {
  @Transactional(readOnly = true)
  public Dto summary(Long userId) {
    Member m = memberService.getMemberByUserId(userId)
      .orElseGet(() -> memberService.createMember(userId));
    return toDto(m);
  }
}`;
const good = bad.replace('@Transactional(readOnly = true)', '@Transactional');
const commentOnly = bad.replace(
  '@Transactional(readOnly = true)',
  '// @Transactional(readOnly = true) 历史说明：曾经这样写，已修'
);

assert(
  readOnlyWriteProblems(bad).some((p) => p.includes('createMember')),
  '注入实炸形态必须红'
);
assert(readOnlyWriteProblems(good).length === 0, '改成可写事务必须绿');
assert(readOnlyWriteProblems(commentOnly).length === 0, '注释里的旧写法不得误判（先脱敏注释）');
assert(readOnlyWriteProblems('class Empty {}').length === 0, '无注解文件必须绿');

// ── MA-393 写入族扩展（2026-11-22）：Tier A 无歧义 MP 写 API ──
const mpInsert = bad.replace('createMember(userId)', 'skuMapper.insert(entity)');
const mpUpdate = bad.replace('createMember(userId)', 'baseMapper.updateById(entity)');
assert(
  readOnlyWriteProblems(mpInsert).some((p) => p.includes('MyBatis-Plus')),
  'Tier A：readOnly 内 .insert( 必须红'
);
assert(
  readOnlyWriteProblems(mpUpdate).some((p) => p.includes('MyBatis-Plus')),
  'Tier A：readOnly 内 .updateById( 必须红'
);
assert(
  readOnlyWriteProblems(good.replace('createMember(userId)', 'skuMapper.insert(entity)')).length ===
    0,
  'Tier A：可写事务内 .insert( 必须绿'
);

// ── Tier B：service 层建档族 ──
const famSave = bad.replace('createMember(userId)', 'orderService.saveOrder(command)');
assert(
  readOnlyWriteProblems(famSave).some((p) => p.includes('saveOrder')),
  'Tier B：readOnly 内 saveOrder( 必须红'
);

// ── Tier B 白名单：校准出的在内存族必须绿（POI 建表 / removeIf）──
const poi = bad.replace(
  'createMember(userId)',
  'sheet.createRow(0).createCell(1).setCellStyle(wb.createCellStyle())'
);
assert(readOnlyWriteProblems(poi).length === 0, 'Tier B：POI 建表在内存族必须绿（白名单）');
const removeIf = bad.replace('createMember(userId)', 'rows.removeIf(r -> r.isStale())');
assert(
  readOnlyWriteProblems(removeIf).length === 0,
  'Tier B：List.removeIf 在内存族必须绿（白名单）'
);

// ── 白名单不得豁免其它写入族：同方法体 POI + saveOrder 并存时 saveOrder 仍红 ──
const mixed = bad.replace(
  'createMember(userId)',
  'sheet.createRow(0);\n    orderService.saveOrder(command)'
);
assert(
  readOnlyWriteProblems(mixed).some((p) => p.includes('saveOrder')),
  '白名单不得豁免同方法体的其它写入族'
);

console.log(`${TAG} OK：${checks} 项断言全过`);
