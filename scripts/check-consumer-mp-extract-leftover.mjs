#!/usr/bin/env node
/**
 * 消费者首页抽取防残留：购物车条已迁到 HomeCartBar，
 * pages/index/index.vue 再留一份 class="cart-bar" 会叠出巨型图标 + 双「查看清单/关门结算」。
 */
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const TAG = '[check-consumer-mp-extract-leftover]';
const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const index = join(root, 'clients/consumer-mp/src/pages/index/index.vue');
const src = readFileSync(index, 'utf8');
const problems = [];

if (/class=["']cart-bar["']/.test(src)) {
  problems.push(
    'index.vue 仍含 class="cart-bar"：底栏已抽到 HomeCartBar，页面禁止留底稿（会叠两套按钮）'
  );
}
if (/class=["']cart-icon["']/.test(src)) {
  problems.push('index.vue 仍含 class="cart-icon"：无组件样式时 SVG 会撑满屏');
}

if (problems.length) {
  console.error(`${TAG} FAIL`);
  for (const p of problems) console.error(` - ${p}`);
  process.exit(1);
}
console.log(`${TAG} ok`);
