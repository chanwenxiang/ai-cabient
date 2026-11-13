#!/usr/bin/env node
/** `check-compose-restart.mjs` 负向用例（S8③ 销账）：运行时服务 restart 被拆 ⇒ 红。
 *   node scripts/check-compose-restart.test.mjs */
import { writeFileSync, readFileSync } from 'node:fs';
import { createSandbox, expectGreen, expectRed } from './lib/gate-sandbox.mjs';

const TAG = '[check-compose-restart.test]';
let checks = 0;
try {
  const sb = createSandbox();
  sb.copyTree('infra', ['.yml', '.yaml']);
  await expectGreen('check-compose-restart.mjs', sb.root);
  checks++;

  const compose = sb.root + '/infra/docker-compose.full.yml';
  let src = readFileSync(compose, 'utf8');
  if (!src.includes('restart: unless-stopped'))
    throw new Error('锚点缺失：full.yml 无 unless-stopped（快照过期）');
  // 注入：网关的 restart 拆掉（R19 复发形态）
  src = src.replace('restart: unless-stopped', 'restart: "no"');
  writeFileSync(compose, src);
  await expectRed('check-compose-restart.mjs', sb.root, 'restart');
  checks++;
} finally {
  /* 沙箱留给系统清理 */
}
console.log(`${TAG} OK：${checks} 项断言全过`);
