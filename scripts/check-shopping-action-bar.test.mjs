#!/usr/bin/env node
/**
 * `check-shopping-action-bar.mjs` 的负向夹具（2026-11-20 真机布局反馈）。
 *
 *   node scripts/check-shopping-action-bar.test.mjs
 */
import { readFileSync } from 'node:fs';
import { CART_BAR_FILE, actionBarProblems } from './check-shopping-action-bar.mjs';

const TAG = '[check-shopping-action-bar.test]';
let checks = 0;
function assert(cond, message) {
  checks++;
  if (!cond) throw new Error(`${TAG} 断言失败：${message}`);
}

const real = readFileSync(CART_BAR_FILE, 'utf8');
assert(real.length > 2000, '真实 HomeCartBar.vue 读取失败（锚点漂移）');
assert(actionBarProblems(real).length === 0, '真实仓面必须绿');

const wrap = real.replace('flex-wrap: nowrap;', 'flex-wrap: wrap;');
assert(wrap !== real, '注入锚点 flex-wrap 找不到');
assert(
  actionBarProblems(wrap).some((p) => p.includes('flex-wrap: nowrap')),
  '恢复 wrap 必须红（这正是「未出账单?」掉第二行的成因）'
);

const width = real.replace('  flex-wrap: nowrap;', '  max-width: 58%;\n  flex-wrap: nowrap;');
assert(width !== real, '注入锚点 max-width 找不到');
assert(
  actionBarProblems(width).some((p) => p.includes('max-width')),
  '重加 58% 宽度上限必须红'
);

const dropped = real.replace('未出账单？', '');
assert(dropped !== real, '注入锚点「未出账单？」找不到');
assert(
  actionBarProblems(dropped).some((p) => p.includes('未出账单')),
  '靠删掉求助按钮换整齐必须红'
);

const promoted = real.replace('.live-door-actions .cart-help-btn.ghost', '.cart-help-btn.ghost');
assert(promoted !== real, '注入锚点 ghost 降级规则找不到');
assert(
  actionBarProblems(promoted).some((p) => p.includes('降级规则')),
  '去掉 ghost 降级、让求助重回主按钮形态必须红'
);

console.log(`${TAG} OK：${checks} 项断言全过（真树绿 / 四种注入红）`);
