/**
 * S-P2-4 门禁：CacheService get/evict 的 prefix 必须用 CacheNames 常量，禁止裸字符串。
 *
 * 用法：node scripts/check-cache-names.mjs
 */
import fs from 'node:fs';
import path from 'node:path';

const roots = [path.resolve('services/trade-service/src/main/java')];

/** 定义 CacheNames 常量的文件本身可含字面量。 */
const SKIP_FILES = new Set([
  path
    .resolve('services/trade-service/src/main/java/com/aicabinet/trade/support/CacheNames.java')
    .replace(/\\/g, '/')
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
    if (!entry.name.endsWith('.java')) continue;
    out.push(full);
  }
  return out;
}

/**
 * cacheService.get("…") / .evict("…") / CacheService 变量链式调用带字面量 prefix。
 * 允许：get(CacheNames.XXX, …) / evict(CacheNames.XXX)
 */
const callRe =
  /(?:cacheService(?:\(\))?|\w*[Cc]ache\w*)\s*\.\s*(get|evict)\s*\(\s*("([^"\\]|\\.)*"|'([^'\\]|\\.)*')/g;

for (const file of roots.flatMap((r) => walk(r))) {
  const norm = file.replace(/\\/g, '/');
  if (SKIP_FILES.has(norm)) continue;
  const text = fs.readFileSync(file, 'utf8');
  let m;
  while ((m = callRe.exec(text))) {
    const line = text.slice(0, m.index).split(/\n/).length;
    const rel = path.relative(process.cwd(), file).replace(/\\/g, '/');
    offenders.push(`${rel}:${line} CacheService.${m[1]}( literal prefix — use CacheNames.* )`);
  }
}

if (offenders.length) {
  console.error('S-P2-4 cache names check failed:\n' + offenders.join('\n'));
  process.exit(1);
}
console.log('check-cache-names: ok');
