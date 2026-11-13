#!/usr/bin/env node
/** `check-scheduling-vs-db-pool.mjs` 负向用例（S8③ 销账）：并发×2 > 连接池 ⇒ 红（R19-20）。
 *   node scripts/check-scheduling-vs-db-pool.test.mjs */
import { writeFileSync, readFileSync } from 'node:fs';
import { createSandbox, expectGreen, expectRed } from './lib/gate-sandbox.mjs';

const TAG = '[check-scheduling-vs-db-pool.test]';
let checks = 0;
try {
  const sb = createSandbox();
  sb.copy('services/trade-service/src/main/resources/application.yml');
  sb.copy('services/trade-service/src/test/resources/application-test.yml');
  // 门禁还扫 services 主源码确认 @Scheduled 存在（判据退化防线）
  sb.copyTree('services/trade-service/src/main/java', ['.java']);
  await expectGreen('check-scheduling-vs-db-pool.mjs', sb.root);
  checks++;

  // 注入：连接池 20→10（8×2=16 > 10，R19-20 假绿转红形态）
  const yml = sb.root + '/services/trade-service/src/main/resources/application.yml';
  let src = readFileSync(yml, 'utf8');
  if (!src.includes('${DB_POOL_SIZE:20}'))
    throw new Error('锚点缺失：DB_POOL_SIZE 默认 20（快照过期）');
  src = src.replace('${DB_POOL_SIZE:20}', '${DB_POOL_SIZE:10}');
  writeFileSync(yml, src);
  await expectRed('check-scheduling-vs-db-pool.mjs', sb.root, '连接池');
  checks++;
} finally {
  /* 沙箱留给系统清理 */
}
console.log(`${TAG} OK：${checks} 项断言全过`);
