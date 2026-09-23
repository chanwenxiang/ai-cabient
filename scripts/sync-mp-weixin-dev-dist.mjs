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
// 微信开发者工具/OneDrive 可能锁住 dst 目录（Windows EPERM）；带重试，仍失败则降级为覆盖拷贝
try {
  rmSync(dst, { recursive: true, force: true, maxRetries: 10, retryDelay: 300 });
} catch (err) {
  console.warn(
    `[sync-dev-dist] rm 失败（${err.code || err.errno}），降级为覆盖拷贝：dst 可能残留已删除页面的旧文件`
  );
}
cpSync(src, dst, { recursive: true, force: true });
console.log(`synced  ${src} -> ${dst}`);
