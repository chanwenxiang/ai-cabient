#!/usr/bin/env node
/**
 * `check-deploy-compose-chain.mjs` 的负向夹具（S8③：改判据必须同批带能红的用例）。
 *
 * 第 6 条是本仓最容易复发的形态：文件头**注释**里写着 `compose build trade-service`，
 * 不剥注释就做位置比对 ⇒ 顺序判据恒假（我自己 2026-11-13 就先踩了这个，夹具把它钉死）。
 *
 *   node scripts/check-deploy-compose-chain.test.mjs
 */
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { evaluate } from './check-deploy-compose-chain.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-deploy-compose-chain.test]';
let checks = 0;
function assert(cond, message) {
  checks++;
  if (!cond) throw new Error(`${TAG} 断言失败：${message}`);
}

const TRACKED = new Set(['infra/docker-compose.yml', 'infra/docker-compose.apps.yml']);
const existsAll = () => true;
const real = readFileSync(resolve(ROOT, 'scripts/deploy-prod.sh'), 'utf8');
// 真实脚本引用 4 个 overlay，全部已入库（AU-185 那次就是把 prod-server overlay 留在服务器上
// 没提交才立的门禁）。这里模拟 `git ls-files` 的结果，而不是硬编码名单——硬编码会在
// 有人新增 overlay 时让夹具假红（假红也是红，但归错因浪费一轮）。
const REAL_TRACKED = new Set([
  ...new Set([...real.matchAll(/-f\s+(infra\/[\w.-]+\.yml)/g)].map((m) => m[1]))
]);

// 1) 真实现网脚本 ⇒ 零问题
{
  assert(
    REAL_TRACKED.size >= 4,
    `夹具前提：应从真实脚本解析出 >=4 个 overlay，实际 ${REAL_TRACKED.size}`
  );
  const p = evaluate(real, REAL_TRACKED, existsAll);
  assert(p.length === 0, `真实 deploy-prod.sh 应判完整：${p.join(' | ')}`);
}

// 2) -f 引用的文件不在盘上 ⇒ 红
{
  const p = evaluate(real, TRACKED, (ref) => ref !== 'infra/docker-compose.apps.yml');
  assert(
    p.some((x) => x.includes('infra/docker-compose.apps.yml') && x.includes('missing on disk')),
    `缺文件必须红：${p.join(' | ')}`
  );
}

// 3) 盘上有但未跟踪（CI 干净克隆会炸）⇒ 红
{
  const p = evaluate(real, new Set(['infra/docker-compose.yml']), existsAll);
  assert(
    p.some((x) => x.includes('NOT tracked in git')),
    `未跟踪文件不得算满足链：${p.join(' | ')}`
  );
}

// 4) 提取器失效（一个 -f 都没有）⇒ 必须红，不能"零引用=零问题"假绿
{
  const p = evaluate('echo hi\n$C build trade-service', TRACKED, existsAll);
  assert(
    p.some((x) => x.includes('extraction rule may be stale')),
    `无 -f 引用必须红：${p.join(' | ')}`
  );
}

// 5) 摘掉漂移对账调用 ⇒ 红（这一步被后人"顺手精简"掉是真实风险）
{
  const noDrift = real.replace('scripts/flyway-drift-check.mjs', 'scripts/__removed__.mjs');
  assert(noDrift !== real, '夹具前提：真实脚本里必须含 flyway-drift-check 调用');
  const p = evaluate(noDrift, TRACKED, existsAll);
  assert(
    p.some((x) => x.includes('不再调用')),
    `漂移对账被摘除必须红：${p.join(' | ')}`
  );
}

// 6) 漂移对账挪到 build 之后 ⇒ 红（顺序才是这条判据的意义）
{
  const driftLine = 'node scripts/flyway-drift-check.mjs --applied-file "$APPLIED_TMP"';
  const reordered = real.replace(driftLine, 'true').concat('\n' + driftLine + '\n');
  const p = evaluate(reordered, TRACKED, existsAll);
  assert(
    p.some((x) => x.includes('之前')),
    `漂移对账排在 build 之后必须红：${p.join(' | ')}`
  );
}

// 7) 关键回归钉：只有**注释**里出现 "build trade-service"、代码里漂移对账在前 ⇒ 必须绿。
//    曾经的实现直接 indexOf(整份脚本)，会被文件头流程注释抢先把 buildAt 提前 ⇒ 恒判顺序错。
{
  const script = [
    '#!/usr/bin/env bash',
    '#   4. 服务器现场 compose build trade-service → up -d   ← 注释，不是执行位置',
    'C="docker compose -f infra/docker-compose.yml -f infra/docker-compose.apps.yml"',
    'node scripts/flyway-drift-check.mjs --applied-file "$APPLIED_TMP"',
    '$C build trade-service'
  ].join('\n');
  const p = evaluate(script, TRACKED, existsAll);
  assert(p.length === 0, `注释不得参与顺序比对（真实脚本头就有这行注释）：${p.join(' | ')}`);
}

console.log(`${TAG} OK：${checks} 项断言全过`);
