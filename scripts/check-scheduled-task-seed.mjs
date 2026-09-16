#!/usr/bin/env node
/**
 * 定时任务「注册表 ↔ 种子行」一致性门禁。
 *
 * 背景（第十轮续）：`scheduled_task` 是定时任务模块的**唯一**可见面 —— 运营台列表、
 * 启停开关、手动触发、最近执行记录全挂在它上面；而 `ScheduledTaskService.finish()`
 * 只更新**已存在**的行（`findByIdForUpdate().orElse(null)`），行不存在时**静默丢弃**执行记录。
 *
 * 于是「注册表里有、种子里没有」的任务会进入一种隐形态：任务照跑，但
 *   ① 运营台列表里根本看不到它；② 无法启停（`requireTaskForUpdate` 直接 404）；
 *   ③ 无法手动触发；④ 最近执行/耗时/结果全部无处落 —— 出问题时没有任何线索。
 * 实测曾发生 7 个任务处于该状态（含 XXL 托管的 `points-expiry`）。
 *
 * 两侧契约：
 *
 *   ScheduledTaskRegistry#register("key", ...)     （Java：有哪些任务、叫什么、什么频率）
 *        ↕ 每个 key 必须有 scheduled_task 种子行
 *   db/migration/*.sql 的 INSERT INTO scheduled_task（运营台可见的登记行）
 *        ↕ 反向：种子行必须有代码侧的 runner（否则是残留登记行，会永远不跑）
 *   任意 .java 里出现该 key 字面量
 *
 * 第三个方向（规则四）：**任何 `tryBegin(TASK_KEY, …)` 调用点都必须已登记**。
 * 上面两条只管「注册表 → 种子」，漏掉了「既没登记行也没注册、却照样在跑」的任务
 * （实测 `cache-purge`：每 5 分钟执行一次，记录被 `finish()` 静默丢弃，运营台不可见）。
 * 注意键常经 `private static final String TASK_KEY = "…"` 间接引用，必须解析常量后比对。
 *
 *   node scripts/check-scheduled-task-seed.mjs
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-scheduled-task-seed]';

const SERVICE_DIR = join(root, 'services', 'trade-service', 'src', 'main');
const REGISTRY_FILE = join(
  SERVICE_DIR,
  'java',
  'com',
  'aicabinet',
  'trade',
  'service',
  'ScheduledTaskRegistry.java'
);
const MIGRATION_DIR = join(SERVICE_DIR, 'resources', 'db', 'migration');

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

function read(file) {
  return readFileSync(file, 'utf8');
}

function walk(dir, filter, out = []) {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) {
      walk(full, filter, out);
    } else if (filter(full)) {
      out.push(full);
    }
  }
  return out;
}

// ── 1. 注册表键（含任务名与分组，便于报错定位）────────────────────────────
const registrySrc = read(REGISTRY_FILE);
const registry = new Map();
// register("key", "名称", GROUP, 描述, lease, action) —— 描述可能是字面量或 ScheduleZones.desc("…")
const REGISTER_RE =
  /register\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,\s*("[A-Z_]+"|[A-Z][A-Z0-9_]*|[A-Z_]+)\s*,/g;
for (const m of registrySrc.matchAll(REGISTER_RE)) {
  registry.set(m[1], { name: m[2], group: m[3] });
}
if (registry.size < 20) {
  fail(
    `未能从 ${REGISTRY_FILE.replace(root + '\\', '').replace(root + '/', '')} 解析出注册表（仅 ${registry.size} 个），锚点可能已被重写`
  );
}

// ── 2. 种子行键（所有迁移里的 INSERT INTO scheduled_task ... VALUES (…), (…) …）──
// 注意：一条 INSERT 常带多行 VALUES，须逐元组取首列，不能只取第一条。
const SQL_STMT_RE =
  /INSERT\s+INTO\s+scheduled_task\s*\([^)]*\)\s*VALUES([\s\S]*?)(?:ON\s+CONFLICT|;)/gi;
const SQL_ROW_RE = /\(\s*'([a-z0-9][a-z0-9_-]*)'\s*,\s*'/g;
const seeded = new Map();
for (const file of walk(MIGRATION_DIR, (f) => f.endsWith('.sql'))) {
  const src = read(file);
  const fileName = file.split(/[\\/]/).pop();
  for (const stmt of src.matchAll(SQL_STMT_RE)) {
    for (const row of stmt[1].matchAll(SQL_ROW_RE)) {
      seeded.set(row[1], fileName);
    }
  }
}
if (seeded.size === 0) {
  fail(`未能在 ${MIGRATION_DIR} 解析出任何 scheduled_task 种子行，锚点可能已被重写`);
}

// ── 3. 规则一：注册表任务必须有种子行（运营台必须看得见）──────────────────
const missingSeed = [];
for (const [key, meta] of registry) {
  if (!seeded.has(key)) {
    missingSeed.push(`${key}（${meta.name}）`);
  }
}

// ── 4. 规则二：种子行必须有代码侧 runner（不能是残留登记行）───────────────
const javaFiles = walk(join(SERVICE_DIR, 'java'), (f) => f.endsWith('.java'));
const javaBlob = javaFiles.map(read).join('\n');
const orphanSeed = [];
for (const key of seeded.keys()) {
  if (registry.has(key)) {
    continue;
  }
  if (!javaBlob.includes(`"${key}"`)) {
    orphanSeed.push(`${key}（来自 ${seeded.get(key)}）`);
  }
}

// ── 5. 规则三：注册表自己不能有重复 key ─────────────────────────────────
const registerKeys = [...registrySrc.matchAll(REGISTER_RE)].map((m) => m[1]);
const dup = registerKeys.filter((k, i) => registerKeys.indexOf(k) !== i);

// ── 6. 规则四：tryBegin 调用点必须已登记（注册表或种子行）─────────────────
// 反方向漏检：规则一只管「注册表 → 种子」。而「有 tryBegin 调用点、却既没种子行也没注册」
// 的任务会在后台静默执行 —— finish() 找不到行时直接把记录丢弃（不抛错、不打日志），
// 运营台看不见、不能启停、不能手动触发。实测 cache-purge 即属此类。
// 键可能是字面量，也可能经 `private static final String TASK_KEY = "…"` 间接引用，
// 必须先解析本文件内的常量再比对 —— 否则会把 ops-fee-bill-monthly 误判成「无 runner」。
// 豁免的语义必须写明：只把 tryBegin 当分布式锁用、刻意不进运营台的任务。
const LOCK_ONLY_TASKS = new Set(['cache-purge']);
const tryBeginKeys = new Map();
for (const file of javaFiles) {
  const src = read(file);
  const consts = new Map();
  for (const m of src.matchAll(
    /(?:static\s+final\s+)?String\s+([A-Z0-9_]+)\s*=\s*"([a-z0-9][a-z0-9_-]*)"/g
  )) {
    consts.set(m[1], m[2]);
  }
  for (const m of src.matchAll(/tryBegin\(\s*([A-Z0-9_]+|"[a-z0-9_-]+")/g)) {
    const raw = m[1].replace(/"/g, '');
    const key = consts.get(raw) || raw;
    if (!/^[a-z0-9][a-z0-9_-]*$/.test(key)) continue;
    if (!tryBeginKeys.has(key)) tryBeginKeys.set(key, file.split(/[\\/]/).pop());
  }
}
if (tryBeginKeys.size < 20) {
  fail(`仅从 Java 解析出 ${tryBeginKeys.size} 个 tryBegin 任务键，锚点可能已被重写`);
}
const unregistered = [...tryBeginKeys]
  .filter(([key]) => !registry.has(key) && !seeded.has(key) && !LOCK_ONLY_TASKS.has(key))
  .map(([key, where]) => `${key}（${where}）`);

const problems = [];
if (missingSeed.length) {
  problems.push(
    `注册表任务缺少 scheduled_task 种子行（运营台看不到 / 不能启停 / 执行记录无处落）：\n    - ` +
      missingSeed.join('\n    - ') +
      `\n  修法：在 db/migration 追加 INSERT ... ON CONFLICT (task_key) DO NOTHING`
  );
}
if (orphanSeed.length) {
  problems.push(
    `种子行在 Java 代码里找不到任何 runner（残留登记行，会永远不跑）：\n    - ` +
      orphanSeed.join('\n    - ')
  );
}
if (dup.length) {
  problems.push(`ScheduledTaskRegistry 存在重复 key：${[...new Set(dup)].join('、')}`);
}
if (unregistered.length) {
  problems.push(
    `任务调用了 tryBegin 但既无种子行也无注册（执行记录会被静默丢弃，运营台不可见/不可启停）：\n    - ` +
      unregistered.join('\n    - ') +
      `\n  修法：补种子行，或在 ScheduledTaskRegistry 注册；若刻意只当分布式锁用，` +
      `请加入 check-scheduled-task-seed.mjs 的 LOCK_ONLY_TASKS 并写明原因`
  );
}

if (problems.length) {
  fail(`\n  ${problems.join('\n  ')}\n`);
}

console.log(
  `${TAG} OK：注册表 ${registry.size} 个任务 ↔ 种子行 ${seeded.size} 个全部对齐，` +
    `tryBegin 调用点 ${tryBeginKeys.size} 个均已登记` +
    `（豁免仅分布式锁用途 ${LOCK_ONLY_TASKS.size} 个：${[...LOCK_ONLY_TASKS].join(', ')}）` +
    `（新增任务必须同时补种子行，否则运营台上看不见）`
);
