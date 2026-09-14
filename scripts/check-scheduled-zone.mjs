/**
 * S-P2-7 门禁：@Scheduled(cron=...) 必须带 zone=（含属性占位），防日界用系统默认时区。
 *
 * 用法：node scripts/check-scheduled-zone.mjs
 */
import fs from 'node:fs';
import path from 'node:path';

const roots = [
  path.resolve('services/trade-service/src/main/java'),
  path.resolve('services/device-service/src/main/java')
];

const offenders = [];

function walk(dir, out = []) {
  if (!fs.existsSync(dir)) return out;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      walk(full, out);
      continue;
    }
    if (!entry.name.endsWith('.java')) continue;
    out.push(full);
  }
  return out;
}

/** 匹配可能跨行的 @Scheduled(...)，要求含 cron= 时必须含 zone= */
const scheduledRe = /@Scheduled\s*\(([\s\S]*?)\)/g;

for (const file of roots.flatMap((r) => walk(r))) {
  const text = fs.readFileSync(file, 'utf8');
  let m;
  while ((m = scheduledRe.exec(text))) {
    const attrs = m[1];
    if (!/\bcron\s*=/.test(attrs)) continue;
    if (/\bzone\s*=/.test(attrs)) continue;
    const line = text.slice(0, m.index).split(/\n/).length;
    const rel = path.relative(process.cwd(), file).replace(/\\/g, '/');
    offenders.push(`${rel}:${line} @Scheduled(cron) missing zone=`);
  }
}

if (offenders.length) {
  console.error('S-P2-7 scheduled zone check failed:\n' + offenders.join('\n'));
  process.exit(1);
}
console.log('check-scheduled-zone: ok');
