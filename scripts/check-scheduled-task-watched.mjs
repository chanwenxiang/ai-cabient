#!/usr/bin/env node
/**
 * check-scheduled-task-watched.mjs — F1「可观测性自证」门禁（收敛计划 2026-11-17）
 *
 * 判据：**每一个真的会被调度的任务，都必须进超期看护的扫描名单。**
 *
 * 背景（真实缺陷，不是假想）
 * --------------------------
 * `ScheduledTaskStaleMonitor` 是「任务停跑」的唯一自动发现装置，而它的扫描名单只有两份：
 * `XxlJobManagedTasks.KEYS`（托管给 xxl-job 的）∪ `SPRING_RESIDENT_TASK_KEYS`（Spring 常驻的）。
 * 于是任务建了、注册了、阈值也配了，**只要漏进这两份名单，看护就永远看不到它** ——
 * 而它在运营台上看起来完全正常（有登记行、有周期描述）。
 *
 * 实证：`charge-pending-reconcile`（扣款回执对账，2 分钟一次）三样都齐 ——
 * 注册表有 `register("charge-pending-reconcile", …)`、`ScheduleZones` 有 10 分钟阈值、
 * V492 有 seed 行 —— 唯独不在上面两份名单里。它是「渠道已扣款、本地仍 PENDING」的
 * 唯一自动收口点，停跑既不告警也不影响任何测试。
 *
 * 为什么不能只靠既有门禁
 * ----------------------
 * - `check-xxl-job-wiring.mjs` 的覆盖方向是「**托管清单 → 下游**」：KEYS 里每个 key 都得有
 *   handler、有 seed、有 cron、有阈值。它从不问「注册表里的 key 是否都在 KEYS 里」——
 *   而漏进 KEYS 恰恰是本次缺陷的形态。
 * - `check-scheduled-task-trybegin.mjs` 的覆盖方向是「**注册表 → 方法体**」：每个注册任务
 *   的方法体里得有 `tryBegin`。它同样不问「这个 key 有没有人看护」。
 *
 * 三条规则（缺一条就留一个盲区）
 * ------------------------------
 *   R1 调度面 → 看护面：源码里每个 `@Scheduled` 方法的 `tryBegin("<key>")`，key 必须属于
 *      `KEYS ∪ SPRING_RESIDENT_TASK_KEYS`。这条抓「新写了个定时任务，忘了进名单」。
 *   R2 注册面 → 看护面：`ScheduledTaskRegistry.register("<key>")` 的每个 key 同理。
 *      这条抓「登记了、运营台可见、但没人看护」—— 本次 charge-pending-reconcile 的实际形态。
 *   R3 看护面自身不能缩水：`watchedKeys()` 必须同时聚合上面两个来源，且两份来源都解析出非空。
 *      否则把 `SPRING_RESIDENT_TASK_KEYS` 从 `watchedKeys()` 里删掉，R1/R2 会拿一份已经
 *      不生效的名单去比对，门禁照样全绿（判据与被判对象脱钩）。
 *
 * 豁免必须写在下方 EXEMPT 且带理由：门禁只认「显式决定」，不认「碰巧没扫到」。
 *
 * 注释一律先剥离再解析：注释里出现 `@Scheduled` / `tryBegin(...)` 不算数
 * （否则加一行注释就能让门禁恒绿）。
 *
 *   node scripts/check-scheduled-task-watched.mjs
 *   node scripts/check-scheduled-task-watched.mjs --self-test
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-scheduled-task-watched]';
const SRC_ROOT = join(ROOT, 'services', 'trade-service', 'src', 'main', 'java');
const MONITOR = join(SRC_ROOT, 'com/aicabinet/trade/service/ScheduledTaskStaleMonitor.java');
const MANAGED = join(SRC_ROOT, 'com/aicabinet/trade/service/XxlJobManagedTasks.java');
const REGISTRY = join(SRC_ROOT, 'com/aicabinet/trade/service/ScheduledTaskRegistry.java');

/**
 * 显式豁免：类名 → 理由。
 * 只允许「结构上无法自证」的场景；「忘了配」不在此列。
 */
const EXEMPT_CLASSES = new Map([
  [
    'ScheduledTaskStaleMonitor',
    '看护者自身：它的停止只能由进程外探针发现（Prometheus up{job="trade-service"} == 0），' +
      '把自己写进自己的扫描名单在「本进程整体消失」时同样沉默，不构成实际覆盖。' +
      '它仍受 check-xxl-job-wiring 3.6 约束（不得进 XXL KEYS，否则跟着一起停）。'
  ],
  [
    'SchedulingPoolCapacitySelfCheck',
    '调度线程池容量自检：不是业务任务，不落 scheduled_task、无 tryBegin、无运营台入口；' +
      '它的失效形态是「容量不足时不再告警」，由启动期自检日志与 setup 阶段门禁兜底。'
  ],
  ['XxlJobWiringSelfCheck', 'XXL 接线自检：与上同类，属启动期自检装置而非周期业务任务。'],
  [
    'CacheConfig',
    'purgeExpiredCache 是**进程内**缓存过期清理（fixedRate 5 分钟）：它刻意不加分布式锁、' +
      '也不落 scheduled_task —— 每个实例都必须各自清自己的堆内缓存，共享锁反而会让 N-1 个实例永远不清。' +
      '失效后果是内存缓慢上涨（有堆上限与 OOM fail-fast 兜底），不属于「任务停跑」类的静默资金/数据风险。'
  ]
]);

/**
 * 注册表侧的显式豁免：任务登记了（运营台可见、可手工触发），但**刻意不进**超期看护。
 * 与 EXEMPT_CLASSES 的区别：那里豁免的是「没有 tryBegin 的调度方法」，这里豁免的是「有注册行但无人看护」。
 */
const EXEMPT_REGISTRY_KEYS = new Map([
  [
    'scheduled-task-stale-monitor',
    '看护者自身：它一旦不跑，自己也就无法报出「我不跑了」；真正的发现手段是进程外探针' +
      '（Prometheus up{job="trade-service"} == 0）。登记进注册表只是为了运营台能手工触发，不是为了被看护。'
  ]
]);

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

/**
 * Java 注释剥离（等长空格替换，保留行号）。
 * 必须能穿过字符串字面量，否则 `"http://x"` 里的 `//` 会把整行后半截当注释吃掉。
 */
export function maskJavaComments(src) {
  const out = [];
  let i = 0;
  let state = null; // null | 'line' | 'block' | 'str' | 'char'
  while (i < src.length) {
    const c = src[i];
    const n = src[i + 1];
    if (state === null) {
      if (c === '/' && n === '/') {
        state = 'line';
        out.push(' ', ' ');
        i += 2;
        continue;
      }
      if (c === '/' && n === '*') {
        state = 'block';
        out.push(' ', ' ');
        i += 2;
        continue;
      }
      if (c === '"') state = 'str';
      else if (c === "'") state = 'char';
      out.push(c);
      i += 1;
      continue;
    }
    if (state === 'line') {
      if (c === '\n') {
        state = null;
        out.push('\n');
      } else out.push(' ');
      i += 1;
      continue;
    }
    if (state === 'block') {
      if (c === '*' && n === '/') {
        state = null;
        out.push(' ', ' ');
        i += 2;
        continue;
      }
      out.push(c === '\n' ? '\n' : ' ');
      i += 1;
      continue;
    }
    // 字符串/字符字面量：转义跳过
    if (c === '\\') {
      out.push(c, n ?? '');
      i += 2;
      continue;
    }
    if (state === 'str' && c === '"') state = null;
    if (state === 'char' && c === "'") state = null;
    out.push(c);
    i += 1;
  }
  return out.join('');
}

function lineOf(src, index) {
  let line = 1;
  for (let i = 0; i < index && i < src.length; i++) if (src[i] === '\n') line++;
  return line;
}

/** 花括号配平取方法体（从 `{` 起）。 */
function readBlock(src, openIndex) {
  let depth = 0;
  for (let i = openIndex; i < src.length; i++) {
    const c = src[i];
    if (c === '{') depth++;
    else if (c === '}') {
      depth--;
      if (depth === 0) return src.slice(openIndex, i + 1);
    }
  }
  return null;
}

/** 取 `(` 起配平的参数串。 */
function readParens(src, openIndex) {
  let depth = 0;
  for (let i = openIndex; i < src.length; i++) {
    const c = src[i];
    if (c === '(') depth++;
    else if (c === ')') {
      depth--;
      if (depth === 0) return src.slice(openIndex + 1, i);
    }
  }
  return null;
}

/** 解析文件内 `[static] [final] String NAME = "literal";` 常量表。 */
export function parseStringConstants(masked) {
  const map = new Map();
  const re = /\bString\s+([A-Z][A-Z0-9_]*)\s*=\s*"([^"]+)"/g;
  for (const m of masked.matchAll(re)) map.set(m[1], m[2]);
  return map;
}

/**
 * 从一个 `@Scheduled` 注解位置解析出「本方法的 tryBegin 参数」。
 * 返回 { key 或 null（无法解析/未调用）, line }。
 */
export function extractScheduledCall(masked, atIndex, constants) {
  const parenAt = masked.indexOf('(', atIndex);
  if (parenAt < 0)
    return { key: null, line: lineOf(masked, atIndex), reason: 'annotation-unparsable' };
  const annotationArgs = readParens(masked, parenAt);
  if (annotationArgs === null) {
    return { key: null, line: lineOf(masked, atIndex), reason: 'annotation-unparsable' };
  }
  const signatureAt = parenAt + annotationArgs.length + 2;
  const bodyOpen = masked.indexOf('{', signatureAt);
  if (bodyOpen < 0) return { key: null, line: lineOf(masked, atIndex), reason: 'body-missing' };
  const body = readBlock(masked, bodyOpen);
  if (body === null) return { key: null, line: lineOf(masked, atIndex), reason: 'body-unparsable' };

  const call = body.indexOf('tryBegin(');
  if (call < 0) {
    return { key: null, line: lineOf(masked, atIndex), reason: 'no-trybegin' };
  }
  const argsAt = call + 'tryBegin'.length;
  const args = readParens(body, argsAt);
  if (args === null) {
    return { key: null, line: lineOf(masked, atIndex), reason: 'trybegin-unparsable' };
  }
  const first = args.split(',')[0].trim();
  const literal = first.match(/^"([^"]+)"$/);
  if (literal) return { key: literal[1], line: lineOf(masked, atIndex), reason: 'literal' };
  if (constants.has(first))
    return {
      key: constants.get(first),
      line: lineOf(masked, atIndex),
      reason: `constant:${first}`
    };
  return { key: null, line: lineOf(masked, atIndex), reason: `unresolved:${first}` };
}

function walkJava(dir, acc = []) {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) walkJava(full, acc);
    else if (name.endsWith('.java')) acc.push(full);
  }
  return acc;
}

/** 从 `KEYS = Set.of(` 里取字面量。 */
export function parseManagedKeys(src) {
  const anchor = src.indexOf('KEYS = Set.of(');
  if (anchor < 0) return null;
  const end = src.indexOf(');', anchor);
  if (end < 0) return null;
  return [...src.slice(anchor, end).matchAll(/"([a-z0-9-]+)"/g)].map((m) => m[1]);
}

/** 从 `SPRING_RESIDENT_TASK_KEYS = …List.of(` 里取字面量。 */
export function parseResidentKeys(src) {
  const anchor = src.indexOf('SPRING_RESIDENT_TASK_KEYS');
  if (anchor < 0) return null;
  const open = src.indexOf('List.of(', anchor);
  if (open < 0) return null;
  const end = src.indexOf(');', open);
  if (end < 0) return null;
  return [...src.slice(open, end).matchAll(/"([a-z0-9-]+)"/g)].map((m) => m[1]);
}

/** R3：看护名单必须由两份来源聚合而成，且解析结果非空。 */
export function checkWatchedComposition(monitorMasked, managedKeys, residentKeys) {
  const problems = [];
  const at = monitorMasked.indexOf('watchedKeys(');
  if (at < 0) {
    problems.push(
      'ScheduledTaskStaleMonitor 里找不到 watchedKeys( —— 看护名单入口被改名，本门禁已失效'
    );
    return problems;
  }
  const body = readBlock(monitorMasked, monitorMasked.indexOf('{', at));
  if (body === null) {
    problems.push('watchedKeys() 方法体无法解析 —— 本门禁已失效');
    return problems;
  }
  if (!/XxlJobManagedTasks\.KEYS/.test(body)) {
    problems.push(
      'watchedKeys() 不再聚合 XxlJobManagedTasks.KEYS —— 托管任务全部失去看护，' +
        '而本门禁若继续用 KEYS 比对就会假绿'
    );
  }
  if (!/SPRING_RESIDENT_TASK_KEYS/.test(body)) {
    problems.push(
      'watchedKeys() 不再聚合 SPRING_RESIDENT_TASK_KEYS —— Spring 常驻任务（refund-retry / settlement-bill / ' +
        'charge-pending-reconcile 等）全部失去看护'
    );
  }
  if (!managedKeys || managedKeys.length === 0) {
    problems.push('XxlJobManagedTasks.KEYS 未解析出任何 key —— 锚点漂移，本门禁已失效');
  }
  if (!residentKeys || residentKeys.length === 0) {
    problems.push('SPRING_RESIDENT_TASK_KEYS 未解析出任何 key —— 锚点漂移，本门禁已失效');
  }
  return problems;
}

/**
 * 纯判据（供 --self-test 直接喂合成数据）。
 * @param {object} p
 * @param {string[]} p.managedKeys
 * @param {string[]} p.residentKeys
 * @param {{key:string,file:string,line:number}[]} p.registryEntries
 * @param {{key:string|null,file:string,line:number,reason:string,className:string}[]} p.scheduledEntries
 * @param {Set<string>} [p.exemptClasses]
 */
export function evaluate({
  managedKeys,
  residentKeys,
  registryEntries,
  scheduledEntries,
  exemptClasses = new Set(),
  exemptRegistryKeys = new Set()
}) {
  const problems = [];
  const watched = new Set([...managedKeys, ...residentKeys]);

  // R1：每个 @Scheduled 方法的 tryBegin key 必须在看护名单里
  for (const entry of scheduledEntries) {
    if (exemptClasses.has(entry.className)) continue;
    if (entry.key === null) {
      problems.push(
        `${entry.file}:${entry.line} 的 @Scheduled 方法无法解析出 tryBegin 的 taskKey（${entry.reason}）` +
          ` —— 要么它没走执行守卫，要么解析器与源码形态漂移；两种情况都必须人工确认后写进本脚本`
      );
      continue;
    }
    if (!watched.has(entry.key)) {
      problems.push(
        `${entry.file}:${entry.line} 的 @Scheduled 任务 "${entry.key}" 不在超期看护名单里` +
          `（XxlJobManagedTasks.KEYS ∪ SPRING_RESIDENT_TASK_KEYS）—— 它停跑时零告警：` +
          `请加成 SPRING_RESIDENT_TASK_KEYS 的一项，或说明它为何不需要看护并写入 EXEMPT_CLASSES`
      );
    }
  }

  // R2：注册表里的每个 key 也必须在看护名单里（本次缺陷的实际形态）
  for (const entry of registryEntries) {
    if (exemptRegistryKeys.has(entry.key)) continue;
    if (!watched.has(entry.key)) {
      problems.push(
        `ScheduledTaskRegistry 注册了 "${entry.key}"（${entry.file}:${entry.line}）但没人看护` +
          ` —— 运营台能看到它、调度也会跑，唯独停跑时没有任何告警`
      );
    }
  }

  return problems;
}

// ── --self-test：内嵌合成数据验证判别力（铁律 38：门禁自身要负向验证）──────────
export function selfTest() {
  const base = {
    managedKeys: ['managed-ok'],
    residentKeys: ['resident-ok']
  };
  const okProblems = evaluate({
    ...base,
    registryEntries: [{ key: 'resident-ok', file: 'Registry.java', line: 1 }],
    scheduledEntries: [
      { key: 'managed-ok', file: 'A.java', line: 10, reason: 'literal', className: 'A' },
      { key: 'resident-ok', file: 'B.java', line: 20, reason: 'constant', className: 'B' }
    ]
  });
  if (okProblems.length) {
    fail(`--self-test 基线误判红：${JSON.stringify(okProblems)}`);
  }

  const orphanScheduled = evaluate({
    ...base,
    registryEntries: [],
    scheduledEntries: [
      { key: 'orphan-key', file: 'C.java', line: 30, reason: 'constant', className: 'C' }
    ]
  });
  if (!orphanScheduled.some((p) => p.includes('orphan-key'))) {
    fail('--self-test 判别力失效：@Scheduled 的任务不在看护名单 ⇒ 未判红');
  }

  const orphanRegistry = evaluate({
    ...base,
    registryEntries: [{ key: 'ghost-key', file: 'Registry.java', line: 7 }],
    scheduledEntries: []
  });
  if (!orphanRegistry.some((p) => p.includes('ghost-key'))) {
    fail('--self-test 判别力失效：注册了但没人看护 ⇒ 未判红');
  }

  const unresolvable = evaluate({
    ...base,
    registryEntries: [],
    scheduledEntries: [
      { key: null, file: 'D.java', line: 40, reason: 'unresolved:SOME_EXPR', className: 'D' }
    ]
  });
  if (!unresolvable.some((p) => p.includes('D.java:40'))) {
    fail('--self-test 判别力失效：无法解析 taskKey ⇒ 未判红');
  }

  const exempted = evaluate({
    ...base,
    registryEntries: [],
    scheduledEntries: [
      { key: 'not-watched-but-exempt', file: 'E.java', line: 50, reason: 'literal', className: 'E' }
    ],
    exemptClasses: new Set(['E'])
  });
  if (exempted.length) {
    fail('--self-test 判别力失效：显式豁免类仍被判红');
  }

  const exemptRegistry = evaluate({
    ...base,
    registryEntries: [{ key: 'self-watchdog', file: 'Registry.java', line: 9 }],
    scheduledEntries: [],
    exemptRegistryKeys: new Set(['self-watchdog'])
  });
  if (exemptRegistry.length) {
    fail('--self-test 判别力失效：显式豁免的注册 key 仍被判红');
  }

  const notExemptRegistry = evaluate({
    ...base,
    registryEntries: [{ key: 'self-watchdog', file: 'Registry.java', line: 9 }],
    scheduledEntries: []
  });
  if (!notExemptRegistry.some((p) => p.includes('self-watchdog'))) {
    fail('--self-test 判别力失效：未登记豁免的注册 key 没人看护 ⇒ 未判红');
  }

  // R3：watchedKeys() 不聚合任一来源时必须红（否则判据与被判对象脱钩）
  const shrinkManaged = checkWatchedComposition(
    'List<String> watchedKeys() { return new ArrayList<>(SPRING_RESIDENT_TASK_KEYS); }',
    base.managedKeys,
    base.residentKeys
  );
  if (!shrinkManaged.some((p) => p.includes('XxlJobManagedTasks.KEYS'))) {
    fail('--self-test 判别力失效：watchedKeys() 丢掉 KEYS ⇒ 未判红');
  }
  const shrinkResident = checkWatchedComposition(
    'List<String> watchedKeys() { return new ArrayList<>(XxlJobManagedTasks.KEYS); }',
    base.managedKeys,
    base.residentKeys
  );
  if (!shrinkResident.some((p) => p.includes('SPRING_RESIDENT_TASK_KEYS'))) {
    fail('--self-test 判别力失效：watchedKeys() 丢掉 SPRING_RESIDENT_TASK_KEYS ⇒ 未判红');
  }
  const compositionOk = checkWatchedComposition(
    'List<String> watchedKeys() { List<String> k = new ArrayList<>(XxlJobManagedTasks.KEYS); k.addAll(SPRING_RESIDENT_TASK_KEYS); return k; }',
    base.managedKeys,
    base.residentKeys
  );
  if (compositionOk.length) {
    fail(`--self-test 基线误判红（watchedKeys 正常形态）：${JSON.stringify(compositionOk)}`);
  }

  // 注释剥离必须等长保留行号（否则所有行号证据都会偏）
  const sample = 'a\n// @Scheduled\nb\n/* x\ny */\nc';
  const masked = maskJavaComments(sample);
  if (masked.split('\n').length !== sample.split('\n').length) {
    fail('maskJavaComments 未保留行数 —— 行号证据会失真');
  }
  if (masked.includes('@Scheduled')) {
    fail('maskJavaComments 未屏蔽行注释中的 @Scheduled —— 注释可骗过门禁');
  }

  console.log(
    `${TAG} self-test OK（基线绿 / 孤儿调度红 / 孤儿注册红 / 解析不出红 / 显式豁免绿 / 名单缩水红）`
  );
}

// ── 执行 ────────────────────────────────────────────────────────────────────
export function runCli() {
  const managedMasked = maskJavaComments(readFileSync(MANAGED, 'utf8'));
  const monitorMasked = maskJavaComments(readFileSync(MONITOR, 'utf8'));
  const registryMasked = maskJavaComments(readFileSync(REGISTRY, 'utf8'));

  const managedKeys = parseManagedKeys(managedMasked);
  const residentKeys = parseResidentKeys(monitorMasked);
  const problems = checkWatchedComposition(monitorMasked, managedKeys, residentKeys);

  const registryEntries = [...registryMasked.matchAll(/register\(\s*"([a-z0-9-]+)"/g)].map((m) => ({
    key: m[1],
    file: relative(ROOT, REGISTRY).replaceAll('\\', '/'),
    line: lineOf(registryMasked, m.index)
  }));
  if (registryEntries.length < 30) {
    fail(
      `ScheduledTaskRegistry 只解析出 ${registryEntries.length} 个 register(...)，预期 30+ —— 解析器已漂移`
    );
  }

  const scheduledEntries = [];
  for (const file of walkJava(SRC_ROOT)) {
    const raw = readFileSync(file, 'utf8');
    if (!raw.includes('@Scheduled')) continue;
    const masked = maskJavaComments(raw);
    if (!masked.includes('@Scheduled')) continue; // 只在注释里出现过
    const constants = parseStringConstants(masked);
    const className = (masked.match(/\bclass\s+(\w+)/) || [
      ,
      file.replace(/.*[\\/]/, '').replace('.java', '')
    ])[1];
    for (const m of masked.matchAll(/@Scheduled\s*\(/g)) {
      const entry = extractScheduledCall(masked, m.index, constants);
      scheduledEntries.push({
        ...entry,
        file: relative(ROOT, file).replaceAll('\\', '/'),
        className
      });
    }
  }
  if (scheduledEntries.length < 30) {
    fail(`全仓只解析出 ${scheduledEntries.length} 个 @Scheduled 方法，预期 30+ —— 解析器已漂移`);
  }

  problems.push(
    ...evaluate({
      managedKeys,
      residentKeys,
      registryEntries,
      scheduledEntries,
      exemptClasses: new Set(EXEMPT_CLASSES.keys()),
      exemptRegistryKeys: new Set(EXEMPT_REGISTRY_KEYS.keys())
    })
  );

  if (problems.length) {
    console.error(`${TAG} FAIL（${problems.length} 项）:`);
    for (const p of problems) console.error(`  - ${p}`);
    process.exit(1);
  }

  console.log(
    `${TAG} OK：${scheduledEntries.length} 个 @Scheduled / ${registryEntries.length} 个注册任务全部在看护名单内` +
      `（托管 ${managedKeys.length} + Spring 常驻 ${residentKeys.length}，豁免 ${EXEMPT_CLASSES.size} 类）`
  );
}

// 直接执行才跑真实树；被 .test.mjs import 时只取导出函数（否则 import 就会替别人跑门禁）。
const invokedDirectly =
  process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (invokedDirectly) {
  if (process.argv.includes('--self-test')) {
    selfTest();
  } else {
    runCli();
  }
}
