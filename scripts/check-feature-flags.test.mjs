#!/usr/bin/env node
/**
 * check-feature-flags R3 读取点判据的负向夹具（2026-11-15 补）。
 *
 * 为什么现在才有：该门禁原先是顶层直接跑的脚本、没有 sibling 夹具；S12 扩面加了
 * getPositiveLimit 后 READ 正则没跟上，5 个资金键读取点失明却一直判绿。判据已抽到
 * scripts/lib/feature-flags-read.mjs，这里用同一份纯逻辑钉住「正则认得它 / 退化就红」。
 *
 *   node scripts/check-feature-flags.test.mjs
 */
import { readFileSync } from 'node:fs';
import {
  allowsUnseededRuntimeFallback,
  makeReadRe,
  countPositiveLimitSites,
  evaluateReadAnchor,
  findConsumerlessFlags,
  maskCommentsOnly,
  POSITIVE_LIMIT_MIN_SITES
} from './lib/feature-flags-read.mjs';

let pass = 0;
let fail = 0;
function check(name, cond, detail = '') {
  if (cond) {
    pass += 1;
    console.log(`  ✓ ${name}`);
  } else {
    fail += 1;
    console.error(`  ✗ ${name}${detail ? ' —— ' + detail : ''}`);
  }
}

// 1) 真实形态：3 处跨类 + 2 处 self（与 2026-11-15 实测的 5 个调用点同形）
const REAL_SITES = [
  'int a = systemConfigService.getPositiveLimit(SystemConfigService.BALANCE_REFUND_MAX_CENTS, 500_000);',
  'int b = systemConfigService.getPositiveLimit(SystemConfigService.RECHARGE_MAX_CENTS, 500_000);',
  'int c = systemConfigService.getPositiveLimit(SystemConfigService.REFUND_SELF_MAX_CENTS, 5000);',
  'map.put("rechargeMaxCents", String.valueOf(self.getPositiveLimit(RECHARGE_MAX_CENTS, 500_000)));',
  'map.put("balanceRefundMaxCents", String.valueOf(self.getPositiveLimit(BALANCE_REFUND_MAX_CENTS, 500_000)));'
];
{
  const text = REAL_SITES.join('\n');
  check(
    '5 个真实 getPositiveLimit 调用点全部被 R3 认出',
    countPositiveLimitSites(text) === REAL_SITES.length,
    String(countPositiveLimitSites(text))
  );
  check(
    '普通读取点仍在同一正则视野内（未因扩面丢失）',
    /getValue\|getInt\|getBoolean\|getDouble\|getLong/.test(makeReadRe().source)
  );
  const scs = readFileSync(
    new URL(
      '../services/trade-service/src/main/java/com/aicabinet/trade/service/SystemConfigService.java',
      import.meta.url
    ),
    'utf8'
  );
  check(
    'SystemConfigService 的 4 个动态 key 读取点与 R4 登记一致',
    (scs.match(/self\.getValue\(key,/g) || []).length === 4,
    String((scs.match(/self\.getValue\(key,/g) || []).length)
  );
}

// 2) 负向：旧正则形态（删掉 getPositiveLimit）一个数不到 ⇒ S12 上限键读取点失明
{
  const OLD_RE =
    /\b(systemConfigService|self)\s*\.\s*(getValue|getInt|getBoolean|getDouble|getLong)\s*\(/g;
  const missed = countPositiveLimitSites(REAL_SITES.join('\n'), OLD_RE);
  check('旧正则对 5 个调用点全盲（复现原缺陷）', missed === 0, String(missed));
  const problems = evaluateReadAnchor(117, missed);
  check(
    '判据退化=红（含「getPositiveLimit」与「失明」）',
    problems.length === 1 &&
      problems[0].includes('getPositiveLimit') &&
      problems[0].includes('失明'),
    problems.join('|')
  );
}

// 3) 锚点双向：实测值绿 / 掉 1 个红 / 零命中红 / 总量下限红
{
  check(
    `实测 5 处 = 绿（下限 ${POSITIVE_LIMIT_MIN_SITES}）`,
    evaluateReadAnchor(117, POSITIVE_LIMIT_MIN_SITES).length === 0
  );
  const dropped = evaluateReadAnchor(117, POSITIVE_LIMIT_MIN_SITES - 1);
  check(
    '掉到 4 处 = 红（且点名 getPositiveLimit）',
    dropped.length === 1 && dropped[0].includes('getPositiveLimit'),
    dropped.join('|')
  );
  check('零命中不得假绿', evaluateReadAnchor(0, 0).length === 2);
  const few = evaluateReadAnchor(10, 5);
  check('读取点总量下限仍生效', few.length === 1 && few[0].includes('下限 40'), few.join('|'));
}

// 4) 正则复用安全：/g 的 lastIndex 不能在两次扫描间串味
{
  const re = makeReadRe();
  const first = countPositiveLimitSites(REAL_SITES.join('\n'), re);
  const second = countPositiveLimitSites(REAL_SITES.join('\n'), re);
  check(
    '同一 /g 正则连续扫两次结果一致（lastIndex 复位）',
    first === 5 && second === 5,
    `${first}/${second}`
  );
}

// 5) 唯独风险纠纷阈值允许不 seed：缺行必须保留环境变量/启动配置回退
{
  const allowed = {
    key: 'risk.max_disputes_per_7_days',
    runtimeFallback: true,
    default: '3'
  };
  check('指定运行回退键允许不 seed', allowsUnseededRuntimeFallback(allowed));
  check(
    '其它键不得借 runtimeFallback 绕过 R5',
    !allowsUnseededRuntimeFallback({ ...allowed, key: 'risk.other' })
  );
  check('默认值被改也不得绕过 R5', !allowsUnseededRuntimeFallback({ ...allowed, default: '5' }));
  check(
    '未显式声明不得绕过 R5',
    !allowsUnseededRuntimeFallback({ ...allowed, runtimeFallback: false })
  );
}

console.log(`\n[check-feature-flags.test] ${pass} 通过 / ${fail} 失败`);
if (fail > 0) process.exit(1);
// ── MA-201：R2 消费者判定改「剥注释、留字符串」后的行为钉 ─────────────────────
{
  const flags = [
    { key: 'demo.dead', deprecated: false },
    { key: 'demo.old', deprecated: true }
  ];
  // ① 注释里提到键不算消费者 ⇒ 死开关必须被报出（修前裸 includes 会假绿）
  const commentOnly = maskCommentsOnly('// TODO: 计划用 "demo.dead" 做降级开关\nint x = 1;');
  const r1 = findConsumerlessFlags(flags, [], [commentOnly], '');
  check(
    '注释里提到键不算消费者',
    r1.includes('demo.dead') && !r1.includes('demo.old'),
    JSON.stringify(r1)
  );

  // ② 字符串字面量里的键是真实消费者（@Value / map.put），不得误报
  const literal = maskCommentsOnly('map.put("flag", "demo.dead");');
  const r2 = findConsumerlessFlags(flags, [], [literal], '');
  check('字符串字面量里的键算消费者', !r2.includes('demo.dead'), JSON.stringify(r2));

  // ③ 常量限定名 SystemConfigService.KEY 算消费者
  const qualifier = maskCommentsOnly('int v = SystemConfigService.DEAD_FLAG;');
  const r3 = findConsumerlessFlags(flags, [['DEAD_FLAG', 'demo.dead']], [qualifier], '');
  check('常量限定名算消费者', !r3.includes('demo.dead'), JSON.stringify(r3));

  // ④ scs 内部裸常量名（声明已剥）算消费者
  const r4 = findConsumerlessFlags(flags, [['DEAD_FLAG', 'demo.dead']], [], 'if (DEAD_FLAG) { }');
  check('scs 内裸常量名算消费者', !r4.includes('demo.dead'), JSON.stringify(r4));

  // ⑤ deprecated 条目跳过，永不进 consumerless
  const r5 = findConsumerlessFlags(flags, [], [''], '');
  check('deprecated 跳过', !r5.includes('demo.old'), JSON.stringify(r5));
}
