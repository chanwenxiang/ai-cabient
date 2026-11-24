#!/usr/bin/env node
/**
 * 后台页头说明收敛门禁：解释性长文案必须只放在 HelpTip 中。
 *
 * 判据：
 *  1. page-card-head__title 内的 span.hint 不得再内嵌 HelpTip（双显反模式）；
 *  2. span.hint / page-hint / header-hint 的静态可见文本超过 24 个字符即失败，动态读数与短标签保留；
 *  3. 全仓扫描到的页头标题块少于 30 个时 fail-closed，防止目录/锚点失效假绿。
 */
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-admin-header-help]';
const ADMIN_SRC = join(ROOT, 'clients/admin-vue/src');
const MAX_STATIC_HINT_CHARS = 24;
const MIN_TITLE_BLOCKS = 30;

function walkVue(dir, out = []) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (['node_modules', 'dist', 'unpackage'].includes(entry.name)) continue;
    const full = join(dir, entry.name);
    if (entry.isDirectory()) walkVue(full, out);
    else if (entry.isFile() && entry.name.endsWith('.vue')) out.push(full);
  }
  return out;
}

export function titleBlocks(src) {
  const blocks = [];
  const startRe = /<div\b[^>]*class=["'][^"']*page-card-head__title[^"']*["'][^>]*>/g;
  let match;
  while ((match = startRe.exec(src)) !== null) {
    const openEnd = startRe.lastIndex;
    const tagRe = /<div\b[^>]*>|<\/div>/g;
    tagRe.lastIndex = openEnd;
    let depth = 1;
    let tag;
    while ((tag = tagRe.exec(src)) !== null) {
      if (tag[0].startsWith('</')) depth -= 1;
      else depth += 1;
      if (depth === 0) {
        blocks.push(src.slice(match.index, tagRe.lastIndex));
        startRe.lastIndex = tagRe.lastIndex;
        break;
      }
    }
  }
  return blocks;
}

function staticText(body) {
  return body
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/<[^>]+>/g, '')
    .replace(/\{\{[\s\S]*?\}\}/g, '')
    .replace(/\s+/g, ' ')
    .trim();
}

export function headerHelpProblems(src, rel = 'fixture.vue') {
  const problems = [];
  const blocks = titleBlocks(src);
  for (const block of blocks) {
    const hintRe = /<span\b[^>]*class=["'][^"']*\bhint\b[^"']*["'][^>]*>([\s\S]*?)<\/span>/g;
    let match;
    while ((match = hintRe.exec(block)) !== null) {
      const body = match[1];
      const line = src.slice(0, src.indexOf(block) + match.index).split(/\r?\n/).length;
      if (body.includes('<HelpTip')) {
        problems.push(`${rel}:${line} 页头 hint 内嵌 HelpTip，形成常驻说明+问号双显`);
      }
      const text = staticText(body);
      if (text.length > MAX_STATIC_HINT_CHARS) {
        problems.push(
          `${rel}:${line} 页头 hint 常驻 ${text.length} 个字符（上限 ${MAX_STATIC_HINT_CHARS}）：${text}`
        );
      }
    }
  }
  const outerHintRe =
    /<span\b[^>]*class=["'][^"']*\b(?:page-hint|header-hint)\b[^"']*["'][^>]*>([\s\S]*?)<\/span>/g;
  let outer;
  while ((outer = outerHintRe.exec(src)) !== null) {
    const body = outer[1];
    const line = src.slice(0, outer.index).split(/\r?\n/).length;
    if (body.includes('<HelpTip')) {
      problems.push(`${rel}:${line} page-hint/header-hint 内嵌 HelpTip，形成双显`);
    }
    const text = staticText(body);
    if (text.length > MAX_STATIC_HINT_CHARS) {
      problems.push(
        `${rel}:${line} page-hint/header-hint 常驻 ${text.length} 个字符（上限 ${MAX_STATIC_HINT_CHARS}）：${text}`
      );
    }
  }
  return problems;
}

function main() {
  const files = walkVue(ADMIN_SRC);
  const problems = [];
  let titleCount = 0;
  for (const file of files) {
    const src = readFileSync(file, 'utf8');
    titleCount += titleBlocks(src).length;
    problems.push(...headerHelpProblems(src, relative(ROOT, file).replace(/\\/g, '/')));
  }
  if (titleCount < MIN_TITLE_BLOCKS) {
    problems.push(`只扫描到 ${titleCount} 个页头标题块（下限 ${MIN_TITLE_BLOCKS}），锚点可能失效`);
  }
  if (problems.length) {
    console.error(`${TAG} FAIL：\n  - ` + problems.join('\n  - '));
    process.exit(1);
  }
  console.log(`${TAG} OK：${titleCount} 个页头标题块无双显反模式、无超长常驻说明`);
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  main();
}
