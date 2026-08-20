/**
 * Ensure WeChat mp-weixin app.json has lazyCodeLoading.
 * uni `dev` builds sometimes omit the field even when set in manifest.json.
 */
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const roots = process.argv.slice(2);
const targets =
  roots.length > 0
    ? roots
    : [
        join(process.cwd(), 'dist/dev/mp-weixin'),
        join(process.cwd(), 'dist/build/mp-weixin')
      ];

let patched = 0;
for (const dir of targets) {
  const file = join(dir, 'app.json');
  if (!existsSync(file)) continue;
  const json = JSON.parse(readFileSync(file, 'utf8'));
  if (json.lazyCodeLoading === 'requiredComponents') {
    console.log(`ok  ${file}`);
    continue;
  }
  json.lazyCodeLoading = 'requiredComponents';
  writeFileSync(file, `${JSON.stringify(json, null, 2)}\n`);
  patched += 1;
  console.log(`patched  ${file}`);
}
if (!patched && targets.every((d) => !existsSync(join(d, 'app.json')))) {
  console.warn('no app.json found under', targets.join(', '));
}
