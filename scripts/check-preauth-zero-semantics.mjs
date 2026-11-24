#!/usr/bin/env node
/**
 * 门禁（2026-11-15）：checkout.preauth_cents 的 **0 语义**不得被静默回落。
 *
 * 缺陷实锤：9d5c724f（S12 扩面）的 commit message 与 CommissionParamBounds 注释都写明
 * 「0 仍合法 = 关闭预授权，该键只进 FUND_GUARDED_KEYS 不进 DC-6」，写侧也确实允许配 0；
 * 但 ConsumerPreauthService.resolvePreauthCents 只有 `fromDb > 0` 分支，0 被当成
 * 「未配置/非法」落回 CheckoutProperties 的 yml 默认额度 —— 运营配 0 关不掉预授权，
 * 仍按默认金额冻结用户余额。下游 freezeForOpen 的 `amount <= 0` 本来就是关闭分支，
 * 缺的只是这一层显式返回 0。
 *
 * 判据贴真实责任边界：① 显式 0 分支 return 0；② 正常 >0 分支仍在（防「全关」）；
 * ③ 下游 amount<=0 清预授权分支仍在；④ 消费者端解析器与 4 个入口必须保留 0，
 * 不能再用 `>0 ? value : null` 回落 ¥20。扫源码前先做**等长掩码**抹注释/字符串，
 * 否则把 `fromDb == 0` 写进注释或日志就能造出假绿（铁律 84①）。
 *
 *   node scripts/check-preauth-zero-semantics.mjs
 */
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-preauth-zero-semantics]';
const SERVICE_REL =
  'services/trade-service/src/main/java/com/aicabinet/trade/service/ConsumerPreauthService.java';
const CLIENT_FILES = {
  account: 'clients/consumer-mp/src/utils/account.ts',
  drawer: 'clients/consumer-mp/src/components/open-prep-drawer.vue',
  verify: 'clients/consumer-mp/src/pages/verify/verify.vue',
  mine: 'clients/consumer-mp/src/pages/mine/mine.vue',
  index: 'clients/consumer-mp/src/pages/index/index.vue'
};

/** 等长掩码：注释与字符串字面量换成等长空格，保留换行（行号/偏移不漂移）。 */
export function maskForScan(src) {
  const out = src.split('');
  const blank = (from, to) => {
    for (let k = from; k < to && k < out.length; k++) if (out[k] !== '\n') out[k] = ' ';
  };
  let i = 0;
  while (i < src.length) {
    const ch = src[i];
    const next = src[i + 1];
    if (ch === '/' && next === '/') {
      let j = i;
      while (j < src.length && src[j] !== '\n') j++;
      blank(i, j);
      i = j;
    } else if (ch === '/' && next === '*') {
      let j = i + 2;
      while (j < src.length && !(src[j] === '*' && src[j + 1] === '/')) j++;
      blank(i, Math.min(j + 2, src.length));
      i = Math.min(j + 2, src.length);
    } else if (ch === '"') {
      let j = i + 1;
      while (j < src.length) {
        if (src[j] === '\\') {
          j += 2;
          continue;
        }
        if (src[j] === '"') {
          j++;
          break;
        }
        j++;
      }
      blank(i, j);
      i = j;
    } else {
      i++;
    }
  }
  return out.join('');
}

/** 从签名后第一个 { 起做括号配对取方法体；找不到返回 null（fail-closed）。 */
export function methodBody(src, signature) {
  const idx = src.indexOf(signature);
  if (idx < 0) return null;
  const open = src.indexOf('{', idx + signature.length);
  if (open < 0) return null;
  let depth = 0;
  for (let i = open; i < src.length; i++) {
    if (src[i] === '{') depth++;
    else if (src[i] === '}') {
      depth--;
      if (depth === 0) return src.slice(open, i + 1);
    }
  }
  return null;
}

/** 纯判定，供同名 .test.mjs 做负向注入。 */
export function evaluateZeroSemantics(rawSrc) {
  const problems = [];
  const src = maskForScan(rawSrc);
  const resolve = methodBody(src, 'public int resolvePreauthCents(String deviceId)');
  if (resolve === null) {
    problems.push('找不到 resolvePreauthCents(String deviceId) 方法体——判据失效，不得判绿');
  } else {
    // 先在掩码文本上压掉连续空白：注释被抹成空格后仍占长度，不压缩的话一段成因注释
    // 就能把「0 分支 → return 0」的字符距离撑过上限、造成假红。压缩后仍限定 300 字符，
    // 避免别处的 return 0 凑数造成假绿。
    const compact = resolve.replace(/\s+/g, ' ');
    if (!/fromDb\s*==\s*0.{0,300}?return\s+0\s*;/.test(compact)) {
      problems.push(
        'resolvePreauthCents 缺显式 `fromDb == 0 → return 0` 分支：DB 配 0 会静默回落 yml 默认额度，' +
          '运营关不掉预授权（9d5c724f 同族分叉）'
      );
    }
    if (!/fromDb\s*>\s*0/.test(resolve)) {
      problems.push(
        'resolvePreauthCents 丢了 `fromDb > 0` 正常分支——不得用「全部返回 0」代替关闭语义'
      );
    }
  }
  // 🔴 真实责任边界：freezeForOpen 只是加锁入口，amount<=0 的关闭分支在私有方法
  // doFreezeForOpen 里（2026-11-15 实证：按入口方法取体只有 635 字符、根本看不到 amount）。
  const freeze = methodBody(src, 'private void doFreezeForOpen(String sessionId)');
  if (freeze === null) {
    problems.push('找不到 doFreezeForOpen 方法体——下游 0 语义锚点失效，不得判绿');
  } else if (!/amount\s*<=\s*0/.test(freeze) || !/clearSessionPreauth\s*\(/.test(freeze)) {
    problems.push(
      'doFreezeForOpen 缺 `amount <= 0 → clearSessionPreauth` 分支：上层返回 0 后无安全落点，' +
        '不得让 0 流进冻结路径'
    );
  }
  return problems;
}

/** 消费者端同族判据：0 必须穿过解析器与四个入口，不能被 >0 静默回落。 */
export function clientZeroSemanticsProblems(rawSources) {
  const problems = [];
  const sources = {};
  for (const [name, raw] of Object.entries(rawSources || {})) {
    sources[name] = typeof raw === 'string' ? maskForScan(raw) : '';
  }
  const account = sources.account || '';
  const parseStart = account.indexOf('export function parsePreauthCents');
  const resolveStart = account.indexOf('export function resolveClientPreauthCents');
  const labelStart = account.indexOf('export function preauthYuanLabel');
  const readyStart = account.indexOf('export function isPayReady');
  const hintStart = account.indexOf('export function payReadyHint');
  if (parseStart < 0 || resolveStart < 0 || labelStart < 0 || readyStart < 0 || hintStart < 0) {
    problems.push('account.ts 缺 parse/resolve/label/isPayReady/payReadyHint 锚点——前端判据失效');
    return problems;
  }
  const parseBlock = account.slice(parseStart, resolveStart);
  const resolveBlock = account.slice(resolveStart, labelStart);
  const labelBlock = account.slice(labelStart, readyStart);
  const readyBlock = account.slice(readyStart, hintStart);
  const hintBlock = account.slice(hintStart);

  if (!/Number\.isSafeInteger\(value\)/.test(parseBlock) || !/value\s*>=\s*0/.test(parseBlock)) {
    problems.push('parsePreauthCents 没保留显式 0（必须接受 >=0，而非 >0）');
  }
  const zeroBranch = resolveBlock.search(/if\s*\(\s*cfg\s*===\s*0\s*\)/);
  const deviceBranch = resolveBlock.search(/const\s+device\s*=/);
  if (zeroBranch < 0) {
    problems.push('resolveClientPreauthCents 缺全局配置 0 → return 0 分支');
  } else if (!/if\s*\(\s*cfg\s*===\s*0\s*\)\s*return\s+0\s*;/.test(resolveBlock)) {
    problems.push('resolveClientPreauthCents 的全局 0 分支没有直接 return 0');
  } else if (deviceBranch >= 0 && zeroBranch > deviceBranch) {
    problems.push('resolveClientPreauthCents 的全局 0 分支位于柜机分支之后，无法压倒设备押金');
  }
  if (!/if\s*\(\s*device\s*!==\s*null\s*\)\s*return\s+device\s*;/.test(resolveBlock)) {
    problems.push('resolveClientPreauthCents 没把 DeviceStatus 已解析的 0 当有效值');
  }
  if (!/if\s*\(\s*preauthCents\s*<=\s*0\s*\)\s*return/.test(labelBlock)) {
    problems.push('preauthYuanLabel 缺 0=关闭分支，会把 0 显示成 ¥0.01');
  }
  if (!/preauthCents\s*<=\s*0\s*\|\|/.test(readyBlock)) {
    problems.push('isPayReady 缺预授权关闭放行分支，仍会要求默认余额门槛');
  }
  if (!/if\s*\(\s*preauthCents\s*<=\s*0\s*\)\s*return/.test(hintBlock)) {
    problems.push('payReadyHint 缺预授权关闭提示，会继续提示充值');
  }

  for (const name of ['drawer', 'verify', 'mine', 'index']) {
    const src = sources[name];
    if (!src) {
      problems.push(`消费者端入口 ${name} 缺失——不得假绿`);
      continue;
    }
    const target = name === 'index' ? 'status.preauthCents' : 'cfg?.preauthCents';
    if (!src.includes(`parsePreauthCents(${target})`)) {
      problems.push(`${name} 没走 parsePreauthCents(${target})，0 可能被静默回落`);
    }
    if (new RegExp(`Number\\s*\\(\\s*${target.replace('?', '\\?')}\\s*\\)`).test(src)) {
      problems.push(`${name} 又直接用 Number(${target}) 解析，绕过 0 语义守卫`);
    }
  }
  return problems;
}

function main() {
  const path = join(ROOT, SERVICE_REL);
  if (!existsSync(path)) {
    console.error(`${TAG} FAIL: 找不到 ${SERVICE_REL}`);
    process.exit(1);
  }
  const problems = evaluateZeroSemantics(readFileSync(path, 'utf8'));
  const clientSources = {};
  for (const [name, rel] of Object.entries(CLIENT_FILES)) {
    const clientPath = join(ROOT, rel);
    if (!existsSync(clientPath)) {
      clientSources[name] = '';
      continue;
    }
    clientSources[name] = readFileSync(clientPath, 'utf8');
  }
  problems.push(...clientZeroSemanticsProblems(clientSources));
  if (problems.length) {
    for (const p of problems) console.error(`${TAG} FAIL ${p}`);
    console.error(`${TAG} 共 ${problems.length} 项`);
    process.exit(1);
  }
  console.log(
    `${TAG} OK：后端 0=关闭（显式分支 + 下游清预授权落点 + >0 正常分支）与消费者端 4 入口解析链全绿`
  );
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  main();
}
