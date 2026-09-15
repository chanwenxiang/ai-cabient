/**
 * uni-app mp-weixin + pnpm workspace：跨包 .vue 会生成带 ../ 的 chunkFileNames，
 * Rollup 校验失败。编译前给 @dcloudio/uni-cli-shared 打一次性补丁。
 */
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
let utilsPath;
try {
  utilsPath = require.resolve('@dcloudio/uni-cli-shared/dist/utils.js');
} catch {
  console.warn('[patch-uni-mp-workspace] uni-cli-shared not found, skip');
  process.exit(0);
}

const marker = "str.replace(/\\.\\.\\//g, '')";
let src = fs.readFileSync(utilsPath, 'utf8');
if (src.includes(marker) || src.includes('str.replace(/\\.\\.\\//g, "")')) {
  console.log('[patch-uni-mp-workspace] already patched');
  process.exit(0);
}

const needle = 'function normalizeNodeModules(str) {';
const idx = src.indexOf(needle);
if (idx < 0) {
  console.warn('[patch-uni-mp-workspace] normalizeNodeModules not found, skip');
  process.exit(0);
}
const retIdx = src.indexOf('return str;', idx);
if (retIdx < 0 || retIdx - idx > 800) {
  console.warn('[patch-uni-mp-workspace] unexpected normalizeNodeModules shape, skip');
  process.exit(0);
}
src =
  src.slice(0, retIdx) +
  "    // ai-cabinet workspace: strip ../ for mp chunkFileNames\n    str = str.replace(/\\.\\.\\//g, '');\n    " +
  src.slice(retIdx);
fs.writeFileSync(utilsPath, src);
console.log('[patch-uni-mp-workspace] patched', path.relative(process.cwd(), utilsPath));
