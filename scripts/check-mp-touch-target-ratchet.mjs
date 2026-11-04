/**
 * 触控热区棘轮门禁（mp 两端，微信规范热区约 7–9mm ⇒ 项目口径 88rpx）。
 *
 * 为什么静态：运行时量尺寸要微信开发者工具实例（9420/9421），pre-push 与 CI 都不该依赖它。
 * 判据：模板里可点 ⇒ 该类在 CSS 里的「可证热区」短边必须 ≥ 88rpx。
 *   可证热区 = 显式 width/min-width（或 width:100%）+ 显式 height/min-height + 伪元素负 inset 的按边扩展。
 *   🔴 2026-11-04 MA-159 修掉三处「恒放行」：
 *     ① 旧版要求「显式宽**且**显式高」否则直接 return false ⇒ `.aux-link`（padding 撑开）与
 *        `.app-btn--sm`（有高无宽）永远算不出来、也永远不判；现在**单边可证就判**，
 *        两边都算不出的归入 `unverifiable` 一档照样要让基线兜着。
 *     ② 旧版 `inset` 只取第一个值 ⇒ `inset: -28rpx 0` 被当成四向 28rpx（横向虚增 56rpx，假绿）。
 *        现在按 CSS 简写 1–4 值展开到四边，并接受无单位零。
 *     ③ 旧版 `if (size && …)` 让「类在别处定义 / 只有 padding」的类直接跳过；另外同一个标签
 *        同时写 `class` 与 `:class` 时只取第一个 ⇒ 模板字符串拼的 `app-btn--${size}` 整串丢失。
 *        现在两个属性都收，模板字符串留下的前缀按 `前缀*` 让该前缀下所有类都可点。
 *
 * 两条棘轮都只减不增（基线是脚本内显式常量，不是清单长度）。加豁免要逐条写理由（EXEMPT）。
 *
 * 自检：`--selftest` 用合成样式验判据（88 边界、单边可证、padding/满宽、inset 1–4 值展开、动态前缀）。
 * 打印：`--print` 列当前两档清单（`--all` 打全量）。
 * 复用：`parseClassSizes` / `collectClickableClasses` / `violationKind` / `expandInsetShorthand`
 * 已导出且 import 无副作用（main 只在本文件被直接执行时跑），审计复核可直接拿它解析真实文件。
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';

const ROOT = process.cwd();
const MIN_TOUCH_RPX = 88;
/**
 * 存量小热区基线（只减不增）。2026-11-03 首扫登记 5 处并已清零；
 * 2026-11-04 修完 MA-159 的三处盲区后重扫，判据变严 ⇒ **8 处可证不达标**，逐条都在 `--print` 里：
 *   consumer coupons .coupon-pick 高 48 / index .category-chip 52 / messages .subscribe-btn 64 /
 *   points .redeem-btn 60；merchant ReplenishLinesSection .scan-line 52 / mine .menu-cell 84；
 *   shared-uni app-button .app-btn--text 64、privacy-consent-modal .privacy-btn 80。
 * 2026-11-04 同一轮把这 8 处按「热区/视觉分离」修完（视觉尺寸不动、纵向热区扩到 88rpx、
 * 横向补 min-width: 88rpx 让判据可证）⇒ 本档归 0，改为「硬禁新增」。
 * 另一档 `UNVERIFIABLE_BASELINE`=235 是「量不出热区的可点类」（旧版当合规放行的那批），
 * 不是都算 bug，而是没证据说达标；随页面改到就补显式尺寸/满宽，把基线往下调。
 */
/**
 * 两条棘轮（都只减不增，2026-11-04 MA-159 之后分成两档）：
 *  - TOUCH_BASELINE：确定的小热区（可证的边 < 88rpx）。
 *  - TOUCH_UNVERIFIABLE_BASELINE：量不出热区的可点类（旧版把它们当合规静默放行）。
 *    这一档不是「都是 bug」，而是「没证据说它达标」——修法统一是补显式尺寸/满宽声明，让它可证。
 */
const BASELINE = Number(process.env.TOUCH_BASELINE || 0);
const UNVERIFIABLE_BASELINE = Number(process.env.TOUCH_UNVERIFIABLE_BASELINE || 235);

/** 逐条豁免：file + class + 原因（新增必须写清为什么允许小于 88rpx） */
const EXEMPT = [];

const SCOPES = [
  'clients/consumer-mp/src',
  'clients/merchant-mp/src',
  // MA-156：组件内部尺寸变体（如 app-button sm/compact 72rpx）此前是扫描盲区
  'packages/shared-uni/src/components'
];

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

const CSS_VALUE_RE = /(?<![-\w])(width|height|min-width|min-height)\s*:\s*([0-9.]+)rpx/g;
/** 满宽（width:100% / left:0;right:0）⇒ 横向可证达标，不必知道具体 rpx */
const FULL_WIDTH_RE = /(?<![-\w])width\s*:\s*100%/;
/**
 * inset 简写要按 1–4 个值展开到四边（MA-159 实测缺陷：旧版只取第一个值，
 * `inset: -28rpx 0` 被当成四向 28rpx ⇒ 横向虚增 56rpx，哪天补了显式宽高就会假绿）。
 */
const INSET_RE =
  /(?:^|[;\s])inset\s*:\s*((?:-?[0-9.]+rpx|-?(?:0+\.0*|0))(?:\s+(?:-?[0-9.]+rpx|-?(?:0+\.0*|0))){0,3})/;

/** CSS 简写 1–4 值 ⇒ [top, right, bottom, left]（负值=向外扩，正值=向内缩，缩的不算热区） */
export function expandInsetShorthand(value) {
  const nums = value
    .trim()
    .split(/\s+/)
    .map((v) => {
      // rpx 值或无单位零（`inset: -28rpx 0` 里的 0 就是无单位，MA-159 实测必须吃下）
      const m = /^(-?[0-9.]+)(rpx)?$/.exec(v);
      if (!m) return NaN;
      const n = Number(m[1]);
      return m[2] || n === 0 ? n : NaN;
    });
  if (nums.some((n) => !Number.isFinite(n))) return null;
  if (nums.length === 1) return [nums[0], nums[0], nums[0], nums[0]];
  if (nums.length === 2) return [nums[0], nums[1], nums[0], nums[1]];
  if (nums.length === 3) return [nums[0], nums[1], nums[2], nums[1]];
  if (nums.length === 4) return nums;
  return null;
}

/**
 * 从样式文本抽 class ⇒ { width, height, minWidth, minHeight, hitV, hitH, fullWidth }
 * hitV / hitH = 伪元素负 inset 在纵/横两向的**合计**扩展量（有效热区 = 尺寸 + hit）。
 */
export function parseClassSizes(cssText) {
  const sizes = new Map();
  const touch = (name) => {
    const existing = sizes.get(name);
    if (existing) return existing;
    const created = {};
    sizes.set(name, created);
    return created;
  };
  // 按规则块切：`sel { decls }`
  const blockRe = /([^{}]+)\{([^{}]*)\}/g;
  let m;
  while ((m = blockRe.exec(cssText))) {
    const decls = m[2];
    for (const rawSel of m[1].split(',')) {
      const sel = rawSel.trim();
      if (!sel || sel.startsWith('@')) continue;
      const pseudo = sel.match(/::(after|before)\b/);
      // 只认「最后一个复合选择器」上的类：`.a .b` 的声明是给 .b 的，不该记到 .a 头上
      const head = pseudo ? sel.slice(0, pseudo.index) : sel;
      const lastCompound = head
        .split(/[\s>+~]+/)
        .filter(Boolean)
        .pop();
      const cls = lastCompound ? lastCompound.match(/\.[A-Za-z][\w-]*/g) : null;
      if (!cls) continue;
      if (pseudo) {
        // 伪元素只有在「绝对定位 + 负 inset」时才是热区扩展，否则是装饰（图形/圆点）
        if (!/position\s*:\s*absolute/.test(decls)) continue;
        const ins = decls.match(INSET_RE);
        if (!ins) continue;
        const sides = expandInsetShorthand(ins[1]);
        if (!sides) continue;
        // 正值是向内缩（装饰定位），不产生热区；负值按边分别累计
        const [t, r, b, l] = sides.map((v) => (v < 0 ? -v : 0));
        const hitV = t + b;
        const hitH = l + r;
        if (hitV === 0 && hitH === 0) continue;
        for (const c of cls) {
          const rec = touch(c.slice(1));
          rec.hitV = Math.max(rec.hitV || 0, hitV);
          rec.hitH = Math.max(rec.hitH || 0, hitH);
        }
        continue;
      }
      for (const c of cls) {
        const rec = touch(c.slice(1));
        if (FULL_WIDTH_RE.test(decls)) rec.fullWidth = true;
        let v;
        CSS_VALUE_RE.lastIndex = 0;
        while ((v = CSS_VALUE_RE.exec(decls))) {
          const [, prop, rawn] = v;
          const px = Number(rawn);
          if (!Number.isFinite(px)) continue;
          if (prop === 'width') rec.width = px;
          else if (prop === 'height') rec.height = px;
          else if (prop === 'min-width') rec.minWidth = px;
          else rec.minHeight = px;
        }
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
    // 🔴 同一个标签可能同时有 class 与 :class（app-button.vue 就是 `class="app-btn"` +
    // `:class="[...]"`），只取第一个会把模板字符串那串整个丢掉。
    const attrs = [...tag.matchAll(/\s:?class\s*=\s*"([^"]*)"/g)].map((x) => x[1]);
    if (!attrs.length) continue;
    for (const raw of attrs) {
      // 分隔符包含模板字符串的 `${` / `}`，否则 `app-btn--${size}` 会被切出 `app-btn--$`
      for (const c of raw.split(/[\s`'"${}]+/)) {
        // 模板字符串（`app-btn--${size}`）只会留下前缀 ⇒ 记成 `前缀*`，让该前缀下所有类都可点；
        // 否则 `.app-btn--sm` 这类真正的小热区永远进不了判据（MA-159 实测盲区）。
        if (/^[A-Za-z][\w-]*-+$/.test(c)) out.add(`${c}*`);
        else if (/^[A-Za-z][\w-]*$/.test(c)) out.add(c);
      }
    }
  }
  return out;
}

/**
 * 判据分三档（MA-159：旧版要求「显式宽且显式高」，否则直接 return false ⇒
 * `.aux-link`（padding 撑开，无量可算）与 `.app-btn--sm`（有高无宽）**恒放行**）：
 *  - 'undersized'   ：任一可证的边 < 88rpx ⇒ 确定的小热区。
 *  - 'unverifiable' ：至少有一条边算不出来（无显式尺寸且非满宽）⇒ 不能当它合规。
 *  - null           ：两条边都可证且都达标。
 * 修法统一是「补显式尺寸或热区分离」，让判据能算得出。
 */
export function violationKind(size) {
  const hitV = size.hitV || 0;
  const hitH = size.hitH || 0;
  const h0 = size.height ?? size.minHeight;
  const w0 = size.width ?? size.minWidth;
  const h = typeof h0 === 'number' ? h0 + hitV : undefined;
  const w = typeof w0 === 'number' ? w0 + hitH : size.fullWidth ? Infinity : undefined;
  if (typeof h === 'number' && h < MIN_TOUCH_RPX) return 'undersized';
  if (typeof w === 'number' && w < MIN_TOUCH_RPX) return 'undersized';
  if (typeof h !== 'number' || typeof w !== 'number') return 'unverifiable';
  return null;
}

function selfTest() {
  const cases = [
    { css: '.a{width:72rpx;height:72rpx}', cls: 'a', want: 'undersized', why: '72×72 明显小于 88' },
    { css: '.a{width:88rpx;height:88rpx}', cls: 'a', want: null, why: '正好 88 合规（边界）' },
    {
      css: '.a{width:40rpx}',
      cls: 'a',
      want: 'undersized',
      why: '只写宽也判得出：40rpx 宽必不达标（旧版在此 return false 放行）'
    },
    {
      css: '.a{height:72rpx}',
      cls: 'a',
      want: 'undersized',
      why: '有高无宽同样判：72rpx 高已不达标（= .app-btn--sm 真实形态）'
    },
    {
      css: '.a{padding:12rpx}',
      cls: 'a',
      want: 'unverifiable',
      why: 'padding 撑开、两向都量不出 ⇒ 不再静默放行（= .aux-link 真实形态）'
    },
    {
      css: '.a{padding:8rpx 16rpx}\n.a::after{content:"";position:absolute;inset:-28rpx 0}',
      cls: 'a',
      want: 'unverifiable',
      why: '纵向扩到可证但横向仍无量 ⇒ unverifiable，不许当合规'
    },
    { css: '.a{width:200rpx;min-height:88rpx}', cls: 'a', want: null, why: 'min-height 达标' },
    {
      css: '.a{width:200rpx;height:96rpx}',
      cls: 'a',
      want: null,
      why: '宽条 + 高达标'
    },
    {
      css: '.a{width:100%}',
      cls: 'a',
      want: 'unverifiable',
      why: '满宽可证横向，但高度无量 ⇒ 仍不可验'
    },
    {
      css: '.a{width:44rpx;height:44rpx}\n.a::after{content:"";position:absolute;inset:-22rpx}',
      cls: 'a',
      want: null,
      why: '单值 inset 四向扩 22 ⇒ 44+44=88（.stepper-btn 惯用法）'
    },
    {
      css: '.a{width:44rpx;height:44rpx}\n.a::after{content:"";position:absolute;inset:4rpx}',
      cls: 'a',
      want: 'undersized',
      why: '正 inset 是内缩不是热区 ⇒ 仍判违规'
    },
    {
      css: '.a{width:44rpx;height:44rpx}\n.a::after{content:"";width:10rpx;height:10rpx}',
      cls: 'a',
      want: 'undersized',
      why: '非绝对定位的伪元素是装饰 ⇒ 仍判违规'
    },
    {
      css: '.a{width:32rpx;height:32rpx}\n.a-hit{width:88rpx;height:88rpx}',
      cls: 'a-hit',
      want: null,
      why: '热区/视觉分离：外层达标'
    },
    {
      css: '.a{width:200rpx;height:88rpx}\n.a .b{width:64rpx;height:6rpx}',
      cls: 'a',
      want: null,
      why: '后代选择器的声明不该记到父类（曾误伤 tool-item）'
    },
    {
      css: '.a{width:200rpx;height:88rpx}\n.a.is-x::after{width:64rpx;height:6rpx}',
      cls: 'a',
      want: null,
      why: '装饰性伪元素不污染基类尺寸'
    }
  ];
  let bad = 0;
  for (const c of cases) {
    const got = violationKind(parseClassSizes(c.css).get(c.cls) || {});
    const ok = got === c.want;
    if (!ok) bad++;
    console.log(`  [${ok ? 'ok' : 'BAD'}] ${c.why} ⇒ ${got}`);
  }
  // inset 简写按边展开（MA-159 第二个缺陷：旧版只取首值 ⇒ -28rpx 0 被当成四向 28）
  const insetCases = [
    { v: '-28rpx 0', want: [56, 0], why: '两值：纵向 28×2、横向 0' },
    { v: '-10rpx -20rpx', want: [20, 40], why: '两值反向：纵向 10×2、横向 20×2' },
    { v: '-4rpx -8rpx -12rpx -16rpx', want: [16, 24], why: '四值：上下 4+12、左右 16+8' },
    { v: '-6rpx', want: [12, 12], why: '单值四向' },
    { v: '4rpx', want: [0, 0], why: '正值不加分' }
  ];
  for (const c of insetCases) {
    const css = `.a{width:10rpx;height:10rpx}\n.a::after{content:"";position:absolute;inset:${c.v}}`;
    const rec = parseClassSizes(css).get('a') || {};
    const got = [rec.hitV || 0, rec.hitH || 0];
    const ok = got[0] === c.want[0] && got[1] === c.want[1];
    if (!ok) bad++;
    console.log(`  [${ok ? 'ok' : 'BAD'}] inset 展开 ${c.why} ⇒ hitV=${got[0]} hitH=${got[1]}`);
  }
  const clickable = collectClickableClasses('<view class="a b" @click="x">');
  console.log(`  [${clickable.has('a') && clickable.has('b') ? 'ok' : 'BAD'}] 模板可点 class 抽取`);
  const dyn = collectClickableClasses('<button :class="`app-btn--${size}`" @click="x">');
  const dynOk = dyn.has('app-btn--*');
  console.log(
    `  [${dynOk ? 'ok' : 'BAD'}] 模板字符串类名按前缀采集（app-btn--* ⇒ app-btn--sm 才会被判）`
  );
  if (bad || !clickable.has('a') || !dynOk) {
    console.error('[selftest] FAIL');
    process.exit(1);
  }
  console.log('[selftest] 判据用例通过');
}

function main() {
  if (process.argv.includes('--selftest')) return selfTest();
  const files = SCOPES.flatMap((s) => walk(join(ROOT, s)));
  const undersized = new Map();
  const unverifiable = new Map();
  for (const f of files) {
    const text = readFileSync(f, 'utf8');
    const styles = [...text.matchAll(/<style[^>]*>([\s\S]*?)<\/style>/gi)]
      .map((x) => x[1])
      .join('\n');
    const cssText = /\.css$/.test(f) ? text : styles;
    if (!cssText.trim()) continue;
    const sizes = parseClassSizes(cssText);
    const clickables = /\.css$/.test(f) ? new Set() : collectClickableClasses(text);
    const rel = relative(ROOT, f).replaceAll('\\', '/');
    // 动态前缀（`app-btn--${size}`）⇒ 该前缀下的每个 CSS 类都按可点算
    const prefixes = [...clickables].filter((k) => k.endsWith('*')).map((k) => k.slice(0, -1));
    const candidates = new Set([
      ...[...clickables].filter((k) => !k.endsWith('*')),
      ...[...sizes.keys()].filter((c) => prefixes.some((p) => c.startsWith(p)))
    ]);
    for (const cls of candidates) {
      // 🔴 旧版这里是 `if (size && isViolation(size))`：类在别处定义、或只有 padding 时
      // `sizes.get(cls)` 是 undefined ⇒ 直接跳过 = 第三种「静默放行」。改成空对象照样判。
      const size = sizes.get(cls) || {};
      const kind = violationKind(size);
      if (!kind) continue;
      if (EXEMPT.some((e) => e.file === rel && e.class === cls)) continue;
      const row = { file: rel, class: cls, ...size };
      (kind === 'undersized' ? undersized : unverifiable).set(`${rel}#${cls}`, row);
    }
  }
  const uRows = [...undersized.values()].sort((a, b) => a.file.localeCompare(b.file));
  const vRows = [...unverifiable.values()].sort((a, b) => a.file.localeCompare(b.file));
  const fmt = (r) =>
    `  - ${r.file} .${r.class} 宽=${r.width ?? r.minWidth ?? (r.fullWidth ? '100%' : '未写')}` +
    ` 高=${r.height ?? r.minHeight ?? '未写'} 伪元素扩 纵${r.hitV || 0}/横${r.hitH || 0}rpx`;

  const showAll = process.argv.includes('--all');
  if (process.argv.includes('--print')) {
    console.log(`小热区（可证不达标）${uRows.length} 处（基线 ${BASELINE}）：`);
    for (const r of showAll ? uRows : uRows.slice(0, 15)) console.log(fmt(r));
    console.log(`\n量不出热区的可点类 ${vRows.length} 处（基线 ${UNVERIFIABLE_BASELINE}）：`);
    for (const r of showAll ? vRows : vRows.slice(0, 15)) console.log(fmt(r));
  }
  const problems = [];
  if (uRows.length > BASELINE) {
    problems.push(
      `小热区 ${uRows.length} > 基线 ${BASELINE}（只减不增）：\n` +
        uRows.slice(0, 10).map(fmt).join('\n')
    );
  } else if (uRows.length < BASELINE) {
    problems.push(
      `小热区实测 ${uRows.length} < 基线 ${BASELINE}：请把 BASELINE 下调到 ${uRows.length}。`
    );
  }
  if (vRows.length > UNVERIFIABLE_BASELINE) {
    problems.push(
      `量不出热区的可点类 ${vRows.length} > 基线 ${UNVERIFIABLE_BASELINE}：新增的可点目标必须能算出热区` +
        `（写显式 width/height 或 min-*，或满宽 width:100%），不能靠「判据看不见」过关：\n` +
        vRows.slice(0, 10).map(fmt).join('\n')
    );
  } else if (vRows.length < UNVERIFIABLE_BASELINE) {
    problems.push(
      `不可验实测 ${vRows.length} < 基线 ${UNVERIFIABLE_BASELINE}：请把 TOUCH_UNVERIFIABLE_BASELINE 下调到 ${vRows.length}。`
    );
  }
  if (problems.length) {
    console.error(
      `[check-mp-touch-target-ratchet] FAIL（阈值 ${MIN_TOUCH_RPX}rpx，扫 ${files.length} 文件）：\n` +
        problems.map((p) => `• ${p}`).join('\n') +
        `\n  修法：可点元素短边提到 ${MIN_TOUCH_RPX}rpx；视觉要小就用「热区/视觉分离」` +
        `（外层 hit 容器 + 负 inset，参考 shared-uni help-tip）；` +
        `横向靠内容撑开的链接请补 min-width 或改满宽块级。`
    );
    process.exit(1);
  }
  console.log(
    `[check-mp-touch-target-ratchet] OK：小热区 ${uRows.length}/${BASELINE}、不可验 ${vRows.length}/${UNVERIFIABLE_BASELINE}` +
      `（阈值 ${MIN_TOUCH_RPX}rpx，扫 ${files.length} 文件）`
  );
}

// 只有直接执行才跑 main：被 import（测试 / 审计复核）时不能有副作用
const invokedDirectly = (process.argv[1] || '')
  .replaceAll('\\', '/')
  .endsWith('check-mp-touch-target-ratchet.mjs');
if (invokedDirectly) main();
