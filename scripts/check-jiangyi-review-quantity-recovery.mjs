#!/usr/bin/env node
/**
 * 门禁：将邑「识别映射复核」拉取差异失败时，必须按后端给出的真实行数校正类目数并重跑。
 *
 * 成因（2026-11-20 生产实测 EG858564255060）：厂商模型档案里的 quantity 是脏数据——该柜
 * 模型声明 1 类，而它自己的 classes.txt 实有 69 行。复核表单的类目数初值是 1，进页兜底与
 * 「从模型带出」也都按档案填，于是运营看到表单「都填好了」，一点「拉取差异」就必然撞
 * 「classes 行数 69 ≠ 模型 quantity 1」，且只有去数文件行数才知道该填几 —— 结算闸口
 * 事实上再次走不通（与 MA-372 同族：闸口本身没错，是没人能填对输入）。
 *
 * 判据（三者同时成立才算修好，缺一即退回「裸报错」）：
 *   ① loadReviewPlan 的失败分支解析出后端给出的行数（报错文案含「classes 行数 N」）；
 *   ② 把该行数回填进复核表单的类目数（quantity）；
 *   ③ 回填后重跑一次拉取（只读 GET，无副作用），而不是让运营自己再点一次。
 *
 * 不放宽校验：「行数 == quantity」仍由后端 JiangyiClassesParser 判定，前端只把真值送进
 * 输入框。因此本门禁同时要求前端**不得**在本地自行放行（不出现把 quantity 强行置 0 /
 * 跳过请求的分支）。
 *
 * 用法：node scripts/check-jiangyi-review-quantity-recovery.mjs
 */
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { maskComments } from './check-jiangyi-txt-url-default.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');

export const VIEW_FILE = join(ROOT, 'clients/admin-vue/src/views/vision/VisionMappingView.vue');

/** 剥注释后取 loadReviewPlan 函数体；定位失败返回 null（锚点漂移要人看，不能静默通过）。 */
export function reviewLoaderBody(source) {
  const src = maskComments(source);
  const m = /async function loadReviewPlan\s*\(\s*\)\s*\{([\s\S]*?)\n\}/.exec(src);
  return m ? m[1] : null;
}

export function viewProblems(source) {
  const body = reviewLoaderBody(source);
  if (body === null) {
    return ['loadReviewPlan 函数体定位失败（锚点漂移，人工确认是否重命名/改签名）'];
  }
  const problems = [];

  // ① 解析后端报错里的真实行数
  if (!body.includes('classes\\s*行数')) {
    problems.push(
      'loadReviewPlan 未解析后端「classes 行数 N」——厂商档案 quantity 是脏数据时运营只能自己数行'
    );
  }

  // ② 回填类目数（必须是把解析结果写回 quantity，不是写个局部变量就完事）
  if (!/\.quantity\s*=\s*actual\b/.test(body)) {
    problems.push('未把解析出的行数回填到复核表单 quantity（输入框仍是脏值，再点还是同一个错）');
  }

  // ③ 回填后必须重跑一次拉取
  const calls = body.split('requestReviewPlan(').length - 1;
  if (calls < 2) {
    problems.push(
      `回填后未重跑拉取（requestReviewPlan 调用 ${calls} 处，需 1 处首次 + 1 处校正后重试）`
    );
  }

  // ④ 不得在前端放宽校验：把 quantity 置 0（后端 0 = 跳过行数校验）等于拆掉闸口
  if (/quantity\s*[:=]\s*(?:'0'|"0"|0)\b/.test(body.replace(/\.quantity\s*=\s*actual\b/g, ''))) {
    problems.push('前端把 quantity 置 0 —— 后端 0 表示跳过行数校验，等于拆掉复核闸口');
  }

  return problems;
}

export function run() {
  const problems = viewProblems(readFileSync(VIEW_FILE, 'utf8')).map((p) => `view: ${p}`);
  if (problems.length) {
    console.error('[check-jiangyi-review-quantity-recovery] FAIL:\n' + problems.join('\n'));
    process.exit(1);
  }
  console.log(
    '[check-jiangyi-review-quantity-recovery] OK：拉取差异失败会按后端真实行数校正类目数并自动重跑'
  );
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  run();
}
