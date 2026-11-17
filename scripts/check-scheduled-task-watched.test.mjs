#!/usr/bin/env node
/**
 * `check-scheduled-task-watched.mjs` 的自测：解析器 + 三条判据的正反两向。
 *
 * 这个门禁的价值全在「解析得准」上：解析器把 `@Scheduled` 认错、把常量名当字符串、
 * 或把注释里的注解算数，判据就会假绿——而它守的恰恰是「任务停跑无人知道」这类
 * 平时完全不显形的问题。所以这里逐个钉解析件，再钉 R1/R2/R3 的红绿两侧。
 *
 *   node scripts/check-scheduled-task-watched.test.mjs
 */
import {
  checkWatchedComposition,
  evaluate,
  extractScheduledCall,
  maskJavaComments,
  parseManagedKeys,
  parseResidentKeys,
  parseStringConstants
} from './check-scheduled-task-watched.mjs';

let pass = 0;
const failures = [];

function ok(name, cond, detail) {
  if (cond) {
    pass++;
  } else {
    failures.push(`${name}${detail ? ` :: ${detail}` : ''}`);
  }
}

function eq(name, actual, expected) {
  ok(name, actual === expected, `期望 ${JSON.stringify(expected)}，实际 ${JSON.stringify(actual)}`);
}

// ── maskJavaComments ───────────────────────────────────────────────────────
{
  const src = 'int a;\n// @Scheduled(fixedRate = 1)\nint b;\n/* @Scheduled(x) */\nint c;';
  const masked = maskJavaComments(src);
  ok('行注释里的 @Scheduled 被屏蔽', !masked.includes('@Scheduled'));
  eq('注释屏蔽保持行数（行号证据不失真）', masked.split('\n').length, src.split('\n').length);
  ok('注释外的代码保留', masked.includes('int a;') && masked.includes('int c;'));
}

{
  // 字符串里的 // 不能被当行注释吃掉，否则同一行后面的代码会「消失」⇒ 漏判
  const src = 'String u = "http://x"; if (!taskService.tryBegin("k", 1)) return;';
  const masked = maskJavaComments(src);
  ok('字符串里的 // 不触发注释剥离', masked.includes('tryBegin("k", 1)'), masked);
}

{
  // 转义引号后紧跟 //，同样是字符串内容
  const src = 'String s = "a\\"//b"; int keep = 1;';
  const masked = maskJavaComments(src);
  ok('转义引号后的 // 不触发注释剥离', masked.includes('int keep = 1;'), masked);
}

// ── parseStringConstants / extractScheduledCall ────────────────────────────
{
  const src = [
    'public class A {',
    '    private static final String TASK_KEY = "demo-task";',
    '    @Scheduled(fixedRate = 1000)',
    '    public void run() {',
    '        if (!taskService.tryBegin(TASK_KEY, 60)) {',
    '            return;',
    '        }',
    '    }',
    '    @Scheduled(cron = "0 0 1 * * *")',
    '    public void runLiteral() {',
    '        if (!taskService.tryBegin("literal-task", 60)) return;',
    '    }',
    '}'
  ].join('\n');

  const constants = parseStringConstants(src);
  eq('常量表解析', constants.get('TASK_KEY'), 'demo-task');

  const firstAt = src.indexOf('@Scheduled');
  const first = extractScheduledCall(src, firstAt, constants);
  eq('常量名解析成 taskKey', first.key, 'demo-task');
  eq('行号指向注解行', first.line, 3);

  const secondAt = src.indexOf('@Scheduled', firstAt + 1);
  const second = extractScheduledCall(src, secondAt, constants);
  eq('字面量 taskKey 直取', second.key, 'literal-task');
}

{
  // 没有 tryBegin：必须显式报 no-trybegin，而不是解析出 null 就静默放过
  const src = [
    'public class B {',
    '    @Scheduled(fixedRate = 1000)',
    '    public void purge() {',
    '        cacheService.purgeExpired();',
    '    }',
    '}'
  ].join('\n');
  const at = src.indexOf('@Scheduled');
  const entry = extractScheduledCall(src, at, new Map());
  eq('缺 tryBegin 时归因为 no-trybegin', entry.reason, 'no-trybegin');
  eq('缺 tryBegin 时 key 为 null', entry.key, null);
}

{
  // 参数是表达式而非名字：必须归因 unresolved，逼人工确认（不能当成「没问题」）
  const src = [
    'public class C {',
    '    @Scheduled(fixedRate = 1000)',
    '    public void run() {',
    '        if (!taskService.tryBegin(keyOf(x), 60)) return;',
    '    }',
    '}'
  ].join('\n');
  const entry = extractScheduledCall(src, src.indexOf('@Scheduled'), new Map());
  eq('表达式参数归因为 unresolved', entry.reason, 'unresolved:keyOf(x)');
}

// ── parseManagedKeys / parseResidentKeys ──────────────────────────────────
{
  const managed = 'public static final Set<String> KEYS = Set.of(\n "a-one",\n "b-two"\n);';
  const keys = parseManagedKeys(managed);
  eq('托管清单解析条数', keys.length, 2);
  eq('托管清单首项', keys[0], 'a-one');

  const resident = 'static final List<String> SPRING_RESIDENT_TASK_KEYS =\n List.of(\n "r-one"\n);';
  const rkeys = parseResidentKeys(resident);
  eq('常驻清单解析', rkeys.join(','), 'r-one');

  eq('锚点缺失时返回 null（门禁据此判失效）', parseManagedKeys('no anchor here'), null);
}

// ── R3：看护名单不得缩水 ──────────────────────────────────────────────────
{
  const okBody =
    'List<String> watchedKeys() { var k = new ArrayList<>(XxlJobManagedTasks.KEYS); k.addAll(SPRING_RESIDENT_TASK_KEYS); return k; }';
  eq('R3 正常形态不报错', checkWatchedComposition(okBody, ['a'], ['b']).length, 0);

  const noKeys =
    'List<String> watchedKeys() { return new ArrayList<>(SPRING_RESIDENT_TASK_KEYS); }';
  ok(
    'R3 丢掉 KEYS ⇒ 红',
    checkWatchedComposition(noKeys, ['a'], ['b']).some((p) => p.includes('XxlJobManagedTasks.KEYS'))
  );

  const noResident =
    'List<String> watchedKeys() { return new ArrayList<>(XxlJobManagedTasks.KEYS); }';
  ok(
    'R3 丢掉常驻名单 ⇒ 红',
    checkWatchedComposition(noResident, ['a'], ['b']).some((p) =>
      p.includes('SPRING_RESIDENT_TASK_KEYS')
    )
  );

  ok(
    'R3 解析不出任何 key ⇒ 红（锚点漂移不能假绿）',
    checkWatchedComposition(okBody, [], []).length === 2
  );
}

// ── R1 / R2 ───────────────────────────────────────────────────────────────
{
  const base = { managedKeys: ['m'], residentKeys: ['r'] };

  eq(
    'R1 托管任务在看护名单内 ⇒ 绿',
    evaluate({
      ...base,
      registryEntries: [],
      scheduledEntries: [{ key: 'm', file: 'A.java', line: 1, reason: 'literal', className: 'A' }]
    }).length,
    0
  );

  ok(
    'R1 孤儿调度 ⇒ 红且指出 key',
    evaluate({
      ...base,
      registryEntries: [],
      scheduledEntries: [
        { key: 'ghost', file: 'A.java', line: 1, reason: 'literal', className: 'A' }
      ]
    }).some((p) => p.includes('"ghost"'))
  );

  ok(
    'R1 解析不出 ⇒ 红且带 file:line',
    evaluate({
      ...base,
      registryEntries: [],
      scheduledEntries: [
        { key: null, file: 'A.java', line: 7, reason: 'no-trybegin', className: 'A' }
      ]
    }).some((p) => p.includes('A.java:7'))
  );

  ok(
    'R2 注册了但没人看护 ⇒ 红',
    evaluate({
      ...base,
      registryEntries: [{ key: 'ghost', file: 'Registry.java', line: 3 }],
      scheduledEntries: []
    }).some((p) => p.includes('"ghost"'))
  );

  eq(
    'R2 显式豁免的注册 key ⇒ 绿',
    evaluate({
      ...base,
      registryEntries: [{ key: 'ghost', file: 'Registry.java', line: 3 }],
      scheduledEntries: [],
      exemptRegistryKeys: new Set(['ghost'])
    }).length,
    0
  );

  eq(
    'R1 显式豁免的类 ⇒ 绿',
    evaluate({
      ...base,
      registryEntries: [],
      scheduledEntries: [
        { key: 'ghost', file: 'A.java', line: 1, reason: 'literal', className: 'A' }
      ],
      exemptClasses: new Set(['A'])
    }).length,
    0
  );
}

if (failures.length) {
  console.error(`[check-scheduled-task-watched.test] FAIL（${failures.length} 项）:`);
  for (const f of failures) console.error(`  - ${f}`);
  process.exit(1);
}

console.log(
  `[check-scheduled-task-watched.test] OK：${pass} 项断言全过` +
    `（注释剥离 5 / 解析件 8 / 名单聚合 4 / R1·R2 判据 7）`
);
