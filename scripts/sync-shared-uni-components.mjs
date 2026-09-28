#!/usr/bin/env node
/**
 * shared-uni 组件分发守卫（C10/M9/C10b）。
 *
 * C10b 后 easycom 与显式 import **直指 `@aicabinet/shared-uni/components/*`**（package exports），
 * 两端 src/components **不再持有本地副本**。本脚本转为「反漂移守卫」：
 *   --check：断言两端 components 目录不存在任何与蓝本同名的本地副本（存在即漂移，exit 1）。
 *   默认模式：输出指引（不再写副本——历史同步行为已废弃，恢复副本=开倒车）。
 */
import { existsSync, readdirSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const CANON = join(ROOT, 'packages/shared-uni/src/components');
const TARGETS = [
  join(ROOT, 'clients/consumer-mp/src/components'),
  join(ROOT, 'clients/merchant-mp/src/components')
];
const CHECK = process.argv.includes('--check');

if (!existsSync(CANON)) {
  console.error('[sync-shared-uni-components] FAIL: missing', CANON);
  process.exit(1);
}

const names = readdirSync(CANON).filter((n) => n.endsWith('.vue'));
const stale = [];

for (const name of names) {
  for (const dir of TARGETS) {
    const dest = join(dir, name);
    if (existsSync(dest)) {
      stale.push(relative(ROOT, dest));
    }
  }
}

if (stale.length) {
  const msg =
    `${stale.length} 个本地副本不应存在（C10b：easycom/import 直指 @aicabinet/shared-uni）：\n` +
    stale.map((p) => `  - ${p}`).join('\n') +
    `\n恢复副本=开倒车。请删除这些文件；若确需本地覆盖，先改本守卫与 debt-tracker 并留痕。`;
  console.error(`[sync-shared-uni-components] FAIL: ${msg}`);
  process.exit(1);
}

if (CHECK) {
  console.log(
    `[sync-shared-uni-components] OK：${names.length} 组件均直指 package，${TARGETS.length} 端无本地副本（C10b/M9 收口）`
  );
  process.exit(0);
}

console.log(
  `[sync-shared-uni-components] done：蓝本 ${names.length} 组件经 package exports 分发（easycom + 显式 import），无需本地副本`
);
