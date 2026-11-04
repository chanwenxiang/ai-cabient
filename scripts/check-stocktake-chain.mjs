#!/usr/bin/env node
/**
 * 柜机盘点链完整性门禁（2026-10-28，铁律 75：修一类 = 落门禁）。
 *
 * 背景：开门柜盘点（V412 时代）此前完全缺失——DeviceSlotService.stocktakeSlot
 * 对开门柜一律 409；本次补齐「盘点开门 + 逐商品实盘调账」。把链路上的关键不变量
 * 焊成机器可查锚点，任何一处被改坏（盘点入口被删、STOCKTAKE 会话被拿去结算、
 * 调账绕过批次台账、首页入口丢失）即 FAIL：
 *
 * A 盘点能力存在性（商户面）
 *   - MerchantDevicePortalService 必须有 openStocktakeDoor / submitDoorStocktake，
 *     且都走 merchant:slots:edit 权限；
 *   - submitDoorStocktake 方法体内必须调 InventoryLotService.stocktakeAdjust
 *     （批次+台账+聚合三层账一起动，铁律 79）与 requirePhotoEvidence（凭证策略）；
 *   - MerchantPortalController 必须暴露 /stocktake-door 与 /door-stocktake。
 *
 * B STOCKTAKE 会话生命周期（防「盘点被当购物结算」）
 *   - DeviceValidationService.isStocktakeSession 存在，且 isNonConsumerSession
 *     方法体必须引用它（关门不走扣款结算）；
 *   - SessionDoorService.onDoorClosed 里 isStocktakeSession 分支必须出现在
 *     RECOGNIZING 迁移之前（关门即完成，不进识别链）；
 *   - ShoppingSessionMapper.findLatestActiveRestockByDeviceId 必须匹配 STOCKTAKE:
 *     前缀（§4.2.14 补货关门上报按设备推进，盘点会话同走 sendOpenStockDoor）。
 *
 * C 前端入口
 *   - device-stocktake.vue 必须调用 openStocktakeDoor / submitDoorStocktake；
 *   - 首页快捷入口（home.vue）留「柜机盘点」——不带 deviceId，到盘点页再扫码选柜；
 *   - 柜机详情（device-detail.vue）**不得**提供盘点入口：开门柜盘点要求「人在柜边」，
 *     入口统一留在首页（扫码选柜）/盘点页扫码；详情页再放入口等于多一条远程开门路径
 *     （2026-10-28 起按柜型隐藏，弹簧柜退场后彻底移除）。
 *
 * 永远 fail-closed：锚点找不到一律 FAIL。
 *   node scripts/check-stocktake-chain.mjs
 */
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-stocktake-chain]';

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

const FILES = {
  portal:
    'services/trade-service/src/main/java/com/aicabinet/trade/service/MerchantDevicePortalService.java',
  controller:
    'services/trade-service/src/main/java/com/aicabinet/trade/api/MerchantPortalController.java',
  validation:
    'services/trade-service/src/main/java/com/aicabinet/trade/service/DeviceValidationService.java',
  door: 'services/trade-service/src/main/java/com/aicabinet/trade/service/SessionDoorService.java',
  mapper:
    'services/trade-service/src/main/java/com/aicabinet/trade/mapper/ShoppingSessionMapper.java',
  stocktakePage: 'clients/merchant-mp/src/pages/device-stocktake/device-stocktake.vue',
  home: 'clients/merchant-mp/src/pages/home/home.vue',
  deviceDetail: 'clients/merchant-mp/src/pages/device-detail/device-detail.vue'
};

for (const [k, rel] of Object.entries(FILES)) {
  if (!existsSync(join(root, rel))) fail(`找不到 ${k}: ${rel}`);
}
const src = {};
for (const [k, rel] of Object.entries(FILES)) {
  src[k] = readFileSync(join(root, rel), 'utf8');
}

/** 提取「方法签名起、到下一个同级声明/类尾」的近似方法体（按缩进启发）。 */
function methodBody(fileSrc, signatureRegex) {
  const m = fileSrc.match(signatureRegex);
  if (!m) return null;
  const start = m.index;
  const end = fileSrc.indexOf('\n    }', start);
  return end === -1 ? fileSrc.slice(start) : fileSrc.slice(start, end + 6);
}

// ── A 商户面盘点能力 ────────────────────────────────────────────────────
const openDoorBody = methodBody(src.portal, /public SessionDto openStocktakeDoor\(/);
if (!openDoorBody || !openDoorBody.includes('merchant:slots:edit')) {
  fail('openStocktakeDoor 缺失或未走 merchant:slots:edit 权限');
}
// 开门指令在私有实现 doOpenStocktakeDoor（短事务分离模式），一并锚定
const doOpenBody = methodBody(src.portal, /private SessionDto doOpenStocktakeDoor\(/);
if (!doOpenBody || !doOpenBody.includes('requestOpenDoorStock')) {
  fail('doOpenStocktakeDoor 未走补货开门真路由 requestOpenDoorStock（将邑 sendOpenStockDoor）');
}
const submitBody = methodBody(
  src.portal,
  /public MerchantDoorStocktakeResult submitDoorStocktake\(/
);
if (!submitBody || !submitBody.includes('merchant:slots:edit')) {
  fail('submitDoorStocktake 缺失或未走 merchant:slots:edit 权限');
}
if (!submitBody.includes('inventoryLotService.stocktakeAdjust(')) {
  fail(
    'submitDoorStocktake 未走 InventoryLotService.stocktakeAdjust——盘点调账必须批次+台账+聚合同步（铁律 79）'
  );
}
if (!submitBody.includes('requirePhotoEvidence')) {
  fail('submitDoorStocktake 未接 requirePhotoEvidence——盘点凭证策略被绕过');
}
for (const path of ['/stocktake-door"', '/door-stocktake"']) {
  if (!src.controller.includes(path)) fail(`MerchantPortalController 缺端点 ${path}`);
}

// ── B STOCKTAKE 会话生命周期 ────────────────────────────────────────────
if (!src.validation.includes('static boolean isStocktakeSession(ShoppingSession')) {
  fail('DeviceValidationService.isStocktakeSession 缺失');
}
const nonConsumerBody = methodBody(
  src.validation,
  /static boolean isNonConsumerSession\(ShoppingSession/
);
if (!nonConsumerBody || !nonConsumerBody.includes('isStocktakeSession(session)')) {
  fail('isNonConsumerSession 未包含 isStocktakeSession——STOCKTAKE 会话会被扣款结算');
}
if (!src.mapper.includes('"STOCKTAKE:"')) {
  fail(
    'findLatestActiveRestockByDeviceId 未匹配 STOCKTAKE: 前缀——关门上报推进不到盘点会话，会话永占柜'
  );
}
const closedBody = methodBody(src.door, /private SessionDto onDoorClosed\(ShoppingSession/);
if (!closedBody || !closedBody.includes('isStocktakeSession(session)')) {
  fail('SessionDoorService.onDoorClosed 缺 isStocktakeSession 分支');
}
const stIdx = closedBody.indexOf('isStocktakeSession(session)');
const recogIdx = closedBody.indexOf('SessionState.RECOGNIZING');
if (recogIdx !== -1 && stIdx > recogIdx) {
  fail('onDoorClosed 里 STOCKTAKE 分支落在 RECOGNIZING 之后——盘点关门会进识别结算链');
}

// ── C 前端入口 ──────────────────────────────────────────────────────────
for (const api of ['openStocktakeDoor', 'submitDoorStocktake']) {
  if (!src.stocktakePage.includes(api)) fail(`device-stocktake.vue 缺 ${api} 调用`);
}
// 盘点入口一：首页快捷项（扫码选柜，不带 deviceId）
if (!src.home.includes('goDeviceStocktake')) {
  fail('home.vue 缺 goDeviceStocktake——首页快捷项「柜机盘点」入口被移除');
}
const homeNav = src.home.match(/goDeviceStocktake\(\)\s*\{[\s\S]{0,300}?\}/);
if (!homeNav || !homeNav[0].includes('/pages/device-stocktake/device-stocktake')) {
  fail('home.vue 的 goDeviceStocktake 未跳盘点页');
}
// 盘点入口二（2026-11-04 退场）：柜机详情**不得**提供盘点入口——开门柜要求人在柜边，
// 入口只在首页扫码选柜；详情页入口等于可远程点开的开门路径。
if (src.deviceDetail.includes('goDeviceStocktake')) {
  fail('device-detail.vue 又出现 goDeviceStocktake——详情页不应提供盘点开门入口（入口在首页扫码）');
}

// ── D 货道面已退场（防回潮）：契约里不得再出现任何 slots 端点 ─────────────
// 2026-11-04 批 3b：ops 4 端点 + merchant 3 端点（GET|PUT /slots、POST /slots/stocktake）全删，
// 货道写面/读面在服务端没有入口了。开门柜盘点只走 /stocktake-door + /door-stocktake。
const contractPath = 'packages/shared-types/src/generated/openapi.ts';
if (!existsSync(join(root, contractPath))) fail(`找不到生成契约 ${contractPath}`);
const contract = readFileSync(join(root, contractPath), 'utf8');
for (const dead of [
  '"/api/v2/merchant/devices/{deviceId}/slots"',
  '"/api/v2/merchant/devices/{deviceId}/slots/stocktake"',
  '"/api/v2/ops/admin/devices/{deviceId}/slots"',
  '"/api/v2/ops/admin/slots/stocktake"',
  '"/api/v2/ops/admin/slots/apply-template"'
]) {
  if (contract.includes(`${dead}:`)) {
    fail(`契约里又出现货道端点 ${dead}——货道面（批 3b）已退场，不得回潮`);
  }
}
if (
  readFileSync(
    join(
      root,
      'services/trade-service/src/main/java/com/aicabinet/trade/service/DeviceSlotService.java'
    ),
    'utf8'
  ).includes('public List<DeviceSlotDto> upsertSlots(')
) {
  fail('DeviceSlotService 又出现 upsertSlots——货道编辑入口已随批 3b 删除');
}

console.log(
  `${TAG} OK：盘点链 ${Object.keys(FILES).length} 文件锚点全在（能力/会话生命周期/入口/货道端点零回潮）`
);
