#!/usr/bin/env node
/**
 * R3 读取点判据的共享纯逻辑（check-feature-flags.mjs 与同名 .test.mjs 同源）。
 *
 * 为什么抽出来：check-feature-flags.mjs 是顶层直接执行的脚本，`.test.mjs` 无法
 * import 它（import 会跑全量扫描并可能 process.exit）。把「正则 + 锚点判定」抽成
 * 无副作用的纯函数后，夹具才能真的钉住判据退化，而不是靠 grep 源码文本自证。
 *
 * 🔴 2026-11-15 修：旧 READ 只认 getValue|getInt|getBoolean|getDouble|getLong，
 * S12 扩面新增的 getPositiveLimit 5 个资金键读取点全部失明（键被读却没登记仍判绿）。
 */

/** 每次返回全新的 /g 正则，避免模块级复用导致 lastIndex 在多文件扫描间串味。 */
export function makeReadRe() {
  return /\b(systemConfigService|self)\s*\.\s*(getValue|getInt|getBoolean|getDouble|getLong|getPositiveLimit)\s*\(/g;
}

/**
 * 2026-11-15 实测：3 处跨类（BalanceRefundService / PaymentService / RefundPolicyService）
 * + 2 处 self（SystemConfigService.consumerPublicConfig）= 5。只许涨，掉下去必须复核。
 */
export const POSITIVE_LIMIT_MIN_SITES = 5;

/** 数一段源码里被 R3 认作 getPositiveLimit 的读取点（夹具用它证明正则没退化）。 */
export function countPositiveLimitSites(text, re = makeReadRe()) {
  let n = 0;
  let m;
  re.lastIndex = 0;
  while ((m = re.exec(text)) !== null) {
    if (m[2] === 'getPositiveLimit') n++;
  }
  return n;
}

/**
 * 纯判定：两个扫描锚点任一退化即返回红消息。
 * readSites 下限 40 是原有防「零命中假绿」哨兵；positiveLimitSites 下限专门防
 * 「把 getPositiveLimit 从正则里删掉、S12 上限键读取点重新失明」这一族复发。
 */
export function evaluateReadAnchor(
  readSites,
  positiveLimitSites,
  minPositive = POSITIVE_LIMIT_MIN_SITES
) {
  const problems = [];
  if (readSites < 40) {
    problems.push(`只扫到 ${readSites} 个读取调用点（下限 40）—— 接收者锚点可能已被重写，判定失效`);
  }
  if (positiveLimitSites < minPositive) {
    problems.push(
      `只扫到 ${positiveLimitSites} 个 getPositiveLimit 读取点（下限 ${minPositive}）——` +
        `R3 判据退化会让 S12 上限键的读取点失明（键被读却没登记仍判绿），检查 READ 正则`
    );
  }
  return problems;
}
