#!/usr/bin/env node
/**
 * 门禁：商户端页面「显式判无权限」之前必须先拉 me（冷启动首屏 me 必为 null）。
 *
 * 背景（2026-11-10 四端审计 §7#1）：useMerchantMe 的 onShow(refresh) 是异步，
 * 页面自己的 onShow(load) 同步先跑 ⇒ 冷启动/深链首屏 me=null，hasPerm 恒 false。
 * purchase/bills 曾因此首屏误报「没有查看账单的权限」/静默不加载（下拉/重进才自愈）。
 * 全仓正确先例是 orders/settlements 等页的 ensureXMerchantMe：判权限前 `await refreshMe()`，
 * 失败回落去权展示缓存。本门禁把该先例钉成判据，防新页再犯。
 *
 * 判据：页面 .vue 含「显式权限拒绝」模式之一——
 *   showError('无…权限') / denyXAccess( / 文案「没有…的权限」
 *   ⇒ 同文件必须出现 `await refreshMe()`（fire-and-forget 不算：首屏 load 仍先于 me 到达）。
 *
 * 边界：purchase 的「静默 return」变体无拒绝文案，静态不可判，靠同轮 ensurePurchaseAccess
 * 修复与 type-check 收口；本门禁只覆盖可静态识别的显式拒绝类。
 *
 * 用法：node scripts/check-merchant-me-gated-load.mjs
 */
import { readFileSync, readdirSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const TAG = '[check-merchant-me-gated-load]';
const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const SRC = process.env.MERCHANT_ME_GATE_ROOT
  ? process.env.MERCHANT_ME_GATE_ROOT
  : join(ROOT, 'clients', 'merchant-mp', 'src', 'pages');

const DENY_RES = [
  /showError\(\s*['"]无[^'"]*权限/,
  /\bdeny\w*Access\s*\(/,
  /['"]没有[^'"]*的权限['"]/
];
const AWAIT_REFRESH_RE = /await\s+refreshMe\s*\(/;

function walk(dir, out = []) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) {
      if (entry.name === 'node_modules' || entry.name === 'dist') continue;
      walk(full, out);
      continue;
    }
    if (!/\.vue$/.test(entry.name)) continue;
    out.push(full);
  }
  return out;
}

function lineOf(text, index) {
  let line = 1;
  for (let i = 0; i < index; i += 1) if (text[i] === '\n') line += 1;
  return line;
}

const files = walk(SRC).map((f) => ({
  full: f,
  rel: relative(ROOT, f).replace(/\\/g, '/')
}));

const MIN_FILES = Number(process.env.MERCHANT_ME_GATE_MIN_FILES || 20);
if (files.length < MIN_FILES) {
  console.error(`${TAG} FAIL: 只扫到 ${files.length} 个页面文件，扫描根疑似错误（${SRC}）`);
  process.exit(1);
}

const problems = [];
let deniedFiles = 0;
for (const { full, rel } of files) {
  const text = readFileSync(full, 'utf8');
  let hit = null;
  for (const re of DENY_RES) {
    const m = re.exec(text);
    if (m) {
      hit = m;
      break;
    }
  }
  if (!hit) continue;
  deniedFiles += 1;
  if (!AWAIT_REFRESH_RE.test(text)) {
    problems.push({
      rel,
      line: lineOf(text, hit.index),
      detail: '显式判无权限但未 await refreshMe()：冷启动首屏 me=null 会误拒'
    });
  }
}

// 判据空转 = 基线腐烂（拒绝模式整体消失/扫描根错位），不许静默绿
if (deniedFiles === 0) {
  console.error(`${TAG} FAIL: ${files.length} 个页面无一命中权限拒绝模式——判据已空转，重新对准`);
  process.exit(1);
}

if (problems.length) {
  console.error(`${TAG} FAIL: ${problems.length} 个页面判权限前未拉 me`);
  for (const p of problems) console.error(`  ${p.rel}:${p.line}  ${p.detail}`);
  console.error(
    '\n修法：判权限前 `await refreshMe()`（失败回落 seedMerchantMeDisplayCache），\n' +
      '先例见 orders.vue ensureOrdersMerchantMe / settlements.vue load。'
  );
  process.exit(1);
}

console.log(
  `${TAG} OK: ${files.length} 个页面，${deniedFiles} 个含权限拒绝的页面均在判权限前 await refreshMe()`
);
