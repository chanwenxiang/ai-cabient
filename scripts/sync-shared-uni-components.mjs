#!/usr/bin/env node
/**
 * 将 packages/shared-uni/src/components 权威蓝本同步到两端小程序本地副本。
 * uni easycom 需本地路径（pages.json），禁止靠手拷贝（C10 / M9）。
 *
 *   node scripts/sync-shared-uni-components.mjs
 *   node scripts/sync-shared-uni-components.mjs --check   # 仅断言一致，exit 1 若漂移
 */
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const CANON = join(ROOT, 'packages/shared-uni/src/components');
const TARGETS = [
  join(ROOT, 'clients/consumer-mp/src/components'),
  join(ROOT, 'clients/merchant-mp/src/components')
];
const CHECK = process.argv.includes('--check');
const HEADER = `<!--
  Canonical: packages/shared-uni/src/components/{name}
  Keep in sync (uni easycom 需本地路径). 同步：node scripts/sync-shared-uni-components.mjs
-->
`;

function sha(s) {
  return createHash('sha256').update(s).digest('hex').slice(0, 12);
}

function stripHeader(src) {
  return src.replace(/^<!--[\s\S]*?-->\s*/, '').replace(/\r\n/g, '\n');
}

function withHeader(name, body) {
  return HEADER.replace('{name}', name) + body.replace(/\r\n/g, '\n').replace(/^\uFEFF/, '');
}

if (!existsSync(CANON)) {
  console.error('[sync-shared-uni-components] FAIL: missing', CANON);
  process.exit(1);
}

const names = readdirSync(CANON).filter((n) => n.endsWith('.vue'));
let drifted = 0;
let written = 0;

for (const name of names) {
  const canonBody = stripHeader(readFileSync(join(CANON, name), 'utf8'));
  const next = withHeader(name, canonBody);
  for (const dir of TARGETS) {
    if (!existsSync(dir)) mkdirSync(dir, { recursive: true });
    const dest = join(dir, name);
    const prev = existsSync(dest) ? readFileSync(dest, 'utf8') : '';
    const same = stripHeader(prev) === canonBody;
    if (!same) {
      drifted++;
      if (CHECK) {
        console.error(
          `[sync-shared-uni-components] DRIFT ${relative(ROOT, dest)} (canon=${sha(canonBody)} local=${sha(stripHeader(prev))})`
        );
      } else {
        writeFileSync(dest, next.endsWith('\n') ? next : next + '\n');
        written++;
        console.log(`[sync-shared-uni-components] wrote ${relative(ROOT, dest)}`);
      }
    }
  }
}

if (CHECK) {
  if (drifted) {
    console.error(
      `[sync-shared-uni-components] FAIL: ${drifted} 处副本与蓝本不一致；请运行 node scripts/sync-shared-uni-components.mjs`
    );
    process.exit(1);
  }
  console.log(
    `[sync-shared-uni-components] OK：${names.length} 组件 × ${TARGETS.length} 端与蓝本一致`
  );
  process.exit(0);
}

console.log(
  `[sync-shared-uni-components] done：蓝本 ${names.length}，写入 ${written}，已一致跳过 ${names.length * TARGETS.length - written}`
);
