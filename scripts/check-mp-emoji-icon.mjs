/**
 * mp 图标字形门禁：禁止在**渲染内容**里使用彩色 emoji 象形符。
 *
 * 背景（2026-11-03 实测订正）：计划文档原写「mp 41 个文件的 emoji 字形」，实为把源码注释里的
 * 标记符号（🔴 ⇒ →）也算了进去。剥掉注释后：彩色 emoji（U+1F300–U+1FAFF / 区域指示符 /
 * U+FE0F emoji 呈现选择符）在渲染内容里 **0 处**；剩下 17 文件 35 个是 `✓ → ★ ☑  ↑ ↩ `
 * 这类单色排版符号，与 CSS 自绘 `app-icon` 观感一致 ⇒ 允许保留，不做批量替换。
 *
 * 所以本门禁是「硬禁新增」而非棘轮：基线恒为 0。要新增图形图标请走 `app-icon` CSS 形态或
 * shared-uni 组件，别用彩色 emoji（微信端字体渲染不一致、无法随主题着色、读屏会念出符号名）。
 *
 * 自检：`--selftest` 验「彩色 emoji 被拦 / 注释里的被忽略 / 单色符号放行」。
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';

const ROOT = process.cwd();
const SCOPES = ['clients/consumer-mp/src', 'clients/merchant-mp/src', 'packages/shared-uni/src'];
/** 彩色 emoji：象形区段 + 区域指示符 + emoji 呈现选择符 VS16 */
const COLOR_EMOJI_RE = /[\u{1F300}-\u{1FAFF}\u{1F1E6}-\u{1F1FF}\u{FE0F}]/gu;

function isDir(p) {
  try {
    return statSync(p).isDirectory();
  } catch {
    return false;
  }
}

function walk(dir, out = []) {
  if (!isDir(dir)) return out;
  for (const name of readdirSync(dir)) {
    if (name === 'node_modules' || name === 'dist') continue;
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (/\.(vue|css)$/.test(p)) out.push(p);
  }
  return out;
}

/** 剥注释：HTML 注释 / C 块注释 / 行注释——源码里的说明性 emoji 不算渲染内容 */
export function stripComments(text) {
  let t = text.replace(/<!--[\s\S]*?-->/g, ' ');
  t = t.replace(/\/\*[\s\S]*?\*\//g, ' ');
  t = t.replace(/(^|[\n;{}])\s*\/\/[^\n]*/g, '$1 ');
  return t;
}

export function findColorEmoji(text) {
  COLOR_EMOJI_RE.lastIndex = 0;
  return COLOR_EMOJI_RE.exec(stripComments(text)) !== null;
}

function selfTest() {
  const cases = [
    { text: '<view>🎉</view>', want: true, why: '模板里的彩色 emoji 必须被拦' },
    { text: '/* 说明：🎉 只是注释 */', want: false, why: '注释里的 emoji 不算渲染内容' },
    { text: '<!-- 🎉 -->', want: false, why: 'HTML 注释不算' },
    { text: '// 🎉 行注释', want: false, why: '行注释不算' },
    { text: '<text>✓</text>', want: false, why: '单色排版符号放行（存量 35 个的口径）' },
    { text: '.a::before{content:"\\2192"}', want: false, why: 'CSS 转义箭头放行' },
    { text: '<text>A\u{FE0F}B</text>', want: true, why: 'VS16 emoji 呈现选择符也拦' }
  ];
  let bad = 0;
  for (const c of cases) {
    const got = findColorEmoji(c.text);
    const ok = got === c.want;
    if (!ok) bad++;
    console.log(`  [${ok ? 'ok' : 'BAD'}] ${c.why} ⇒ ${got}`);
  }
  if (bad) {
    console.error(`[selftest] FAIL（${bad} 条不符）`);
    process.exit(1);
  }
  console.log('[selftest] 判据用例通过');
}

function main() {
  if (process.argv.includes('--selftest')) return selfTest();
  const files = SCOPES.flatMap((s) => walk(join(ROOT, s)));
  const hits = [];
  for (const f of files) {
    const text = readFileSync(f, 'utf8');
    if (!COLOR_EMOJI_RE.test(stripComments(text))) continue;
    COLOR_EMOJI_RE.lastIndex = 0;
    const shown = (stripComments(text).match(COLOR_EMOJI_RE) || []).slice(0, 4).join(' ');
    hits.push(`${relative(ROOT, f).replaceAll('\\', '/')} [${shown}]`);
  }
  if (hits.length) {
    console.error(
      `[check-mp-emoji-icon] FAIL：渲染内容出现彩色 emoji ${hits.length} 个文件。\n` +
        hits.map((h) => `  - ${h}`).join('\n') +
        `\n  改用 app-icon CSS 形态或 shared-uni 组件（微信端 emoji 字体渲染不一致、不能随主题着色、读屏会念符号名）。`
    );
    process.exit(1);
  }
  console.log(`[check-mp-emoji-icon] OK：渲染内容无彩色 emoji（扫 ${files.length} 文件，基线 0）`);
}

main();
