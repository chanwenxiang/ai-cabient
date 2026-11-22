#!/usr/bin/env node
/**
 * 门禁：用户复核 P2①/P3-3/P3-4/P3-6 的四条修复不得退化。
 *
 * ① 消费者订阅模板 fail-closed：resolveConsumerTemplateId 不得回落商户模板，
 *    UnpaidOrderService 不得再塞 mock-unpaid-template 假 id。
 * ② autoBind 的厂商 HTTP 查询（resolveTextName）不得在 @Transactional 方法内。
 * ③ 复核态判据唯一实现在 JiangyiMappingReviewService，JiangyiLearningLibraryService 残留类已删。
 * ④ VisionMappingView.load() 仅换柜（resetForm=true）时清空复核表单，筛选栏回车不清。
 *
 * 用法：node scripts/check-consumer-template-and-tx-scope.mjs
 */
import { readFileSync, existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
export const PROPS = join(
  ROOT,
  'services/trade-service/src/main/java/com/aicabinet/trade/config/WeChatMiniAppProperties.java'
);
export const UNPAID = join(
  ROOT,
  'services/trade-service/src/main/java/com/aicabinet/trade/service/UnpaidOrderService.java'
);
export const AUTO = join(
  ROOT,
  'services/trade-service/src/main/java/com/aicabinet/trade/service/JiangyiMappingAutoBindService.java'
);
export const REVIEW = join(
  ROOT,
  'services/trade-service/src/main/java/com/aicabinet/trade/service/JiangyiMappingReviewService.java'
);
export const LEFTOVER = join(
  ROOT,
  'services/trade-service/src/main/java/com/aicabinet/trade/service/JiangyiLearningLibraryService.java'
);
export const VIEW = join(ROOT, 'clients/admin-vue/src/views/vision/VisionMappingView.vue');

/** 等长空格脱敏注释，保行号。 */
export function maskComments(src) {
  let out = '';
  let i = 0;
  while (i < src.length) {
    if (src.startsWith('//', i)) {
      while (i < src.length && src[i] !== '\n') {
        out += ' ';
        i++;
      }
    } else if (src.startsWith('/*', i)) {
      out += '  ';
      i += 2;
      while (i < src.length && !src.startsWith('*/', i)) {
        out += src[i] === '\n' ? '\n' : ' ';
        i++;
      }
      if (i < src.length) {
        out += '  ';
        i += 2;
      }
    } else {
      out += src[i];
      i++;
    }
  }
  return out;
}

/** ① 消费者模板 fail-closed。 */
export function templateProblems(propsSrc, unpaidSrc) {
  const p = maskComments(propsSrc);
  const u = maskComments(unpaidSrc);
  const problems = [];
  const body = /resolveConsumerTemplateId\s*\([^)]*\)\s*\{([\s\S]*?)\n    \}/.exec(p);
  if (!body) {
    problems.push('resolveConsumerTemplateId 方法体定位失败');
  } else if (/:\s*subscribeTemplateId\b/.test(body[1])) {
    problems.push('resolveConsumerTemplateId 仍回落商户模板（两端 appid 不通用，微信回 400）');
  }
  if (/mock-unpaid-template/.test(u)) {
    problems.push('UnpaidOrderService 仍用 mock-unpaid-template 假模板 id（必 400 却记成已发送）');
  }
  return problems;
}

/** ② HTTP 查询不在事务内。 */
export function txProblems(source) {
  const src = maskComments(source);
  const problems = [];
  if (/@Transactional(?!\s*\(readOnly\s*=\s*true)/.test(src)) {
    const autoBind = /public Result autoBind\s*\([^)]*\)\s*\{/.exec(src);
    const before = autoBind ? src.slice(Math.max(0, autoBind.index - 200), autoBind.index) : '';
    if (/@Transactional/.test(before)) {
      problems.push(
        'autoBind 仍标 @Transactional：厂商 HTTP 查询（resolveTextName）会占着数据库连接'
      );
    }
  }
  if (!/txTemplate\.execute\s*\(/.test(src)) {
    problems.push('autoBind 未用 TransactionTemplate 把 DB 写入包成短事务');
  }
  if (!/resolveTextName\s*\(productId\)/.test(src)) {
    problems.push('autoBind 未调用 resolveTextName（识别名取数链断了）');
  }
  return problems;
}

/** ③ 判据唯一实现：残留壳只能委托，不得再写第二份 classesVersion 比较。 */
export function verdictProblems(reviewSrc, leftoverSrc) {
  const problems = [];
  if (!/static boolean isMappingVerified\s*\(\s*JiangyiDevice/.test(maskComments(reviewSrc))) {
    problems.push('JiangyiMappingReviewService 缺 isMappingVerified 唯一判据');
  }
  if (existsSync(LEFTOVER)) {
    const shell = maskComments(leftoverSrc);
    const body = /isMappingVerified\s*\(\s*JiangyiDevice\s+device\s*\)\s*\{([\s\S]*?)\n    \}/.exec(
      shell
    );
    if (!body) {
      problems.push('JiangyiLearningLibraryService 残留壳定位失败');
    } else if (/getClassesVersion\s*\(|getMappingVerifiedVersion\s*\(/.test(body[1])) {
      problems.push(
        '残留壳里仍有第二份复核判据（getClassesVersion/getMappingVerifiedVersion），必须委托给 JiangyiMappingReviewService'
      );
    } else if (!/JiangyiMappingReviewService\.isMappingVerified\s*\(/.test(body[1])) {
      problems.push('残留壳未委托给唯一判据');
    }
  }
  return problems;
}

/** ④ 筛选栏回车不清空表单。 */
export function viewProblems(source) {
  const src = maskComments(source);
  const problems = [];
  if (!/async function load\s*\(\s*resetForm\s*=\s*false\s*\)/.test(src)) {
    problems.push('VisionMappingView.load 缺 resetForm 参数（筛选栏回车会清空复核表单）');
  }
  if (!/if\s*\(\s*resetForm\s*\)\s*\{/.test(src)) {
    problems.push('load 未按 resetForm 条件清空表单');
  }
  const deviceCalls = (src.match(/load\s*\(\s*true\s*\)/g) || []).length;
  if (deviceCalls < 2) {
    problems.push('换柜调用点（applyDeviceFromRoute/onDeviceChange）未传 load(true)');
  }
  return problems;
}

export function run() {
  const problems = [
    ...templateProblems(readFileSync(PROPS, 'utf8'), readFileSync(UNPAID, 'utf8')).map(
      (p) => `template: ${p}`
    ),
    ...txProblems(readFileSync(AUTO, 'utf8')).map((p) => `tx: ${p}`),
    ...verdictProblems(
      readFileSync(REVIEW, 'utf8'),
      existsSync(LEFTOVER) ? readFileSync(LEFTOVER, 'utf8') : ''
    ).map((p) => `verdict: ${p}`),
    ...viewProblems(readFileSync(VIEW, 'utf8')).map((p) => `view: ${p}`)
  ];
  if (problems.length) {
    console.error('[check-consumer-template-and-tx-scope] FAIL:\n' + problems.join('\n'));
    process.exit(1);
  }
  console.log('[check-consumer-template-and-tx-scope] OK：四条复核修复均在位');
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  run();
}
