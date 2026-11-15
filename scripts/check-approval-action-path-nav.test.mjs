#!/usr/bin/env node
/**
 * `check-approval-action-path-nav.mjs` 规则 E（MA-279）的负向夹具。
 *
 *   node scripts/check-approval-action-path-nav.test.mjs
 */
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { casePaths, mismatchedActionPaths } from './lib/dashboard-action-path.mjs';

const TAG = '[check-approval-action-path-nav.test]';
let checks = 0;
function assert(cond, message) {
  checks++;
  if (!cond) throw new Error(`${TAG} 断言失败：${message}`);
}
const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const DASH = resolve(ROOT, 'clients/admin-vue/src/views/dashboard/DashboardView.vue');

// 1) 真仓面：判据路径与跳转路径逐 case 一致
{
  const src = readFileSync(DASH, 'utf8');
  const bad = mismatchedActionPaths(src);
  assert(bad.length === 0, `真仓面仍有不同源的 case：${bad.join(' | ')}`);
  const target = casePaths(src, 'actionTargetPath');
  const action = casePaths(src, 'goAction');
  assert(
    target.get('LOW_STOCK') === '/stock-health',
    `LOW_STOCK 判据必须与实际跳转同为 /stock-health，实际 ${target.get('LOW_STOCK')}`
  );
  assert(action.get('LOW_STOCK') === '/stock-health', 'goAction 的 LOW_STOCK 跳转路径');
  assert(target.get('REPLENISHMENT') === '/replenishment', 'REPLENISHMENT 不受影响');
}

// 2) 负向：把 LOW_STOCK 判据改回 /replenishment ⇒ 必须报不一致（这是原缺陷的字面形态）
{
  const synthetic = `
function actionTargetPath(row) {
  switch (row.type) {
    case 'LOW_STOCK':
      return '/replenishment';
    case 'REPLENISHMENT':
      return '/replenishment';
    default:
      return '/devices';
  }
}
function goAction(row) {
  switch (row.type) {
    case 'LOW_STOCK':
      router.push({ path: '/stock-health', query: q });
      break;
    case 'REPLENISHMENT':
      router.push({ path: '/replenishment' });
      break;
  }
}
`;
  const bad = mismatchedActionPaths(synthetic);
  assert(
    bad.some((b) => b.startsWith('LOW_STOCK:')),
    `改回旧路径必须红：${bad.join('|')}`
  );
  assert(!bad.some((b) => b.startsWith('REPLENISHMENT:')), '同源的 case 不该误报');
}

// 3) 连续 case 共享同一段时，每个 case 都要拿到路径（防「空段被吞」造成假绿）
{
  const src = `
function actionTargetPath(row) {
  switch (row.type) {
    case 'A':
    case 'B':
      return '/shared';
    default:
      return '/devices';
  }
}
function goAction(row) {
  switch (row.type) {
    case 'A':
      router.push({ path: '/shared' });
      break;
    default:
      break;
  }
}
`;
  const paths = casePaths(src, 'actionTargetPath');
  assert(
    paths.get('A') === '/shared' && paths.get('B') === '/shared',
    `连续 case 都要解析到路径：${JSON.stringify([...paths])}`
  );
}

// 4) 锚点失效必须红（函数改名/整段删掉时不能静默判绿）
{
  const bad = mismatchedActionPaths('const x = 1;');
  assert(bad.length > 0, '两个函数体都找不到时必须报告判据失效');
}

console.log(`${TAG} OK：${checks} 项断言全过`);
