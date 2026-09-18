#!/usr/bin/env node
/**
 * 边端持久化队列：**落盘线程**判据门禁。
 *
 * 背景（2026-09-18 取证）：
 *   `PrefsJsonQueue` 是边端 MQTT 出站队列与离线上传队列共用的存储层，`save()` 用
 *   `SharedPreferences.Editor.commit()` —— **同步刷盘**，且整个「读-改-写」都在
 *   `@Synchronized` 的锁内完成。它自己的注释写着「调用方须在后台线程…勿在主线程大批量调用，
 *   以免 ANR」—— 但那只是**注释**：没有任何东西拦得住「以后有人在主线程调一次 enqueue」。
 *   这正是失效形态里的「信号在骗读者」：契约被写下来、被相信，但没有判据。
 *
 *   而 `edge/` 目前**零测试资产、零 CI 作业**（无 `gradlew`、`app/build` 从未生成过），
 *   所以「补一个 Kotlin 单测」在本仓当前状态下只是装饰品 —— 没人会跑，也跑不起来。
 *   本门禁改而把那条契约做成**可执行判据**，且只依赖静态文本（任何环境都能跑）：
 *
 *     ① 锁内同步落盘这一前提必须仍然成立（改成 `apply()` 就该连带撤销线程约束，不能只改一半）；
 *     ② 喂给 `CabinetController` 的 CoroutineScope 必须是**非主线程** Dispatcher；
 *     ③ **每一个**接触队列的调用点都必须在下方清单里显式声明，并写明它凭什么不在主线程上
 *        —— 新增调用点而未声明 ⇒ 红；清单条目已失效（调用点消失了）⇒ 也红
 *        （否则清单会随重构腐烂成一份漂亮的谎话）。
 *
 * 清单里每条都必须能回答：「这段代码跑在哪个线程上？」。答不出来就不该进清单。
 *
 *   node scripts/check-edge-queue-threading.mjs
 */
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-edge-queue-threading]';

/** 边端 Kotlin 源码根；清单键相对此目录。 */
const EDGE_SRC = join(
  root,
  'edge',
  'android-app',
  'app',
  'src',
  'main',
  'java',
  'com',
  'aicabinet',
  'edge'
);
const CABINET_SERVICE = join(EDGE_SRC, 'service', 'CabinetService.kt');
const PREFS_QUEUE = join(EDGE_SRC, 'queue', 'PrefsJsonQueue.kt');

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

function readText(file, label) {
  if (!existsSync(file)) {
    fail(`缺少 ${label}（${relative(root, file)}）—— 门禁锚点已失效，请同步本脚本`);
  }
  return readFileSync(file, 'utf8');
}

/** 剥掉行注释/KDoc，避免「注释里举个例子」骗过判据（同 check-xxl-job-wiring 的处理）。 */
function stripComments(source) {
  return source
    .split('\n')
    .map((line) => line.replace(/\/\/.*$/, ''))
    .filter((line) => !line.trim().startsWith('*') && !line.trim().startsWith('/*'))
    .join('\n');
}

// ── ① 前提：锁内同步落盘 ────────────────────────────────────────────────────
const prefsSource = stripComments(readText(PREFS_QUEUE, 'PrefsJsonQueue.kt'));
if (!/\.commit\(\)/.test(prefsSource)) {
  fail(
    'PrefsJsonQueue.save() 里已找不到 `.commit()`。\n' +
      '  本门禁的「调用方不得在主线程」约束是**建立在同步落盘之上的**：commit() 会阻塞直到刷盘完成。\n' +
      '  若确实要改成 apply()（不阻塞、但崩溃时可能丢队列数据），请把决策一并落地：\n' +
      '    · 更新 PrefsJsonQueue 的契约注释；\n' +
      '    · 删除本门禁的规则 ③（调用点清单）或把它降级为「仅供审计」。\n' +
      '  只改一半 = 契约与判据对不上，比不改更危险。'
  );
}
if (!/@Synchronized/.test(prefsSource)) {
  fail('PrefsJsonQueue 的读-改-写不再是 @Synchronized —— 并发正确性前提已变，请复核本门禁');
}

// ── ② 前提：喂给 CabinetController 的 scope 必须非主线程 ────────────────────
const serviceSource = stripComments(readText(CABINET_SERVICE, 'CabinetService.kt'));
// 取 CoroutineScope( 之后同一行的实参文本。
// ⚠️ 别用 `\([^)]*Dispatchers…`：实参里的 `SupervisorJob()` 自带右括号，[^)]* 会在那里截断，
// 于是**解析永远失败**（本门禁首跑就踩了这一下 —— 判据写错的表现和「契约被破坏」一模一样）。
const scopeArgs = (serviceSource.match(/CoroutineScope\s*\(([^\n]*)/) || [])[1];
if (scopeArgs === undefined) {
  fail(
    'service/CabinetService.kt 里找不到 `CoroutineScope(` —— ' +
      '「CabinetController → offlineQueue.enqueue → 落盘」这条链的线程依据已无从判定，请同步本门禁'
  );
}
if (/Dispatchers\s*\.\s*Main\b/.test(scopeArgs)) {
  fail(
    'service/CabinetService.kt 的 CoroutineScope 用了 Dispatchers.Main。\n' +
      '  CabinetController 在协程内做同步落盘（PrefsJsonQueue.commit）与阻塞网络上传（MinIO），\n' +
      '  换成 Main 会让落盘与上传直接跑在主线程上 ⇒ ANR / NetworkOnMainThreadException。'
  );
}
if (!/Dispatchers\s*\.\s*(Default|IO)\b/.test(scopeArgs)) {
  fail(
    `service/CabinetService.kt 的 CoroutineScope 实参里解析不出 Dispatchers.Default|IO：\n` +
      `    CoroutineScope(${scopeArgs.trim()})\n` +
      `  这是「CabinetController → offlineQueue.enqueue → 落盘」链**不在主线程**的依据；\n` +
      `  解析不出就必须人工复核并同步本门禁，不能让判断凭默认值成立。`
  );
}

// ── ③ 调用点清单：每一处都必须被声明 ────────────────────────────────────────
// 键 = "<相对 EDGE_SRC 的路径>::<API>"（同文件同 API 的多处接触只声明一次）。
// 每条都要写清「凭什么不在主线程上」—— 这句话是给人复核用的，不是给程序看的。
const DECLARED = new Map([
  [
    'mqtt/MqttDeviceClient.kt::enqueue',
    'publish()：调用方为心跳线程池(scheduleAtFixedRate)、Paho 回调线程(connectComplete)、' +
      '或 CabinetController 的 Dispatchers.Default 协程'
  ],
  ['mqtt/MqttDeviceClient.kt::size', 'flushOutbound() ← connectComplete()：Paho 客户端回调线程'],
  ['mqtt/MqttDeviceClient.kt::drain', 'flushOutbound() ← connectComplete()：Paho 客户端回调线程'],
  [
    'mqtt/OutboundMqttQueue.kt::mutate',
    'enqueue()/drain() 内部调用，线程随调用方（见本文件 enqueue/drain 两条）'
  ],
  [
    'mqtt/OutboundMqttQueue.kt::size',
    'size() 内部调用，线程随调用方（见 MqttDeviceClient.kt::size）'
  ],
  [
    'service/CabinetController.kt::enqueue',
    'finishShoppingClose() ← handleOpenDoor 的 scope.launch（Dispatchers.Default）；' +
      '此处只在上传失败分支触发，与同函数内的阻塞上传同一线程'
  ],
  [
    'upload/OfflineUploadQueue.kt::mutate',
    'enqueue() 内部调用，线程随调用方（见 CabinetController.kt::enqueue）'
  ],
  [
    'upload/OfflineUploadQueue.kt::enqueue',
    'enqueueSingle() 内部委托；enqueueSingle 当前无外部调用者（若将来接线，必须重新复核线程）'
  ],
  [
    'upload/OfflineUploadQueue.kt::snapshot',
    'processQueue() ← start() 在 offline-upload 单线程 executor 上调度（scheduleWithFixedDelay + execute）'
  ],
  [
    'upload/OfflineUploadQueue.kt::replaceAll',
    'processQueue() ← 同上（offline-upload 单线程 executor）'
  ]
]);

/** 写类 API：任意接收者。 */
const WRITE_APIS = ['enqueueSingle', 'enqueue', 'drain', 'mutate', 'replaceAll', 'snapshot'];
/** 读类 API：只在已知队列接收者上判定，避免把 List.size() 也算进来。 */
const QUEUE_RECEIVERS = ['outboundQueue', 'offlineQueue', 'store'];

function walkKt(dir, out = []) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) {
      if (entry.name === 'build') continue;
      walkKt(full, out);
    } else if (entry.name.endsWith('.kt')) {
      out.push(full);
    }
  }
  return out;
}

if (!existsSync(EDGE_SRC)) {
  fail(`找不到边端源码目录 ${relative(root, EDGE_SRC)} —— 门禁已失效`);
}
const files = walkKt(EDGE_SRC);
if (files.length < 10) {
  fail(`边端只解析出 ${files.length} 个 .kt 文件，明显偏少 —— 门禁已失效`);
}

const found = new Map(); // key -> [line, ...]
const DEF_RE = /^\s*(?:override\s+|private\s+|internal\s+|public\s+)*fun\s+[A-Za-z0-9_]+\s*\(/;
for (const file of files) {
  const rel = relative(EDGE_SRC, file).replace(/\\/g, '/');
  const lines = stripComments(readFileSync(file, 'utf8')).split('\n');
  lines.forEach((line, index) => {
    // ⚠️ 定义行只能**剥掉签名**，不能整行跳过：`fun size(): Int = store.size()`
    // 的**尾部**就是一处真实接触点，整行跳过会让门禁以为该条目「已失效」。
    // 而带块的 `fun enqueue(...) {` 剥掉签名后只剩 ` {`，自然不会误判。
    let scanLine = line;
    const def = scanLine.match(DEF_RE);
    if (def) {
      const close = scanLine.indexOf(')', def.index + def[0].length);
      scanLine = close >= 0 ? scanLine.slice(close + 1) : '';
    }
    for (const api of WRITE_APIS) {
      // 接收者可省：`store.mutate {` 与类内自调 `enqueue(...)` 都算接触点。
      if (new RegExp(`(?:[A-Za-z0-9_.]+\\.)?${api}\\s*[({]`).test(scanLine)) {
        const key = `${rel}::${api}`;
        if (!found.has(key)) found.set(key, []);
        found.get(key).push(index + 1);
      }
    }
    for (const receiver of QUEUE_RECEIVERS) {
      if (new RegExp(`\\b${receiver}\\.size\\s*\\(\\s*\\)`).test(scanLine)) {
        const key = `${rel}::size`;
        if (!found.has(key)) found.set(key, []);
        found.get(key).push(index + 1);
      }
    }
  });
}

// 守卫门禁自身：接触点太少说明解析锚点被重写，门禁会静默变成「永远绿」。
const MIN_CONTACT_POINTS = 8;
const totalContacts = [...found.values()].reduce((sum, list) => sum + list.length, 0);
if (totalContacts < MIN_CONTACT_POINTS) {
  fail(
    `只解析出 ${totalContacts} 处队列接触点（预期 ≥ ${MIN_CONTACT_POINTS}）—— ` +
      `多半是队列 API 被改名/搬走，本门禁已失去判别力，请同步本脚本`
  );
}

const problems = [];
const undeclared = [...found.keys()].filter((key) => !DECLARED.has(key)).sort();
for (const key of undeclared) {
  problems.push(
    `${key}（第 ${found.get(key).join(', ')} 行）：接触持久化队列但未在本门禁清单中声明。\n` +
      `      落盘是同步刷盘；请确认它跑在哪个线程上，然后在 DECLARED 里补一条并写明依据`
  );
}
const stale = [...DECLARED.keys()].filter((key) => !found.has(key)).sort();
for (const key of stale) {
  problems.push(
    `${key}：清单里有、代码里已找不到 —— 该条目已失效。\n` +
      `      要么调用点被删/改名（请更新清单），要么解析被绕过（请复核判据）`
  );
}

if (problems.length) {
  fail(`边端持久化队列的落盘线程契约被破坏：\n  - ${problems.join('\n  - ')}`);
}

console.log(
  `${TAG} OK（接触点 ${totalContacts} 处／已声明 ${DECLARED.size} 条；` +
    `锁内同步落盘 ✓、CabinetScope 非主线程 ✓）`
);
for (const [key, thread] of DECLARED) {
  console.log(`  · ${key} —— ${thread}`);
}
