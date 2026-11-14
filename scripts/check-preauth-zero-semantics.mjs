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
 * ③ 下游 amount<=0 清预授权分支仍在。扫源码前先做**等长掩码**抹注释/字符串，
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

function main() {
  const path = join(ROOT, SERVICE_REL);
  if (!existsSync(path)) {
    console.error(`${TAG} FAIL: 找不到 ${SERVICE_REL}`);
    process.exit(1);
  }
  const problems = evaluateZeroSemantics(readFileSync(path, 'utf8'));
  if (problems.length) {
    for (const p of problems) console.error(`${TAG} FAIL ${p}`);
    console.error(`${TAG} 共 ${problems.length} 项`);
    process.exit(1);
  }
  console.log(`${TAG} OK：0=关闭预授权（显式分支 + 下游清预授权落点 + >0 正常分支）三锚点全绿`);
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  main();
}
