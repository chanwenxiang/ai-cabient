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

console.log(`${TAG} OK：${checks} 项断言全过`);
