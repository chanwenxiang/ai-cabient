#!/usr/bin/env node
/**
 * `check-placement-event-auth-contract` 的负向验证（证明门禁**真的能红**）。
 *
 * 做法：把 `clients/consumer-mp/src` 下的 .ts/.vue 复制到 `.tmp/placement-event-gate/` 沙箱，
 * 用 `PLACEMENT_GATE_ROOT` 让门禁只扫沙箱；每个用例注入一种违规形态，断言 exit=1 且命中预期规则号。
 *
 * 三条硬要求：
 *   ① 仓库源文件只读不写 —— 收尾用 md5 逐个证明零残留；
 *   ② 不做批量删除（safe-delete 垫片会拦，且本机门禁自测「假红」多半崩在收尾 rmSync）；
 *   ③ 含「注释里写了、代码里没有」的用例 —— 证明扫描前抹注释，不是搜关键词。
 *
 * 用例：
 *   N1 banner 组件改回直连端点                        → P1
 *   N2 删掉 applyTokenSession 的 flush                 → P2
 *   N3 result.vue 的 reportPlacementEvent 只留注释     → P3（自证 maskComments 有效）
 *   N4 队列去掉上限                                    → P4
 *   P  还原原样                                        → exit 0
 *
 * 用法：node scripts/devops/verify-placement-event-auth-contract.mjs
 */
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { dirname, extname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const TAG = '[verify-placement-event-auth-contract]';
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const GATE = join(root, 'scripts', 'check-placement-event-auth-contract.mjs');
const SANDBOX = join(root, '.tmp', 'placement-event-gate');

const REL_SRC = join('clients', 'consumer-mp', 'src');
const REL_API = join(REL_SRC, 'utils', 'consumer-api.ts');
const REL_QUEUE = join(REL_SRC, 'utils', 'placement-event-queue.ts');
const REL_BANNER = join(REL_SRC, 'components', 'marketing-ad-banner.vue');
const REL_AD_BANNER = join(REL_SRC, 'components', 'device-ad-banner.vue');
const REL_RESULT = join(REL_SRC, 'pages', 'result', 'result.vue');

function md5(text) {
  return createHash('md5').update(text).digest('hex');
}

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

function collect(dir, acc = []) {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    const st = statSync(full);
    if (st.isDirectory()) collect(full, acc);
    else if (['.ts', '.vue'].includes(extname(name))) acc.push(full);
  }
  return acc;
}

const sourceFiles = collect(join(root, REL_SRC));
if (sourceFiles.length === 0) fail('源目录里没有 .ts/.vue，路径结构已变');
const originals = new Map(sourceFiles.map((f) => [f, readFileSync(f, 'utf8')]));
const digests = new Map(sourceFiles.map((f) => [f, md5(originals.get(f))]));

/** 把整个 src 树复制进沙箱（只复制被扫描的扩展名）。 */
function resetSandbox() {
  for (const [file, text] of originals) {
    const target = join(SANDBOX, relative(root, file));
    mkdirSync(dirname(target), { recursive: true });
    writeFileSync(target, text);
  }
}

function patch(relPath, fn) {
  const abs = join(root, relPath);
  const before = originals.get(abs);
  if (before == null) fail(`找不到源文件：${relPath}`);
  const after = fn(before);
  if (after === before) fail(`注入未生效：${relPath}（源码结构已变，请同步更新本脚本）`);
  const target = join(SANDBOX, relPath);
  mkdirSync(dirname(target), { recursive: true });
  writeFileSync(target, after);
}

function runGate() {
  const res = spawnSync(process.execPath, [GATE], {
    cwd: root,
    encoding: 'utf8',
    env: { ...process.env, PLACEMENT_GATE_ROOT: SANDBOX }
  });
  return { code: res.status ?? -1, out: `${res.stdout || ''}${res.stderr || ''}` };
}

const cases = [
  {
    name: 'N1 banner 组件改回直连端点',
    rule: 'P1',
    apply: () =>
      patch(REL_BANNER, (s) =>
        s.replace(
          /consumerApi\.reportPlacementEvent\(/,
          'request<void>(ConsumerEndpoints.marketingPlacementEvent, "POST", '
        )
      )
  },
  {
    name: 'N2 删掉登录落定的 flush',
    rule: 'P2',
    apply: () => patch(REL_API, (s) => s.replace(/\n\s*void placementEvents\(\)\.flush\(\);/g, ''))
  },
  {
    name: 'N3 打点入口只留注释（代码已删）',
    rule: 'P3',
    apply: () =>
      patch(REL_RESULT, (s) =>
        s.replace(
          /consumerApi\.reportPlacementEvent\(\{ position, activityId, eventType \}\);/,
          '// consumerApi.reportPlacementEvent({ position, activityId, eventType });'
        )
      )
  },
  {
    name: 'N4 队列去掉上限',
    rule: 'P4',
    apply: () =>
      patch(REL_QUEUE, (s) =>
        s.replace(/DEFAULT_MAX_PENDING/g, 'UNBOUNDED').replace(/maxSize/g, 'limitless')
      )
  },
  {
    name: 'N5 设备投放位去掉登录守卫（游客态直接拉内容）',
    rule: 'P6',
    apply: () =>
      patch(REL_AD_BANNER, (s) => s.replace('if (!isConsumerLoggedIn()) {', 'if (false) {'))
  },
  {
    name: 'N6 登录落定点漏掉设备打点补发与会话就绪广播',
    rule: 'P5',
    apply: () =>
      patch(REL_API, (s) =>
        s
          .replace(/\n\s*void adPlayEvents\(\)\.flush\(\);/, '')
          .replace(/\n\s*consumerSessionReady\.emit\(\);/, '')
      )
  },
  {
    name: 'N7 设备投放位改回直连 deviceAdPlay 端点',
    rule: 'P1',
    apply: () =>
      patch(REL_AD_BANNER, (s) =>
        s.replace(
          /consumerApi\.reportAdPlay\(props\.deviceId, \{/,
          'request<null>(ConsumerEndpoints.deviceAdPlay(props.deviceId), "POST", {'
        )
      )
  },
  {
    name: 'N8 展示位打点队列守卫改回 isConsumerLoggedIn（过期票直发）',
    rule: 'P7',
    apply: () =>
      patch(REL_API, (s) =>
        s.replace(/isLoggedIn: canSendTrackedEventsNow,/, 'isLoggedIn: isConsumerLoggedIn,')
      )
  }
];

let failed = 0;

for (const c of cases) {
  resetSandbox();
  c.apply();
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
resetSandbox();
const green = runGate();
if (green.code !== 0) {
  console.error(`${TAG} FAIL: 还原后门禁仍红（exit=${green.code}）\n${green.out}`);
  failed += 1;
} else {
  console.log(`${TAG} ok  还原原样 → exit 0`);
}

// 零残留：本脚本只读仓库源文件
for (const [file, digest] of digests) {
  if (md5(readFileSync(file, 'utf8')) !== digest) {
    console.error(`${TAG} FAIL: ${relative(root, file)} 被负向脚本改动`);
    failed += 1;
  }
}

if (failed === 0) {
  console.log(`${TAG} 全部通过（${cases.length} 条负向 + 1 条还原 + 源文件 md5 零残留）`);
}
process.exit(failed === 0 ? 0 : 1);
