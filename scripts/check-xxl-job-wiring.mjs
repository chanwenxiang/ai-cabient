#!/usr/bin/env node
/**
 * XXL-JOB「托管任务」接线一致性门禁。
 *
 * 背景（第十轮 P0）：内置 @Scheduled 会对 `XxlJobManagedTasks` 清单里的任务
 * **无条件让位**给 XXL-JOB（见 ScheduledTaskService.shouldYieldToXxlJob）。于是这条
 * 链路上任何一处漂移，都会让任务**永久且静默地停跑**：内置调度不再兜底，业务表
 * last_run_at 只是停止推进，没有任何报错，测试也覆盖不到（测试不跑 docker）。
 * 实测曾发生：`XXL_JOB_ADMIN_ADDRESSES` 多带 `/xxl-job-admin` 前缀 → 执行器注册
 * 吃 404 → `xxl_job_registry` 为空 → 11 个任务（对账/分账/佣金/保证金/自动解锁…）
 * 100% 派发 "Address Router Fail"，停跑约 19 小时无人发现。
 *
 * 代码侧可静态校验的契约：
 *
 *   XxlJobManagedTasks.KEYS              （Java：哪些任务必须让位）
 *        ↕ 每个 key 必须有具名 handler
 *   ScheduledTaskXxlJobHandler           （@XxlJob("xxxJob") → runKey("<taskKey>")）
 *        ↕ 每个具名 handler 必须被排期
 *   infra/xxl-job/seed_aicabinet_jobs.sql（executor_handler 列）
 *        ↕ schedule_conf 必须与 ScheduleZones.XXL_CRON_BY_TASK 逐条一致
 *
 * 业务定时任务**全量托管**（2026-09-16 起，生产多实例）后新增四条硬校验：
 *   - 每个托管 key 必须在 ScheduleZones.XXL_CRON_BY_TASK 有 cron，且与种子的 schedule_conf
 *     **逐条相同**。两处手工维护必然漂移，而超期看护阈值是按调度周期推的 —— cron 漂移会
 *     连带把看护阈值带偏。
 *   - 每个托管 key 必须在 ScheduledTaskRegistry 注册，否则调度中心派发进来后
 *     registry.get(key) 为空 → handleFail「任务未注册」→ 任务照停。
 *   - **cron 必须静态可解析**：6 段 Quartz，且「日」/「周」二者只能有一个是 `?`。
 *     「两处一致」只保证不漂移，**不保证调度器解析得了** —— 实测 `ops-fee-bill-monthly`
 *     曾写成 7 段 `0 30 1 1 * * ?`（日=1 与周=* 同时限定），XXL `storeExpressionVals` 抛错、
 *     `refreshNextValidTime` 失败，该 job 永不触发，而当时 3.1~3.9 全部通过。
 *   - 托管任务的「最大静默时长」必须覆盖（见下）。
 *
 * 另外校验两处曾真实引发故障/掩盖故障的漂移：
 *   - xxl-job-admin 3.x 的 context-path 是 "/"，执行器侧 `XXL_JOB_ADMIN_ADDRESSES`
 *     的路径部分必须与之一致（多一段前缀 = 注册 404 = 全部托管任务停跑）。
 *   - 超期看护暴露的 Gauge 必须有 Prometheus 规则消费，否则「指标告警」这一路是假的
 *     （实证：tasks_scheduled_execution_seconds_count{outcome="ERROR"} 长期有值却无人报警）。
 *
 *   node scripts/check-xxl-job-wiring.mjs
 */
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-xxl-job-wiring]';

const MANAGED_TASKS_FILE = join(
  root,
  'services',
  'trade-service',
  'src',
  'main',
  'java',
  'com',
  'aicabinet',
  'trade',
  'service',
  'XxlJobManagedTasks.java'
);
const HANDLER_FILE = join(
  root,
  'services',
  'trade-service',
  'src',
  'main',
  'java',
  'com',
  'aicabinet',
  'trade',
  'service',
  'ScheduledTaskXxlJobHandler.java'
);
const SEED_FILE = join(root, 'infra', 'xxl-job', 'seed_aicabinet_jobs.sql');
const INFRA_DIR = join(root, 'infra');

function read(file, label) {
  try {
    return readFileSync(file, 'utf8');
  } catch (error) {
    fail(`无法读取 ${label}`, [file, String(error.message)]);
  }
  return '';
}

function fail(message, details = []) {
  console.error(`${TAG} FAIL: ${message}`);
  for (const line of details) if (line) console.error(`  ${line}`);
  process.exit(1);
}

/** 归一化 URL/context-path 的路径部分：无路径、空、尾部斜杠都视为 "/"。 */
function normalizePath(value) {
  if (!value) return '/';
  const trimmed = value.trim().replace(/\/+$/, '');
  if (!trimmed || trimmed === '/') return '/';
  return trimmed.startsWith('/') ? trimmed : `/${trimmed}`;
}

/** 取 URL 的路径部分（scheme://host:port[/path]）。非法 URL 返回 null。 */
function urlPath(url) {
  const match = url.trim().match(/^[a-z][a-z0-9+.-]*:\/\/[^/]+(\/.*)?$/i);
  if (!match) return null;
  return normalizePath(match[1] || '/');
}

// ── 1. Java 清单：哪些任务必须让位 ────────────────────────────────────────────
const managedSource = read(MANAGED_TASKS_FILE, 'XxlJobManagedTasks.java');
const keysAnchor = managedSource.indexOf('KEYS = Set.of(');
if (keysAnchor < 0) {
  fail('XxlJobManagedTasks.java 中找不到 KEYS = Set.of( 锚点，门禁已失效，请同步本脚本');
}
const keysEnd = managedSource.indexOf(');', keysAnchor);
if (keysEnd < 0) fail('XxlJobManagedTasks.java 的 KEYS 声明无法解析');
const managedKeys = [
  ...managedSource.slice(keysAnchor, keysEnd).matchAll(/"([a-z0-9][a-z0-9-]*)"/g)
].map((m) => m[1]);
if (managedKeys.length === 0) fail('XxlJobManagedTasks.KEYS 未解析出任何 taskKey，门禁已失效');

// ── 2. Handler：@XxlJob("xxxJob") → runKey("<taskKey>") ──────────────────────
const handlerSource = read(HANDLER_FILE, 'ScheduledTaskXxlJobHandler.java');
const pairPattern =
  /@XxlJob\("([A-Za-z0-9_$]+)"\)\s*public\s+void\s+[A-Za-z0-9_$]+\(\)\s*\{\s*runKey\("([a-z0-9][a-z0-9-]*)"\)/g;
const pairs = [...handlerSource.matchAll(pairPattern)].map((m) => ({
  handler: m[1],
  taskKey: m[2]
}));
if (pairs.length === 0) {
  fail('ScheduledTaskXxlJobHandler.java 未解析出任何「@XxlJob + runKey(字面量)」配对，门禁已失效');
}
const namedHandlers = new Set(pairs.map((p) => p.handler));
const keyToHandlers = new Map();
for (const { handler, taskKey } of pairs) {
  if (!keyToHandlers.has(taskKey)) keyToHandlers.set(taskKey, []);
  keyToHandlers.get(taskKey).push(handler);
}

// ── 3. 种子：executor_handler 列 ─────────────────────────────────────────────
// 行结构固定为 ` 'handler', '', 'SERIAL_EXECUTION', ...`，故以 SERIAL_EXECUTION 为锚，
// 取该行第一个引号字面量。避免依赖 handler 命名后缀（不强制叫 *Job）。
const seedSource = read(SEED_FILE, 'seed_aicabinet_jobs.sql');
const seededHandlers = new Set();
for (const line of seedSource.split('\n')) {
  if (!line.includes('SERIAL_EXECUTION')) continue;
  const match = line.match(/'([^']+)'/);
  if (match) seededHandlers.add(match[1]);
}
if (seededHandlers.size === 0) {
  fail('seed_aicabinet_jobs.sql 未解析出任何 executor_handler，门禁已失效');
}

const problems = [];

// 3.1 托管但无具名 handler：通用 runScheduledTask 不算（种子不会自动带 JobParam）
const noHandler = managedKeys.filter((key) => !keyToHandlers.has(key));
if (noHandler.length) {
  problems.push(
    `托管任务缺少具名 @XxlJob handler（只有通用 runScheduledTask 不够）：${noHandler.join(', ')}`
  );
}

// 3.2 具名 handler 未排期 → 永远不会有触发
const unscheduled = [...namedHandlers].filter((h) => !seededHandlers.has(h)).sort();
if (unscheduled.length) {
  problems.push(`@XxlJob handler 未在 seed_aicabinet_jobs.sql 排期：${unscheduled.join(', ')}`);
}

// 3.3 种子引用了不存在的 handler → 派发报 job handler not found
const dangling = [...seededHandlers].filter((h) => !namedHandlers.has(h)).sort();
if (dangling.length) {
  problems.push(`种子排期了不存在的 handler：${dangling.join(', ')}`);
}

// 3.4 具名 handler 指向未托管 key → 运行时会被 runKey 拒绝（handleFail）
const unmanaged = [...new Set(pairs.map((p) => p.taskKey))]
  .filter((key) => !managedKeys.includes(key))
  .sort();
if (unmanaged.length) {
  problems.push(
    `@XxlJob handler 指向未在 XxlJobManagedTasks.KEYS 登记的 taskKey：${unmanaged.join(', ')}`
  );
}

// 3.5 每个托管任务必须有「最大静默时长」阈值 —— 超期看护靠它判定停跑，
//     缺条目会让该任务被静默跳过，看护等于不存在。
const zonesFile = join(
  root,
  'services',
  'trade-service',
  'src',
  'main',
  'java',
  'com',
  'aicabinet',
  'trade',
  'support',
  'ScheduleZones.java'
);
const zonesSource = read(zonesFile, 'ScheduleZones.java');
const maxSilenceAnchor = zonesSource.indexOf('MAX_SILENCE_BY_TASK = Map.ofEntries(');
if (maxSilenceAnchor < 0) {
  fail('ScheduleZones.java 中找不到 MAX_SILENCE_BY_TASK 锚点，门禁已失效，请同步本脚本');
}
const maxSilenceEnd = zonesSource.indexOf(');', maxSilenceAnchor);
if (maxSilenceEnd < 0) fail('ScheduleZones.java 的 MAX_SILENCE_BY_TASK 声明无法解析');
const watchKeys = [
  ...zonesSource.slice(maxSilenceAnchor, maxSilenceEnd).matchAll(/Map\.entry\("([a-z0-9-]+)"/g)
].map((m) => m[1]);
if (watchKeys.length === 0) fail('MAX_SILENCE_BY_TASK 未解析出任何条目，门禁已失效');

const unwatched = managedKeys.filter((key) => !watchKeys.includes(key));
if (unwatched.length) {
  problems.push(
    `托管任务缺少超期看护阈值（ScheduleZones.MAX_SILENCE_BY_TASK）：${unwatched.join(', ')}` +
      ` —— 缺条目时看护会静默跳过该任务，停跑无人发现`
  );
}

// 3.6 看护者自己不能进托管清单：它一旦让位，就会跟着被看护的对象一起停跑
const MONITOR_KEY = 'scheduled-task-stale-monitor';
if (managedKeys.includes(MONITOR_KEY)) {
  problems.push(
    `${MONITOR_KEY} 不应出现在 XxlJobManagedTasks.KEYS 中：看护任务必须由 Spring 常驻执行，` +
      `否则调度中心出故障时它与被看护任务同时停跑`
  );
}

// 3.7 看护暴露的指标必须有告警规则消费 ——「指标存在但无人消费」等于没有告警。
//     实证：`tasks_scheduled_execution_seconds_count{...GrowthLogArchiveScheduler,
//     error="UnsupportedTemporalTypeException",outcome="ERROR"}` 长期为 1，
//     而 alert_rules.yml 里无任何规则引用该指标族 —— 任务 100% 失败却零告警。
//     同理，看护自己新加的 Gauge 若没人消费，三路可见性里那条「指标告警」就是假的。
const monitorSource = read(
  join(
    root,
    'services',
    'trade-service',
    'src',
    'main',
    'java',
    'com',
    'aicabinet',
    'trade',
    'service',
    'ScheduledTaskStaleMonitor.java'
  ),
  'ScheduledTaskStaleMonitor.java'
);
const alertRulesSource = read(
  join(root, 'infra', 'prometheus', 'alert_rules.yml'),
  'alert_rules.yml'
);
// Micrometer 的 "a.b.c" 落到 Prometheus 是 "a_b_c"（已用 /actuator/prometheus 实测核对）。
const monitorGauges = [
  ...new Set(
    [...monitorSource.matchAll(/Gauge\.builder\("([a-z0-9.]+)"/g)].map((m) =>
      m[1].replace(/\./g, '_')
    )
  )
];
if (monitorGauges.length === 0) {
  fail('ScheduledTaskStaleMonitor.java 未解析出任何 Gauge 指标名，门禁已失效，请同步本脚本');
}
// 逐任务静默秒数是给看板/排障用的，不配告警规则：每个任务的阈值不同（20 分钟~26 小时），
// 想写成 PromQL 就必须把阈值在 YAML 里复制一份 → 两处漂移。超期判据只在 Java 侧维护，
// 告警走聚合值 aicabinet_scheduled_task_stale_count。豁免必须显式列出，不允许静默通过。
const DASHBOARD_ONLY_GAUGES = new Set(['aicabinet_scheduled_task_silence_seconds']);
const unalerted = monitorGauges.filter(
  (g) => !DASHBOARD_ONLY_GAUGES.has(g) && !alertRulesSource.includes(g)
);
if (unalerted.length) {
  problems.push(
    `看护指标已暴露但 infra/prometheus/alert_rules.yml 无任何规则消费：${unalerted.join(', ')}` +
      ` —— 指标只是被采集，不会产生告警`
  );
}

// 3.8 托管任务的 cron 必须在两处一致：ScheduleZones.XXL_CRON_BY_TASK（Java）与
//     seed_aicabinet_jobs.sql 的 schedule_conf（真正生效的那份）。两处手工维护必然漂移，
//     而超期看护阈值是按调度周期推出来的 —— 频率漂移会把看护阈值一起带偏（漏报或误报）。
const cronAnchor = zonesSource.indexOf('XXL_CRON_BY_TASK = Map.ofEntries(');
if (cronAnchor < 0) {
  fail('ScheduleZones.java 中找不到 XXL_CRON_BY_TASK 锚点，门禁已失效，请同步本脚本');
}
const cronEnd = zonesSource.indexOf(');', cronAnchor);
if (cronEnd < 0) fail('ScheduleZones.java 的 XXL_CRON_BY_TASK 声明无法解析');
const declaredCrons = new Map(
  [
    ...zonesSource.slice(cronAnchor, cronEnd).matchAll(/Map\.entry\("([a-z0-9-]+)",\s*"([^"]+)"\)/g)
  ].map((m) => [m[1], m[2]])
);
if (declaredCrons.size === 0) fail('XXL_CRON_BY_TASK 未解析出任何条目，门禁已失效');

const noCron = managedKeys.filter((key) => !declaredCrons.has(key));
if (noCron.length) {
  problems.push(
    `托管任务缺少 XXL cron 约定（ScheduleZones.XXL_CRON_BY_TASK）：${noCron.join(', ')}`
  );
}

const orphanCron = [...declaredCrons.keys()].filter((key) => !managedKeys.includes(key)).sort();
if (orphanCron.length) {
  problems.push(`XXL_CRON_BY_TASK 存在非托管条目（僵尸约定）：${orphanCron.join(', ')}`);
}

// 每条种子元组的 (handler → schedule_conf)；handler 反查 taskKey 后与 Java 侧逐条比对。
// 元组跨三行，故用 [\s\S]*? 非贪婪跨行匹配。
const seedTupleRe =
  /\(\d+,\s*\d+,\s*'[^']*',[\s\S]*?'CRON',\s*'([^']+)',[\s\S]*?'([A-Za-z0-9_$]+)',\s*''/g;
const seedCronByHandler = new Map();
for (const m of seedSource.matchAll(seedTupleRe)) seedCronByHandler.set(m[2], m[1]);
if (seedCronByHandler.size === 0) {
  fail('未能从 seed_aicabinet_jobs.sql 解析出 (handler, cron) 元组，门禁已失效，请同步本脚本');
}

const cronMismatch = [];
for (const { handler, taskKey } of pairs) {
  const seedCron = seedCronByHandler.get(handler);
  const javaCron = declaredCrons.get(taskKey);
  if (seedCron && javaCron && seedCron !== javaCron) {
    cronMismatch.push(`${taskKey}：seed="${seedCron}" ≠ Java="${javaCron}"`);
  }
}
if (cronMismatch.length) {
  problems.push(
    `cron 两处不一致（ScheduleZones.XXL_CRON_BY_TASK ↔ seed_aicabinet_jobs.sql）：\n    - ` +
      cronMismatch.join('\n    - ')
  );
}

// 3.9 托管任务必须已在 ScheduledTaskRegistry 注册 —— 否则调度中心派发到执行器后
//     registry.get(key) 为空，runKey 直接 handleFail「任务未注册」，任务照停（且只在
//     调度台报失败，业务侧毫无痕迹）。
const registrySource = read(
  join(
    root,
    'services',
    'trade-service',
    'src',
    'main',
    'java',
    'com',
    'aicabinet',
    'trade',
    'service',
    'ScheduledTaskRegistry.java'
  ),
  'ScheduledTaskRegistry.java'
);
const registryKeys = new Set(
  [...registrySource.matchAll(/register\(\s*"([^"]+)"/g)].map((m) => m[1])
);
if (registryKeys.size === 0) {
  fail('ScheduledTaskRegistry.java 未解析出任何 register 调用，门禁已失效，请同步本脚本');
}
const notRegistered = managedKeys.filter((key) => !registryKeys.has(key));
if (notRegistered.length) {
  problems.push(
    `托管任务未在 ScheduledTaskRegistry 注册（XXL 触发会 handleFail「任务未注册」）：${notRegistered.join(', ')}`
  );
}

// 3.10 XXL cron 必须能被调度中心**真正解析** —— 「两处一致」只保证不漂移，不保证能执行。
//      XXL 的 CronExpression 是 Quartz 语义：6 段（秒 分 时 日 月 周），且
//      **「日」(dom) 与「周」(dow) 必须且只能有一个写 `?`**。两个都写（如 7 段
//      `0 30 1 1 * * ?`）会在 storeExpressionVals 直接抛错 —— admin 日志出现
//      `refreshNextValidTime error for job: jobId=…`，该 job 永远算不出下次触发时间，
//      表现与「没接线」完全一样：让位给了调度中心，调度中心却永远不派发。
//      实测 jobId=119（ops-fee-bill-monthly）曾因此完全停摆，而当时 3.1~3.9 全部通过。
const cronShapeProblems = [];
for (const [key, cron] of declaredCrons) {
  const parts = cron.trim().split(/\s+/);
  if (parts.length !== 6) {
    cronShapeProblems.push(
      `${key}：「${cron}」是 ${parts.length} 段，XXL 只接受 6 段（秒 分 时 日 月 周）`
    );
    continue;
  }
  const dom = parts[3];
  const dow = parts[5];
  if ((dom === '?') + (dow === '?') !== 1) {
    cronShapeProblems.push(
      `${key}：「${cron}」的「日」=${dom}、「周」=${dow} —— 两者必须且只能有一个写 ?，` +
        `否则 XXL CronExpression 拒绝解析，该 job 永不触发`
    );
  }
}
if (cronShapeProblems.length) {
  problems.push(
    `XXL cron 静态可解析性校验未通过（能通过一致性校验但调度器解析不了）：\n    - ` +
      cronShapeProblems.join('\n    - ')
  );
}

// ── 4. 执行器地址 与 调度中心 context-path 必须一致 ──────────────────────────
const composeFiles = readdirSync(INFRA_DIR).filter((f) => /^docker-compose.*\.ya?ml$/.test(f));
if (composeFiles.length === 0) fail('infra/ 下未找到任何 docker-compose 文件，门禁已失效');

const declaredPaths = new Set();
const addressDefaults = [];
let adminImageSeen = false;

for (const file of composeFiles) {
  const source = read(join(INFRA_DIR, file), 'utf8');
  if (/image:\s*\S*xxl-job-admin/.test(source)) adminImageSeen = true;
  for (const match of source.matchAll(/PARAMS:\s*"([^"]*)"/g)) {
    const ctx = match[1].match(/--server\.servlet\.context-path=(\S+)/);
    declaredPaths.add(normalizePath(ctx ? ctx[1] : '/'));
  }
  for (const match of source.matchAll(/XXL_JOB_ADMIN_ADDRESSES:\s*\$\{[^:}]+:-([^}]*)\}/g)) {
    addressDefaults.push({ file, default: match[1].trim() });
  }
}

if (!adminImageSeen) fail('infra/ 下未找到 xxl-job-admin 服务定义，门禁已失效');
if (addressDefaults.length === 0) fail('未找到任何 XXL_JOB_ADMIN_ADDRESSES 默认值，门禁已失效');
if (declaredPaths.size === 0) fail('无法判定 xxl-job-admin 的 context-path，门禁已失效');

for (const { file, default: fallback } of addressDefaults) {
  const path = urlPath(fallback);
  if (path === null) {
    problems.push(`${file}: XXL_JOB_ADMIN_ADDRESSES 默认值不是合法 URL：${fallback}`);
    continue;
  }
  if (!declaredPaths.has(path)) {
    problems.push(
      `${file}: XXL_JOB_ADMIN_ADDRESSES 默认值路径为 ${path}，` +
        `而 xxl-job-admin 声明的 context-path 是 ${[...declaredPaths].join(' / ')}` +
        `（多/少前缀会让执行器注册 404 → 注册表为空 → 所有托管任务派发 Address Router Fail）`
    );
  }
}

if (problems.length) {
  fail(
    'XXL-JOB 托管任务接线不一致 —— 受影响任务会「让位给调度中心但调度中心接不到」，永久静默停跑',
    problems.map((p) => `- ${p}`)
  );
}

console.log(
  `${TAG} OK（托管任务 ${managedKeys.length} 个，具名 handler ${namedHandlers.size} 个，` +
    `种子 ${seededHandlers.size} 条，cron 约定 ${declaredCrons.size} 条与种子逐条一致且均为 6 段 Quartz 可解析形态，` +
    `看护阈值 ${watchKeys.length} 条，` +
    `看护指标 ${monitorGauges.length} 个（告警消费 ${monitorGauges.length - DASHBOARD_ONLY_GAUGES.size} 个、` +
    `看板豁免 ${DASHBOARD_ONLY_GAUGES.size} 个），` +
    `地址默认值 ${addressDefaults.length} 处，` +
    `admin context-path ${[...declaredPaths].join('/')}）`
);
