#!/usr/bin/env node
/**
 * check-scheduled-zone 的负向夹具（S8③：判据改动必须同批带夹具）。
 *
 * 钉住两条判据，缺一条就会「静静退化」：
 *   ① `@Scheduled(cron=...)` 不带 zone= ⇒ 红（日界用系统默认时区）；
 *   ② 注解默认 cron 与 application.yml 同名键的默认值不一致 ⇒ 红。
 *      这条正是 MA-406 的形态：注解改成 10:30、yml 还是 01:30 ⇒ 属性存在 ⇒ 注解默认永不生效，
 *      实际按 01:30 触发，而运营台文案写 10:30（页面说 A、实际跑 B）。
 *
 * 反例方向也要有：yml 未声明该键（注解默认就是真值）⇒ 绿，不得误报。
 *
 *   node scripts/check-scheduled-zone.test.mjs
 */
import {
  effectiveDefault,
  findCronDefaultMismatches,
  findTimingContractViolations,
  flattenYml,
  TIMING_CONTRACTS
} from './check-scheduled-zone.mjs';

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

const KEY = 'aicabinet.reconciliation.scheduled-cron';

function javaSrc(cronDefault, withZone = true) {
  return [
    'class X {',
    `    @Scheduled(cron = "\${${KEY}:${cronDefault}}"${withZone ? ', zone = "Asia/Shanghai"' : ''})`,
    '    public void run() { }',
    '}'
  ].join('\n');
}

function ymlSrc(envName, cronDefault) {
  return [
    'aicabinet:',
    '  reconciliation:',
    `    scheduled-cron: \${${envName}:${cronDefault}}`
  ].join('\n');
}

const run = (javaText, ymlText) =>
  findCronDefaultMismatches({
    javaSources: [{ rel: 'X.java', text: javaText }],
    ymlSources: [{ text: ymlText }]
  });

// 1) 一致 ⇒ 绿
{
  const offenders = run(
    javaSrc('0 30 10,14 * * *'),
    ymlSrc('RECON_SCHEDULED_CRON', '0 30 10,14 * * *')
  );
  check('注解默认 == yml 默认 ⇒ 绿', offenders.length === 0, offenders.join('|'));
}

// 2) MA-406 真实形态：注解 10:30 / yml 01:30 ⇒ 红，且必须点名是关键
{
  const offenders = run(
    javaSrc('0 30 10,14 * * *'),
    ymlSrc('RECON_SCHEDULED_CRON', '0 30 1 * * *')
  );
  check('注解与 yml 默认不一致 ⇒ 红', offenders.length === 1, offenders.join('|'));
  check(
    '报错必须点名属性键与两侧取值',
    offenders[0]?.includes(KEY) &&
      offenders[0]?.includes('0 30 10,14 * * *') &&
      offenders[0]?.includes('0 30 1 * * *'),
    offenders.join('|')
  );
}

// 3) yml 未声明该键 ⇒ 注解默认即真值，绿（不得误报）
{
  const offenders = run(javaSrc('0 30 10,14 * * *'), ['other:', '  key: 1'].join('\n'));
  check('yml 未声明该键 ⇒ 绿（不误报）', offenders.length === 0, offenders.join('|'));
}

// 4) 缺 zone= ⇒ 红（原有判据不得被本次改动拆掉）
{
  const offenders = run(javaSrc('0 30 10,14 * * *', false), ymlSrc('ENV', '0 30 10,14 * * *'));
  check(
    '@Scheduled(cron) 缺 zone= ⇒ 红',
    offenders.some((o) => o.includes('missing zone=')),
    offenders.join('|')
  );
}

// 5) 展平器与默认值解析：嵌套键、行内注释、裸值、无法解析的占位
{
  const flat = flattenYml(
    [
      '# 注释行',
      'aicabinet:',
      '  schedule:',
      '    zone: Asia/Shanghai  # 行内注释',
      '  ratio: 1.5'
    ].join('\n')
  );
  check(
    'flattenYml 展平嵌套键',
    flat.get('aicabinet.schedule.zone') === 'Asia/Shanghai',
    JSON.stringify([...flat])
  );
  check(
    'flattenYml 去行内注释',
    flat.get('aicabinet.ratio') === '1.5',
    String(flat.get('aicabinet.ratio'))
  );
  check(
    'effectiveDefault 取 ${ENV:x} 的 x',
    effectiveDefault('${A:0 30 1 * * *}') === '0 30 1 * * *'
  );
  check('effectiveDefault 裸值原样', effectiveDefault('0 30 1 * * *') === '0 30 1 * * *');
  check('effectiveDefault 解析不了 ⇒ null（不猜）', effectiveDefault('${A:${B}}') === null);
}

// 6) 调度时刻契约：日任务固定 cron、提醒避开深夜、高频任务首次执行错峰
function timingSources() {
  const byFile = new Map();
  for (const contract of TIMING_CONTRACTS) {
    if (contract.xxlCron != null) {
      const lines = byFile.get(contract.file) ?? [`class ${contract.file.replace('.java', '')} {`];
      lines.push(
        '  static final Map<String, String> XXL_CRON_BY_TASK = Map.ofEntries(',
        `    Map.entry("${contract.taskKey}", "${contract.xxlCron}")`,
        '  );'
      );
      byFile.set(contract.file, lines);
      continue;
    }
    const attrs = contract.cron
      ? `cron = "${contract.cron}", zone = "Asia/Shanghai"`
      : `fixedRate = 60000, initialDelay = ${contract.initialDelay}`;
    const lines = byFile.get(contract.file) ?? [`class ${contract.file.replace('.java', '')} {`];
    lines.push(`  @Scheduled(${attrs})`, `  public void ${contract.method}() {}`);
    byFile.set(contract.file, lines);
  }
  return [...byFile].map(([rel, lines]) => ({ rel, text: [...lines, '}'].join('\n') }));
}

{
  const sources = timingSources();
  const offenders = findTimingContractViolations(sources);
  check('完整调度时刻契约 ⇒ 绿', offenders.length === 0, offenders.join('|'));
  const commentDecoy = sources.map((src) =>
    src.rel === 'GrowthLogArchiveScheduler.java'
      ? {
          ...src,
          text: src.text.replace(
            '  @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Shanghai")',
            '  // @Scheduled(fixedRate = 86400000)\n  @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Shanghai")'
          )
        }
      : src
  );
  check(
    '注释里的假 @Scheduled 不能骗过契约',
    findTimingContractViolations(commentDecoy).length === 0
  );
  check(
    '日任务 cron 常量钉住 03:00 / 04:00',
    TIMING_CONTRACTS.find((c) => c.file === 'GrowthLogArchiveScheduler.java')?.cron ===
      '0 0 3 * * *' &&
      TIMING_CONTRACTS.find((c) => c.file === 'SkuReviewScheduler.java')?.cron === '0 0 4 * * *'
  );
  check(
    '提醒 cron 常量钉住 00:05/06:05/12:05/18:05',
    TIMING_CONTRACTS.filter((c) =>
      ['PointsExpiryScheduler.java', 'CouponExpiryReminderScheduler.java'].includes(c.file)
    ).every((c) => c.cron === '0 5 0,6,12,18 * * *')
  );
  check(
    '日结 Spring 与 XXL cron 常量钉住 10:30/14:30',
    TIMING_CONTRACTS.find((c) => c.file === 'ReconciliationScheduler.java')?.cron ===
      '0 30 10,14 * * *' &&
      TIMING_CONTRACTS.find((c) => c.file === 'ScheduleZones.java')?.xxlCron === '0 30 10,14 * * ?'
  );

  const xxlDrift = sources.map((src) =>
    src.rel === 'ScheduleZones.java'
      ? { ...src, text: src.text.replace('0 30 10,14 * * ?', '0 30 1 * * ?') }
      : src
  );
  const xxlOffenders = findTimingContractViolations(xxlDrift);
  check(
    '日结 XXL cron 退回 01:30 ⇒ 红',
    xxlOffenders.some((o) => o.includes('XXL_CRON_BY_TASK[reconciliation]') && o.includes('应为')),
    xxlOffenders.join('|')
  );

  const dailyDrift = sources.map((src) =>
    src.rel === 'GrowthLogArchiveScheduler.java'
      ? {
          ...src,
          text: src.text.replace(
            '@Scheduled(cron = "0 0 3 * * *", zone = "Asia/Shanghai")',
            '@Scheduled(fixedRate = 86400000)'
          )
        }
      : src
  );
  const dailyOffenders = findTimingContractViolations(dailyDrift);
  check(
    '日任务退回 fixedRate ⇒ 红',
    dailyOffenders.some(
      (o) => o.includes('GrowthLogArchiveScheduler.java') && o.includes('cron 应为')
    ),
    dailyOffenders.join('|')
  );

  const noStagger = sources.map((src) =>
    src.rel === 'DevicePresenceService.java'
      ? { ...src, text: src.text.replace(', initialDelay = 20000', '') }
      : src
  );
  const staggerOffenders = findTimingContractViolations(noStagger);
  check(
    '高频任务删 initialDelay ⇒ 红',
    staggerOffenders.some(
      (o) => o.includes('DevicePresenceService.java') && o.includes('initialDelay 应为')
    ),
    staggerOffenders.join('|')
  );

  const duplicateStagger = sources.map((src) =>
    src.rel === 'CompensationTaskScheduler.java'
      ? { ...src, text: src.text.replace('initialDelay = 5000', 'initialDelay = 10000') }
      : src
  );
  const duplicateOffenders = findTimingContractViolations(duplicateStagger);
  check(
    '两个高频任务 initialDelay 相同 ⇒ 红',
    duplicateOffenders.some((o) => o.includes('未错峰')),
    duplicateOffenders.join('|')
  );
}

console.log(`\n[check-scheduled-zone.test] ${pass} 通过 / ${fail} 失败`);
if (fail > 0) process.exit(1);
