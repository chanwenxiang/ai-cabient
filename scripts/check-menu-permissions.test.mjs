#!/usr/bin/env node
/** `check-menu-permissions.mjs` 负向用例（S8③ 销账）：菜单用了后端不存在的权限码 ⇒ 红。
 *   node scripts/check-menu-permissions.test.mjs */
import { writeFileSync, readFileSync } from 'node:fs';
import { createSandbox, expectGreen, expectRed } from './lib/gate-sandbox.mjs';

const TAG = '[check-menu-permissions.test]';
let checks = 0;
try {
  const sb = createSandbox();
  sb.copy('clients/admin-vue/src/config/menu.ts');
  sb.copyTree('services/trade-service/src/main/java', ['.java']);
  await expectGreen('check-menu-permissions.mjs', sb.root);
  checks++;

  // 注入：菜单加一个后端全找不到的权限码
  const menu = sb.root + '/clients/admin-vue/src/config/menu.ts';
  let src = readFileSync(menu, 'utf8');
  if (!src.includes("perm: 'ops:dashboard:view'"))
    throw new Error('锚点缺失：ops:dashboard:view（快照过期）');
  src = src.replace("perm: 'ops:dashboard:view',", "perm: 'ops:ghost:view',");
  writeFileSync(menu, src);
  await expectRed('check-menu-permissions.mjs', sb.root, 'ops:ghost:view');
  checks++;
} finally {
  /* 沙箱留给系统清理 */
}
console.log(`${TAG} OK：${checks} 项断言全过`);
