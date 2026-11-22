#!/usr/bin/env node
/**
 * 门禁：开机回执的类表漂移检测不得占住回执线程，且撤戳必须带「快照态」谓词。
 *
 * 成因（2026-11-22，MA-388 / 用户复核 P3-1）：
 * ① `confirmModelByTxtUrl` 命中不到 SENT 时**同步**调 `revokeVerificationIfModelDrifted`，后者要拉一次
 *    OSS classes.txt（档位 3s 连接 + 10s 读）。这条链的另一端是网关的单线程守护线程
 *    （`DeviceInfoController.txtConfirmExecutor`）⇒ 一台柜机慢网会把后续所有柜机的确认转达排在后面；
 *    整站停电恢复后几十台同时开机时，靠后的柜机 SENT→CONFIRMED 迟迟不落地，可能先被下发看门狗判超时。
 * ② 拉取期间运营可能复核通过并盖了新戳，而撤戳是无条件 `UPDATE … SET version=null`
 *    ⇒ 一条正确的复核被一条过期的漂移判定作废（「定时清扫禁拿快照直写状态」同族）。
 *
 * 判据链（任一处退化即红）：
 *   ① 回执方法体内调 `scheduleDriftCheck(…)`，且**不**直调 `revokeVerificationIfModelDrifted(…)`
 *   ② `scheduleDriftCheck` 把检测交给 executor 执行，并在 finally 摘掉同柜去重键
 *   ③ 漂移撤戳走 `clearMappingVerificationIfStill(deviceId, verifiedVersion, …)`，
 *      方法体内不得出现无条件 `clearMappingVerification(…)`
 *   ④ mapper 的条件更新真带 `eq(…getMappingVerifiedVersion, expectedVersion)`，且空快照早退
 *
 * 用法：node scripts/check-jiangyi-receipt-offload.mjs
 */
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');

export const SERVICE_FILE = join(
  ROOT,
  'services/trade-service/src/main/java/com/aicabinet/trade/service/JiangyiModelSyncService.java'
);
export const MAPPER_FILE = join(
  ROOT,
  'services/trade-service/src/main/java/com/aicabinet/trade/mapper/JiangyiDeviceMapper.java'
);

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

/**
 * 取某个**方法声明**的整段花括号体（含花括号）。找不到返回 null。
 *
 * 不能直接 `indexOf(name + '(')`：调用点在前、声明在后时会取错（`scheduleDriftCheck` 正是这种顺序），
 * 故先用「若干修饰符/类型词 + 方法名(」声明形态定位，再做花括号配平。
 * 修饰符词表不能写死（`public|private|default|…` 组合太多，mapper 的 `default int xxx(` 就是漏网形态）⇒
 * 用「行首至少一个词 + 方法名(」定位；调用点前面有 `=`/`(`/`.` 等非词字符，匹配不上。
 */
export function methodBody(maskedSrc, name) {
  const decl = new RegExp(`^[ \\t]*(?:[\\w<>\\[\\]]+[ \\t]+)+${name}[ \\t]*\\(`, 'm');
  const m = maskedSrc.match(decl);
  if (!m) return null;
  const open = maskedSrc.indexOf('{', m.index + m[0].length);
  if (open < 0) return null;
  let depth = 0;
  for (let i = open; i < maskedSrc.length; i++) {
    const c = maskedSrc[i];
    if (c === '{') depth++;
    else if (c === '}') {
      depth--;
      if (depth === 0) return maskedSrc.slice(open, i + 1);
    }
  }
  return null;
}

/** ① 回执方法体：必须排队、不得在回执线程里直接拉 OSS。 */
export function receiptProblems(source) {
  const src = maskComments(source);
  const body = methodBody(src, 'confirmModelByTxtUrl');
  if (body == null) {
    return ['找不到 confirmModelByTxtUrl 方法体（改名？判据失效即红）'];
  }
  const problems = [];
  if (/\brevokeVerificationIfModelDrifted\s*\(/.test(body)) {
    problems.push(
      '开机回执线程内直接调 revokeVerificationIfModelDrifted（要拉 OSS，最长 3s+10s）——' +
        '网关侧确认转达是单线程，一台慢网会拖住所有柜机的确认'
    );
  }
  if (!/\bscheduleDriftCheck\s*\(\s*deviceId\s*,/.test(body)) {
    problems.push('回执命中不到 SENT 时未调 scheduleDriftCheck(deviceId, …)：漂移检测要么丢了、要么回到了同步');
  }
  return problems;
}

/** ② 调度方法体：交给 executor + finally 摘去重键。 */
export function schedulerProblems(source) {
  const src = maskComments(source);
  const body = methodBody(src, 'scheduleDriftCheck');
  if (body == null) {
    return ['找不到 scheduleDriftCheck 方法体'];
  }
  const problems = [];
  if (!/driftCheckExecutor\s*\.\s*(execute|submit)\s*\(/.test(body)) {
    problems.push('scheduleDriftCheck 没有把检测交给 driftCheckExecutor（还是同步跑在回执线程里）');
  }
  if (!/finally\s*\{[^}]*driftChecksInFlight\s*\.\s*remove\s*\(\s*deviceId\s*\)/s.test(body)) {
    problems.push(
      'scheduleDriftCheck 未在 finally 里摘掉同柜去重键——漏摘会让该柜机此后永远不再检测漂移（静默 fail-open）'
    );
  }
  return problems;
}

/** ③ 漂移撤戳：条件更新，不得无条件清空。 */
export function revokeProblems(source) {
  const src = maskComments(source);
  const body = methodBody(src, 'revokeVerificationIfModelDrifted');
  if (body == null) {
    return ['找不到 revokeVerificationIfModelDrifted 方法体'];
  }
  const problems = [];
  if (!/clearMappingVerificationIfStill\s*\(\s*deviceId\s*,\s*verifiedVersion\s*,/.test(body)) {
    problems.push(
      '漂移撤戳未用 clearMappingVerificationIfStill(deviceId, verifiedVersion, …)：' +
        '拉 OSS 期间被并发复核盖的新戳会被无条件清空'
    );
  }
  if (/\bclearMappingVerification\s*\(/.test(body)) {
    problems.push('漂移撤戳方法体内仍有无条件 clearMappingVerification(…)（会把并发复核的新戳一起抹掉）');
  }
  return problems;
}

/** ④ mapper 条件更新真带快照态谓词。 */
export function mapperProblems(source) {
  const src = maskComments(source);
  const body = methodBody(src, 'clearMappingVerificationIfStill');
  if (body == null) {
    return ['JiangyiDeviceMapper 缺 clearMappingVerificationIfStill（条件撤戳无实现）'];
  }
  const problems = [];
  if (!/\.eq\s*\(\s*JiangyiDevice::getMappingVerifiedVersion\s*,\s*expectedVersion\s*\)/.test(body)) {
    problems.push('clearMappingVerificationIfStill 缺 eq(getMappingVerifiedVersion, expectedVersion) 谓词——等于无条件清空');
  }
  if (!/expectedVersion\s*==\s*null\s*\|\|\s*expectedVersion\s*\.\s*isBlank\s*\(\s*\)/.test(body)) {
    problems.push('clearMappingVerificationIfStill 缺「空快照直接放弃」早退（空值 eq 会清掉任何戳）');
  }
  return problems;
}

export function run() {
  const service = readFileSync(SERVICE_FILE, 'utf8');
  const problems = [
    ...receiptProblems(service).map((p) => `receipt: ${p}`),
    ...schedulerProblems(service).map((p) => `scheduler: ${p}`),
    ...revokeProblems(service).map((p) => `revoke: ${p}`),
    ...mapperProblems(readFileSync(MAPPER_FILE, 'utf8')).map((p) => `mapper: ${p}`)
  ];
  if (problems.length) {
    console.error('[check-jiangyi-receipt-offload] FAIL:\n' + problems.join('\n'));
    process.exit(1);
  }
  console.log(
    '[check-jiangyi-receipt-offload] OK：回执线程不拉 OSS（排队+同柜去重）、撤戳带快照态谓词'
  );
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  run();
}
