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
  actionBarProblems(wrap).some((p) => p.includes('不换行')),
  '恢复 wrap 必须红'
);

const width = real.replace('  flex-wrap: nowrap;', '  max-width: 58%;\n  flex-wrap: nowrap;');
assert(width !== real, '注入锚点 max-width 找不到');
assert(
  actionBarProblems(width).some((p) => p.includes('max-width')),
  '重加宽度上限必须红'
);

const dropped = real.replaceAll('未出账单？', '');
assert(dropped !== real, '注入锚点「未出账单？」找不到');
assert(
  actionBarProblems(dropped).some((p) => p.includes('未出账单')),
  '靠删掉求助按钮换整齐必须红'
);

const promoted = real.replace('.cart-help-btn.ghost {', '.cart-help-btn {');
assert(promoted !== real, '注入锚点 ghost 次操作规则找不到');
assert(
  actionBarProblems(promoted).some((p) => p.includes('次操作')),
  '去掉 ghost 次操作规则必须红'
);

const flat = real.replace('flex-direction: column;', 'flex-direction: row;');
assert(flat !== real, '注入锚点购物态分层找不到');
assert(
  actionBarProblems(flat).some((p) => p.includes('flex-direction: column')),
  '把购物车、状态、按钮重新混排必须红'
);

const smallHit = real.replace(
  '.cart-help-btn {\n  margin: 0;\n  padding: 0 24rpx;\n  min-width: 88rpx;\n  min-height: 88rpx;',
  '.cart-help-btn {\n  margin: 0;\n  padding: 0 24rpx;\n  min-width: 88rpx;\n  min-height: 64rpx;'
);
assert(smallHit !== real, '注入锚点 88rpx 热区找不到');
assert(
  actionBarProblems(smallHit).some((p) => p.includes('88rpx')),
  '缩小主操作热区必须红'
);

const nakedColor = real.replace('background: var(--card-bg);', 'background: #ffffff;');
assert(nakedColor !== real, '注入锚点令牌背景找不到');
assert(
  actionBarProblems(nakedColor).some((p) => p.includes('令牌')),
  '新增裸色值必须红'
);

console.log(`${TAG} OK：${checks} 项断言全过（真树绿 / 七种注入红）`);
