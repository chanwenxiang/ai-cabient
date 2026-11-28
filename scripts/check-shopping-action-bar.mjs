#!/usr/bin/env node
/**
 * 门禁：购物态底部动作条必须区分「购物车 / 状态 / 主操作 / 次操作」四层。
 *
 * 2026-11-27 #54：旧版把「请关门」状态 chip 与两个按钮横排，视觉上像三个同级操作，
 * 窄屏还会错位。现按两行结构钉死：第一行购物车，第二行左侧状态、右侧主次按钮。
 */
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
export const CART_BAR_FILE = join(ROOT, 'clients/consumer-mp/src/components/HomeCartBar.vue');
const TAG = '[check-shopping-action-bar]';

export function actionBarProblems(raw) {
  const src = raw.replace(/<!--[\s\S]*?-->/g, '').replace(/\/\*[\s\S]*?\*\//g, '');
  const block = (selector) => new RegExp(`\\${selector}\\s*\\{([^}]*)\\}`).exec(src);
  const problems = [];
  const shopping = block('.cart-bar--shopping');
  const live = block('.live-door-actions');
  const buttons = block('.door-action-buttons');
  const primary = block('.cart-help-btn');
  const ghost = block('.cart-help-btn.ghost');
  if (!shopping || !/flex-direction:\s*column/.test(shopping[1])) {
    problems.push('购物态必须按行分层（.cart-bar--shopping 使用 flex-direction: column）');
  }
  if (!src.includes('class="shopping-main-row"') || !src.includes('class="door-status"')) {
    problems.push('缺少购物车主行或独立状态区，不能把状态 chip 与按钮混排');
  }
  if (!live || !/flex-wrap:\s*nowrap/.test(live[1]) || /max-width/.test(live[1])) {
    problems.push('.live-door-actions 必须不换行且不得设置 max-width');
  }
  if (!buttons || !/display:\s*flex/.test(buttons[1])) {
    problems.push('缺少 .door-action-buttons，主次操作必须独立成组');
  }
  for (const label of ['请关门', '刷新状态', '未出账单？']) {
    if (!src.includes(label)) problems.push(`模板里找不到动作「${label}」——不得靠删按钮解决布局`);
  }
  if (!primary || !/min-height:\s*88rpx/.test(primary[1])) {
    problems.push('主操作热区必须显式 min-height: 88rpx');
  }
  if (!ghost || !/background:\s*var\(--card-bg\)/.test(ghost[1])) {
    problems.push('次操作必须使用卡片色描边按钮，不能升级成实心主按钮');
  }
  if (/text-decoration:\s*underline/.test(src)) {
    problems.push('次操作使用按钮而非下划线文字链，避免与状态文案混排');
  }
  if (!src.includes('role="status"') || !src.includes('aria-live="polite"')) {
    problems.push('关门状态必须提供读屏 live-region');
  }
  if (
    !src.includes('aria-label="查看购物车"') ||
    !src.includes('aria-label="关门后未出账单？获取帮助"')
  ) {
    problems.push('购物车与求助按钮缺少明确 a11y 名称');
  }
  const style = src.match(/<style scoped>[\s\S]*?<\/style>/)?.[0] || '';
  if (/#[0-9a-fA-F]{3,8}\b|rgba?\(/i.test(style)) {
    problems.push('HomeCartBar 样式必须全部使用项目令牌，禁止新增裸色值');
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
  console.log(`${TAG} OK：购物车主行/状态/主次操作分层、88rpx 热区与 a11y 齐备`);
}
