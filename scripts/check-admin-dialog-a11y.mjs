/**
 * A-P2-008：el-dialog / el-drawer / ResizableDrawer 必须有 title 或 aria-label / aria-labelledby。
 * ResizableDrawer.vue 自身转发 attrs，跳过内部 el-drawer；调用方须带 title。
 *
 * 用法：node scripts/check-admin-dialog-a11y.mjs
 */
import fs from 'node:fs';
import path from 'node:path';

const roots = [
  path.resolve('clients/admin-vue/src/views'),
  path.resolve('clients/admin-vue/src/components'),
  path.resolve('clients/admin-vue/src/layouts')
];
const SKIP_FILES = new Set([
  path.resolve('clients/admin-vue/src/components/ResizableDrawer.vue').replace(/\\/g, '/')
]);

const offenders = [];

function walk(dir, out = []) {
  if (!fs.existsSync(dir)) return out;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      walk(full, out);
      continue;
    }
    if (!entry.name.endsWith('.vue')) continue;
    out.push(full);
  }
  return out;
}

const tagRe = /<(el-dialog|el-drawer|ResizableDrawer)\b([\s\S]*?)>/gi;

for (const file of roots.flatMap((r) => walk(r))) {
  const norm = file.replace(/\\/g, '/');
  if (SKIP_FILES.has(norm)) continue;
  const text = fs.readFileSync(file, 'utf8');
  let m;
  while ((m = tagRe.exec(text))) {
    const attrs = m[2];
    if (/title\s*=/.test(attrs) || /aria-label\s*=/.test(attrs) || /aria-labelledby\s*=/.test(attrs)) {
      continue;
    }
    const line = text.slice(0, m.index).split(/\n/).length;
    const rel = path.relative(process.cwd(), file).replace(/\\/g, '/');
    offenders.push(`${rel}:${line} <${m[1]}> missing title|aria-label|aria-labelledby`);
  }
}

if (offenders.length) {
  console.error('A-P2-008 dialog/drawer a11y check failed:\n' + offenders.join('\n'));
  process.exit(1);
}
console.log('check-admin-dialog-a11y: ok');
