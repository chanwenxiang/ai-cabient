#!/usr/bin/env node
/**
 * 门禁：仓储流水/交接状态的**码必须在字典里**（D28-B11 / D28-B12，2026-10-29 三端口径复审）。
 *
 * 为什么要有它：`warehouse_movement.movement_type / ref_type`、`warehouse_outbound(_line).handover_status`、
 * `warehouse_in_transit.status` 的取值全部是 Java 字面量/常量，而三端的标签一律走
 * `dictLabel(type, code)`；shared-dict-core 对**未登记的 SCREAMING_SNAKE 码兜底成「未知」**
 * （`packages/shared-dict/src/index.ts:825` 起）。⇒ 后端新写一个码、字典没跟上，
 * 表现是「库存流水页整列变『未知』」，而单测与编译全绿。本轮实测共享库里就有
 * 6 行 `OUTBOUND_CANCEL` 显示成「未知」。
 *
 * 对账两侧：
 *   ① 代码写入集：静态抽取仓储写入者的实参（规则表 CALL_ARGS + 逐文件 setter，见下，
 *      每条形如「哪个构造方法/方法、第几个实参写哪个字典列」）；
 *   ② 字典：`packages/shared-dict` 的 `DICT`（内置 baseline）+ `SysDictBootstrap.seedCatalog()`
 *      （新库补种；已存在的库由 `ensureMissingDictItems` 在启动时补 **缺的值** ⇒ 新增值不需要迁移）。
 *
 * 判据：
 *   R1 写入集 ⊆ 字典（四个 dict 类型各自的列各一条）；
 *   R2 ①②两侧对这四个类型的 **值→标签** 必须逐字相等（#355：改标签要三处同改，
 *      少一处就出现「DB 覆盖内置」或「内置覆盖 DB」的漂移）；
 *   R3 抽取到**没登记的动态实参**即红 —— 动态站点必须人来登记取值集合，不许沉默漏掉。
 *
 * 🔴 刻意不在范围内：柜机侧 `inventory_movement`（`InventoryLotService` 写的 SALE/ADJ/PULL_OFF…）
 *   是另一张表、没有对应字典类型、也不由 `warehouse_movement_type` 渲染，纳入会假红。
 *   `wallet/平台资金` 的 `setRefType` 同理（不同表不同列）⇒ setter 规则按**文件**白名单，不全局扫。
 *
 * 只读源码文本，离线可跑。负向验证：`node scripts/devops/verify-warehouse-movement-dict-gate.mjs`
 *
 *   node scripts/check-warehouse-movement-dict.mjs
 */
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-warehouse-movement-dict]';
const P = (rel) => join(ROOT, ...rel.split('/'));

const TRADE = 'services/trade-service/src/main/java/com/aicabinet/trade';
const FILES = {
  dict: 'packages/shared-dict/src/index.ts',
  bootstrap: `${TRADE}/service/SysDictBootstrap.java`,
  warehouseService: `${TRADE}/service/WarehouseService.java`,
  binService: `${TRADE}/service/WarehouseBinService.java`,
  transferService: `${TRADE}/service/WarehouseTransferService.java`,
  inventoryOps: `${TRADE}/service/InventoryOpsService.java`,
  procurement: `${TRADE}/service/ProcurementService.java`,
  demoData: `${TRADE}/service/DemoDataService.java`,
  inTransit: `${TRADE}/service/InTransitService.java`
};

const MOVEMENT_TYPE = 'warehouse_movement_type';
const REF_TYPE = 'business_reference_type';
const HANDOVER = 'handover_status';
const IN_TRANSIT = 'in_transit_status';

/**
 * 构造方法/方法实参规则：`marker` 是**带上下文的完整针**（`new 全限定名(` 或 `.方法名(`），
 * 这样记录/方法**声明行**不会被当成调用点抽（声明行的实参是 `String refType` 这种类型声明，
 * 抽了会假红）。`argIndex` 是顶层逗号切分后的下标（跳过嵌套括号与字符串）。
 * 每条都注明写进哪张表的哪一列、对应哪个字典类型 —— 改这些签名要同步改这里。
 */
const CALL_ARGS = [
  {
    marker: 'new MovementSpec(',
    argIndex: 0,
    dict: MOVEMENT_TYPE,
    why: 'warehouse_movement.movement_type（WarehouseService 内所有仓库流水的唯一出口）'
  },
  {
    marker: 'new MovementSpec(',
    argIndex: 2,
    dict: REF_TYPE,
    why: 'warehouse_movement.ref_type（同上，第 3 个实参）'
  },
  {
    // movement_type 恒为 BIN_STOCK（由上一条 MovementSpec 收到），这里只收 ref_type。
    marker: 'new WarehouseService.BinStockChangeCommand(',
    argIndex: 4,
    dict: REF_TYPE,
    why: 'warehouse_movement.ref_type：货位增减/报损/调拨进出共用 BIN_STOCK，只看 ref_type 分得清'
  },
  {
    marker: 'new WarehouseService.PurchaseReceiveCommand(',
    argIndex: 5,
    dict: REF_TYPE,
    why: 'warehouse_movement.ref_type：采购收货落哪个业务单'
  },
  {
    marker: '.returnPurchaseStock(',
    argIndex: 5,
    dict: REF_TYPE,
    why: 'warehouse_movement.ref_type：采购退货落哪个业务单'
  }
];

/** setter 规则：按文件白名单（同一方法名在别的类里写的是别的表/别的列）。 */
const SETTER_ARGS = [
  { file: FILES.warehouseService, name: 'setMovementType', dict: MOVEMENT_TYPE },
  { file: FILES.warehouseService, name: 'setRefType', dict: REF_TYPE },
  { file: FILES.demoData, name: 'setMovementType', dict: MOVEMENT_TYPE },
  { file: FILES.demoData, name: 'setRefType', dict: REF_TYPE },
  { file: FILES.warehouseService, name: 'setHandoverStatus', dict: HANDOVER },
  // 🔴 只扫 InTransitService：WarehouseService 里的 setStatus 写的是 warehouse_outbound_status
  //（另一个字典），混进来会把两张表的状态集揉成一锅。
  { file: FILES.inTransit, name: 'setStatus', dict: IN_TRANSIT }
];

/**
 * 已登记的动态实参：值不是字面量、机器求不出来，但**来源在本门禁覆盖范围内**。
 * key = 文件 basename，value = 允许的实参文本。新增未登记的动态实参 ⇒ 红。
 */
const DYNAMIC_OK = {
  'WarehouseService.java': [
    // 流水落库的唯一 sink：entity ← MovementSpec 记录，取值已由 CALL_ARGS 第 0/2 参收齐。
    'movement.movementType()',
    'movement.refType()',
    // BIN_STOCK 的 ref_type 是调用方传的，取值由 BinStockChangeCommand 各调用点收齐。
    'command.refType()',
    // receivePurchaseStock/returnPurchaseStock 里的局部变量（= command.refType() / 形参），
    // 取值由 PurchaseReceiveCommand 与 .returnPurchaseStock( 两条规则收齐。
    'refType'
  ]
};

const errors = [];
const fail = (msg) => errors.push(msg);

function stripComments(src) {
  return src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/.*$/gm, '$1');
}

/** 从 idx（指向开括号）起取平衡括号内的实参文本（不含外层括号）；没配平返回 null。 */
function parenBody(src, idx) {
  let depth = 0;
  let out = '';
  let inStr = false;
  for (let i = idx; i < src.length; i++) {
    const c = src[i];
    if (inStr) {
      out += c;
      if (c === '"' && src[i - 1] !== '\\') inStr = false;
      continue;
    }
    if (c === '"') {
      inStr = true;
      out += c;
      continue;
    }
    if (c === '(') {
      depth++;
      if (depth > 1) out += c;
      continue;
    }
    if (c === ')') {
      depth--;
      if (depth === 0) return out;
      out += c;
      continue;
    }
    if (depth > 0) out += c;
  }
  return null;
}

/** 顶层逗号切分（忽略嵌套括号/字符串里的逗号）。 */
function splitArgs(body) {
  const parts = [];
  let cur = '';
  let depth = 0;
  let inStr = false;
  for (let i = 0; i < body.length; i++) {
    const c = body[i];
    if (inStr) {
      cur += c;
      if (c === '"' && body[i - 1] !== '\\') inStr = false;
      continue;
    }
    if (c === '"') {
      inStr = true;
      cur += c;
      continue;
    }
    if (c === '(') depth++;
    else if (c === ')') depth--;
    if (c === ',' && depth === 0) {
      parts.push(cur.trim());
      cur = '';
      continue;
    }
    cur += c;
  }
  parts.push(cur.trim());
  return parts;
}

function load(rel) {
  const abs = P(rel);
  if (!existsSync(abs)) fail(`找不到文件：${rel}`);
  return stripComments(readFileSync(abs, 'utf8'));
}

/** 文件内的 `String NAME = "value"`（含 static final / 局部 final），用于解析标识符实参。 */
function constMap(src) {
  const map = new Map();
  for (const m of src.matchAll(/\bString\s+([A-Z][A-Z0-9_]*)\s*=\s*"([^"]+)"/g)) {
    if (!map.has(m[1])) map.set(m[1], m[2]);
  }
  return map;
}

/** 一个实参里可能出现的候选码：字符串字面量 + 可解析的大写常量；三目/多分支一次全收。 */
function codesInArg(arg, localConsts, globalConsts) {
  const codes = [];
  const trimmed = arg.trim();
  if (!trimmed) return { codes, dynamic: null };
  for (const m of trimmed.matchAll(/"([^"]+)"|\b([A-Z][A-Z0-9_]{2,})\b/g)) {
    if (m[1] !== undefined) {
      codes.push(m[1]);
      continue;
    }
    const v = localConsts.get(m[2]) ?? globalConsts.get(m[2]);
    if (v) codes.push(v);
    // 全大写却没查到常量：多半是类名/缩写，不当码；真正的漏网由「整个实参一个都没解析出来」兜住。
  }
  return { codes, dynamic: codes.length === 0 ? trimmed : null };
}

// ── ① 代码写入集 ──────────────────────────────────────────────────────────────
const written = {
  [MOVEMENT_TYPE]: new Map(),
  [REF_TYPE]: new Map(),
  [HANDOVER]: new Map(),
  [IN_TRANSIT]: new Map()
};
const dynamicSites = [];
const loaded = new Map();
for (const rel of [
  FILES.warehouseService,
  FILES.binService,
  FILES.transferService,
  FILES.inventoryOps,
  FILES.procurement,
  FILES.demoData,
  FILES.inTransit,
  FILES.bootstrap
]) {
  loaded.set(rel, load(rel));
}

// 常量表：先按本文件找，找不到再到全部在范围内的文件里找（跨文件引用的 static final）。
const localConstMaps = new Map();
for (const [rel, src] of loaded) localConstMaps.set(rel, constMap(src));
const globalConsts = new Map();
for (const m of localConstMaps.values())
  for (const [k, v] of m) if (!globalConsts.has(k)) globalConsts.set(k, v);

function record(dict, code, where) {
  const set = written[dict];
  if (!set.has(code)) set.set(code, new Set());
  set.get(code).add(where);
}

function consumeArgs(rule, src, rel) {
  const base = rel.split('/').pop();
  const local = localConstMaps.get(rel) ?? new Map();
  let cursor = 0;
  while (true) {
    const at = src.indexOf(rule.marker, cursor);
    if (at < 0) return;
    cursor = at + rule.marker.length;
    const body = parenBody(src, at + rule.marker.length - 1);
    if (body === null) {
      fail(`${base}：${rule.marker} 括号没配平，抽取器读不动（写法变了，请同步本门禁）`);
      continue;
    }
    const parts = splitArgs(body);
    if (parts.length <= rule.argIndex) {
      fail(
        `${base}：${rule.marker} 只有 ${parts.length} 个实参，取不到第 ${rule.argIndex + 1} 个` +
          `（签名变了，请同步本门禁的 CALL_ARGS/SETTER_ARGS）`
      );
      continue;
    }
    const { codes, dynamic } = codesInArg(parts[rule.argIndex], local, globalConsts);
    for (const c of codes) record(rule.dict, c, `${base} ${rule.marker}`);
    if (dynamic) {
      const allowed = (DYNAMIC_OK[base] ?? []).includes(dynamic);
      if (!allowed) {
        dynamicSites.push(`${base}: ${rule.marker}${dynamic.slice(0, 90)}（写进 ${rule.dict}）`);
      }
    }
  }
}

for (const rel of loaded.keys()) {
  const src = loaded.get(rel);
  for (const rule of CALL_ARGS) consumeArgs(rule, src, rel);
  for (const rule of SETTER_ARGS) {
    if (rule.file !== rel) continue;
    consumeArgs({ marker: `.${rule.name}(`, argIndex: 0, dict: rule.dict }, src, rel);
  }
}

// ── ② 字典：shared-dict 内置 baseline ────────────────────────────────────────
/**
 * 只取需要的**那一个**字典块，按 `码: '标签'` 逐行解析。
 * 🔴 不用 `Function('return {...}')()` 整块求值：`DICT` 里有 TS 断言
 *   （`category_code: {} as Record<string, string>`，:794）——eval 必炸，
 *   而 `scripts/gen-dict-seed.js` 正是这么写的（所以它现在也是坏的，别照抄）。
 */
function dictBlockFromShared(rel, typeName) {
  const src = stripComments(readFileSync(P(rel), 'utf8'));
  const start = src.indexOf(`\n  ${typeName}: {`);
  if (start < 0) {
    fail(`${rel} 里找不到字典类型 ${typeName} 的块（缩进/写法变了，请同步本门禁）`);
    return {};
  }
  const open = src.indexOf('{', start);
  const body = parenBodyBraced(src, open);
  if (body === null) {
    fail(`${rel}：${typeName} 的花括号没配平`);
    return {};
  }
  if (body.includes('{')) {
    fail(`${rel}：${typeName} 里出现嵌套对象，本门禁的扁平解析读不动`);
    return {};
  }
  const out = {};
  // 🔴 不按行锚定：字典块允许一行写多个 `码: '标签'`（HEAD 的 in_transit_status 就是一行四个），
  //   行锚定会静默只读到第一个，把「两侧一致」比对变成假红/假绿。注释已在上一步剥掉。
  for (const m of body.matchAll(/([A-Za-z_][A-Za-z0-9_]*)\s*:\s*'([^']*)'/g)) {
    out[m[1]] = m[2];
  }
  if (Object.keys(out).length === 0) fail(`${rel}：${typeName} 解析出 0 个标签`);
  return out;
}

/** 同 parenBody，但配平的是花括号（字典块用）。 */
function parenBodyBraced(src, idx) {
  let depth = 0;
  let out = '';
  let inStr = false;
  for (let i = idx; i < src.length; i++) {
    const c = src[i];
    if (inStr) {
      out += c;
      if (c === "'" && src[i - 1] !== '\\') inStr = false;
      continue;
    }
    if (c === "'") {
      inStr = true;
      out += c;
      continue;
    }
    if (c === '{') {
      depth++;
      if (depth > 1) out += c;
      continue;
    }
    if (c === '}') {
      depth--;
      if (depth === 0) return out;
      out += c;
      continue;
    }
    if (depth > 0) out += c;
  }
  return null;
}

const shared = {};
for (const dict of [MOVEMENT_TYPE, REF_TYPE, HANDOVER, IN_TRANSIT]) {
  shared[dict] = dictBlockFromShared(FILES.dict, dict);
}

// ── ② 字典：SysDictBootstrap 内置集（启动时 ensureMissingDictItems 会补种已存在的库）──
function dictFromBootstrap(typeName) {
  const src = loaded.get(FILES.bootstrap) ?? '';
  const local = localConstMaps.get(FILES.bootstrap) ?? new Map();
  const start = src.indexOf(`map.put("${typeName}"`);
  if (start < 0) {
    fail(`${FILES.bootstrap} 里找不到 map.put("${typeName}", ...)`);
    return null;
  }
  const open = src.indexOf('(', start + `map.put("${typeName}"`.length);
  const body = parenBody(src, open);
  if (body === null) {
    fail(`${FILES.bootstrap}：${typeName} 的 map.put 括号没配平`);
    return null;
  }
  const mBlock = /\bm\(([\s\S]*)\)\s*$/.exec(body);
  if (!mBlock) {
    fail(`${FILES.bootstrap}：${typeName} 里解析不到 m(...) 标签对（写法变了，请同步本门禁）`);
    return null;
  }
  const tokens = [];
  for (const t of mBlock[1].matchAll(/"([^"]+)"|([A-Z][A-Z0-9_]*)/g)) {
    tokens.push(t[1] !== undefined ? t[1] : t[2]);
  }
  const out = {};
  for (let i = 0; i + 1 < tokens.length; i += 2) {
    const raw = tokens[i];
    const value = /^[A-Z][A-Z0-9_]*$/.test(raw)
      ? (local.get(raw) ?? globalConsts.get(raw) ?? raw)
      : raw;
    out[value] = tokens[i + 1];
  }
  if (Object.keys(out).length === 0) fail(`${FILES.bootstrap}：${typeName} 内置集解析为空`);
  return out;
}

// ── R1 写入集 ⊆ 字典 ─────────────────────────────────────────────────────────
for (const [dict, map] of Object.entries(written)) {
  const sharedMap = shared[dict];
  if (!sharedMap) {
    fail(`${FILES.dict} 里没有字典类型 ${dict}`);
    continue;
  }
  for (const [code, where] of map) {
    if (!Object.prototype.hasOwnProperty.call(sharedMap, code)) {
      fail(
        `代码会写但 ${dict} 字典没有：${code}（${[...where].join(' / ')}）` +
          `⇒ 后台该列渲染成「未知」；补码要同时改 ${FILES.dict} 与 SysDictBootstrap（R2 会比对）`
      );
    }
  }
}

// ── R2 两侧值→标签逐字相等 ───────────────────────────────────────────────────
for (const dict of [MOVEMENT_TYPE, REF_TYPE, HANDOVER, IN_TRANSIT]) {
  const a = shared[dict] ?? {};
  const b = dictFromBootstrap(dict) ?? {};
  const onlyShared = Object.keys(a).filter((k) => !Object.prototype.hasOwnProperty.call(b, k));
  const onlyBoot = Object.keys(b).filter((k) => !Object.prototype.hasOwnProperty.call(a, k));
  const labelDiff = Object.keys(a).filter(
    (k) => Object.prototype.hasOwnProperty.call(b, k) && a[k] !== b[k]
  );
  if (onlyShared.length) {
    fail(
      `${dict}：shared-dict 有而 SysDictBootstrap 没有：${onlyShared.join(', ')}（新库/已存在库补不出这些码）`
    );
  }
  if (onlyBoot.length) {
    fail(
      `${dict}：SysDictBootstrap 有而 shared-dict 没有：${onlyBoot.join(', ')}（DB 无此标签时兜底会露出英文码）`
    );
  }
  for (const k of labelDiff) {
    fail(`${dict}.${k}：两侧标签不一致 shared-dict=「${a[k]}」 SysDictBootstrap=「${b[k]}」`);
  }
}

// ── R3 动态站点必须登记 ──────────────────────────────────────────────────────
if (dynamicSites.length) {
  fail(
    '未登记的动态实参站点（机器求不出取值）——把取值加进代码或登记到 DYNAMIC_OK 再提交：\n' +
      dynamicSites.map((s) => `    - ${s}`).join('\n')
  );
}

if (errors.length) {
  console.error(`${TAG} FAIL`);
  for (const e of errors) console.error(`  ✗ ${e}`);
  process.exit(1);
}

const counts = Object.entries(written)
  .map(([d, m]) => `${d} ${m.size} 码`)
  .join('，');
console.log(
  `${TAG} PASS（${counts} 全部在字典内；四个类型的 shared-dict ↔ SysDictBootstrap 值→标签逐字一致；` +
    `动态实参站点 ${Object.values(DYNAMIC_OK).reduce((n, a) => n + a.length, 0)} 条已全部登记）`
);
