#!/usr/bin/env node
/**
 * 门禁：设备自报的当前模型 classes.txt 地址必须落库，并在后台复核表单默认带出。
 *
 * 成因（2026-11-20 生产实查 EG858564255060）：`receiveCurrentTxtUrl` 报回的 txtUrl 只打
 * 日志、不落库；而「复核（结算闸口）」的 classes 地址只能人工手抄，厂商模型库
 * `getModelFileByApi` 对出厂预置模型返回空数组 ⇒ 运营既无候选也无从得知，闸口走不通。
 *
 * 判据链（任一处退化都会让运营退回手抄）：
 *   ① 迁移建列 jiangyi_device.current_txt_url
 *   ② 实体有 currentTxtUrl 字段
 *   ③ confirmModelByTxtUrl 里落库，且写入前经 absoluteTxtUrl 拼成绝对地址
 *   ④ 绑定视图 DTO 透出（toDto 传 getCurrentTxtUrl）
 *   ⑤ 后台复核表单用 currentTxtUrl 兜底填 classes 地址
 *
 * 用法：node scripts/check-jiangyi-txt-url-default.mjs
 */
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const MIGRATION_DIR = join(ROOT, 'services/trade-service/src/main/resources/db/migration');

export const SYNC_FILE = join(
  ROOT,
  'services/trade-service/src/main/java/com/aicabinet/trade/service/JiangyiModelSyncService.java'
);
export const DOMAIN_FILE = join(
  ROOT,
  'services/trade-service/src/main/java/com/aicabinet/trade/domain/JiangyiDevice.java'
);
export const DTO_FILE = join(
  ROOT,
  'services/common/common-core/src/main/java/com/aicabinet/common/dto/JiangyiDeviceDto.java'
);
export const CONTROLLER_FILE = join(
  ROOT,
  'services/trade-service/src/main/java/com/aicabinet/trade/api/AdminJiangyiController.java'
);
export const VIEW_FILE = join(ROOT, 'clients/admin-vue/src/views/vision/VisionMappingView.vue');

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

/** ③ 落库必须发生在 confirmModelByTxtUrl 内，且经 absoluteTxtUrl 拼绝对地址。 */
export function syncProblems(source) {
  const src = maskComments(source);
  const problems = [];
  const body = /public JiangyiModelDeployment confirmModelByTxtUrl\s*\([\s\S]*?\n    \}/.exec(src);
  if (!body) {
    problems.push('confirmModelByTxtUrl 方法体定位失败（锚点漂移，人工确认是否改名）');
    return problems;
  }
  const fn = body[0];
  if (!/markCurrentTxtUrl\s*\(/.test(fn)) {
    problems.push('confirmModelByTxtUrl 未把设备自报的 txtUrl 落库（运营又要手抄 classes 地址）');
  }
  if (!/markCurrentTxtUrl\s*\([\s\S]{0,200}?absoluteTxtUrl\s*\(/.test(fn)) {
    problems.push('落库前未经 absoluteTxtUrl：设备报的是相对路径 model/xxx.txt，原样入库拉不动');
  }
  return problems;
}

/** ② 实体字段。 */
export function domainProblems(source) {
  const src = maskComments(source);
  return /private\s+String\s+currentTxtUrl\s*;/.test(src)
    ? []
    : ['jiangyi_device 实体缺 currentTxtUrl 字段（列建了也读不到）'];
}

/** ④ DTO 透出。 */
export function dtoProblems(source) {
  const src = maskComments(source);
  return /String\s+currentTxtUrl\s*,/.test(src)
    ? []
    : ['JiangyiDeviceDto 缺 currentTxtUrl，后台拿不到设备自报地址'];
}

/** ④ 绑定视图映射（构造点必须传值，否则恒 null）。 */
export function controllerProblems(source) {
  const src = maskComments(source);
  return /getCurrentTxtUrl\s*\(\s*\)/.test(src)
    ? []
    : ['AdminJiangyiController.toDto 未传 getCurrentTxtUrl（字段恒 null）'];
}

/** ⑤ 前端兜底：只兜底不覆盖，运营填过的值必须保住。 */
export function viewProblems(source) {
  const src = maskComments(source);
  const problems = [];
  if (!/reviewForm\.value\.textUrl\s*=\s*bound\??\.currentTxtUrl/.test(src)) {
    problems.push('复核表单未用 bound.currentTxtUrl 兜底填 classes 地址');
  }
  if (!/bound\??\.currentTxtUrl\s*&&\s*!reviewForm\.value\.textUrl\.trim\s*\(\s*\)/.test(src)) {
    problems.push('兜底必须带「输入框为空」判据：无条件覆盖会抹掉运营手填的值');
  }
  return problems;
}

/** ① 迁移：建列且幂等。 */
export function migrationProblems(files) {
  const hit = files.filter((f) => /^V\d+__.*current_txt_url.*\.sql$/i.test(f));
  if (!hit.length) return ['缺少 current_txt_url 的迁移文件'];
  const problems = [];
  for (const name of hit) {
    const body = readFileSync(join(MIGRATION_DIR, name), 'utf8');
    if (!/ADD COLUMN IF NOT EXISTS\s+current_txt_url/i.test(body)) {
      problems.push(`${name}: 未用 ADD COLUMN IF NOT EXISTS current_txt_url（非幂等）`);
    }
    if (!/^\s*--\s*MIGRATION_KIND\s*:/im.test(body)) {
      problems.push(`${name}: 缺 MIGRATION_KIND 头注释`);
    }
  }
  return problems;
}

export function run() {
  const problems = [
    ...syncProblems(readFileSync(SYNC_FILE, 'utf8')).map((p) => `sync: ${p}`),
    ...domainProblems(readFileSync(DOMAIN_FILE, 'utf8')).map((p) => `domain: ${p}`),
    ...dtoProblems(readFileSync(DTO_FILE, 'utf8')).map((p) => `dto: ${p}`),
    ...controllerProblems(readFileSync(CONTROLLER_FILE, 'utf8')).map((p) => `controller: ${p}`),
    ...viewProblems(readFileSync(VIEW_FILE, 'utf8')).map((p) => `view: ${p}`),
    ...migrationProblems(readdirSync(MIGRATION_DIR)).map((p) => `migration: ${p}`)
  ];
  if (problems.length) {
    console.error('[check-jiangyi-txt-url-default] FAIL:\n' + problems.join('\n'));
    process.exit(1);
  }
  console.log(
    '[check-jiangyi-txt-url-default] OK：设备自报 classes 地址已落库（绝对地址）并经绑定视图带出到复核表单'
  );
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  run();
}
