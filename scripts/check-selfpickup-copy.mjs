#!/usr/bin/env node
/**
 * 门禁：铁律 80 / CB-074 的「自提自送」口径在**用户面**的落地——字典标签三处同改 + 旧叙事已下线。
 *
 * 背景（2026-10-29 三端口径复审 A3/A4/A5/B2）：补货员在异地、有自己的分仓，每天从分仓备货、
 * 带着商品去补货。「分仓→柜」这一段**不存在**物流意义上的「拣货→发运→在途→签收」，
 * 只有采购段（供应商→分仓）才有在途。但字典与页面一直用那套物流词，于是
 * 运营看到「待发运/在途/已签收」、补货员看到「签收在途商品」、库里 `warehouse_in_transit`
 * 记的其实是「领而未上的差额」——三端各说各话。
 *
 * 🔴 改标签必须**三处同改**，少一处就出现「DB 覆盖内置」或「内置覆盖 DB」的漂移：
 *   ① `packages/shared-dict`（内置 baseline，`dictLabel()` 的兜底）
 *   ② `SysDictBootstrap`（新库启动补种，只 insert 不 update）
 *   ③ V415 的 UPDATE（已存在的库；运行时 ACTIVE 字典行优先级高于内置）
 *
 * 判据：
 *   R1 ①里每条新标签在位；R2 ②里同标签 + 类型名在位；R3 ③里逐条 UPDATE 在位，
 *      且**带 `AND dict_label = '<旧标签>'`**（不带就会把运营自定义过的标签当脏数据洗掉，
 *      也不幂等）；R4 一批**旧用户面词**在钉住的文件中不得再出现；R5 新叙事（tab 名、
 *      子页 hint、同级导航、菜单标题）在位。
 *
 * 🔴 不动的东西：表名/列名/枚举值（`IN_TRANSIT`/`PARTIAL`/`OUTBOUND_SHIP` 等字面量）、
 *   锁 key、状态机判据、跨仓**调拨**那一段（真有运输，V314 的在途损耗口径保留）。
 *
 * 全部判据只看源码文本，离线可跑。
 * 负向验证：`node scripts/devops/verify-selfpickup-copy-gate.mjs`
 *
 *   node scripts/check-selfpickup-copy.mjs
 */
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-selfpickup-copy]';
const P = (rel) => join(ROOT, ...rel.split('/'));

const FILES = {
  dict: 'packages/shared-dict/src/index.ts',
  bootstrap:
    'services/trade-service/src/main/java/com/aicabinet/trade/service/SysDictBootstrap.java',
  migration:
    'services/trade-service/src/main/resources/db/migration/V415__warehouse_selfpickup_dict_labels.sql',
  warehouseView: 'clients/admin-vue/src/views/warehouse/WarehouseView.vue',
  transitTab: 'clients/admin-vue/src/components/warehouse/WarehouseTransitTab.vue',
  outboundsTab: 'clients/admin-vue/src/components/warehouse/WarehouseOutboundsTab.vue',
  outboundComposable: 'clients/admin-vue/src/composables/warehouse/useWarehouseOutbounds.ts',
  csvComposable: 'clients/admin-vue/src/composables/warehouse/useWarehouseCsv.ts',
  filterComposable: 'clients/admin-vue/src/composables/warehouse/useWarehouseListFilters.ts',
  routeActions: 'clients/admin-vue/src/composables/replenishment/useReplenishmentTaskActions.ts',
  menu: 'clients/admin-vue/src/config/menu.ts',
  router: 'clients/admin-vue/src/router/index.ts',
  dashboard: 'clients/admin-vue/src/views/dashboard/DashboardView.vue',
  bigScreen: 'clients/admin-vue/src/views/dashboard/BigScreenView.vue',
  apiMessages: 'services/trade-service/src/main/java/com/aicabinet/trade/support/ApiMessages.java',
  workbench:
    'services/trade-service/src/main/java/com/aicabinet/trade/service/OpsWorkbenchQueryService.java',
  mpFulfillment: 'clients/merchant-mp/src/composables/useReplenishmentFulfillment.ts',
  mpPrep: 'clients/merchant-mp/src/pages/prep-generate/prep-generate.vue',
  merchantNav: 'clients/merchant-mp/src/config/merchant-nav.ts',
  pageMaster: 'clients/admin-vue/src/views/warehouse/WarehouseMasterPage.vue',
  pageProcurement: 'clients/admin-vue/src/views/warehouse/WarehouseProcurementPage.vue',
  pageStock: 'clients/admin-vue/src/views/warehouse/WarehouseStockPage.vue',
  pageFulfillment: 'clients/admin-vue/src/views/warehouse/WarehouseFulfillmentPage.vue'
};

/** 字典标签真值表：value 不变，只换用户面的词（old = 迁移要锚定的旧默认标签）。 */
const LABELS = [
  ['warehouse_outbound_status', 'SHIPPED', '已发运', '已领出'],
  ['handover_status', 'PENDING', '待备货', '未交接'],
  ['handover_status', 'READY', '待发运', '已拣待领'],
  ['handover_status', 'IN_TRANSIT', '在途', '已领未上柜'],
  ['handover_status', 'PARTIAL', '部分签收', '部分上柜'],
  ['handover_status', 'RECEIVED', '已签收', '已上柜'],
  ['in_transit_status', 'IN_TRANSIT', '在途', '未结清'],
  ['in_transit_status', 'RECEIVED', '已签收', '已结清'],
  ['warehouse_movement_type', 'OUTBOUND_SHIP', '发运', '领货出库'],
  ['ops_alert_type', 'IN_TRANSIT_OVERDUE', '签收超时', '未上柜超时']
];

/** 类型名（dict_name）：只有 ②③ 两处，shared-dict 不带类型名。 */
const TYPE_NAMES = [
  ['handover_status', '交接状态', '上柜交接状态'],
  ['in_transit_status', '在途状态', '领货未结状态']
];

const problems = [];
const src = {};
for (const [key, rel] of Object.entries(FILES)) {
  if (!existsSync(P(rel))) {
    problems.push(`找不到文件 ${rel}——本类口径的判据依赖它，缺文件按红处理（不猜）`);
    src[key] = null;
    continue;
  }
  src[key] = readFileSync(P(rel), 'utf8');
}

/** 取 `key: { ... }` 对象块（花括号配平）。配平失败返回 null。 */
function tsObjectBlock(srcText, key) {
  const at = srcText.search(new RegExp(`(^|\\n)\\s*${key}\\s*:\\s*\\{`));
  if (at < 0) return null;
  const open = srcText.indexOf('{', at);
  let depth = 0;
  for (let i = open; i < srcText.length; i++) {
    if (srcText[i] === '{') depth++;
    else if (srcText[i] === '}') {
      depth--;
      if (depth === 0) return srcText.slice(open, i + 1);
    }
  }
  return null;
}

/** 取 `map.put("type", t(...))` 那一段（圆括号配平）。 */
function javaMapBlock(srcText, type) {
  const at = srcText.indexOf(`map.put("${type}", t(`);
  if (at < 0) return null;
  const open = srcText.indexOf('(', at + 'map.put'.length);
  let depth = 0;
  for (let i = open; i < srcText.length; i++) {
    if (srcText[i] === '(') depth++;
    else if (srcText[i] === ')') {
      depth--;
      if (depth === 0) return srcText.slice(open, i + 1);
    }
  }
  return null;
}

// ── R1 内置 baseline（①）─────────────────────────────────────────────────────
const dictBlocks = {};
for (const type of new Set(LABELS.map((l) => l[0]))) {
  dictBlocks[type] = src.dict ? tsObjectBlock(src.dict, type) : null;
  if (src.dict && !dictBlocks[type]) {
    problems.push(`shared-dict 里找不到 ${type} 的标签块——内置 baseline 无从核对`);
  }
}
for (const [type, value, prev, next] of LABELS) {
  const block = dictBlocks[type];
  if (!block) continue;
  if (!new RegExp(`${value}\\s*:\\s*'${next}'`).test(block)) {
    problems.push(
      `shared-dict 的 ${type}.${value} 不是「${next}」——用户面又回到物流词（铁律 80）` +
        `（块内仍是「${prev}」=${new RegExp(`${value}\\s*:\\s*'${prev}'`).test(block)}）`
    );
  }
}

// ── R2 新库补种（②）──────────────────────────────────────────────────────────
const bootBlocks = {};
for (const type of new Set(LABELS.map((l) => l[0]))) {
  bootBlocks[type] = src.bootstrap ? javaMapBlock(src.bootstrap, type) : null;
  if (src.bootstrap && !bootBlocks[type]) {
    problems.push(`SysDictBootstrap 里找不到 map.put("${type}", ...)——新库将种不出这套口径`);
  }
}
for (const [type, , , next] of LABELS) {
  const block = bootBlocks[type];
  if (!block) continue;
  if (!block.includes(`"${next}"`)) {
    problems.push(
      `SysDictBootstrap 的 ${type} 缺「${next}」——老库读 ③、新库读 ②，两处不同步就有两种说法`
    );
  }
}
for (const [type, , nextName] of TYPE_NAMES) {
  if (!src.bootstrap) break;
  if (!src.bootstrap.includes(`map.put("${type}", t("${nextName}"`)) {
    problems.push(
      `SysDictBootstrap 里 ${type} 的类型名不是「${nextName}」（字典管理页会显示旧名）`
    );
  }
}

// ── R3 已存在库的 UPDATE 迁移（③）────────────────────────────────────────────
if (src.migration) {
  if (!/COMPETITOR_REF:\s*CB-074/.test(src.migration)) {
    problems.push('V415 迁移头没有 COMPETITOR_REF: CB-074');
  }
  if (!/MIGRATION_KIND:\s*backfill/.test(src.migration)) {
    problems.push(
      'V415 写了数据却没有 MIGRATION_KIND: backfill（门禁 check:flyway-seed-separation 也会红）'
    );
  }
  for (const [type, value, prev, next] of LABELS) {
    const row = new RegExp(
      `UPDATE sys_dict_data[\\s\\S]{0,220}?SET dict_label = '${next}'[\\s\\S]{0,220}?` +
        `dict_type = '${type}'[\\s\\S]{0,120}?dict_value = '${value}'[\\s\\S]{0,120}?dict_label = '${prev}'`
    );
    if (!row.test(src.migration)) {
      problems.push(
        `V415 缺一条把 ${type}.${value} 从「${prev}」改成「${next}」的 UPDATE，` +
          `且必须带 AND dict_label = '${prev}'（否则覆盖运营自定义标签、也不幂等）`
      );
    }
  }
  for (const [type, prev, next] of TYPE_NAMES) {
    const row = new RegExp(
      `UPDATE sys_dict_type[\\s\\S]{0,200}?SET dict_name = '${next}'[\\s\\S]{0,200}?` +
        `dict_type = '${type}'[\\s\\S]{0,120}?dict_name = '${prev}'`
    );
    if (!row.test(src.migration)) {
      problems.push(`V415 缺一条把 ${type} 的类型名从「${prev}」改成「${next}」的 UPDATE`);
    }
  }
}

// ── R4 旧用户面叙事已下线 ────────────────────────────────────────────────────
const BANNED = [
  ['warehouseView', ['label="在途"', '清理空草稿/脏在途', '已发运']],
  ['transitTab', ['在途 / 时限', '发运时间', '已运 ']],
  ['outboundsTab', ['确认发运', '已发运', '不算在途']],
  ['outboundComposable', ['确认发运', '已发运', '转为在途', '脏在途', '孤儿发运', '未签收']],
  ['csvComposable', ["filePrefix: '在途'", '在途时长', '发运时间']],
  ['filterComposable', ['暂无在途', '到柜签收']],
  ['routeActions', ['已发运未签收', '脏出库/在途']],
  ['menu', ['调拨与在途']],
  [
    'merchantNav',
    ['分仓出库', '出库与补货任务']
  ] /* MA-155：mine 导航描述在门禁白名单盲区（2026-11-04 实测） */,
  ['router', ['调拨与在途']],
  ['dashboard', ['签收超时']],
  ['bigScreen', ["IN_TRANSIT_OVERDUE: '在途'"]],
  ['apiMessages', ['请先发运出库', '签收/部分签收']],
  ['workbench', ['补货签收超时']],
  ['mpFulfillment', ['签收在途商品']],
  ['mpPrep', ['· 在途', '实际 − 在途']]
];
for (const [key, words] of BANNED) {
  if (!src[key]) continue;
  for (const word of words) {
    if (src[key].includes(word)) {
      problems.push(
        `${FILES[key]} 里仍有「${word}」——仓→柜是自提自送，这套物流词会把「领而未上的差额」` +
          `讲成「货在路上等签收」（三端口径歧义的来源）`
      );
    }
  }
}

// ── R5 新叙事在位（防只删不改）───────────────────────────────────────────────
const REQUIRED = [
  ['warehouseView', 'label="已领未上柜"', 'transit tab 名不再是「已领未上柜」'],
  [
    'warehouseView',
    'data-testid="warehouse-sibling-nav"',
    '子页的同级导航被删掉了（拆页后只能回侧栏重找）'
  ],
  ['warehouseView', 'v-for="n in warehouseSiblings"', '同级导航条不再渲染兄弟页'],
  ['outboundComposable', '确认领货', '出库按钮没有回到「领货」动词'],
  ['menu', "title: '领货与调拨'", '侧栏标题不是「领货与调拨」'],
  ['router', "title: '领货与调拨'", '路由 meta.title 与侧栏不同名（同一页三种叫法）'],
  ['mpPrep', '未上柜', '商户端备货页仍按「在途」展示扣减项'],
  ['mpFulfillment', '领而未上', '商户端完成提示不再说明差额去向']
];
for (const [key, needle, why] of REQUIRED) {
  if (!src[key]) continue;
  if (!src[key].includes(needle)) problems.push(`${FILES[key]}：${why}`);
}

/**
 * 「自提自送」这句必须在**页头说明**里，不能只在别处出现过一次就算数——
 * 所以取 pageHint 这个 computed 的函数体单独判（整文件 includes 会被 TAB_DESC 蒙混过关）。
 */
if (src.warehouseView) {
  const at = src.warehouseView.indexOf('const pageHint = computed(');
  const hintBlock =
    at < 0 ? null : src.warehouseView.slice(at, src.warehouseView.indexOf('\n});', at));
  if (!hintBlock) {
    problems.push('WarehouseView 里找不到 pageHint 这个 computed——页头说明换了写法，请重订门禁');
  } else {
    for (const needle of ['自提自送', '差额']) {
      if (!hintBlock.includes(needle)) {
        problems.push(
          `pageHint 里不再说明「${needle}」——运营在页头看不到「仓→柜是自己搬运的差额」`
        );
      }
    }
  }
}
for (const key of ['pageMaster', 'pageProcurement', 'pageStock', 'pageFulfillment']) {
  if (!src[key]) continue;
  if (!/(^|\s)hint="/.test(src[key])) {
    problems.push(`${FILES[key]} 没传 hint 属性 —— 子页说明落回了整页兜底文案，四个子页同说一句`);
  }
}

if (!src.merchantNav) {
  problems.push('merchant-mp 的 merchant-nav.ts 读不到——发起补货导航描述无从校验');
} else {
  const navDesc = src.merchantNav.match(/desc: '(缺啥补啥[^']*)'/);
  if (!navDesc) {
    problems.push('merchant-nav 里找不到「缺啥补啥」发起补货描述（可能已改写，请重订门禁锚）');
  } else if (!/领货上柜/.test(navDesc[1]) || /出库/.test(navDesc[1])) {
    problems.push(`发起补货描述未对齐「领货上柜」口径：${navDesc[1]}`);
  }
}

if (problems.length) {
  console.error(`${TAG} FAIL:\n  ${problems.join('\n  ')}\n`);
  process.exit(1);
}

console.log(
  `${TAG} OK：铁律 80 / CB-074 的「领货 → 上柜」口径三处同改（shared-dict baseline / ` +
    `SysDictBootstrap 补种 / V415 带旧标签锚点的 UPDATE），${LABELS.length} 条标签 + ` +
    `${TYPE_NAMES.length} 个类型名逐条对账；旧「发运/签收/在途」用户面叙事在 ${BANNED.length} 个文件中已下线，` +
    `新叙事（tab 名、菜单与路由同名、子页 hint、同级导航、商户端差额说明）在位`
);
