#!/usr/bin/env node
/**
 * `check-session-refresh-contract` 的负向验证（证明门禁**真的能红**）。
 *
 * 做法：在 `.tmp/session-refresh-gate/` 沙箱里复制两份源文件，逐个注入违规形态，
 * 用 `SESSION_REFRESH_GATE_ROOT` 让门禁只扫沙箱；每次断言 exit=1 且 stderr 命中预期规则号。
 *
 * 三条硬要求（同 `check-mp-defect-classes` 负向脚本口径）：
 *   ① 仓库源文件只读不写 —— 收尾用 md5 证明零残留；
 *   ② 不做批量删除（safe-delete 垫片会拦；且本机门禁自测「假红」多半就崩在收尾 rmSync）；
 *   ③ 必须包含「注释里写了、代码里没有」的用例 —— 否则 maskComments 退化成
 *      「搜关键词」，注释掉的调用会被当成实现了。
 *
 * 用例：
 *   N1 删掉 `await ensureSessionFresh()`            → R1
 *   N2 只留注释、删掉调用                            → R1（自证 maskComments 有效）
 *   N3 把 UNAUTHORIZED 压平回无条件 reject 401       → R2
 *   N4 `isMpAuthFailure` 改成 `status >= 400`        → R3
 *   P  还原原样                                      → exit 0
 *
 * 用法：node scripts/verify-session-refresh-contract.mjs
 */
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const TAG = '[verify-session-refresh-contract]';
// 本脚本在 scripts/devops/ 下：仓库根是上两级
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const GATE = join(root, 'scripts', 'check-session-refresh-contract.mjs');
const SANDBOX = join(root, '.tmp', 'session-refresh-gate');

const REL_DICT = join('clients', 'merchant-mp', 'src', 'utils', 'dict-runtime.ts');
const REL_REQUEST = join('packages', 'shared-uni', 'src', 'request.ts');

function md5(text) {
  return createHash('md5').update(text).digest('hex');
}

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

const repoDict = readFileSync(join(root, REL_DICT), 'utf8');
const repoRequest = readFileSync(join(root, REL_REQUEST), 'utf8');
const repoDictMd5 = md5(repoDict);
const repoRequestMd5 = md5(repoRequest);

mkdirSync(join(SANDBOX, dirname(REL_DICT)), { recursive: true });
mkdirSync(join(SANDBOX, dirname(REL_REQUEST)), { recursive: true });

function writeSandbox(dict, request) {
  writeFileSync(join(SANDBOX, REL_DICT), dict);
  writeFileSync(join(SANDBOX, REL_REQUEST), request);
}

function runGate() {
  const res = spawnSync(process.execPath, [GATE], {
    cwd: root,
    encoding: 'utf8',
    env: { ...process.env, SESSION_REFRESH_GATE_ROOT: SANDBOX }
  });
  return {
    code: res.status ?? -1,
    out: `${res.stdout || ''}${res.stderr || ''}`
  };
}

/** 把「无条件压平」的违规形态注入：删掉 401/403 分档后重新压成 UNAUTHORIZED。 */
function flattenRefreshBranch(src) {
  const start = src.indexOf('if (res.statusCode === 401 || res.statusCode === 403) {');
  if (start < 0) fail('注入点失效：找不到 401/403 分档（源码结构已变，请同步更新本脚本）');
  const end = src.indexOf('\n        }\n', start);
  if (end < 0) fail('注入点失效：找不到 401/403 分档的闭合括号');
  return src.slice(0, start) + '/* flattened */' + src.slice(end + '\n        }\n'.length);
}

const cases = [
  {
    name: 'N1 删掉冷启动续期调用',
    rule: 'R1',
    dict: (s) => s.replace(/\n\s*await ensureSessionFresh\(\);/g, ''),
    request: (s) => s
  },
  {
    name: 'N2 续期只写在注释里（代码已删）',
    rule: 'R1',
    dict: (s) =>
      s
        .replace(/\n\s*await ensureSessionFresh\(\);/g, '\n  // await ensureSessionFresh();')
        .replace(
          /export async function loadRuntimeDict\(\) \{/,
          'export async function loadRuntimeDict() {'
        ),
    request: (s) => s
  },
  {
    name: 'N3 UNAUTHORIZED 压平回无条件 reject 401',
    rule: 'R2',
    dict: (s) => s,
    request: flattenRefreshBranch
  },
  {
    name: 'N4 isMpAuthFailure 改成 status >= 400',
    rule: 'R3',
    dict: (s) => s,
    request: (s) =>
      s.replace(
        /if \(e\.status === 401 \|\| e\.status === 403\) return true;/,
        'if ((e.status as number) >= 400) return true;'
      )
  }
];

let failed = 0;

for (const c of cases) {
  const dictPatched = c.dict(repoDict);
  const requestPatched = c.request(repoRequest);
  if (dictPatched === repoDict && requestPatched === repoRequest) {
    console.error(`${TAG} FAIL: ${c.name} —— 注入未生效（源码结构已变，请同步更新本脚本）`);
    failed += 1;
    continue;
  }
  writeSandbox(dictPatched, requestPatched);
  const { code, out } = runGate();
  const red = code === 1 && out.includes(c.rule);
  if (red) {
    console.log(`${TAG} ok  ${c.name} → exit 1 / ${c.rule}`);
  } else {
    console.error(`${TAG} FAIL: ${c.name} 未按预期变红（exit=${code}）\n${out}`);
    failed += 1;
  }
}

// 还原：门禁必须重新变绿
writeSandbox(repoDict, repoRequest);
const green = runGate();
if (green.code !== 0) {
  console.error(`${TAG} FAIL: 还原后门禁仍红（exit=${green.code}）\n${green.out}`);
  failed += 1;
} else {
  console.log(`${TAG} ok  还原原样 → exit 0`);
}

// 零残留：门禁/本脚本都不得改动仓库源文件
if (md5(readFileSync(join(root, REL_DICT), 'utf8')) !== repoDictMd5) {
  console.error(`${TAG} FAIL: ${REL_DICT} 被负向脚本改动`);
  failed += 1;
}
if (md5(readFileSync(join(root, REL_REQUEST), 'utf8')) !== repoRequestMd5) {
  console.error(`${TAG} FAIL: ${REL_REQUEST} 被负向脚本改动`);
  failed += 1;
}
if (failed === 0) console.log(`${TAG} 全部通过（${cases.length} 条负向 + 1 条还原 + md5 零残留）`);
process.exit(failed === 0 ? 0 : 1);
