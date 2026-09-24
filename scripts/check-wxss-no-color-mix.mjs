#!/usr/bin/env node
/**
 * 门禁：禁止在小程序 WXSS 里使用 `color-mix()`（审计 WX-01）。
 *
 * 原理：`color-mix()` 是 CSS Color 5 函数，**微信小程序 WXSS 不支持**。
 * 更隐蔽的是：它不会像「不认识的属性」那样被整条丢弃，而是**把该属性重置为初始值**
 * （真机实测：`background: color-mix(...)` 渲染成 `rgba(0, 0, 0, 0)` 即透明；
 *  且「静态值 + color-mix 双声明」也救不回——含 color-mix 的那条会重置属性）。
 * ⇒ 只能写**静态值**（rgba/hex）。代价是失去按 CSS 变量跟主题的能力，
 *    但两个小程序的主题变量（`--warning` / `--danger` / `--white`）均为静态值、
 *    且无暗色切换，故静态值是等价替换。
 *
 * 判定落点＝**真正编译成 WXSS 的那几句**：`.vue` 的 `<style>` 块（含 lang="scss"）+ 裸 `.scss`/`.css`/`.wxss`。
 * 先把注释整段抹成**等长空白（保留换行）**，再按行判定 —— 这样
 *  ① 报出的行号与原文一一对应；② 「说明注释里提到 color-mix」不会误命中
 *  （本仓已踩过：直接 grep 会把注释算进计数）。
 *
 * ⚠️ 不扫 `clients/admin-vue` 与各端 `dist/`：前者是浏览器 Web 端，`color-mix()` 在现代浏览器
 *    完全合法；后者是构建产物，不该有独立判据。
 */
import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { join, dirname, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');

/** 强制范围：本次修复范围（蓝本 + 商家小程序），命中即失败。 */
const ENFORCED = ['packages/shared-uni/src', 'clients/merchant-mp/src'];
/**
 * 已知欠账（**不静默豁免**）：消费端小程序同名缺陷尚未修。
 * 原因：`clients/consumer-mp` 正被另一会话改造（`index.vue` / `marketing` / `nearby` / `recharge`
 * 等均在其在制品内），并发改写同一批 `<style>` 会互相覆盖；且该项超出「商家后台」任务范围。
 * 命中时**逐条播报**（不阻断），待其修完后把该目录移入 ENFORCED。
 */
const PENDING = [
  { dir: 'clients/consumer-mp/src', reason: '并发会话在制品 + 超出本次商家后台范围' }
];

const COLOR_MIX = /color-mix\s*\(/i;

/** 把注释整段抹成等长空白（**保留换行**）⇒ 行号不变，注释内容不再可命中。 */
const blankKeepNewlines = (m) => m.replace(/[^\n]/g, ' ');
function stripComments(text) {
  return (
    text
      .replace(/<!--[\s\S]*?-->/g, blankKeepNewlines)
      .replace(/\/\*[\s\S]*?\*\//g, blankKeepNewlines)
      // 行注释只认「前置空白 + //」，以免误伤 `url(http://…)`
      .replace(/[ \t]*\/\/.*$/gm, '')
  );
}

function* walk(dir) {
  if (!existsSync(dir)) return;
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) yield* walk(p);
    else yield p;
  }
}

/** 带行号扫描：只判定「会编译成 WXSS」的行。 */
function scan(base) {
  const hits = [];
  for (const file of walk(join(ROOT, base))) {
    if (!/\.(vue|scss|css|wxss)$/.test(file)) continue;
    const rel = file
      .slice(ROOT.length + 1)
      .split(sep)
      .join('/');
    const isVue = file.endsWith('.vue');
    const lines = stripComments(readFileSync(file, 'utf8')).split(/\r?\n/);

    // .vue 只认 <style> 块内的行；裸样式文件整份都算。
    let inStyle = !isVue;
    lines.forEach((line, idx) => {
      if (isVue) {
        if (/<style\b/i.test(line)) inStyle = true;
        if (!inStyle) return;
      }
      if (COLOR_MIX.test(line)) hits.push(`${rel}:${idx + 1}  ${line.trim().slice(0, 92)}`);
      if (isVue && /<\/style>/i.test(line)) inStyle = false;
    });
  }
  return hits;
}

const violations = [];
for (const base of ENFORCED) violations.push(...scan(base));

const pendingHits = [];
for (const { dir, reason } of PENDING) {
  const hits = scan(dir);
  if (hits.length) pendingHits.push({ dir, reason, hits });
}

if (pendingHits.length) {
  console.warn('[check-wxss-no-color-mix] ⚠️  未强制范围存在同类缺陷（已知欠账，不阻断）：');
  for (const { dir, reason, hits } of pendingHits) {
    console.warn(`  - ${dir}：${hits.length} 处（${reason}）`);
    for (const h of hits.slice(0, 5)) console.warn(`      ${h}`);
    if (hits.length > 5) console.warn(`      … 另 ${hits.length - 5} 处`);
  }
}

if (violations.length > 0) {
  console.error(
    `[check-wxss-no-color-mix] FAIL：WXSS 里出现 color-mix()（小程序不支持，会把该属性重置为初始值）：${violations.length} 处`
  );
  for (const v of violations) console.error(`  - ${v}`);
  console.error(
    '  修法：改为**静态值**（rgba/hex）。⚠️ 不要写「静态值 + color-mix」双声明——实测会重置属性。'
  );
  console.error(
    '  ⚠️ 共享组件（packages/shared-uni/src/components）须**先改蓝本再改副本**，否则副本会被同步覆盖。'
  );
  process.exit(1);
}
console.log(`[check-wxss-no-color-mix] OK：强制范围（${ENFORCED.join(' / ')}）无 color-mix()`);
