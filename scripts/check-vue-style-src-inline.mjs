#!/usr/bin/env node
/**
 * 门禁：`<style src="...">` 标签里不允许再写内联 CSS。
 *
 * 背景（2026-11-02 商户端样式扫查，踩了两次）：uni-app 对
 * `<style scoped src="./x.page.css">` 只加载外部文件，**标签体内的 CSS 会被静默丢弃**——
 * 我改「销售报表」维度切换样式时写进了 .vue，编译产物里查不到规则才发现。
 * 这个错误没有任何报错，只能靠门禁拦。
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-vue-style-src-inline]';
const SCAN_ROOTS = ['clients/consumer-mp/src', 'clients/merchant-mp/src', 'clients/admin-vue/src'];

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
    for (const m of src.matchAll(/<style\b([^>]*)>([\s\S]*?)<\/style>/g)) {
      const attrs = m[1] || '';
      const body = (m[2] || '').replace(/\/\*[\s\S]*?\*\//g, '').replace(/<!--[\s\S]*?-->/g, '');
      if (!/\bsrc\s*=/.test(attrs)) continue;
      if (/\{/.test(body.trim())) {
        problems.push(relative(ROOT, file));
      }
    }
  }
}

if (problems.length) {
  console.error(
    `${TAG} FAIL: 以下 .vue 的 <style src="…"> 标签体内写了 CSS（会被 uni-app 静默丢弃）：\n` +
      [...new Set(problems)].map((p) => `  - ${p}`).join('\n') +
      '\n修复：把规则写进 src 指向的外部样式文件（如 xxx.page.css），标签体保持为空。'
  );
  process.exit(1);
}
console.log(`${TAG} OK：未发现 <style src> 标签体内写 CSS`);
