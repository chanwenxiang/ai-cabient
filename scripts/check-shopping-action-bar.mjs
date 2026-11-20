#!/usr/bin/env node
/**
 * 门禁：购物态底部动作条必须**单行**，且三个动作都在位。
 *
 * 2026-11-20 真机反馈「按钮布局有问题」：`HomeCartBar.vue` 的
 * `.live-door-actions` 原为 `max-width:58% + flex-wrap:wrap` ⇒「请关门 / 刷新状态」
 * 挤在第一行，「未出账单?」掉到第二行右对齐，看起来像漏排的第三个主按钮。
 * 修法是不换行、取消宽度上限，并把「未出账单?」降为弱化文字链。
 *
 * 判据（先脱敏注释，避免说明文字被当代码）：
 *  1) `.live-door-actions` 块必须显式 `flex-wrap: nowrap`，不得再出现 `max-width`；
 *  2) 三个动作文案仍在模板里（防「为了排整齐直接删按钮」）；
 *  3) ghost 按钮有 `.live-door-actions .cart-help-btn.ghost` 降级规则。
 */
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
export const CART_BAR_FILE = join(ROOT, 'clients/consumer-mp/src/components/HomeCartBar.vue');
const TAG = '[check-shopping-action-bar]';

export function actionBarProblems(raw) {
  const src = raw.replace(/<!--[\s\S]*?-->/g, '').replace(/\/\*[\s\S]*?\*\//g, '');
  const block = /\.live-door-actions\s*\{([^}]*)\}/.exec(src);
  const problems = [];
  if (!block) {
    problems.push('找不到 .live-door-actions 样式块（结构已变，门禁失去判别力）');
  } else {
    if (!/flex-wrap:\s*nowrap/.test(block[1])) {
      problems.push(
        '.live-door-actions 必须显式 flex-wrap: nowrap（wrap 会把「未出账单?」挤到第二行）'
      );
    }
    if (/max-width/.test(block[1])) {
      problems.push('.live-door-actions 不得设 max-width（58% 上限正是这次错位的成因）');
    }
  }
  for (const label of ['请关门', '刷新状态', '未出账单？']) {
    if (!src.includes(label)) problems.push(`模板里找不到动作「${label}」——不得靠删按钮解决布局`);
  }
  if (!/\.live-door-actions\s+\.cart-help-btn\.ghost\s*\{/.test(src)) {
    problems.push(
      '缺 .live-door-actions .cart-help-btn.ghost 降级规则（「未出账单?」会重新抢主按钮视觉重量）'
    );
  }
  return problems;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const problems = actionBarProblems(readFileSync(CART_BAR_FILE, 'utf8'));
  if (problems.length) {
    console.error(`${TAG} FAIL:`);
    for (const p of problems) console.error(`  - ${p}`);
    process.exit(1);
  }
  console.log(`${TAG} OK：底部动作条单行、三动作齐备、求助入口已降级`);
}
