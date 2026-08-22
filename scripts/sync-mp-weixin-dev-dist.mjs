/**
 * uni `build --mode development` 仍写入 dist/build/mp-weixin；
 * 真机调试通常导入 dist/dev/mp-weixin。本脚本把 build 同步到 dev，避免沿用过期产物。
 */
import { cpSync, existsSync, rmSync } from 'node:fs';
import { join } from 'node:path';

const root = process.cwd();
const src = join(root, 'dist/build/mp-weixin');
const dst = join(root, 'dist/dev/mp-weixin');
if (!existsSync(src)) {
  console.warn('skip sync: missing', src);
  process.exit(0);
}
rmSync(dst, { recursive: true, force: true });
cpSync(src, dst, { recursive: true });
console.log(`synced  ${src} -> ${dst}`);
