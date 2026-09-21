/**
 * uni-app mp-weixin + pnpm workspace：跨包 .vue 会生成带 ../ 的 chunkFileNames，
 * Rollup 校验失败。编译前给 @dcloudio/uni-cli-shared 打一次性补丁。
 */
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(scriptDir, '..');

/**
 * pnpm 是**严格结构**：`@dcloudio/uni-cli-shared` 只对声明它的那个 workspace 包可见
 * （`clients/merchant-mp/node_modules/@dcloudio/uni-cli-shared` 是 symlink），
 * **仓库根 node_modules 里没有它**。
 *
 * 早期版本用 `createRequire(import.meta.url)`（＝从 `scripts/` 解析）⇒ 恒解析不到
 * ⇒ 只打一行 warn 就 `exit(0)`：**看起来跑过了、其实什么都没做**，
 * 于是 `uni build -p mp-weixin` 继续以
 * `Invalid pattern "../../../packages/shared-uni/..." for "output.chunkFileNames"` 失败。
 * （典型消音失败：退出码 0 + 只有 warn，很容易被判成「补丁已应用」。）
 *
 * 修法：按「谁装了它就先问谁」的顺序逐个候选根解析，全部失败才报错退出（exit 1），
 * 不再静默假成功。
 */
const candidateManifests = [
  path.join(repoRoot, 'clients/merchant-mp/package.json'),
  path.join(process.cwd(), 'package.json'),
  path.join(repoRoot, 'package.json')
];

let utilsPath = null;
for (const manifest of candidateManifests) {
  if (!fs.existsSync(manifest)) continue;
  try {
    utilsPath = createRequire(manifest).resolve('@dcloudio/uni-cli-shared/dist/utils.js');
    break;
  } catch {
    // 换下一个候选根。
  }
}
if (!utilsPath) {
  console.error(
    '[patch-uni-mp-workspace] uni-cli-shared not found from any of: ' +
      candidateManifests.join(', ') +
      '\n  请先在 clients/merchant-mp 执行依赖安装（pnpm install）。'
  );
  process.exit(1);
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
