/**
 * 触控热区棘轮门禁（mp 两端，微信规范热区约 7–9mm ⇒ 项目口径 88rpx）。
 *
 * 为什么静态：运行时量尺寸要微信开发者工具实例（9420/9421），pre-push 与 CI 都不该依赖它。
 * 静态判据只认「模板里可点 ⇒ 该类在 CSS 中同时写了显式 width 与 height/min-height（rpx）」，
 * 取 min(w,h) < 88rpx 记违规；靠 padding/内容撑开的元素量不出来 ⇒ 不判（宁可漏报不误伤）。
 * 「热区/视觉分离」写法（外层 hit 容器 88rpx + 内层小圆点 + 负边距）天然合规：外层达标即可。
 *
 * 棘轮：只减不增。迁完一批把 BASELINE 下调，别加豁免除非有明确理由（EXEMPT 里逐条写原因）。
 *
 * 自检：`--selftest` 用合成样式验判据（含 88 边界、只写 width、负边距热区、padding 不判）。
 * 打印：`--print` 列当前违规清单。
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';

const ROOT = process.cwd();
const MIN_TOUCH_RPX = 88;
/**
 * 存量小热区基线（只减不增）。2026-11-03 首扫登记 5 处，均为「图标型按钮本体小于 88rpx」：
 *   - consumer order-appeal-sheet .evidence-del 32×32（凭证删除 ×）
 *   - consumer index .stepper-btn 44×44（数量加减）
 *   - consumer index / result .promo-popup-close 48×48（弹窗关闭 ×，两处）
 *   - merchant ReplenishLinesSection .qty-btn 56×56（补货行数量加减）
 * 修法统一用「热区/视觉分离」（外层 hit 容器 88rpx + 负边距，参考 shared-uni help-tip）；
 * 改一处就把本值调小，并跑 `--print` 核对。
 */
const BASELINE = Number(process.env.TOUCH_BASELINE || 5);

/** 逐条豁免：file + class + 原因（新增必须写清为什么允许小于 88rpx） */
const EXEMPT = [];

const SCOPES = ['clients/consumer-mp/src', 'clients/merchant-mp/src'];

function walk(dir, out = []) {
  if (!statExists(dir)) return out;
  for (const name of readdirSync(dir)) {
    if (name === 'node_modules' || name === 'dist' || name.startsWith('.')) continue;
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (/\.(vue|css|scss)$/.test(p)) out.push(p);
  }
  return out;
}

function statExists(p) {
  try {
    return statSync(p).isDirectory();
  } catch {
    return false;
  }
}

const CSS_VALUE_RE = /(?<![-\w])(width|height|min-height)\s*:\s*([0-9.]+)rpx/g;

/** 从样式文本抽 class ⇒ { width, height, minHeight } */
export function parseClassSizes(cssText) {
  const sizes = new Map();
  // 按规则块切：`sel { decls }`
  const blockRe = /([^{}]+)\{([^{}]*)\}/g;
  let m;
  while ((m = blockRe.exec(cssText))) {
    const decls = m[2];
    for (const rawSel of m[1].split(',')) {
      const sel = rawSel.trim();
      if (!sel || sel.startsWith('@')) continue;
      // 伪元素（::after/::before）的声明不是元素本体 ⇒ 整条跳过
      if (sel.includes('::')) continue;
      // 只认「最后一个复合选择器」上的类：`.a .b` 的声明是给 .b 的，不该记到 .a 头上
      const lastCompound =
        sel
          .split(/[\s>+~]+/)
          .filter(Boolean)
          .pop() || '';
      const cls = lastCompound.match(/\.[A-Za-z][\w-]*/g);
      if (!cls) continue;
      for (const c of cls) {
        const name = c.slice(1);
        const rec = sizes.get(name) || {};
        let v;
        CSS_VALUE_RE.lastIndex = 0;
        while ((v = CSS_VALUE_RE.exec(decls))) {
          const [, prop, rawn] = v;
          const px = Number(rawn);
          if (!Number.isFinite(px)) continue;
          if (prop === 'width') rec.width = px;
          else if (prop === 'height') rec.height = px;
          else rec.minHeight = px;
        }
        if (Object.keys(rec).length) sizes.set(name, rec);
      }
    }
  }
  return sizes;
}

/** 模板里可点元素用到的 class 集合（@click 或 role="button"） */
export function collectClickableClasses(tpl) {
  const out = new Set();
  const tagRe = /<(view|text|button|image|a|label|checkbox|switch)[^>]*>/gi;
  let m;
  while ((m = tagRe.exec(tpl))) {
    const tag = m[0];
    if (!/@click|@tap|role\s*=\s*"button"/.test(tag)) continue;
    const clsAttr = tag.match(/\sclass\s*=\s*"([^"]*)"/) || tag.match(/\s:class\s*=\s*"([^"]*)"/);
    if (!clsAttr) continue;
    for (const c of clsAttr[1].split(/[\s`'"]+/)) {
      if (/^[A-Za-z][\w-]*$/.test(c)) out.add(c);
    }
  }
  return out;
}

/** 判据：显式同时有宽与高（或 min-height）且短边 < 88rpx ⇒ 违规 */
export function isViolation(size) {
  const h = size.height ?? size.minHeight;
  if (typeof size.width !== 'number' || typeof h !== 'number') return false;
  return Math.min(size.width, h) < MIN_TOUCH_RPX;
}

function selfTest() {
  const cases = [
    { css: '.a{width:72rpx;height:72rpx}', cls: 'a', want: true, why: '72×72 明显小于 88' },
    { css: '.a{width:88rpx;height:88rpx}', cls: 'a', want: false, why: '正好 88 合规（边界）' },
    { css: '.a{width:40rpx}', cls: 'a', want: false, why: '只写宽不判（量不出高）' },
    { css: '.a{padding:12rpx}', cls: 'a', want: false, why: 'padding 撑开的不判' },
    { css: '.a{width:200rpx;min-height:88rpx}', cls: 'a', want: false, why: 'min-height 达标' },
    {
      css: '.a{width:32rpx;height:32rpx}\n.a-hit{width:88rpx;height:88rpx}',
      cls: 'a-hit',
      want: false,
      why: '热区/视觉分离：外层达标'
    },
    {
      css: '.a{width:200rpx;height:88rpx}\n.a .b{width:64rpx;height:6rpx}',
      cls: 'a',
      want: false,
      why: '后代选择器的声明不该记到父类（曾误伤 tool-item）'
    },
    {
      css: '.a{width:200rpx;height:88rpx}\n.a.is-x::after{width:64rpx;height:6rpx}',
      cls: 'a',
      want: false,
      why: '伪元素规则整条跳过'
    }
  ];
  let bad = 0;
  for (const c of cases) {
    const got = isViolation(parseClassSizes(c.css).get(c.cls) || {});
    const ok = got === c.want;
    if (!ok) bad++;
    console.log(`  [${ok ? 'ok' : 'BAD'}] ${c.why} ⇒ ${got}`);
  }
  const clickable = collectClickableClasses('<view class="a b" @click="x">');
  console.log(`  [${clickable.has('a') && clickable.has('b') ? 'ok' : 'BAD'}] 模板可点 class 抽取`);
  if (bad || !clickable.has('a')) {
    console.error('[selftest] FAIL');
    process.exit(1);
  }
  console.log('[selftest] 判据用例通过');
}

function main() {
  if (process.argv.includes('--selftest')) return selfTest();
  const files = SCOPES.flatMap((s) => walk(join(ROOT, s)));
  const violations = [];
  for (const f of files) {
    const text = readFileSync(f, 'utf8');
    const styles = [...text.matchAll(/<style[^>]*>([\s\S]*?)<\/style>/gi)]
      .map((x) => x[1])
      .join('\n');
    const cssText = /\.css$/.test(f) ? text : styles;
    if (!cssText.trim()) continue;
    const sizes = parseClassSizes(cssText);
    const clickables = /\.css$/.test(f) ? new Set() : collectClickableClasses(text);
    for (const cls of clickables) {
      const size = sizes.get(cls);
      if (size && isViolation(size)) {
        if (
          EXEMPT.some((e) => e.file === relative(ROOT, f).replaceAll('\\', '/') && e.class === cls)
        )
          continue;
        violations.push({ file: relative(ROOT, f).replaceAll('\\', '/'), class: cls, ...size });
      }
    }
  }
  const uniq = new Map();
  for (const v of violations) uniq.set(`${v.file}#${v.class}`, v);
  const rows = [...uniq.values()].sort((a, b) => a.file.localeCompare(b.file));
  if (process.argv.includes('--print')) {
    for (const r of rows)
      console.log(`  ${r.file} .${r.class} ${r.width}×${r.height ?? r.minHeight}rpx`);
    console.log(
      `[check-mp-touch-target-ratchet] 违规 ${rows.length} 处（基线 ${BASELINE}，阈值 ${MIN_TOUCH_RPX}rpx）`
    );
  }
  if (rows.length > BASELINE) {
    console.error(
      `[check-mp-touch-target-ratchet] FAIL: 小热区 ${rows.length} > 基线 ${BASELINE}（只减不增）。\n` +
        rows
          .slice(0, 10)
          .map((r) => `  - ${r.file} .${r.class} ${r.width}×${r.height ?? r.minHeight}rpx`)
          .join('\n') +
        `\n  修法：可点元素短边提到 ${MIN_TOUCH_RPX}rpx；视觉要小就用「热区/视觉分离」（外层 hit 容器 + 负边距，参考 shared-uni help-tip）。`
    );
    process.exit(1);
  }
  console.log(
    `[check-mp-touch-target-ratchet] OK：小热区 ${rows.length}/${BASELINE}（阈值 ${MIN_TOUCH_RPX}rpx，扫 ${files.length} 文件）`
  );
}

main();
