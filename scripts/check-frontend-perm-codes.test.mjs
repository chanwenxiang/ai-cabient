#!/usr/bin/env node
/** `check-frontend-perm-codes.mjs` 负向用例（S8③ 销账）：前端用了后端不存在的权限码 ⇒ 红。
 *   node scripts/check-frontend-perm-codes.test.mjs */
import { writeFileSync, readFileSync } from 'node:fs';
import { createSandbox, expectGreen, expectRed } from './lib/gate-sandbox.mjs';

const TAG = '[check-frontend-perm-codes.test]';
let checks = 0;
try {
  const sb = createSandbox();
  sb.copy('clients/admin-vue/src/config/menu.ts');
  sb.copy('clients/merchant-mp/src/config/merchant-nav.ts');
  sb.copy('clients/merchant-mp/src/pages.json');
  sb.copyTree('packages/shared-rbac/src', ['.ts']);
  sb.copyTree('services/trade-service/src/main/java', ['.java']);
  await expectGreen('check-frontend-perm-codes.mjs', sb.root);
  checks++;

  // 注入：前端出现后端不存在的权限码
  const menu = sb.root + '/clients/admin-vue/src/config/menu.ts';
  let src = readFileSync(menu, 'utf8');
  if (!src.includes("perm: 'ops:dashboard:view'"))
    throw new Error('锚点缺失：ops:dashboard:view（快照过期）');
  src = src.replace("perm: 'ops:dashboard:view',", "perm: 'ops:ghost:do',");
  writeFileSync(menu, src);
  await expectRed('check-frontend-perm-codes.mjs', sb.root, 'ops:ghost:do');
  checks++;
} finally {
  /* 沙箱留给系统清理 */
}
console.log(`${TAG} OK：${checks} 项断言全过`);
