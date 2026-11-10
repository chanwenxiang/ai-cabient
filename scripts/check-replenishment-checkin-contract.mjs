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
 *   8. 补货任务的**展示面/待办取数必须下推 SQL**（`findRestockingNowTasks` / `findWorkbenchTasks` /
 *      `findDeviceIdsByStatusAndDeviceIn`），不得回到「取上限窗口
 *      （`findByStatusInOrderByCreatedAtAsc(…, 500)`）再内存过滤」——见下方 B13 的成因。
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
const TASK_MAPPER_FILE = join(TRADE_MAIN, 'mapper', 'ReplenishmentTaskMapper.java');
const PORTAL_DEVICE_FILE = join(TRADE_MAIN, 'service', 'MerchantDevicePortalService.java');
const OPS_DEVICE_FILE = join(TRADE_MAIN, 'service', 'OpsDeviceAdminService.java');
const MERCHANT_WORKBENCH_FILE = join(TRADE_MAIN, 'service', 'MerchantWorkbenchQueryService.java');
const OPS_WORKBENCH_FILE = join(TRADE_MAIN, 'service', 'OpsWorkbenchQueryService.java');
const PUSHED_DOWN_QUERY = 'findRestockingNowTasks';
const RESTOCKING_DEVICE_IDS_QUERY = 'findRestockingNowDeviceIds';
const WINDOWED_QUERY = 'findByStatusInOrderByCreatedAtAsc';
// 规则 9（备货单「改量重提」判据的跨端契约）：后端字段 → 前端判据，少任一侧都会静默退化
const PREP_PREVIEW_DTO_FILE = join(
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
  'MerchantPrepPreviewGroupDto.java'
);
const PREP_SERVICE_FILE = join(TRADE_MAIN, 'service', 'MerchantReplenishmentService.java');
// R4-B-新4（=R3-B-R3-7）：改量重提判据有**两个消费页**——手动生成页与备货单页，
// 只锚 prep-generate 一页时，prep-list 的调用点被删/不传 lastTaskStatus 会静默假绿。
const PREP_PAGE_FILES = [
  join(root, 'clients', 'merchant-mp', 'src', 'pages', 'prep-generate', 'prep-generate.vue'),
  join(root, 'clients', 'merchant-mp', 'src', 'pages', 'prep-list', 'prep-list.vue')
];
const PREP_SUBMIT_UTIL_FILE = join(
  root,
  'clients',
  'merchant-mp',
  'src',
  'utils',
  'self-replenish-submit.ts'
);

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

/**
 * 把注释替换成**等长空格**（保留换行，行号不变）：规则 8 扫的是「代码里还有没有旧写法」，
 * 而修复处的说明注释恰恰会引用 `findByStatusInOrderByCreatedAtAsc(…, 500)` 作为反例
 * （铁律 84①：不抹注释就会把注释判成违规，把人逼成删注释）。
 * 反向同样必要（E-R3-2）：「删真实调用、留注释示例」不能判绿 —— 锚点检查前必须先剥注释。
 * 字符串/字符/模板字符串（`" `'`）感知：串里的 `//`（如 URL）不当注释起点，避免误吞真代码。
 * html=true 时额外剥 `<!-- -->`（.vue 模板注释）；Java 走 html=false。
 */
function maskCommentsLike(source, { html = false } = {}) {
  let out = '';
  let state = 'code';
  for (let i = 0; i < source.length; i++) {
    const c = source[i];
    const n = source[i + 1];
    if (state === 'code') {
      if (c === '/' && n === '/') {
        out += '  ';
        i++;
        state = 'line';
      } else if (c === '/' && n === '*') {
        out += '  ';
        i++;
        state = 'block';
      } else if (html && c === '<' && source.startsWith('<!--', i)) {
        out += '    ';
        i += 3;
        state = 'html';
      } else if (c === '"' || c === "'" || c === '`') {
        out += c;
        state = c; // 记住是哪种引号，遇到配对引号才出串
      } else {
        out += c;
      }
      continue;
    }
    if (state === 'line') {
      out += c === '\n' ? '\n' : ' ';
      if (c === '\n') state = 'code';
      continue;
    }
    if (state === 'block') {
      if (c === '*' && n === '/') {
        out += '  ';
        i++;
        state = 'code';
      } else {
        out += c === '\n' ? '\n' : ' ';
      }
      continue;
    }
    if (state === 'html') {
      if (c === '-' && source.startsWith('-->', i)) {
        out += '   ';
        i += 2;
        state = 'code';
      } else {
        out += c === '\n' ? '\n' : ' ';
      }
      continue;
    }
    // 字符串态
    if (c === '\\' && i + 1 < source.length) {
      out += c + source[++i];
      continue;
    }
    out += c;
    if (c === state) state = 'code';
  }
  return out;
}

export function maskJavaComments(source) {
  return maskCommentsLike(source);
}

/** 前端（.vue / .ts）注释剥离：`//`、块注释、`<!-- -->` 均替换为等长空格（保行号）。 */
function maskFrontendComments(source) {
  return maskCommentsLike(source, { html: true });
}

/**
 * 从**已剥注释**的源码里切出名为 `name` 的方法/函数体切片（brace 配平）：
 * 定位 `name(` 的声明（其后参数括号配平、紧跟 `{` 的那处 —— 排除普通调用点），
 * 再从 `{` 起做大括号配平，返回每个方法体的 [start, end)（含首尾大括号）。
 * 重载（如 prepPreview 两个）会各返回一片；判据要求「至少一片」还是「每一片」由调用方定。
 */
function extractBracedBodies(masked, name) {
  const bodies = [];
  const needle = `${name}(`;
  let at = masked.indexOf(needle);
  while (at >= 0) {
    // 参数括号配平，找声明体开括号
    let depth = 0;
    let j = at + needle.length - 1;
    for (; j < masked.length; j++) {
      if (masked[j] === '(') depth++;
      else if (masked[j] === ')') {
        depth--;
        if (depth === 0) break;
      }
    }
    let k = j + 1;
    while (k < masked.length && /\s/.test(masked[k])) k++;
    // TS 形态 `): Promise<X> {`：`)` 与 `{` 之间可隔一段返回类型注解 —— 跳过它找 `{`；
    // 途中先遇 `;`（无体声明/调用）则放弃，避免把下一个函数误认成本体。
    while (k < masked.length && masked[k] !== '{' && masked[k] !== ';' && masked[k] !== '}') k++;
    if (masked[k] === '{') {
      // R9 化妆1 尝试过「配平前剥字符串」，但 unmatched 引号会吞掉余量 ⇒ 已回滚；
      // 现有锚体内无字符串花括号（实跑绿），如未来锚体引入再以状态机重做并配注入用例。
      let bd = 0;
      let e = k;
      for (; e < masked.length; e++) {
        if (masked[e] === '{') bd++;
        else if (masked[e] === '}') {
          bd--;
          if (bd === 0) break;
        }
      }
      if (bd === 0 && e < masked.length) bodies.push(masked.slice(k, e + 1));
    }
    at = masked.indexOf(needle, at + needle.length);
  }
  return bodies;
}

// ── 真值 A：doCheckInTask 的闸门顺序 ─────────────────────────────────────────
const serviceSrc = read(SERVICE_FILE);
// R5-E1：真值 A 改用剥注释 + 大括号配平切片（extractBracedBodies）——旧实现裸切+含注释判序，
// 「删真闸、留含关键字注释」可保绿（E-R3-2 同类），且 `\n    }` 锚与缩进强耦合。
const maskedService = maskJavaComments(serviceSrc);
const checkinBodies = extractBracedBodies(maskedService, 'doCheckInTask');
if (!checkinBodies.length) {
  fail('ReplenishmentService 里找不到 doCheckInTask 方法体，锚点可能已被重写');
}
const methodBody = checkinBodies[0];

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

// ── 规则 8：展示面「补货中」判据必须下推 SQL（B13，2026-11-10）───────────────
// 缺陷形态：`findByStatusInOrderByCreatedAtAsc(List.of("IN_PROGRESS"), 500).stream().filter(isRestockingNow)`。
// 出库明细生成即置 IN_PROGRESS 但 check_in_at 为空；这类行只有「签到 4h 超时收口」会处理，
// 而收口只扫**已签到**的行 ⇒ 它们不会自愈，只会越积越多。攒够 500 条后，最早 500 条全是脏行，
// 新签到的任务被 LIMIT 挤出窗口 ⇒ 手机上「补货中」永远不亮，而挡开门的判据是逐柜查的、结论相反。
// 修法：把 status/check_in_at 下推进 SQL 且不设上限（签到的在途任务是个位数量级）。
const mapperSrc = read(TASK_MAPPER_FILE);
const maskedMapper = maskJavaComments(mapperSrc);
// 两个方法都要守：`findRestockingNowTasks` 供展示面「补货中」，`findRestockingNowDeviceIds` 供「占柜」统计。
// 占柜口径必须与 isRestockingNow 同源（只算已签到的 IN_PROGRESS），否则运营看到的占用数虚高。
for (const name of [PUSHED_DOWN_QUERY, RESTOCKING_DEVICE_IDS_QUERY]) {
  const at = maskedMapper.indexOf(`${name}(`);
  if (at < 0) {
    problems.push(
      `ReplenishmentTaskMapper 里找不到 ${name}() —— 「谁在补货」的判据又被搬回内存过滤，` +
        '未签到的 IN_PROGRESS 脏行攒够上限后会把真任务挤出窗口'
    );
    continue;
  }
  const open = maskedMapper.indexOf('{', at);
  const close = maskedMapper.indexOf('\n    }', open);
  const body = maskedMapper.slice(open, close < 0 ? maskedMapper.length : close);
  // 判据必须钉在**谓词**上，不能只找 `getCheckInAt`：`select(… getCheckInAt)` 里也有这个词，
  // 光找词会让「把 isNotNull 换成别的列」这种注入假绿（2026-11-10 负向验证当场抓到）。
  if (!/isNotNull\s*\(\s*ReplenishmentTask::getCheckInAt\s*\)/.test(body)) {
    problems.push(
      `${name}() 没把 check_in_at 非空下推进 SQL —— 只筛 status 的话，` +
        '没签到的行也会被当成「正在补货/正在占柜」，等价于没修'
    );
  }
  if (!/eq\s*\(\s*ReplenishmentTask::getStatus\s*,\s*"IN_PROGRESS"\s*\)/.test(body)) {
    problems.push(`${name}() 没把 status=IN_PROGRESS 下推进 SQL`);
  }
  if (/\.last\s*\(\s*"LIMIT/.test(body)) {
    problems.push(
      `${name}() 又加了 LIMIT 上限 —— 上限与「谁在补货」无关，` +
        '历史脏行攒够就该截断真任务（这正是 B13 的形态）'
    );
  }
}
for (const [label, file, requiredList] of [
  ['MerchantDevicePortalService', PORTAL_DEVICE_FILE, [PUSHED_DOWN_QUERY]],
  ['OpsDeviceAdminService', OPS_DEVICE_FILE, [PUSHED_DOWN_QUERY]],
  ['MerchantWorkbenchQueryService', MERCHANT_WORKBENCH_FILE, ['findWorkbenchTasks']],
  // 运营工作台两个入口：待办走 findWorkbenchTasks，「占柜」必须与 isRestockingNow 同源（只算已签到）
  [
    'OpsWorkbenchQueryService',
    OPS_WORKBENCH_FILE,
    ['findWorkbenchTasks', RESTOCKING_DEVICE_IDS_QUERY]
  ]
]) {
  const src = maskJavaComments(read(file));
  for (const required of requiredList) {
    if (!src.includes(`${required}(`)) {
      problems.push(
        `${label} 的补货任务取数没走 ${required}() —— 判据必须下推且与 isRestockingNow 同源（B13），` +
          '上限窗口 + 内存过滤会让域受限账号看不到自己的任务，只按状态统计会让「占柜」虚高'
      );
    }
  }
  if (src.includes(`${WINDOWED_QUERY}(`)) {
    problems.push(
      `${label} 又用 ${WINDOWED_QUERY}(…) 取上限窗口后内存过滤 —— ` +
        '该方法已下线：窗口是按 createdAt 全局取的，超过窗口的新任务/域内任务会被挤掉（B13）'
    );
  }
}

// ── 规则 9：备货单「改量重提」判据的跨端契约（R2-B1 残留，2026-11-10）──────────────
// 缺陷形态：前端记着「上次成功提交的明细指纹」，指纹变了就拦下并让用户去任务页改量
//（后端按 clientRequestNo 回放**不核对明细**，直接重提会静默回放旧数量）。
// 但那张任务被取消/已完成后再拦，就是把用户锁死：提示去改量，而任务页里没有可改的任务。
// 修法 = 预览接口带上「本柜最近一张任务的状态」，前端按「任务还在不在进行中」分流。
//
// 这是一条**跨端契约**，四个落点少任一侧都会静默退化：
//   DTO 少字段        ⇒ 前端永远拿到 null ⇒ 退化成「无条件拦」（缺陷原形）
//   服务不查/不传     ⇒ 同上
//   前端不传参        ⇒ 拿到字段也没用，判据恒按「还在进行中」
//   终态集合被清空    ⇒ 见上（负向验证就是清空它，用例立刻红）
// 之所以要静态门禁而不是只靠用例：用例只钉纯函数，删掉 DTO 字段/服务取数/页面传参
// 三处中的任一处，纯函数用例照样全绿（2026-11-10 实测确认过这个盲区）。
const prepDtoSrc = maskJavaComments(read(PREP_PREVIEW_DTO_FILE));
if (!/\bString\s+lastTaskStatus\b/.test(prepDtoSrc)) {
  problems.push(
    'MerchantPrepPreviewGroupDto 里没有 `String lastTaskStatus` 分量 —— 前端拿不到本柜最近任务状态，' +
      '「改量重提」会退化成无条件拦（任务已取消也提示去改量，用户被锁死）'
  );
}
// E-R3-1：两条服务端判据钉进 **prepPreview 方法体切片**（brace 配平），不再全文件 substring ——
// 全文件判法下「查询移出 prepPreview、字面量留在同文件他处」照样绿。重载有两片，
// 要求**至少一片**同时含「查」与「回填」（委托重载是纯转发，不应被误伤）。
const prepServiceSrc = maskJavaComments(read(PREP_SERVICE_FILE));
const prepPreviewBodies = extractBracedBodies(prepServiceSrc, 'prepPreview');
if (prepPreviewBodies.length === 0) {
  problems.push(
    'MerchantReplenishmentService 里切不出 prepPreview 方法体（签名/结构漂移？）—— ' +
      '「查最近任务状态」判据失去锚点'
  );
} else if (
  !prepPreviewBodies.some(
    (body) =>
      body.includes('findLatestStatusByDeviceIds(') &&
      body.includes('latestTaskStatus.get(deviceId)')
  )
) {
  const queryElsewhere = prepServiceSrc.includes('findLatestStatusByDeviceIds(');
  problems.push(
    'MerchantReplenishmentService.prepPreview 方法体内没有「findLatestStatusByDeviceIds(...) 查询 + ' +
      'latestTaskStatus.get(deviceId) 回填」这对判据' +
      (queryElsewhere
        ? '（字面量在同文件他处出现 —— 查询被挪出 prepPreview，DTO 字段会恒为 null）'
        : '') +
      ' —— DTO 字段会恒为 null（同上）'
  );
}
// E-R3-2：前端锚点检查前先剥注释（等长空格保行号）——「删真实调用、留注释示例」不能判绿。
// R4-B-新4：两个消费页（prep-generate / prep-list）各自都要「调用包装函数且 400 字符窗口含
// lastTaskStatus」，只锚一页时另一页的调用点被删会静默假绿。
for (const PREP_PAGE_FILE of PREP_PAGE_FILES) {
  const pageName = PREP_PAGE_FILE.replaceAll('\\', '/').split('/').slice(-2).join('/');
  const prepPageSrc = maskFrontendComments(read(PREP_PAGE_FILE));
  // 2026-11-10：改量重提判据重度重构（shouldBlockPrepResubmit 移入 self-replenish-submit.ts，
  // 页面改调包装 decidePrepResubmitWithRefresh(...) 并传 lastTaskStatus，再由它内部调
  // shouldBlockPrepResubmit + 实时复查纠正竞态）。门禁锚点对新调用点，并保留两条不变量：
  //   ① 页面调用包装函数且传 lastTaskStatus（否则按「进行中」恒拦，R2-B1 回归）；
  //   ② util 里 shouldBlockPrepResubmit 仍有**真实调用**（P0-3 旧键回放判据不被内联/删除/空心化）。
  const prepVerdictAt = prepPageSrc.indexOf('decidePrepResubmitWithRefresh(');
  if (prepVerdictAt < 0) {
    problems.push(
      `${pageName} 不再调用 decidePrepResubmitWithRefresh(...) —— 改量重提判据被内联/删除，` +
        'P0-3（旧键静默回放旧数量）与 R2-B1（任务已取消仍拦）两条都会回归'
    );
  } else {
    const verdictCall = prepPageSrc.slice(prepVerdictAt, prepVerdictAt + 400);
    if (!verdictCall.includes('lastTaskStatus')) {
      problems.push(
        `${pageName} 调用 decidePrepResubmitWithRefresh 时没传 lastTaskStatus —— ` +
          '判据会恒按「任务还在进行中」处理，任务已取消的用户仍被锁死'
      );
    }
  }
}
const prepUtilSrc = maskFrontendComments(read(PREP_SUBMIT_UTIL_FILE));
// E-R3-2：不能只查「shouldBlockPrepResubmit( 出现过」——**函数定义自身即满足**，
// 包装函数停止调它（空心化）门禁不红。改为：定义仍在 + decidePrepResubmitWithRefresh
// 函数体切片内必须有真实调用（排除定义行：定义在包装函数体外，切片内的必是调用）。
if (!/function\s+shouldBlockPrepResubmit\s*\(/.test(prepUtilSrc)) {
  problems.push(
    'self-replenish-submit.ts 里找不到 shouldBlockPrepResubmit(...) 定义 —— ' +
      'P0-3 旧键回放判据被删，改量会静默回放旧数量'
  );
}
const decideBodies = extractBracedBodies(prepUtilSrc, 'decidePrepResubmitWithRefresh');
if (decideBodies.length === 0) {
  problems.push(
    'self-replenish-submit.ts 里切不出 decidePrepResubmitWithRefresh 函数体（签名/结构漂移？）—— ' +
      '「包装函数不得空心化」判据失去锚点'
  );
} else if (!decideBodies.some((body) => body.includes('shouldBlockPrepResubmit('))) {
  problems.push(
    'self-replenish-submit.ts 的 decidePrepResubmitWithRefresh 函数体内没有调用 ' +
      'shouldBlockPrepResubmit(...) —— 包装函数已空心化（定义还在但不再消费，判据形同虚设）'
  );
}
const shouldBlockBodies = extractBracedBodies(prepUtilSrc, 'shouldBlockPrepResubmit');
if (!shouldBlockBodies.some((b) => b.includes('PREP_TASK_ENDED_STATUSES'))) {
  problems.push(
    'shouldBlockPrepResubmit 函数体不再消费 PREP_TASK_ENDED_STATUSES —— 终态分流被判据旁路（R5-A8②）'
  );
}
if (!/PREP_TASK_ENDED_STATUSES\s*=\s*\[[^\]]*'CANCELLED'/.test(prepUtilSrc)) {
  problems.push(
    'self-replenish-submit.ts 的 PREP_TASK_ENDED_STATUSES 不再包含 CANCELLED —— ' +
      '「任务已取消 ⇒ 作废旧键重提」这条分支失效（B1 后端已对此类回放返 409）'
  );
}

if (problems.length) {
  fail(`\n  ${problems.join('\n  ')}\n`);
}

console.log(
  `${TAG} OK：doCheckInTask 闸门顺序（终态 → 无坐标 → 缺定位）、${MESSAGE_KEYS.length} 条文案、` +
    `默认距离 ${declaredMax}m、边界 ${inside}m/${outside}m 与 e2e-replenishment.ps1 的期望值全部一致；` +
    `终态闸与开门共用同一条文案、坐标判据唯一（${KEY_COORDS_PREDICATE}）、` +
    `deviceHasCoords 已声明且被实跑脚本消费；` +
    `补货任务取数全部下推（${PUSHED_DOWN_QUERY} / findWorkbenchTasks，无上限窗口）；` +
    `备货预览在 prepPreview 方法体内查+回填 lastTaskStatus，两页（prep-generate/prep-list）均消费判据且包装函数未空心化`
);
