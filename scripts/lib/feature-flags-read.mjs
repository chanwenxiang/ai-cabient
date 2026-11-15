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

/**
 * MA-201：等长掩码（只剥注释、保留字符串字面量）。
 *
 * R2 消费者判据原先直接对裸源码做 includes —— 注释里提到某个键/常量名会被当成
 * 「有消费者」，死开关被掩护成活开关。与 check-preauth-zero-semantics 的 maskForScan
 * 同一套「等长空格、行号不漂移」的思路，但**不剥字符串**：键出现在字符串字面量里
 * （如 @Value / map.put("…")）是真实消费者，剥掉会造成反假绿。
 */
export function maskCommentsOnly(src) {
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
    } else {
      i++;
    }
  }
  return out.join('');
}

/**
 * MA-201：R2 消费者判定（纯函数）。入参全部是**已剥注释**的文本：
 *
 *   - flags            注册表条目（{key, deprecated}）
 *   - constEntries     SystemConfigService 的常量声明 [名字, 键值]（声明本身已剥掉，
 *                      否则「定义了自己」会被误读成「有人用它」）
 *   - otherMasked      除 SystemConfigService 外的 main Java 文本（已剥注释）
 *   - scsConsumerMasked SystemConfigService 自身剥声明/seed 调用后再剥注释的文本
 *
 * 返回「找不到任何消费者」的键（死开关候选）。
 */
export function findConsumerlessFlags(flags, constEntries, otherMasked, scsConsumerMasked) {
  const consumerless = [];
  for (const f of flags) {
    if (f.deprecated) continue;
    const asLiteral = `"${f.key}"`;
    const names = constEntries.filter(([, v]) => v === f.key).map(([n]) => n);
    const qualifiers = names.map((n) => `SystemConfigService.${n}`);
    const hit =
      otherMasked.some((t) => t.includes(asLiteral)) ||
      (qualifiers.length > 0 && otherMasked.some((t) => qualifiers.some((q) => t.includes(q)))) ||
      names.some((n) => new RegExp(`\\b${n}\\b`).test(scsConsumerMasked));
    if (!hit) consumerless.push(f.key);
  }
  return consumerless;
}
