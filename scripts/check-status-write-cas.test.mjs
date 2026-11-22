#!/usr/bin/env node
/** `check-status-write-cas.mjs` 的负向夹具（MA-396）。 */
import assert from 'node:assert/strict';
import { stripComments, methodSites, suspectSites } from './check-status-write-cas.mjs';

const TAG = '[check-status-write-cas.test]';
let checks = 0;
function ok(cond, msg) {
  checks++;
  if (!cond) throw new Error(`${TAG} 断言失败：${msg}`);
}

// 1) 嫌疑形态必须被识别（setStatus + updateById 共现）
const suspect = `
@Service class WithdrawService {
  public void failSingle(Long id) {
    Withdraw w = mapper.selectById(id);
    w.setStatus("FAILED");
    mapper.updateById(w);
  }
}
`;
const s1 = suspectSites(suspect, 'x/WithdrawService.java');
ok(
  s1.length === 1 && s1[0].key === 'x/WithdrawService.java#failSingle',
  `case1 应识别 1 站点，实得 ${JSON.stringify(s1)}`
);

// 2) 注释里的 setStatus/updateById 不得误判（先脱敏注释，铁律 84①）
const commented = `
@Service class A {
  // 历史：w.setStatus("FAILED"); mapper.updateById(w); 已改条件写入
  public void ok1(Long id) { log.info(id); }
}
`;
ok(suspectSites(commented, 'x/A.java').length === 0, 'case2 注释共现不得误判');

// 3) 只有写没有状态突变（或反之）不记站点
const writeOnly = `
@Service class B {
  public void touch(Long id) { mapper.updateById(mapper.selectById(id)); }
}
`;
ok(suspectSites(writeOnly, 'x/B.java').length === 0, 'case3 仅写无状态突变不记站点');

// 4) 块注释同理脱敏 + 跨行状态突变仍命中
const blockC = `
@Service class C {
  /**
   * 说明：setStatus 与 updateById 不能同时出现（示例文档）
   */
  public void real(Long id) {
    e.setStatus("PAID");
    mapper.update(e);
  }
}
`;
ok(suspectSites(blockC, 'x/C.java').length === 1, 'case4 块注释脱敏后真命中仍识别');

// 5) 方法名捕获：重载同名合一键（key 只含方法名不含行号，行号漂移免疫）
const overloads = `
@Service class D {
  public void mark(Long id) { e.setStatus("A"); repo.update(e); }
  public void mark(Long id, String by) { e.setStatus("B"); repo.update(e); }
}
`;
const s5 = methodSites(stripComments(overloads), 'x/D.java').filter((s) => s.key.endsWith('#mark'));
ok(s5.length === 2, `case5 重载各自成站点（同键），实得 ${s5.length}`);

// 6) 控制流语句不当方法起点（if/try 行有 { 但不是方法）
const ctrl = `
@Service class E {
  public void f(Long id) {
    if (ready) {
      e.setStatus("S");
      repo.update(e);
    }
  }
}
`;
const s6 = suspectSites(ctrl, 'x/E.java');
ok(
  s6.length === 1 && s6[0].key.endsWith('#f'),
  `case6 控制流不抢方法键，实得 ${JSON.stringify(s6)}`
);

// 7) stripComments 保持行号（等长替换，含尾换行）
const numbered = 'a // x\nb /* y\nz */ c\nd\n';
ok(stripComments(numbered).split('\n').length === numbered.split('\n').length, 'case7 脱敏保行数');

console.log(`${TAG} OK：${checks} 项断言全过`);
