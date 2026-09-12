/**
 * T-D2：小程序源码 a11y 抽检（静态）。
 * - 统计含 @click 的元素是否带 role / aria-label，或为 button/app-button/navigator
 * - 统计功能性图标是否带 aria-label / aria-hidden
 *
 * 用法：node scripts/check-mp-a11y-coverage.mjs
 * 环境变量 MIN_ROLE_PCT=90（默认 90；R3-X01 从 70 收紧）
 */
import fs from 'node:fs';
import path from 'node:path';

const ROOTS = ['clients/consumer-mp/src', 'clients/merchant-mp/src'];
const MIN_ROLE_PCT = Number(process.env.MIN_ROLE_PCT || 90);

function walk(dir, files = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) {
      if (e.name === 'node_modules' || e.name === 'dist') continue;
      walk(p, files);
    } else if (e.name.endsWith('.vue')) files.push(p);
  }
  return files;
}

let clickables = 0;
let withRole = 0;
let iconNodes = 0;
let iconOk = 0;
const samples = [];

for (const root of ROOTS) {
  for (const file of walk(path.resolve(root))) {
    const text = fs.readFileSync(file, 'utf8');
    const tpl = text.split(/<script\b/)[0] || text;
    // rough: opening tags with @click
    const tagRe = /<(view|text|button|navigator|app-button|image)([^>]*?)>/gi;
    let m;
    while ((m = tagRe.exec(tpl))) {
      const tag = m[1].toLowerCase();
      const attrs = m[2];
      if (!/@click\b/.test(attrs)) continue;
      if (tag === 'button' || tag === 'app-button' || tag === 'navigator') {
        clickables += 1;
        withRole += 1;
        continue;
      }
      clickables += 1;
      // 接受任意 ARIA role（tab/switch/checkbox/dialog/button…）或 aria-label
      if (/\brole\s*=/.test(attrs) || /\baria-label\s*=/.test(attrs)) {
        withRole += 1;
      } else if (samples.length < 12) {
        samples.push(
          `${path.relative(process.cwd(), file)}: <${tag} @click> missing role/aria-label`
        );
      }
    }
    // icons
    const iconRe = /<(view|text|image)([^>]*app-icon[^>]*)>/gi;
    while ((m = iconRe.exec(tpl))) {
      iconNodes += 1;
      const attrs = m[2];
      if (/aria-hidden\s*=\s*["']true["']/.test(attrs) || /\baria-label\s*=/.test(attrs)) {
        iconOk += 1;
      }
    }
  }
}

const rolePct = clickables ? Math.round((withRole / clickables) * 1000) / 10 : 100;
const iconPct = iconNodes ? Math.round((iconOk / iconNodes) * 1000) / 10 : 100;

console.log(`[mp-a11y] clickables with role/aria: ${withRole}/${clickables} (${rolePct}%)`);
console.log(`[mp-a11y] icons with aria-hidden/label: ${iconOk}/${iconNodes} (${iconPct}%)`);
console.log(`[mp-a11y] threshold MIN_ROLE_PCT=${MIN_ROLE_PCT}`);
for (const s of samples) console.log('  sample', s);

if (rolePct < MIN_ROLE_PCT) {
  console.error(`[mp-a11y] FAIL role coverage ${rolePct}% < ${MIN_ROLE_PCT}%`);
  process.exit(1);
}
console.log('[mp-a11y] ok');
