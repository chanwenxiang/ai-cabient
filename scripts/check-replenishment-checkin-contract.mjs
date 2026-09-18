#!/usr/bin/env node
/**
 * 补货签到「服务端契约 ↔ E2E 脚本期望」一致性门禁。
 *
 * 背景（2026-09-18 实测）：`scripts/e2e-replenishment.ps1` 的签到步骤曾是旧约定 ——
 * 「柜机无坐标 ⇒ 发空 body 走放行路径」。服务端 `ReplenishmentService.doCheckInTask` 改成
 * 「无坐标拒签」(fail-closed) 之后，那条路径变成必然 400：**只要柜机漏填坐标，脚本第 5 步必红**。
 * 而该脚本聚合链与 CI 都没接（`grep` 全 0 命中）⇒ 契约改了没有任何信号 ——
 * 正是项目既有的失效形态⑤「存在无人消费」。本门禁就是补这个消费点。
 *
 * 它把「脚本里的期望值」钉在**源码真值**上，离线可跑（不需要起服务）：
 *
 *   真值 A  `ReplenishmentService.doCheckInTask` 的闸门顺序
 *           （终态闸 → 柜机无坐标 → 请求无坐标。顺序错了会把「柜机缺坐标」误报成「用户没开定位」）
 *   真值 B  `ApiMessages` 的四条文案
 *   真值 C  `SystemConfigService` 里 `max_distance_m` 的 upsertIfAbsent 默认值
 *   真值 D  终态闸：签到与开门必须**同一套判据**（同一条 `REPLENISHMENT_TASK_FINISHED` + 同样的状态集合）
 *   真值 E  「柜机有没有坐标」只能有一个判据（`DeviceLocationSupport.hasCoords`），
 *           且必须一路传到 DTO 的 `deviceHasCoords`（客户端据此前置拦）
 *
 *   消费方  `scripts/e2e-replenishment.ps1` 的 `$CheckInContract` 真值块 + 负向用例断言
 *           `scripts/e2e-checkin-contract.ps1`（实跑：终态拒签 + deviceHasCoords 是否真出现在响应里）
 *
 * 七组规则：
 *   1. 四条文案必须是 Java 常量的**子串**且长度 ≥ 8（只写「约」这种碎片等于没断言）
 *   2. `MaxDistanceDefaultM` 必须等于服务端 upsertIfAbsent 的默认值
 *   3. 边界用例必须夹住默认值：`BoundaryInsideM < MaxDistanceDefaultM < BoundaryOutsideM`
 *   4. 真值块必须**真被消费**：文案各自出现在 `-ExpectMessageContains` 断言里，
 *      且**设备侧闸要有两条**（空 body + 带合法坐标）—— 后者正是旧逻辑放行的那条路径，
 *      少了它「柜机缺坐标仍被拒」就没有证据。两个边界值至少被引用一次 ——
 *      否则块会退化成「写了但没人用」的装饰品，门禁自身也就变成恒真
 *      （这比没有门禁更糟：它给人已经守住的错觉）。
 *   5. 负向断言助手（`Assert-E2eApiRejected` / `Assert-E2eTaskStillTerminal`）必须存在于
 *      `e2e-lib.ps1` —— 否则实跑脚本会在运行时炸。
 *   6. 终态闸必须**在坐标闸之前**，与开门共用同一条文案与状态集合；
 *      实跑脚本必须对 COMPLETED / CANCELLED 各断言一次拒签，且**缺任务时必须显式报失败**
 *      （判的是「缺任务 ⇒ 记失败」这个**结构**，不是某一句文案 —— 见下方注释里的假红事故）。
 *   7. `deviceHasCoords` 的判据不得被重新内联，且实跑脚本必须证明它真出现在响应里
 *      （只断言 DTO 里有这个字段 = 只证明了「声明」，没证明「送达」）。
 *
 *   node scripts/check-replenishment-checkin-contract.mjs
 */
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-replenishment-checkin-contract]';

const TRADE_MAIN = join(
  root,
  'services',
  'trade-service',
  'src',
  'main',
  'java',
  'com',
  'aicabinet',
  'trade'
);
const SERVICE_FILE = join(TRADE_MAIN, 'service', 'ReplenishmentService.java');
const MESSAGES_FILE = join(TRADE_MAIN, 'support', 'ApiMessages.java');
const CONFIG_FILE = join(TRADE_MAIN, 'service', 'SystemConfigService.java');
const DEVICE_VALIDATION_FILE = join(TRADE_MAIN, 'service', 'DeviceValidationService.java');
const COORDS_SUPPORT_FILE = join(TRADE_MAIN, 'support', 'DeviceLocationSupport.java');
const PORTAL_SERVICE_FILE = join(TRADE_MAIN, 'service', 'MerchantInventoryPortalService.java');
const DTO_FILE = join(
  root,
  'services',
  'common',
  'common-core',
  'src',
  'main',
  'java',
  'com',
  'aicabinet',
  'common',
  'dto',
  'ReplenishmentTaskDto.java'
);
const E2E_FILE = join(root, 'scripts', 'e2e-replenishment.ps1');
const E2E_CONTRACT_FILE = join(root, 'scripts', 'e2e-checkin-contract.ps1');
const E2E_LIB_FILE = join(root, 'scripts', 'e2e-lib.ps1');

const KEY_DEVICE_MISSING = 'REPLENISHMENT_CHECK_IN_DEVICE_LOCATION_MISSING';
const KEY_LOCATION_REQUIRED = 'REPLENISHMENT_CHECK_IN_LOCATION_REQUIRED';
const KEY_TOO_FAR = 'REPLENISHMENT_CHECK_IN_TOO_FAR';
const KEY_REQUIRE_LOCATION = 'REPLENISHMENT_CHECK_IN_REQUIRE_LOCATION';
const KEY_TASK_FINISHED = 'REPLENISHMENT_TASK_FINISHED';
const KEY_COORDS_PREDICATE = 'DeviceLocationSupport.hasCoords';
const STATUS_CANCELLED = 'STATUS_CANCELLED';
const STATUS_COMPLETED = 'STATUS_COMPLETED';

const MESSAGE_KEYS = [
  'DeviceLocationMissingMessage',
  'LocationRequiredMessage',
  'TooFarMessage',
  'TaskFinishedMessage'
];
const NUMBER_KEYS = ['MaxDistanceDefaultM', 'BoundaryInsideM', 'BoundaryOutsideM'];

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

function read(file) {
  if (!existsSync(file)) fail(`找不到文件：${file.replace(root, '.')}`);
  return readFileSync(file, 'utf8');
}

// ── 真值 A：doCheckInTask 的闸门顺序 ─────────────────────────────────────────
const serviceSrc = read(SERVICE_FILE);
// 用带参数类型的声明定位，避免命中调用点 `runWithTaskLock(taskId, () -> doCheckInTask(...))`
const methodStart = serviceSrc.indexOf('doCheckInTask(Long');
if (methodStart < 0) fail('ReplenishmentService 里找不到 doCheckInTask 声明，锚点可能已被重写');
const braceStart = serviceSrc.indexOf('{', methodStart);
const braceEnd = serviceSrc.indexOf('\n    }', braceStart);
if (braceStart < 0 || braceEnd < 0 || braceEnd <= braceStart) {
  fail('无法确定 doCheckInTask 方法体范围，锚点可能已被重写');
}
const methodBody = serviceSrc.slice(braceStart, braceEnd);

const iDeviceGate = methodBody.indexOf(KEY_DEVICE_MISSING);
const iRequestGate = methodBody.indexOf(KEY_LOCATION_REQUIRED);
const iStatusGate = methodBody.indexOf(KEY_TASK_FINISHED);
const hasRequireLocation = methodBody.includes(KEY_REQUIRE_LOCATION);

// ── 真值 B：ApiMessages 文案 ─────────────────────────────────────────────────
const messagesSrc = read(MESSAGES_FILE);
function javaLiteral(constName) {
  const m = messagesSrc.match(new RegExp(`${constName}\\s*=\\s*"([^"]*)"`));
  if (!m) fail(`ApiMessages 里找不到常量 ${constName}，锚点可能已被重写`);
  return m[1];
}
const javaMessages = {
  DeviceLocationMissingMessage: javaLiteral(KEY_DEVICE_MISSING),
  LocationRequiredMessage: javaLiteral(KEY_LOCATION_REQUIRED),
  TooFarMessage: javaLiteral(KEY_TOO_FAR),
  TaskFinishedMessage: javaLiteral(KEY_TASK_FINISHED)
};

// ── 真值 C：max_distance_m 的服务端默认值 ────────────────────────────────────
const configSrc = read(CONFIG_FILE);
const distMatch = configSrc.match(
  /upsertIfAbsent\(\s*REPLENISHMENT_CHECK_IN_MAX_DISTANCE_M\s*,\s*"(\d+)"/
);
if (!distMatch) {
  fail(
    'SystemConfigService 里找不到 REPLENISHMENT_CHECK_IN_MAX_DISTANCE_M 的 upsertIfAbsent 默认值'
  );
}
const javaMaxDistanceM = Number(distMatch[1]);

// ── 消费方：e2e-replenishment.ps1 的 $CheckInContract 真值块 ──────────────────
const e2eSrc = read(E2E_FILE);
const blockStart = e2eSrc.indexOf('$CheckInContract = @{');
if (blockStart < 0) {
  fail('e2e-replenishment.ps1 里找不到 $CheckInContract 真值块（契约已无人声明）');
}
const blockEnd = e2eSrc.indexOf('}', blockStart);
if (blockEnd < 0) fail('$CheckInContract 真值块没有闭合');
const blockText = e2eSrc.slice(blockStart, blockEnd);

const contract = new Map();
for (const m of blockText.matchAll(/^\s*([A-Za-z]+)\s*=\s*(?:"([^"]*)"|(\d+))\s*$/gm)) {
  contract.set(m[1], m[2] !== undefined ? m[2] : Number(m[3]));
}

const missingKeys = [...MESSAGE_KEYS, ...NUMBER_KEYS].filter((k) => !contract.has(k));
if (missingKeys.length) {
  fail(`$CheckInContract 缺少键：${missingKeys.join(', ')}`);
}

// ── 规则 1：文案必须是 Java 常量的子串 ──────────────────────────────────────
const problems = [];
for (const key of MESSAGE_KEYS) {
  const declared = String(contract.get(key));
  const literal = javaMessages[key];
  // 门槛不能超过文案本身：整条文案比 8 字还短时（如「补货任务已结束」＝7 字），
  // 最强断言就是「声明整条」。硬写 8 会逼人把断言削成碎片，反而更弱。
  const minLen = Math.min(8, literal.length);
  if (declared.length < minLen) {
    problems.push(
      `${key} 声明得太短（「${declared}」，${declared.length} 字，至少 ${minLen}）—— 碎片式断言等于没断言`
    );
  } else if (!literal.includes(declared)) {
    problems.push(
      `${key} 与服务端文案不一致：脚本声明「${declared}」，ApiMessages 实为「${literal}」`
    );
  }
}

// ── 规则 2：默认距离必须与服务端一致 ────────────────────────────────────────
const declaredMax = Number(contract.get('MaxDistanceDefaultM'));
if (declaredMax !== javaMaxDistanceM) {
  problems.push(
    `MaxDistanceDefaultM 与服务端不一致：脚本 ${declaredMax}，SystemConfigService 默认 ${javaMaxDistanceM}`
  );
}

// ── 规则 3：边界用例必须夹住默认值 ─────────────────────────────────────────
const inside = Number(contract.get('BoundaryInsideM'));
const outside = Number(contract.get('BoundaryOutsideM'));
if (!(inside > 0)) {
  problems.push(`BoundaryInsideM 必须为正数，实得 ${inside}`);
}
if (!(outside > inside)) {
  problems.push(`BoundaryOutsideM(${outside}) 必须大于 BoundaryInsideM(${inside})`);
}
if (inside > 0 && inside < declaredMax && declaredMax < outside) {
  // 唯一正确形状
} else {
  problems.push(
    `边界用例没有夹住默认值：需要 BoundaryInsideM(${inside}) < ` +
      `MaxDistanceDefaultM(${declaredMax}) < BoundaryOutsideM(${outside})`
  );
}

// ── 规则 4：真值块必须真被消费（否则门禁自身恒真） ──────────────────────────
// 设备侧闸要两条断言：① 空 body；② **带合法坐标**。第 ② 条才是「柜机缺坐标也照样拒签」的证据，
// 也正是旧逻辑（deviceHasCoords=false 直接放行）会漏掉的那条路径 —— 少一条就把覆盖打穿了。
const MIN_ASSERTIONS = {
  DeviceLocationMissingMessage: 2,
  LocationRequiredMessage: 1,
  TooFarMessage: 1
};
for (const [key, min] of Object.entries(MIN_ASSERTIONS)) {
  const re = new RegExp(`-ExpectMessageContains\\s+\\$CheckInContract\\.${key}\\b`, 'g');
  const count = [...e2eSrc.matchAll(re)].length;
  if (count < min) {
    problems.push(
      `$CheckInContract.${key} 只有 ${count} 处 -ExpectMessageContains 断言，期望至少 ${min} 处` +
        (key === 'DeviceLocationMissingMessage'
          ? '（空 body + 带合法坐标，后者是旧逻辑放行的那条路径）'
          : '')
    );
  }
}
for (const key of NUMBER_KEYS) {
  const used = new RegExp(`\\$CheckInContract\\.${key}\\b`).test(e2eSrc);
  if (!used) {
    problems.push(`$CheckInContract.${key} 声明了但从未被使用`);
  }
}

// ── 规则 5：脚本依赖的负向断言助手必须存在 ─────────────────────────────────
const libSrc = read(E2E_LIB_FILE);
if (!/function\s+Assert-E2eApiRejected/.test(libSrc)) {
  problems.push(
    'scripts/e2e-lib.ps1 里没有 Assert-E2eApiRejected —— e2e-replenishment.ps1 的负向用例会在运行时炸'
  );
}
// 终态拒签的**后半句**证据：被拒之后任务必须还在终态。
// 只断言「返回 409」是不够的 —— 「先改了状态再抛异常」同样能骗过状态码断言。
if (!/function\s+Assert-E2eTaskStillTerminal/.test(libSrc)) {
  problems.push(
    'scripts/e2e-lib.ps1 里没有 Assert-E2eTaskStillTerminal —— ' +
      '「终态没被复活」这条不变量就没有实跑证据'
  );
}

// ── 规则 6：终态闸必须在实跑脚本里被消费 ───────────────────────────────────
// 逐项列，不用「文案断言 ≥2 处」那种计数：实跑脚本用 for 循环覆盖两个终态时只有**一处**语法上的
// 断言，计数就会把正确写法判成缺失（我第一版就踩了这个）。改成对「覆盖了哪些事实」逐条断言。
const e2eContractSrc = read(E2E_CONTRACT_FILE);
const E2E_CONTRACT_REQUIREMENTS = [
  [
    /-ExpectMessageContains\s+\$CheckInContract\.TaskFinishedMessage/,
    '终态拒签的报文断言不见了 —— 「任务已结束」这条文案没人在实跑里比'
  ],
  [
    /'-ExpectStatus\s+409'|"-ExpectStatus\s+409"|-ExpectStatus\s+409/,
    '终态拒签必须是 409（与开门同一个状态码）'
  ],
  [/'COMPLETED'/, '实跑脚本没有覆盖 COMPLETED 终态'],
  [/'CANCELLED'/, '实跑脚本没有覆盖 CANCELLED 终态'],
  [
    // 判「缺终态任务时必须显式报失败」，判的是**结构**而不是某一句文案：
    // 这一条最初写成 /库里找不到任何/ —— 那是在判句子。2026-09-18 重写实跑脚本时句子换了
    //（语义没变：仍然「取不到任务 ⇒ 记一条 $false」），门禁却报了**假红**。
    // 现在要求的是：存在「任务缺失」判断，且该分支内记了一条失败结果。
    /if\s*\(\s*-not\s+\$\w+\s*\)\s*\{[\s\S]{0,600}?Add-Result\b[\s\S]{0,300}?\$false/,
    '缺终态任务时脚本没有显式报失败 —— 会静默跳过，「没验证」就会被读成「已验证」'
  ]
];
for (const [pattern, why] of E2E_CONTRACT_REQUIREMENTS) {
  if (!pattern.test(e2eContractSrc)) problems.push(why);
}
// ⚠️ fail-loud 那条判据的已知边界：它守的是「存在『缺任务⇒记失败』这个形状」，
// 守不住「这个形状必须出现在终态用例里」—— 有人把它挪到别的分支、同时在终态用例里静默 continue，
// 本门禁看不出来。那一类漂移只能靠实跑输出里有没有 `[FAIL] … 未覆盖` 人工确认。
// 只要求「有调用」，不数调用点数：覆盖 COMPLETED/CANCELLED 靠同一个 for 循环时，
// 语法上只有一处调用 —— 数点会把正确写法判成缺失（本门禁第一版两次踩到同一个坑）。
// 两个终态各自被覆盖，由上面的 'COMPLETED' / 'CANCELLED' 两条 + 下面的 fail-loud 守住。
// ⚠️ 已知边界：若有人把两个终态拆成两条独立分支、只在其中一条调这个助手，本门禁看不出来 ——
// 这类「结构变了但文本还在」的漂移要靠实跑输出人工确认，不要以为这里已经守住了。
const terminalChecks = [...e2eContractSrc.matchAll(/Assert-E2eTaskStillTerminal\b/g)].length;
if (terminalChecks < 1) {
  problems.push(
    'e2e-checkin-contract.ps1 没有调用 Assert-E2eTaskStillTerminal —— ' +
      '「终态没被复活」这条不变量只剩状态码断言，而「先改状态再抛异常」同样返回 409'
  );
}
// ── 规则 7：deviceHasCoords 必须真的送达（只断言 DTO 有字段＝只证明声明）────
// 判据是「有属性访问」（`.deviceHasCoords`），不是裸词 —— 裸词会被消息文案里的
// 「实得 deviceHasCoords=…」这类散文满足，那样的判据等于恒真。
if (!/\.deviceHasCoords\b/.test(e2eContractSrc)) {
  problems.push(
    'e2e-checkin-contract.ps1 没有读取响应里的 .deviceHasCoords —— ' +
      '「字段真的出现在响应里」就没有证据，客户端的前置拦可能建立在一个永远为 null 的字段上'
  );
}
const dtoSrc = read(DTO_FILE);
if (!/Boolean\s+deviceHasCoords\b/.test(dtoSrc)) {
  problems.push(
    'ReplenishmentTaskDto 里没有 Boolean deviceHasCoords —— 客户端拿不到「本柜没坐标」这个事实'
  );
}
const coordsSupportSrc = read(COORDS_SUPPORT_FILE);
if (!/static\s+boolean\s+hasCoords\s*\(/.test(coordsSupportSrc)) {
  problems.push(
    'DeviceLocationSupport 里没有 static boolean hasCoords(...)，坐标判据的单一出处没了'
  );
}
for (const [label, file] of [
  ['ReplenishmentService', SERVICE_FILE],
  ['MerchantInventoryPortalService', PORTAL_SERVICE_FILE]
]) {
  const src = read(file);
  const uses = [...src.matchAll(new RegExp(KEY_COORDS_PREDICATE.replace('.', '\\.'), 'g'))].length;
  if (uses < 1) {
    problems.push(`${label} 没有用 ${KEY_COORDS_PREDICATE}(...) —— 坐标判据又被各写一份`);
  }
  // 反向：把判据重新内联成裸 null 比较（正是本类缺陷的形态）
  const inlined = [
    ...src.matchAll(/getLatitude\(\)\s*!=\s*null\s*&&\s*\w+\.getLongitude\(\)\s*!=\s*null/g)
  ].length;
  if (inlined > 0) {
    problems.push(
      `${label} 里又出现了内联的 getLatitude() != null && …getLongitude() != null（${inlined} 处）——` +
        '坐标判据必须走 DeviceLocationSupport.hasCoords，否则两处会各自漂移'
    );
  }
}
// 签到与开门必须共用同一条终态文案：两处「一致性」容易一起写错，
// 所以这只是**必要条件**，有效性由 e2e 实跑（真返回 409）兜底。
const deviceValidationSrc = read(DEVICE_VALIDATION_FILE);
const doorUsesFinished = deviceValidationSrc.includes(KEY_TASK_FINISHED);
const checkInUsesFinished = methodBody.includes(KEY_TASK_FINISHED);
if (!doorUsesFinished) {
  problems.push(
    `DeviceValidationService（开门）里读不到 ${KEY_TASK_FINISHED} —— 终态判据被改成了另一条文案，` +
      '两个入口会对同一状态给出不同说法'
  );
}
if (!checkInUsesFinished) {
  problems.push(
    `doCheckInTask 里读不到 ${KEY_TASK_FINISHED} —— 签到又能复活终态任务了` +
      '（CANCELLED 会被改回 IN_PROGRESS，而柜机冻结判据正是 status=IN_PROGRESS 且 checkInAt≠null）'
  );
}
for (const status of [STATUS_CANCELLED, STATUS_COMPLETED]) {
  if (!methodBody.includes(status)) {
    problems.push(`doCheckInTask 的终态闸没有点名 ${status}`);
  }
}

// ── 真值 A 的判定（放在最后汇总，便于把所有问题一次报全） ──────────────────
if (iDeviceGate < 0) {
  problems.push(
    `doCheckInTask 里读不到 ${KEY_DEVICE_MISSING} —— 柜机漏填坐标会重新变成静默放行（fail-open）`
  );
}
if (iRequestGate < 0) {
  problems.push(`doCheckInTask 里读不到 ${KEY_LOCATION_REQUIRED} —— 请求侧定位闸被删了`);
}
if (!hasRequireLocation) {
  problems.push(`doCheckInTask 里读不到 ${KEY_REQUIRE_LOCATION} 开关，闸门可能已失效`);
}
if (iDeviceGate >= 0 && iRequestGate >= 0 && iDeviceGate > iRequestGate) {
  problems.push(
    '闸门顺序反了：柜机无坐标闸必须**先于**请求无坐标闸，' +
      '否则柜机缺坐标时用户会收到「请开启定位」这种指向错误的提示'
  );
}
if (iStatusGate >= 0 && iDeviceGate >= 0 && iStatusGate > iDeviceGate) {
  problems.push(
    '终态闸被挪到了坐标闸之后：已取消/已完成的任务会先收到「去补录柜机坐标」这种' +
      '**做了也没用**的指引（补了坐标也仍然签不了），而不是「补货任务已结束」'
  );
}

if (problems.length) {
  fail(`\n  ${problems.join('\n  ')}\n`);
}

console.log(
  `${TAG} OK：doCheckInTask 闸门顺序（终态 → 无坐标 → 缺定位）、${MESSAGE_KEYS.length} 条文案、` +
    `默认距离 ${declaredMax}m、边界 ${inside}m/${outside}m 与 e2e-replenishment.ps1 的期望值全部一致；` +
    `终态闸与开门共用同一条文案、坐标判据唯一（${KEY_COORDS_PREDICATE}）、` +
    `deviceHasCoords 已声明且被实跑脚本消费`
);
