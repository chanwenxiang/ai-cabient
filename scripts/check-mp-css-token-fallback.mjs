#!/usr/bin/env node
/**
 * 门禁：小程序端 page 级 CSS 令牌兜底必须完整（2026-10-24 商户端登录页实证）。
 *
 * 🔴 缺陷类（铁律 75：修的是这一类，不是这一处）：
 *   theme.css 把全部令牌声明在 `:root` 上，但 **WXSS 只支持 element/#id/.class/
 *   ::after/::before + `page`** —— `:root` 在小程序端不命中 ⇒ theme.css 令牌整体失效。
 *   各端必须在 App.vue 的 `page, uni-page-body {}` 块把令牌**逐字兜底**。
 *   merchant-mp 此前缺 46 个（含 --white/--brand-wx），导致：
 *     · `color: var(--white)` 整条失效 → 回落继承 page 的深色 ⇒ 青绿底深字；
 *     · `background: linear-gradient(135deg, var(--brand-wx), …)` 整条失效 ⇒ 按钮全透明。
 *   消费端因补齐了兜底而幸免——两端清单漂移就是本门禁要拦的事故形态。
 *
 * 判据（与写侧口径一致，铁律 51）：
 *   对每个小程序端：theme.css `:root` 的令牌集合 ⊆ 该端 App.vue page 块声明集合。
 *   值允许端侧刻意覆写（如 --text-muted/--page-bg），门禁只查「缺项」，不比对值。
 *
 * 自检：内置 mini fixture 走判据函数（防恒真/恒假），任何断言失败即 exit 1。
 */
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const MP_APPS = ['clients/consumer-mp/src/App.vue', 'clients/merchant-mp/src/App.vue'];
const THEME = 'packages/shared-uni/src/theme.css';

/** 提取 theme.css `:root` 块内声明的令牌名。 */
export function themeTokens(themeCss) {
  const start = themeCss.indexOf(':root {');
  if (start < 0) return new Set();
  const end = themeCss.indexOf('\n}', start);
  const body = themeCss.slice(start, end);
  return new Set([...body.matchAll(/(--[a-z0-9-]+)\s*:/g)].map((m) => m[1]));
}

/** 提取 App.vue 中 `page, uni-page-body { … }` 块内声明的令牌名（花括号配平）。 */
export function pageBlockTokens(appVue) {
  const sel = appVue.indexOf('page,');
  if (sel < 0) return new Set();
  const open = appVue.indexOf('{', sel);
  if (open < 0) return new Set();
  let depth = 0;
  let end = open;
  for (let k = open; k < appVue.length; k++) {
    if (appVue[k] === '{') depth++;
    else if (appVue[k] === '}') {
      depth--;
      if (depth === 0) {
        end = k;
        break;
      }
    }
  }
  return new Set([...appVue.slice(open, end).matchAll(/(--[a-z0-9-]+)\s*:/g)].map((m) => m[1]));
}

// ── 自检（防恒真/恒假）：判据函数对合成样本必须给出预期结果 ──
(function selftest() {
  const fail = (m) => {
    console.error('[selftest] FAIL: ' + m);
    process.exit(1);
  };
  const theme = ':root {\n  --white: #ffffff;\n  --brand-wx: #07c160;\n}';
  const t = themeTokens(theme);
  if (t.size !== 2 || !t.has('--white') || !t.has('--brand-wx')) fail('themeTokens 解析错');
  const okApp = 'x\npage,\nuni-page-body {\n  --white: #fff;\n  --brand-wx: #7c1;\n}\n';
  if (pageBlockTokens(okApp).size !== 2) fail('pageBlockTokens 应解析到 2 项');
  // 恒真陷阱：选择器不存在时必须返回空集（而非把全文件当块）
  if (pageBlockTokens('no page selector here').size !== 0) fail('无 page 块必须返回空集');
  // 判据方向：缺项必须被发现（防恒假）
  const missing = [...t].filter((x) => !pageBlockTokens(okApp).has(x));
  if (missing.length !== 0) fail('补齐样本不应有缺项');
  const missing2 = [...t].filter(
    (x) => !pageBlockTokens('page,\nuni-page-body {\n  --white: #fff;\n}').has(x)
  );
  if (missing2.join() !== '--brand-wx')
    fail('缺项样本必须揪出 --brand-wx，实际=' + missing2.join());
  console.log('[selftest] 判据函数 4 例自检通过');
})();

const themeSet = themeTokens(readFileSync(join(ROOT, THEME), 'utf8'));
const problems = [];
for (const app of MP_APPS) {
  const declared = pageBlockTokens(readFileSync(join(ROOT, app), 'utf8'));
  const missing = [...themeSet].filter((t) => !declared.has(t));
  if (missing.length) {
    problems.push(
      `${app} 的 page 兜底块缺 ${missing.length} 个 theme.css 令牌：${missing.slice(0, 12).join(' ')}${missing.length > 12 ? ' …' : ''}`
    );
  }
}
if (problems.length) {
  console.error(
    '[check-mp-css-token-fallback] FAIL：小程序端 page 级令牌兜底不完整（:root 在 WXSS 不命中，缺项即整条 var() 声明失效）：'
  );
  for (const p of problems) console.error('  - ' + p);
  console.error(
    '  修法：把缺失令牌按 theme.css 值逐字补进该端 App.vue 的 page, uni-page-body 块（参照 consumer-mp 注释）。'
  );
  process.exit(1);
}
console.log(
  `[check-mp-css-token-fallback] OK：${MP_APPS.length} 端 page 块覆盖 theme.css 全部 ${themeSet.size} 个令牌`
);
