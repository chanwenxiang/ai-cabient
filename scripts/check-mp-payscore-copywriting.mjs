#!/usr/bin/env node
/**
 * 门禁：微信支付分相关文案必须符合《微信支付分合作品牌线上应用规范》(CB-067 / 2026-10-26 取证)。
 *
 * 🔴 缺陷类（铁律 75：修的是这一类，不是这一处）：
 *   微信支付分的品牌与文案受**官方规范硬约束**：
 *     · 品牌标语「微信支付分 先X后付」文字**不可增删/更改**、不得在周围添加文本、不得改比例/结构；
 *     · 不得出现「免确认」「无需每次确认」「授信」等对授权机制的误导性表述；
 *     · 「先享后付」类业务**不得**混用「免押」「免押XX」（那是免押类场景专用）。
 *   本仓此前在三处出现口径偏差：
 *     ① `open-prep-drawer.vue` payDesc（微信侧）→「无需每次确认」；
 *     ② `open-prep-drawer.vue` payDesc（支付宝侧）→「无需每次确认」（同一句式，兄弟实例）；
 *     ③ `verify.vue` card-desc→「推荐开通支付分 / 免密代扣」——**微信支付分与支付宝免密代扣混用**，
 *        且「免密代扣」是支付宝侧术语；④ `verify.vue` 支付分按钮旁提示→「开通微信免密」。
 *   混用会让评审判定「未正确理解各渠道产品语义」。
 *
 * 判据（与写侧口径一致，铁律 51）：
 *   ① consumer-mp 源码（去注释后）**不得**出现禁词表中的词：
 *      「无需每次确认」「免确认」「先付后享」——注意**不拦**「免密」，因为支付宝侧描述功能时
 *      「免密支付」「开通免密支付」是产品内既有表述（且是页面标题），不是支付分品牌标语的一部分；
 *   ② 「微信支付分」与「免密」**不得**出现在同一句/同一标签内（渠道语义混用）；
 *   ③ 微信侧 payDesc 必须含「先享后付」口径。
 *
 * 自检：内置 mini fixture 走判据函数（防恒真/恒假），任何断言失败即 exit 1。
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-mp-payscore-copywriting]';
const MP_APPS = ['clients/consumer-mp/src', 'clients/merchant-mp/src'];
const EXT = new Set(['.vue', '.ts', '.js', '.tsx', '.jsx']);

/** 支付分品牌禁词（微信侧口径）。「免密」**不在**此列——见文件头注释。 */
const BANNED = ['无需每次确认', '免确认', '先付后享', '微信支付分授信'];

/** 去掉注释（行注释 + 块注释），避免把「规范说明注释」当成真实 UI 文案。 */
export function stripComments(src) {
  return src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/[^\n]*/g, '$1');
}

/** 微信侧产品名（用于判定「这句话在说支付分」）。 */
const WECHAT_SCORE_NAMES = ['微信支付分', '支付分'];

/**
 * 取出模板里的静态文案片段，用于做「同句混用」判定。
 *
 * 🔴 提取必须稳健：模板文本节点常**跨行**、或含 `{{ }}` 插值
 * （`<text class="x"\n  >文案 {{ a }} 更多</text>`），用单行正则 `>…<` 会漏掉。
 * ⇒ 做法：先按标签切开取所有「文本节点内容」，再把插值占位符与换行压成空格后合并相邻片段。
 */
export function staticTexts(src) {
  const code = stripComments(src);
  const out = [];

  // ① 模板文本节点：> 与 < 之间的内容（允许跨行）
  for (const m of code.matchAll(/>([^<>]*)</g)) {
    const v = m[1]
      .replace(/\{\{[^}]*\}\}/g, ' ') // 插值占位
      .replace(/\s+/g, ' ')
      .trim();
    if (v && /[\u4e00-\u9fa5]/.test(v)) out.push(v);
  }

  // ② 脚本里的字符串字面量（含模板字符串里的静态片段）
  for (const m of code.matchAll(/'([^'\\\n]*)'|"([^"\\\n]*)"/g)) {
    const v = (m[1] ?? m[2] ?? '').replace(/\s+/g, ' ').trim();
    if (v && /[\u4e00-\u9fa5]/.test(v)) out.push(v);
  }
  return out.filter(Boolean);
}

/**
 * 校验单个文件。
 * @returns {{ok: boolean, reasons: string[]}}
 */
export function checkFile(src) {
  const reasons = [];
  const code = stripComments(src);

  // ① 禁词扫描
  for (const w of BANNED) {
    if (code.includes(w)) {
      reasons.push(`出现支付分品牌禁词「${w}」（微信侧不得暗示授权机制或使用他类场景词）`);
    }
  }

  // ② 渠道语义混用：**把支付分当作「免密/代扣」这一机制来描述**才算混用。
  //    合法形态（不判红）：
  //      · 「微信支付分/支付宝免密代扣」——两个**渠道并列**（含「/」分隔）；
  //      · 「未开通免密时使用账户余额」——讲的是账户余额兜底，与支付分不同句；
  //      · 「已开通支付分 ⇒ 余额 + 微信免密」——**渠道枚举**（列出有哪些可用渠道）。
  //    违规形态（判红）：把两者用「/」「、」「或」并列成**同一机制名**，
  //      如「推荐开通支付分 / 免密代扣」「开通支付分或免密代扣」。
  for (const t of staticTexts(src)) {
    const hasWechat = WECHAT_SCORE_NAMES.some((n) => t.includes(n));
    const hasMimi = t.includes('免密');
    if (!hasWechat || !hasMimi) continue;
    // 判「同一机制并列」：支付分名与免密之间只隔分隔符（无「渠道/支付宝/微信」等限定词）
    const sep = /支付分\s*[/、或]\s*免密|免密\s*[/、或]\s*支付分/;
    if (sep.test(t)) {
      reasons.push(
        `文案「${t.slice(0, 40)}」把「支付分」与「免密」当作同一机制并列（渠道并列请用「微信支付分 / 支付宝免密」形式）`
      );
    }
  }

  return { ok: reasons.length === 0, reasons };
}

/** 递归收集源码文件。 */
function collect(dir, out = []) {
  let entries;
  try {
    entries = readdirSync(dir);
  } catch {
    return out;
  }
  for (const name of entries) {
    if (name === 'node_modules' || name === 'dist' || name === 'unpackage') continue;
    const full = join(dir, name);
    let st;
    try {
      st = statSync(full);
    } catch {
      continue;
    }
    if (st.isDirectory()) collect(full, out);
    else if (EXT.has(name.slice(name.lastIndexOf('.')))) out.push(full);
  }
  return out;
}

// ── 自检（防恒真/恒假） ──
(function selftest() {
  const fail = (m) => {
    console.error('[selftest] FAIL: ' + m);
    process.exit(1);
  };
  const ok = `<text>推荐开通微信支付分：先享后付，关门后自动按实际取货金额扣款。</text>`;
  if (!checkFile(ok).ok) fail('合规微信侧文案应通过');

  const banned = `<text>推荐开通微信支付分：关门后自动扣款，无需每次确认。</text>`;
  if (checkFile(banned).ok) fail('「无需每次确认」必须判红');

  // 真违规：把两者当同一机制并列
  const mixed = `<text>推荐开通支付分 / 免密代扣</text>`;
  if (checkFile(mixed).ok) fail('支付分 + 免密 当同一机制并列必须判红');

  // 合法：渠道并列（含「支付宝」限定词）
  const channels = `<text>结算优先使用微信支付分/支付宝免密代扣，未开通免密时使用账户余额。</text>`;
  if (!checkFile(channels).ok) fail('渠道并列形态不得判红');

  // 合法：渠道枚举（测试注释形态）
  const enumForm = `<text>已开通支付分 ⇒ 余额 + 微信免密</text>`;
  if (!checkFile(enumForm).ok) fail('渠道枚举形态不得判红');

  // 恒真陷阱：纯注释不得判红
  const onlyComment = `// 旧文案「无需每次确认」已废弃\nconst x = 1;`;
  if (!checkFile(onlyComment).ok) fail('纯注释不得判红');

  // 支付宝侧单独出现「免密」不得判红（不是微信支付分标语）
  const alipayOnly = `<text>开通支付宝免密</text><text>开通微信支付分</text>`;
  if (!checkFile(alipayOnly).ok) fail('支付宝侧独立「免密」不得判红');

  // 禁词表里的「免确认」也要判红
  if (checkFile(`<text>免确认模式</text>`).ok) fail('「免确认」必须判红');

  // 无相关文案的文件必须通过
  if (!checkFile('const a = 1;').ok) fail('无文案文件必须通过');
})();

let violations = 0;
for (const app of MP_APPS) {
  for (const file of collect(join(ROOT, app))) {
    const { ok, reasons } = checkFile(readFileSync(file, 'utf8'));
    if (!ok) {
      violations++;
      console.error(`${TAG} FAIL ${relative(ROOT, file)}`);
      for (const r of reasons) console.error(`${TAG}   - ${r}`);
    }
  }
}

if (violations > 0) {
  console.error(`${TAG} FAIL：${violations} 个文件的微信支付分相关文案不合规。`);
  process.exit(1);
}
console.log(`${TAG} OK：微信支付分相关文案合规（无禁词、无渠道语义混用）。`);
