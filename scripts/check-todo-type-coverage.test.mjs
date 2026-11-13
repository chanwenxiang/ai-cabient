#!/usr/bin/env node
/** `check-todo-type-coverage.mjs` 负向用例（S8③ 销账）：后端新增字典码未进前端快照 ⇒ 红。
 *   node scripts/check-todo-type-coverage.test.mjs */
import { writeFileSync, readFileSync } from 'node:fs';
import { createSandbox, expectGreen, expectRed } from './lib/gate-sandbox.mjs';

const TAG = '[check-todo-type-coverage.test]';
let checks = 0;
try {
  const sb = createSandbox();
  sb.copy('services/trade-service/src/main/java/com/aicabinet/trade/service/SysDictBootstrap.java');
  sb.copy('clients/merchant-mp/src/utils/todo-list.test.ts');
  await expectGreen('check-todo-type-coverage.mjs', sb.root);
  checks++;

  // 注入：后端字典加一个前端快照没有的新码
  const java =
    sb.root +
    '/services/trade-service/src/main/java/com/aicabinet/trade/service/SysDictBootstrap.java';
  let src = readFileSync(java, 'utf8');
  if (!src.includes('"CHARGE_PENDING_STALE"'))
    throw new Error('锚点缺失：CHARGE_PENDING_STALE（快照过期）');
  src = src.replace('"CHARGE_PENDING_STALE"', '"BRAND_NEW_FAKE_TYPE"');
  writeFileSync(java, src);
  await expectRed('check-todo-type-coverage.mjs', sb.root, 'BRAND_NEW_FAKE_TYPE');
  checks++;
} finally {
  /* 沙箱留给系统清理 */
}
console.log(`${TAG} OK：${checks} 项断言全过`);
