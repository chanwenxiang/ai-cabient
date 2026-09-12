/**
 * T-C7：将常见业务硬编码色替换为 design token（跳过 var(--x, #fallback) 内的 fallback）。
 * 支持 #fff / #ffffff → var(--white)；跳过 `--token: #hex` 定义行。
 * 图表色不在此脚本范围；admin Analytics 等保留。
 *
 * 用法：
 *   node scripts/migrate-hex-tokens.mjs
 *   node scripts/migrate-hex-tokens.mjs --white-only   # 仅清 #fff/#ffffff
 */
import fs from 'node:fs';
import path from 'node:path';

const WHITE_ONLY = process.argv.includes('--white-only');

const HEX_TO_TOKEN = {
  '#0f766e': 'var(--brand)',
  '#134e4a': 'var(--brand-deep)',
  '#047857': 'var(--brand)',
  '#059669': 'var(--brand)',
  '#065f46': 'var(--brand-deep)',
  '#0d9488': 'var(--brand)',
  '#14b8a6': 'var(--brand)',
  '#10b981': 'var(--success)',
  '#16a34a': 'var(--success)',
  '#34d399': 'var(--success)',
  '#ecfdf5': 'var(--brand-soft)',
  '#ccfbf1': 'var(--brand-mist)',
  '#d1fae5': 'var(--brand-soft)',
  '#b91c1c': 'var(--danger)',
  '#dc2626': 'var(--danger)',
  '#ef4444': 'var(--danger)',
  '#991b1b': 'var(--danger)',
  '#ff3b30': 'var(--danger)',
  '#f87171': 'var(--danger)',
  '#b45309': 'var(--warning)',
  '#d97706': 'var(--warning)',
  '#ca8a04': 'var(--warning)',
  '#f59e0b': 'var(--warning)',
  '#c2410c': 'var(--accent-orange)',
  '#ff6b35': 'var(--accent-orange)',
  '#ff9500': 'var(--accent-orange)',
  '#ff8f00': 'var(--accent-orange)',
  '#0f172a': 'var(--text-primary)',
  '#14201b': 'var(--text-primary)',
  '#1f2937': 'var(--text-primary)',
  '#111827': 'var(--text-primary)',
  '#374151': 'var(--text-primary)',
  '#334155': 'var(--text-primary)',
  '#333333': 'var(--text-primary)',
  '#333': 'var(--text-primary)',
  '#64748b': 'var(--text-muted)',
  '#6b7280': 'var(--text-muted)',
  '#475569': 'var(--text-muted)',
  '#53645b': 'var(--text-muted)',
  '#68766e': 'var(--text-muted)',
  '#576b95': 'var(--color-link-secondary)',
  '#94a3b8': 'var(--text-subtle)',
  '#9aa4a0': 'var(--text-subtle)',
  '#a1aaa5': 'var(--text-subtle)',
  '#909399': 'var(--text-subtle)',
  '#a5b4c8': 'var(--text-subtle)',
  '#cbd5e1': 'var(--text-subtle)',
  '#e2e8f0': 'var(--card-border)',
  '#e5e5e5': 'var(--card-border)',
  '#e5e7eb': 'var(--card-border)',
  '#e4e7ed': 'var(--card-border)',
  '#eee': 'var(--card-border)',
  '#eeeeee': 'var(--card-border)',
  '#dcdfe6': 'var(--card-border)',
  '#dceee6': 'var(--brand-soft)',
  '#e7eeea': 'var(--color-border-subtle)',
  '#eef2f0': 'var(--color-border-subtle)',
  '#eef2ef': 'var(--color-border-subtle)',
  '#e3eae6': 'var(--color-border-subtle)',
  '#e4ebe7': 'var(--color-border-subtle)',
  '#f3f4f6': 'var(--color-border-subtle)',
  '#f1f5f9': 'var(--color-border-subtle)',
  '#f5f5f5': 'var(--color-border-subtle)',
  '#f0f0f0': 'var(--color-border-subtle)',
  '#f4f7f5': 'var(--surface-muted)',
  '#f7faf8': 'var(--surface-muted)',
  '#f8fafc': 'var(--surface-muted)',
  '#f5f7fa': 'var(--surface-muted)',
  '#f0fdfa': 'var(--page-bg)',
  '#ffffff': 'var(--white)',
  '#fff': 'var(--white)',
  '#0369a1': 'var(--info)',
  '#2563eb': 'var(--info)',
  '#0958d9': 'var(--info)',
  '#38bdf8': 'var(--info)',
  '#0ea5e9': 'var(--info)',
  '#e0f2fe': 'var(--info-soft)',
  '#eff6ff': 'var(--info-soft)',
  '#e6f4ff': 'var(--info-soft)',
  '#bae0ff': 'var(--info-soft)',
  '#fff7ed': 'var(--warning-soft)',
  '#fff7e6': 'var(--warning-soft)',
  '#fff8e6': 'var(--warning-soft)',
  '#fff3e0': 'var(--warning-soft)',
  '#fef3c7': 'var(--warning-soft)',
  '#fefce8': 'var(--warning-soft)',
  '#ffedd5': 'var(--warning-soft)',
  '#fde68a': 'var(--warning-soft)',
  '#ffe58f': 'var(--warning-soft)',
  '#a16207': 'var(--warning)',
  '#92400e': 'var(--warning)',
  '#78350f': 'var(--warning)',
  '#fef2f2': 'var(--danger-soft)',
  '#ffecec': 'var(--danger-soft)',
  '#fee2e2': 'var(--danger-soft)',
  '#ffe4e6': 'var(--danger-soft)',
  '#fce7f3': 'var(--accent-rose-soft)',
  '#be185d': 'var(--accent-rose)',
  '#9f1239': 'var(--accent-rose)',
  '#fb7185': 'var(--accent-rose)',
  '#07c160': 'var(--brand-wx)',
  '#1677ff': 'var(--brand-alipay)',
  '#d4d4d4': 'var(--card-border)',
  '#b2b2b2': 'var(--text-subtle)',
  '#ccc': 'var(--card-border)',
  '#bbbbbb': 'var(--text-subtle)',
  '#bbb': 'var(--text-subtle)',
  '#555': 'var(--text-muted)',
  '#555555': 'var(--text-muted)',
  '#eab308': 'var(--warning)',
  '#fcd34d': 'var(--warning-soft)',
  '#2dd4bf': 'var(--chart-1)',
  '#5eead4': 'var(--chart-1)',
  '#60a5fa': 'var(--chart-2)',
  '#fbbf24': 'var(--chart-3)',
  '#a78bfa': 'var(--chart-4)',
  '#f97316': 'var(--chart-5)',
  '#888': 'var(--text-subtle)',
  '#888888': 'var(--text-subtle)',
  '#111': 'var(--text-primary)',
  '#111111': 'var(--text-primary)',
  '#26342d': 'var(--text-primary)',
  '#173026': 'var(--text-primary)',
  '#ad6800': 'var(--warning)',
  '#d48806': 'var(--warning)',
  '#9ca3af': 'var(--text-subtle)',
  '#d1d5db': 'var(--card-border)',
  '#e8eef2': 'var(--color-border-subtle)',
  '#f2f3f5': 'var(--surface-muted)',
  '#f2f4f8': 'var(--surface-muted)',
  '#eef6f2': 'var(--brand-soft)',
  '#f4fef8': 'var(--brand-soft)',
  '#fffaf0': 'var(--warning-soft)',
  '#fffdf5': 'var(--warning-soft)',
  '#fff5f5': 'var(--danger-soft)',
  '#fff7f7': 'var(--danger-soft)',
  '#fff1f2': 'var(--danger-soft)',
  '#bbf7d0': 'var(--brand-mist)',
  '#ffd591': 'var(--warning-soft)',
  '#9a5b39': 'var(--accent-orange)'
};

const TARGET_DIRS = [
  'clients/consumer-mp/src',
  'clients/merchant-mp/src',
  'packages/shared-uni/src/components'
];

/** theme.css 是 token 源，不改；App.vue 允许改用法，跳过定义行 */
const SKIP_NAMES = new Set(['theme.css']);

function protectFallbacks(src) {
  const holes = [];
  const out = src.replace(/var\([^)]*#[0-9a-fA-F]+[^)]*\)/g, (m) => {
    const i = holes.length;
    holes.push(m);
    return `__VAR_HOLE_${i}__`;
  });
  return { out, holes };
}

function restoreFallbacks(src, holes) {
  return src.replace(/__VAR_HOLE_(\d+)__/g, (_, i) => holes[Number(i)]);
}

function isTokenDefinitionLine(src, matchIndex) {
  const lineStart = src.lastIndexOf('\n', matchIndex - 1) + 1;
  const lineEnd = src.indexOf('\n', matchIndex);
  const line = src.slice(lineStart, lineEnd === -1 ? undefined : lineEnd);
  return /^\s*--[\w-]+:/.test(line);
}

function replaceHex(cssLike) {
  let n = 0;
  const { out, holes } = protectFallbacks(cssLike);
  let text = out.replace(/#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})\b/g, (hex, offset) => {
    if (isTokenDefinitionLine(out, offset)) return hex;
    const key = hex.toLowerCase();
    const full =
      key.length === 4
        ? `#${key[1]}${key[1]}${key[2]}${key[2]}${key[3]}${key[3]}`
        : key;
    if (full === '#000000' || full === '#000') return hex;
    if (WHITE_ONLY && full !== '#ffffff') return hex;
    const token = HEX_TO_TOKEN[full] || HEX_TO_TOKEN[key];
    if (!token) return hex;
    n += 1;
    return token;
  });
  text = restoreFallbacks(text, holes);
  return { text, n };
}

function walk(dir, files = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (entry.name === 'node_modules' || entry.name === 'dist') continue;
      walk(full, files);
    } else if (/\.(vue|css)$/.test(entry.name) && !SKIP_NAMES.has(entry.name)) {
      files.push(full);
    }
  }
  return files;
}

let total = 0;
const touched = [];
for (const root of TARGET_DIRS) {
  const abs = path.resolve(root);
  if (!fs.existsSync(abs)) continue;
  for (const file of walk(abs)) {
    const before = fs.readFileSync(file, 'utf8');
    const { text, n } = replaceHex(before);
    if (n > 0) {
      fs.writeFileSync(file, text);
      total += n;
      touched.push(`${path.relative(process.cwd(), file)}:${n}`);
    }
  }
}

console.log(
  `Replaced ${total} hex → token in ${touched.length} files${WHITE_ONLY ? ' (white-only)' : ''}`
);
for (const t of touched.slice(0, 50)) console.log(' ', t);
if (touched.length > 50) console.log(`  … +${touched.length - 50} more`);
