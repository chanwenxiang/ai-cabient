#!/usr/bin/env node
/**
 * 门禁：商品页「挂接即生效」必须把 V400 复核态逐柜回显，且提示「先上架再挂接」。
 *
 * 成因（2026-11-21 用户复核）：autoBind 会把柜机映射行挂上 SKU + ACTIVE，明细显示「已挂上」，
 * 但未复核柜的识别仍被结算侧整单转 DISPUTED（JiangyiShelfWriter:91 有 isVerified 闸，
 * autoBind 原来没有）⇒ 运营看到成功却不知还要去识别映射页复核。另：floor-goods 跳过
 * 非在售 SKU，但 classId 兜底仍能按 SKU 价结算（既有口径），需提示先上架再挂接。
 *
 * 这是纯展示门禁：只钉「复核态被读到 + 逐柜显示待复核 + 上架提示存在」，不碰挂接判定与资金逻辑。
 *
 * 判据链（任一处退化即红）：
 *   ① JiangyiMappingAutoBindService 注入并调用 mappingReviewService.isVerified
 *   ② DeviceNote record 带 mappingVerified 字段
 *   ③ 前端 AutoBindNote interface 带 mappingVerified 字段
 *   ④ 前端逐柜明细对 BOUND/ALREADY 且 mappingVerified===false 渲染「待复核」
 *   ⑤ 前端弹窗有「先上架」提示文案
 *
 * 用法：node scripts/check-jiangyi-autobind-verified-hint.mjs
 */
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');

export const SERVICE_FILE = join(
  ROOT,
  'services/trade-service/src/main/java/com/aicabinet/trade/service/JiangyiMappingAutoBindService.java'
);
export const VIEW_FILE = join(ROOT, 'clients/admin-vue/src/views/skus/SkuJiangyiLinkDialog.vue');

/** 等长空格替换注释，保行号；本仓所有静态门禁统一口径。 */
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

/** ① 注入 isVerified 判据，且真调用（注释不算）。 */
export function serviceProblems(source) {
  const src = maskComments(source);
  const problems = [];
  if (!/private\s+final\s+JiangyiMappingReviewService\s+mappingReviewService\s*;/.test(src)) {
    problems.push('autoBind 服务未注入 JiangyiMappingReviewService（复核态无从读取）');
  }
  if (!/mappingReviewService\.isVerified\s*\(\s*deviceId\s*\)/.test(src)) {
    problems.push('autoBind 未调用 isVerified(deviceId)：未复核柜会显示「已挂上」却不结算');
  }
  return problems;
}

/** ② record 带字段。 */
export function noteProblems(source) {
  const src = maskComments(source);
  return /record\s+DeviceNote\s*\([^)]*boolean\s+mappingVerified\s*[,)]/.test(src)
    ? []
    : ['DeviceNote record 缺 mappingVerified 字段（逐柜复核态传不到前端）'];
}

/** ③④⑤ 前端：字段 + 逐柜标记 + 上架提示。 */
export function viewProblems(source) {
  const src = maskComments(source);
  const problems = [];
  if (!/mappingVerified\s*\??:\s*boolean/.test(src)) {
    problems.push('前端 AutoBindNote 缺 mappingVerified 字段');
  }
  if (!/note\.mappingVerified\s*===\s*false/.test(src)) {
    problems.push('逐柜明细未判 mappingVerified===false');
  }
  if (!/待复核/.test(src)) {
    problems.push('逐柜明细缺「待复核」标记');
  }
  if (!/先上架[，,]\s*再挂接/.test(src)) {
    problems.push(
      '弹窗缺「先上架，再挂接」提示（floor-goods 跳过非在售 SKU，但 classId 兜底仍结算）'
    );
  }
  return problems;
}

export function run() {
  const problems = [
    ...serviceProblems(readFileSync(SERVICE_FILE, 'utf8')).map((p) => `service: ${p}`),
    ...noteProblems(readFileSync(SERVICE_FILE, 'utf8')).map((p) => `note: ${p}`),
    ...viewProblems(readFileSync(VIEW_FILE, 'utf8')).map((p) => `view: ${p}`)
  ];
  if (problems.length) {
    console.error('[check-jiangyi-autobind-verified-hint] FAIL:\n' + problems.join('\n'));
    process.exit(1);
  }
  console.log(
    '[check-jiangyi-autobind-verified-hint] OK：autoBind 逐柜回显复核态 + 待复核标记 + 上架提示齐备'
  );
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  run();
}
