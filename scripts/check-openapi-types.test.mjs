#!/usr/bin/env node
/**
 * `check-openapi-types.mjs` 的负向用例（批次 T 附带）。
 *
 * 钉的是 2026-11-04 批次 S 那次自伤：regen 把生成物里的中文写成双重编码乱码，
 * 结构与形状门禁全绿，只有 CI 起 HEAD 的 jar 抽真 spec 逐字节比对才红。
 * 现在哨兵必须在本地就把那一类产物拦下。
 *
 * 全程只在 `.tmp` 沙箱副本上跑，不碰真实生成物；每向都断言退出码，不只看日志。
 *
 *   node scripts/check-openapi-types.test.mjs
 */
import { cpSync, existsSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { runAsync } from './lib/async-spawn.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const GATE = join(ROOT, 'scripts', 'check-openapi-types.mjs');
const REAL_DIR = join(ROOT, 'packages', 'shared-types', 'src', 'generated');
const SANDBOX = join(ROOT, '.tmp', 'openapi-types-gate-sandbox');
const TAG = '[check-openapi-types.test]';

let failed = 0;
function check(name, ok, detail) {
  if (ok) {
    console.log(`${TAG} OK ${name}`);
    return;
  }
  failed++;
  console.error(`${TAG} FAIL ${name}${detail ? ' —— ' + detail : ''}`);
}

async function runGate(extraEnv = {}) {
  // 一律走 async-spawn：同步子进程在 WorkBuddy 下 EBUSY ⇒ status=null 会被判成「全红」，
  // 而本测试的判据恰恰是退出码（lessons #292）
  const r = await runAsync(process.execPath, [GATE], {
    cwd: ROOT,
    env: { OPENAPI_GENERATED_DIR: SANDBOX, ...extraEnv }
  });
  return { code: r.status, out: (r.stdout || '') + (r.stderr || '') };
}

/** 双重编码：UTF-8 字节按 latin1 再解一遍——正是批次 S 产物里那种形态。 */
const toMojibake = (zh) => Buffer.from(zh, 'utf8').toString('latin1');

function sandboxCopy() {
  rmSync(SANDBOX, { recursive: true, force: true });
  cpSync(REAL_DIR, SANDBOX, { recursive: true });
}

const realBefore = readFileSync(join(REAL_DIR, 'openapi.ts'), 'utf8');
const gateBefore = readFileSync(GATE, 'utf8');

// 1) 干净副本必须绿（否则沙箱本身不可信，后面几向都没意义）
sandboxCopy();
let r = await runGate();
check('干净生成物副本 → exit 0', r.code === 0, `exit=${r.code} ${r.out.slice(-400)}`);

// 2) 注入双重编码注释 → 必须红，且报出乱码而不是别的
sandboxCopy();
const openapiPath = join(SANDBOX, 'openapi.ts');
const src = readFileSync(openapiPath, 'utf8');
writeFileSync(
  openapiPath,
  src.replace(
    'export interface paths {',
    `/** ${toMojibake('点位费用配置（按柜）')} */\nexport interface paths {`
  )
);
r = await runGate();
check('注入双重编码注释 → exit 1', r.code === 1, `exit=${r.code} ${r.out.slice(-300)}`);
check('红因必须来自哨兵', r.out.includes('双重编码乱码'), r.out.slice(-300));
check('样例要回显乱码首处', /疑似双重编码乱码（样例 "[^"]*"/.test(r.out), r.out.slice(-300));

// 3) 正常中文注释不得误报（哨兵按「 latin1 尾随字节 」判定，不能把 U+4E00 之类算进去）
sandboxCopy();
writeFileSync(
  openapiPath,
  readFileSync(openapiPath, 'utf8').replace(
    'export interface paths {',
    `/** 点位费用配置（按柜）——真正的中文 */\nexport interface paths {`
  )
);
r = await runGate();
check('正常中文注释 → 仍 exit 0', r.code === 0, `exit=${r.code} ${r.out.slice(-300)}`);

// 4) 生成物缺失必须红（哨兵不能把它变成「读不到就跳过」）
sandboxCopy();
rmSync(openapiPath);
r = await runGate();
check('openapi.ts 缺失 → exit 1', r.code === 1 && r.out.includes('missing'), `exit=${r.code}`);

// 5) 沙箱清理 + 真实树未被改动（门禁脚本与生成物逐字节相等）
rmSync(SANDBOX, { recursive: true, force: true });
check('沙箱已删除', !existsSync(SANDBOX));
check(
  '真实 openapi.ts 未被本测试改动',
  readFileSync(join(REAL_DIR, 'openapi.ts'), 'utf8') === realBefore
);
check('门禁脚本未被本测试改动', readFileSync(GATE, 'utf8') === gateBefore);

if (failed) {
  console.error(`${TAG} ${failed} 向未通过`);
  process.exit(1);
}
console.log(`${TAG} 全部通过`);
