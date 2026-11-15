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
// MA-200：识别演示豁免已随 V482/页面退役删除 —— 豁免清单必须保持空，
// 且不得再把 ops:recognition-demo:view 放回来（V138 语义由按钮码 ops:sku:demo 承载）
{
  const src = readFileSync('scripts/check-menu-permissions.mjs', 'utf8');
  const allow = src.match(/const ALLOWLIST = new Map\(\[([\s\S]*?)\]\);/);
  assert_ok(allow && allow[1].trim() === '', 'menu ALLOWLIST 必须为空（MA-200 拍板）');
  assert_ok(!src.includes("'ops:recognition-demo:view'"), 'recognition-demo:view 不得回到豁免清单');
  checks++;
}
function assert_ok(cond, msg) {
  if (!cond) {
    console.error(`${TAG} FAIL：${msg}`);
    process.exit(1);
  }
}
console.log(`${TAG} OK：${checks} 项断言全过`);
