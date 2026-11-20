#!/usr/bin/env node
/**
 * check-jiangyi-review-quantity-recovery 的负向夹具：
 * 真树绿 + 五种注入红（缺解析 / 缺回填 / 缺重试 / 前端放宽校验 / 锚点漂移）+ 真树字节零污染。
 */
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { VIEW_FILE, reviewLoaderBody, viewProblems } from './check-jiangyi-review-quantity-recovery.mjs';

const TAG = '[check-jiangyi-review-quantity-recovery.test]';
let checks = 0;
function assert(cond, message) {
  checks++;
  if (!cond) throw new Error(`${TAG} 断言失败：${message}`);
}
const md5 = (p) => createHash('md5').update(readFileSync(p)).digest('hex');

const before = md5(VIEW_FILE);
const view = readFileSync(VIEW_FILE, 'utf8');
assert(view.length > 5000, 'VisionMappingView 读取失败或体积异常（锚点漂移）');

// 真树必须绿
assert(reviewLoaderBody(view) !== null, '真树 loadReviewPlan 必须能定位');
assert(viewProblems(view).length === 0, '真树必须绿');

// ① 不解析后端给出的行数（回到「裸报错」，运营只能自己数文件行数）
const noParse = view.replace('/classes\\s*行数\\s*(\\d+)/.exec(msg)', '/行数\\s*(\\d+)/.exec(msg)');
assert(noParse !== view, '注入锚点「classes 行数」正则找不到');
assert(
  viewProblems(noParse).some((p) => p.includes('未解析后端「classes 行数')),
  '不解析行数必须红'
);

// ② 解析了但不回填（输入框仍是脏值，再点还是同一个错）
const noFill = view.replace('f.quantity = actual;', 'const _ignored = actual;');
assert(noFill !== view, '注入锚点 f.quantity = actual 找不到');
assert(
  viewProblems(noFill).some((p) => p.includes('未把解析出的行数回填')),
  '不回填 quantity 必须红'
);

// ③ 回填了但不重跑（把校正的责任又推回给运营）
const noRetry = view.replace(
  'reviewPlan.value = await requestReviewPlan(actual);',
  'reviewPlan.value = reviewPlan.value;'
);
assert(noRetry !== view, '注入锚点「校正后重跑」找不到');
assert(
  viewProblems(noRetry).some((p) => p.includes('回填后未重跑拉取')),
  '校正后不重跑必须红'
);

// ④ 前端把 quantity 置 0 硬绕过（后端 0 = 跳过行数校验，等于拆闸口）
const bypass = view.replace(
  '  reviewPlanning.value = true;',
  '  const quantity = 0;\n  reviewPlanning.value = true;'
);
assert(bypass !== view, '注入锚点 reviewPlanning.value = true 找不到');
assert(
  viewProblems(bypass).some((p) => p.includes('把 quantity 置 0')),
  '前端置 0 绕过校验必须红（等同拆掉复核闸口）'
);

// ⑤ 函数重命名 → 锚点漂移必须判红，不能静默通过
const renamed = view.replace('async function loadReviewPlan()', 'async function loadReviewPlanV2()');
assert(renamed !== view, '注入锚点函数名找不到');
assert(
  viewProblems(renamed).some((p) => p.includes('函数体定位失败')),
  '函数重命名（锚点漂移）必须红'
);

assert(md5(VIEW_FILE) === before, '真树被写入污染');
console.log(
  `${TAG} OK：${checks} 项断言全过（真树绿 / 五种注入红 / md5 零污染）`
);
