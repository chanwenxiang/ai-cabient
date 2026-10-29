#!/usr/bin/env node
/**
 * 门禁：「领而未上」差额事实链（CB-074 / V414）——迁移 → 实体 → 签收分摊 → 月结拆列 → 后台列，
 * 断任何一环都判红。
 *
 * 背景（2026-10-29 三端口径复审 A1/A2/A6）：出库行历史上**没有实收列**，于是
 * 「签收」只能拿 `Integer.MAX_VALUE` 冒充无穷把整单盖成 RECEIVED；后果是三端看到的是
 * **同一个领货量**——月结「上柜」列其实是出库发运量，「领了没上」在后台/小程序/数据库
 * 三处都查不出来，巡检又把 PARTIAL 当卡单报。修法不是补文案，而是让差额落成单据事实：
 * `warehouse_outbound_line.remainder_qty`，签收按 SKU 实收分摊，月结把「领货/上柜/领而未上」
 * 三列一起摆出来。本门禁守的是这条链**不再退回去**：
 *
 *   ① 台账与迁移：CB-074 有条目；V414 头写 `COMPETITOR_REF: CB-074` 且真加列。
 *   ② 实体与 DTO：行上有 `remainderQty`，两个 DTO 把差额/上柜量传到前端。
 *   ③ 签收算法：实收 = `min(未分配实收, 领货量)`，差额 = 领货 − 实收 写入行；
 *      满量 RECEIVED、未满 PARTIAL。⛔ `WarehouseService` 里再出现 `Integer.MAX_VALUE`
 *      当签收量 = 伪签收回归（历史根因）。
 *   ④ 差额**只记单据**：`applyLineReceipt` 里不得写库存流水/批次（铁律 79 已按领货量扣过仓，
 *      再写=双扣；铁律 78 开门柜库存真值只在 `device_sku_inventory`）。
 *   ⑤ 分支判据：整单入柜只在「任务一条 RESTOCK 行都没有」时发生，判据是 `hasRestockLines`
 *      而不是「实收合计 > 0」——后者会把「现场如实记 0」反转成柜机虚增。
 *   ⑥ 巡检：`STUCK_HANDOVER` 只判 `IN_TRANSIT`；PARTIAL 是合法未结态，报出来就是假异常。
 *   ⑦ 月结：上柜列必须是 `qty − remainderQty`，不能退回直接取领货量。
 *   ⑧ 后台：出库明细有「领而未上」列、月结下钻有「领货/上柜/领而未上」三列、
 *      月结 hint 文案仍说明「领货」口径（用户面口径与代码口径同源，铁律 12/46）。
 *
 * 全部判据只看**源码文本**，离线可跑；生成类型（`openapi.ts`）是否与 Java 形状同步由
 * `check-openapi-regen-required` 与 CI 的 live regen 那一步负责，本门禁不重复判。
 *
 * 负向验证：`node scripts/devops/verify-outbound-remainder-gate.mjs`
 * （沙箱副本逐规则注入红，真实树哈希前后对比；已接 `check:audit-gates`）。
 *
 *   node scripts/check-outbound-remainder-ledger.mjs
 */
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-outbound-remainder-ledger]';

const P = (rel) => join(ROOT, ...rel.split('/'));
const TRADE = 'services/trade-service/src/main/java/com/aicabinet/trade';
const DTO_DIR = 'services/common/common-core/src/main/java/com/aicabinet/common/dto';

const FILES = {
  benchmark: 'docs/COMPETITOR_BENCHMARK.md',
  migration:
    'services/trade-service/src/main/resources/db/migration/V414__outbound_line_remainder_qty.sql',
  entity: `${TRADE}/domain/WarehouseOutboundLine.java`,
  warehouseService: `${TRADE}/service/WarehouseService.java`,
  replenishmentService: `${TRADE}/service/ReplenishmentService.java`,
  consistency: `${TRADE}/service/DataConsistencyService.java`,
  monthly: `${TRADE}/service/WarehouseMonthlyCloseService.java`,
  lineDto: `${DTO_DIR}/WarehouseOutboundLineDto.java`,
  closeDto: `${DTO_DIR}/WarehouseMonthlyCloseRestockLineDto.java`,
  outboundsTab: 'clients/admin-vue/src/components/warehouse/WarehouseOutboundsTab.vue',
  monthlyTab: 'clients/admin-vue/src/components/warehouse/WarehouseMonthlyCloseTab.vue',
  warehouseView: 'clients/admin-vue/src/views/warehouse/WarehouseView.vue'
};

const problems = [];

function read(key) {
  const rel = FILES[key];
  if (!existsSync(P(rel))) {
    problems.push(`找不到文件 ${rel}——本类缺陷的判据依赖它，缺文件按红处理（不猜）`);
    return null;
  }
  return readFileSync(P(rel), 'utf8');
}

/** 去掉 Java 块注释与行注释（R5 用它把 javadoc 里的「不再用 Integer.MAX_VALUE」排除在外）。 */
function stripJavaComments(src) {
  return src.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/\/\/[^\n]*/g, ' ');
}

/** 取方法体：从声明处第一个 `{` 到与之配对的 `}`（配平失败返回 null，不猜范围）。 */
function methodBody(src, declaration) {
  const at = src.indexOf(declaration);
  if (at < 0) return null;
  const open = src.indexOf('{', at);
  if (open < 0) return null;
  let depth = 0;
  for (let i = open; i < src.length; i++) {
    if (src[i] === '{') depth++;
    else if (src[i] === '}') {
      depth--;
      if (depth === 0) return src.slice(open, i + 1);
    }
  }
  return null;
}

const src = {};
for (const key of Object.keys(FILES)) src[key] = read(key);

// ── ① 台账 + 迁移 ────────────────────────────────────────────────────────────
if (src.benchmark && !/^### CB-074\b/m.test(src.benchmark)) {
  problems.push('docs/COMPETITOR_BENCHMARK.md 里没有 CB-074 条目——差额的记账口径失去出处');
}
if (src.migration) {
  if (!/COMPETITOR_REF:\s*CB-074/.test(src.migration)) {
    problems.push(
      'V414 迁移头没有 COMPETITOR_REF: CB-074（门禁 check:competitor-benchmark 的 R5 也会判红）'
    );
  }
  if (
    !/ALTER\s+TABLE\s+warehouse_outbound_line\s+ADD\s+COLUMN[\s\S]{0,80}?\bremainder_qty\b/i.test(
      src.migration
    )
  ) {
    problems.push('V414 没有真的给 warehouse_outbound_line 加 remainder_qty 列');
  }
}

// ── ② 实体 + DTO ─────────────────────────────────────────────────────────────
if (src.entity && !/private\s+int\s+remainderQty\s*;/.test(src.entity)) {
  problems.push('WarehouseOutboundLine 实体上没有 private int remainderQty——差额没有落点');
}
for (const [key, names] of [
  ['lineDto', ['remainderQty']],
  ['closeDto', ['shelvedQty', 'remainderQty']]
]) {
  if (!src[key]) continue;
  for (const field of names) {
    if (!new RegExp(`\\bint\\s+${field}\\s*,`).test(src[key])) {
      problems.push(
        `${FILES[key].split('/').pop()} 的 record 里缺少 int ${field}——后端算得出但传不到前端`
      );
    }
  }
}

// ── ③ 签收算法 ───────────────────────────────────────────────────────────────
const receiptBody = src.warehouseService
  ? methodBody(src.warehouseService, 'private void applyLineReceipt(')
  : null;
if (!receiptBody) {
  problems.push(
    'WarehouseService 里找不到 applyLineReceipt 方法体——签收算法已被重写，本类缺陷的判据失效，请重订门禁'
  );
} else {
  for (const [needle, why] of [
    [
      /received\s*=\s*Math\.min\(\s*left\s*,\s*shipped\s*\)/,
      '实收不再被领货量钳制（min(未分配实收, 领货量) 没了）——超收会把差额写成负数'
    ],
    [
      /setRemainderQty\(\s*shipped\s*-\s*received\s*\)/,
      '差额没写回行上（setRemainderQty(shipped - received) 缺失）——「领而未上」又不可见'
    ],
    [
      /received\s*>=\s*shipped\s*\?\s*RECEIVED\s*:\s*PARTIAL/,
      '行状态不再按实收分满量/未满——全部盖成 RECEIVED 就是伪签收换了个写法'
    ]
  ]) {
    if (!needle.test(receiptBody)) problems.push(why);
  }
  // ④ 差额只记单据：签收路径不得碰库存。
  for (const [needle, why] of [
    [
      /addRestock\s*\(/,
      'applyLineReceipt 里出现了 inventoryLotService.addRestock(...)——把差额补记进柜机违反铁律 78'
    ],
    [
      /movementRepository\.insert|createMovement\s*\(/,
      'applyLineReceipt 里出现了库存流水写入——发运已按领货量扣过仓，再写=双扣（铁律 79）'
    ]
  ]) {
    if (needle.test(receiptBody)) problems.push(why);
  }
}
if (src.warehouseService) {
  const code = stripJavaComments(src.warehouseService);
  const sentinels = [...code.matchAll(/Integer\.MAX_VALUE/g)].length;
  if (sentinels > 0) {
    problems.push(
      `WarehouseService 代码里出现 Integer.MAX_VALUE ${sentinels} 处——用它当「签收量」是本类缺陷的历史根因` +
        `（伪全量签收会把差额洗成 0）。全量签收请调 markDeviceHandoverReceivedFull。`
    );
  }
}

// ── ⑤ 分支判据 ───────────────────────────────────────────────────────────────
const handoverBody = src.replenishmentService
  ? methodBody(src.replenishmentService, 'private int receiveOutboundHandover(')
  : null;
if (!handoverBody) {
  problems.push(
    'ReplenishmentService 里找不到 receiveOutboundHandover 方法体，签收分支判据无从核对'
  );
} else {
  if (!/hasRestockLines/.test(handoverBody)) {
    problems.push(
      'receiveOutboundHandover 不再按「任务有没有 RESTOCK 行」分支——若退回按实收合计>0 判空，' +
        '现场如实登记 0 件会掉进整单入柜分支，把柜机库存凭空加出来'
    );
  }
  if (/appliedRestockQty\s*>\s*0/.test(handoverBody)) {
    problems.push('整单入柜的条件又变成「实收合计 > 0」——这正是 2026-10-29 订正掉的错判据');
  }
  if (
    !/receiveHandoverPair\s*\(/.test(handoverBody) ||
    !/receiveHandoverPairFull\s*\(/.test(handoverBody)
  ) {
    problems.push(
      '签收不再走 receiveHandoverPair / receiveHandoverPairFull 这一对入口，两个服务方法会各自漂移'
    );
  }
}
if (src.replenishmentService) {
  const pairBody = methodBody(src.replenishmentService, 'private void receiveHandoverPair(');
  if (!pairBody || !/markDeviceHandoverReceived\s*\(/.test(pairBody)) {
    problems.push(
      'receiveHandoverPair 没有把实收明细交给 markDeviceHandoverReceived——逐项签收链路断了'
    );
  }
  if (
    !/resolveAppliedQtyForCatchUpReceive/.test(src.replenishmentService) &&
    !/receivedQtyBySkuOf/.test(src.replenishmentService)
  ) {
    problems.push('receivedQtyBySkuOf 消失——补签收路径不再以任务行为实收来源');
  }
}

// ── ⑥ 巡检判据 ───────────────────────────────────────────────────────────────
if (src.consistency) {
  const stuckAt = src.consistency.indexOf('STUCK_HANDOVER');
  if (stuckAt < 0) {
    problems.push('DataConsistencyService 里找不到 STUCK_HANDOVER 判据段');
  } else {
    const window = src.consistency.slice(stuckAt, stuckAt + 600);
    if (!/handover_status\s*=\s*'IN_TRANSIT'/.test(window)) {
      problems.push("STUCK_HANDOVER 不再只判 handover_status = 'IN_TRANSIT'");
    }
    if (/handover_status\s+IN\s*\(\s*'IN_TRANSIT'\s*,\s*'PARTIAL'/.test(window)) {
      problems.push('PARTIAL 又被当成卡单上报——它是「领而未上」的合法未结态，报出来即假异常');
    }
  }
}

// ── ⑦ 月结拆列 ───────────────────────────────────────────────────────────────
if (src.monthly) {
  if (!/acc\.qty\s*-\s*acc\.remainderQty/.test(src.monthly)) {
    problems.push('月结的上柜件数不再是 qty − remainderQty——「上柜」列会退回成领货量（A1 原缺陷）');
  }
  if (!/private\s+int\s+remainderQty\s*;/.test(src.monthly)) {
    problems.push('月结聚合器里不再累计 remainderQty，下钻的「领而未上」会是 0');
  }
}

// ── ⑧ 后台可见 ───────────────────────────────────────────────────────────────
if (src.outboundsTab) {
  if (!src.outboundsTab.includes('领而未上')) {
    problems.push('出库明细页没有「领而未上」列——差额在后台不可见（三端口径又各说各话）');
  }
  if (!/remainderQty/.test(src.outboundsTab)) {
    problems.push('出库明细页不再读 remainderQty，列成了写死的空壳');
  }
}
if (src.monthlyTab) {
  for (const label of ['领货', '上柜', '领而未上']) {
    if (!src.monthlyTab.includes(label)) {
      problems.push(
        `月结页缺少「${label}」列——三列（领货/上柜/领而未上）必须一起摆，缺一列就重新产生口径歧义`
      );
    }
  }
  if (!/row\.shelvedQty/.test(src.monthlyTab)) {
    problems.push('月结下钻不再渲染 shelvedQty（上柜实收），差额只剩一个可对不上账的总数');
  }
}
if (src.warehouseView) {
  const hint = (src.warehouseView.match(/'[^']*月结一张表[^']*'/) || [''])[0];
  if (!hint.includes('领货')) {
    problems.push(
      '月结页的提示文案不再说明「领货」口径——后端改了口径而用户面文案还在说「上柜」（铁律 12）'
    );
  }
}

if (problems.length) {
  console.error(`${TAG} FAIL:\n  ${problems.join('\n  ')}\n`);
  process.exit(1);
}

console.log(
  `${TAG} OK：CB-074/V414 的「领而未上」事实链完整——行上有 remainder_qty、签收按 min(实收,领货) 分摊` +
    `（差额只记单据、不碰库存与流水）、整单入柜只在无 RESTOCK 行时发生、巡检只判 IN_TRANSIT、` +
    `月结出「领货/上柜/领而未上」三列、后台两页均可读；WarehouseService 无 Integer.MAX_VALUE 伪签收`
);
