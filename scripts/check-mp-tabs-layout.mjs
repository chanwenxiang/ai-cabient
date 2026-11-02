#!/usr/bin/env node
/**
 * 门禁：<app-underline-tabs> 必须显式声明 layout。
 *
 * 背景（2026-11-02 商户端样式扫查）：组件默认 `layout="auto"` —— ≤5 项**等分**，
 * 5 项且标签较长时每格被压成「已…」「争…」（订单页实测），文字直接不可读。
 * 等分还是横滑是**页面级决策**，不允许靠默认值碰运气；显式写 layout 才能被 review 看见。
 * 合法值：auto / equal / scroll。
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-mp-tabs-layout]';
const SCAN_ROOTS = ['clients/consumer-mp/src', 'clients/merchant-mp/src'];
const ALLOWED = new Set(['auto', 'equal', 'scroll']);

function* walk(dir) {
  let entries;
  try {
    entries = readdirSync(dir);
  } catch {
    return;
  }
  for (const name of entries) {
    if (name === 'node_modules' || name === 'dist') continue;
    const p = join(dir, name);
    const st = statSync(p);
    if (st.isDirectory()) yield* walk(p);
    else if (name.endsWith('.vue')) yield p;
  }
}

const problems = [];
for (const rel of SCAN_ROOTS) {
  for (const file of walk(join(ROOT, rel))) {
    const src = readFileSync(file, 'utf8');
    for (const m of src.matchAll(/<app-underline-tabs\b([\s\S]*?)(\/>|>)/g)) {
      const attrs = m[1] || '';
      const layout = /\blayout\s*=\s*["']([^"']+)["']/.exec(attrs);
      if (!layout) {
        problems.push(`${relative(ROOT, file)}（缺 layout）`);
      } else if (!ALLOWED.has(layout[1])) {
        problems.push(`${relative(ROOT, file)}（layout="${layout[1]}" 非法）`);
      }
    }
  }
}

if (problems.length) {
  console.error(
    `${TAG} FAIL: 以下 <app-underline-tabs> 未显式声明合法 layout（auto/equal/scroll）：\n` +
      problems.map((p) => `  - ${p}`).join('\n') +
      '\n说明：5 项以上等分会被压成省略号；横滑请写 layout="scroll"。'
  );
  process.exit(1);
}
console.log(`${TAG} OK：<app-underline-tabs> 均显式声明了合法 layout`);
