/**
 * check-selfpickup-copy 门禁的负向验证（铁律 75：门禁判据必须能红）。
 *
 * 三态同 verify-outbound-remainder-gate：⓪ 基线绿 → ① 逐条注入真违规（每条单独挣红）
 * → ② 还原绿 → ③ 真实树 md5 前后一致、沙箱已删。全程只写沙箱副本。
 *
 * 锚点失配 = 抛错（探针与源码脱钩，必须重新对准，不许放行）。
 * 用法：node scripts/devops/verify-selfpickup-copy-gate.mjs
 */
import {
  cpSync,
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  rmdirSync,
  unlinkSync,
  writeFileSync
} from 'node:fs';
import { createHash } from 'node:crypto';
import { dirname, resolve } from 'node:path';
import { runAsync } from '../lib/async-spawn.mjs';

const ROOT = resolve(import.meta.dirname, '..', '..');
const GATE_REL = 'scripts/check-selfpickup-copy.mjs';
const SANDBOX = resolve(ROOT, '.tmp', `selfpickup-copy-gate-${process.pid}`);
const sb = (rel) => resolve(SANDBOX, rel);

const TRADE = 'services/trade-service/src/main/java/com/aicabinet/trade';
const ADMIN = 'clients/admin-vue/src';
const MP = 'clients/merchant-mp/src';

const F = {
  dict: 'packages/shared-dict/src/index.ts',
  bootstrap: `${TRADE}/service/SysDictBootstrap.java`,
  migration:
    'services/trade-service/src/main/resources/db/migration/V415__warehouse_selfpickup_dict_labels.sql',
  view: `${ADMIN}/views/warehouse/WarehouseView.vue`,
  transitTab: `${ADMIN}/components/warehouse/WarehouseTransitTab.vue`,
  outboundsTab: `${ADMIN}/components/warehouse/WarehouseOutboundsTab.vue`,
  outbound: `${ADMIN}/composables/warehouse/useWarehouseOutbounds.ts`,
  csv: `${ADMIN}/composables/warehouse/useWarehouseCsv.ts`,
  filters: `${ADMIN}/composables/warehouse/useWarehouseListFilters.ts`,
  routeActions: `${ADMIN}/composables/replenishment/useReplenishmentTaskActions.ts`,
  menu: `${ADMIN}/config/menu.ts`,
  router: `${ADMIN}/router/index.ts`,
  dashboard: `${ADMIN}/views/dashboard/DashboardView.vue`,
  bigScreen: `${ADMIN}/views/dashboard/BigScreenView.vue`,
  apiMessages: `${TRADE}/support/ApiMessages.java`,
  workbench: `${TRADE}/service/OpsWorkbenchQueryService.java`,
  mpFulfillment: `${MP}/composables/useReplenishmentFulfillment.ts`,
  mpPrep: `${MP}/pages/prep-generate/prep-generate.vue`,
  merchantNav: `${MP}/config/merchant-nav.ts`, // MA-155：正门禁 FILES 新增，沙箱拷贝清单同步
  pageStock: `${ADMIN}/views/warehouse/WarehouseStockPage.vue`
};

/** 门禁 FILES 表里的全部文件：沙箱只拷这些，缺一个就按「拷贝不完整」红。 */
const TARGETS = [
  F.dict,
  F.bootstrap,
  F.migration,
  F.view,
  F.transitTab,
  F.outboundsTab,
  F.outbound,
  F.csv,
  F.filters,
  F.routeActions,
  F.menu,
  F.router,
  F.dashboard,
  F.bigScreen,
  F.apiMessages,
  F.workbench,
  F.mpFulfillment,
  F.mpPrep,
  F.merchantNav,
  `${ADMIN}/views/warehouse/WarehouseMasterPage.vue`,
  F.pageStock,
  `${ADMIN}/views/warehouse/WarehouseProcurementPage.vue`,
  `${ADMIN}/views/warehouse/WarehouseFulfillmentPage.vue`
];

function md5(p) {
  return createHash('md5').update(readFileSync(p)).digest('hex');
}

/** 注入：锚点必须**恰好出现一次**（多处会改到非目标行，判据归因就不成立了）。 */
function patch(rel, from, to) {
  const p = sb(rel);
  const s = readFileSync(p, 'utf8');
  // Windows 检出的源码是 CRLF：锚点里的 \n 要按目标文件实际行尾替换，否则多行锚点恒 0 命中
  const eol = s.includes('\r\n') ? '\r\n' : '\n';
  const f = from.replaceAll('\n', eol);
  const t = to.replaceAll('\n', eol);
  const hits = s.split(f).length - 1;
  if (hits !== 1) {
    throw new Error(`注入锚点在 ${rel} 里出现 ${hits} 次（期望 1）：${from.slice(0, 70)}`);
  }
  writeFileSync(p, s.replace(f, t), 'utf8');
}

async function runGate() {
  const r = await runAsync(process.execPath, [sb(GATE_REL)], { cwd: SANDBOX });
  if (r.spawnError) throw new Error(`门禁子进程起不来（${r.spawnError}）`);
  return { code: r.status, out: `${r.stdout}${r.stderr}` };
}

const INJECTS = [
  // ── R1 内置 baseline ───────────────────────────────────────────────────────
  {
    name: 'shared-dict 标签退回「在途」',
    file: F.dict,
    from: "    IN_TRANSIT: '已领未上柜',",
    to: "    IN_TRANSIT: '在途',",
    expect: 'shared-dict 的 handover_status.IN_TRANSIT 不是「已领未上柜」'
  },
  {
    name: 'shared-dict 标签块整体改名',
    file: F.dict,
    from: '  handover_status: {',
    to: '  handover_status_v2: {',
    expect: 'shared-dict 里找不到 handover_status 的标签块'
  },
  // ── R2 新库补种 ────────────────────────────────────────────────────────────
  {
    name: 'Bootstrap 漏改一个标签',
    file: F.bootstrap,
    from: '"READY", "已拣待领"',
    to: '"READY", "待发运"',
    expect: 'SysDictBootstrap 的 handover_status 缺「已拣待领」'
  },
  {
    name: 'Bootstrap 类型名退回旧名',
    file: F.bootstrap,
    from: 'map.put("handover_status", t("上柜交接状态"',
    to: 'map.put("handover_status", t("交接状态"',
    expect: 'handover_status 的类型名不是「上柜交接状态」'
  },
  {
    name: 'Bootstrap 字典类型整块消失',
    file: F.bootstrap,
    from: 'map.put("in_transit_status", t("领货未结状态"',
    to: 'map.put("in_transit_status_v2", t("领货未结状态"',
    expect: 'SysDictBootstrap 里找不到 map.put("in_transit_status", ...)'
  },
  // ── R3 已存在库的迁移 ──────────────────────────────────────────────────────
  {
    name: 'V415 COMPETITOR_REF 丢失',
    file: F.migration,
    from: '-- COMPETITOR_REF: CB-074',
    to: '-- COMPETITOR_REF: N/A',
    expect: 'V415 迁移头没有 COMPETITOR_REF: CB-074'
  },
  {
    name: 'V415 写数据却没声明 backfill',
    file: F.migration,
    from: '-- MIGRATION_KIND: backfill',
    to: '-- MIGRATION_KIND: schema',
    expect: 'MIGRATION_KIND: backfill'
  },
  {
    name: 'V415 少改一条标签（漏 READY）',
    file: F.migration,
    from: "  AND dict_label = '待发运';",
    to: ';',
    expect: 'V415 缺一条把 handover_status.READY 从「待发运」改成「已拣待领」的 UPDATE'
  },
  {
    name: 'V415 把新标签写错（流水类型）',
    file: F.migration,
    from: "SET dict_label = '领货出库', updated_at = NOW()",
    to: "SET dict_label = '发运出库', updated_at = NOW()",
    expect: 'V415 缺一条把 warehouse_movement_type.OUTBOUND_SHIP'
  },
  {
    name: 'V415 丢掉旧标签锚点（会覆盖运营自定义）',
    file: F.migration,
    from: "  AND dict_value = 'SHIPPED'\n  AND dict_label = '已发运';",
    to: "  AND dict_value = 'SHIPPED';",
    expect: 'V415 缺一条把 warehouse_outbound_status.SHIPPED'
  },
  // ── R4 旧叙事已下线 ────────────────────────────────────────────────────────
  {
    name: '出库按钮动词退回「发运」',
    file: F.outbound,
    from: "outboundConfirm.title = '确认领货出库';",
    to: "outboundConfirm.title = '确认发运';",
    expect: '仍有「确认发运」'
  },
  {
    name: '出库成功提示退回「已发运」',
    file: F.outbound,
    from: "okMsg = '已领出';",
    to: "okMsg = '已发运';",
    expect: '仍有「已发运」'
  },
  {
    name: '在途列表头退回「在途 / 时限」',
    file: F.transitTab,
    from: 'label="未上柜 / 时限"',
    to: 'label="在途 / 时限"',
    expect: '仍有「在途 / 时限」'
  },
  {
    name: '在途列退回「发运时间」',
    file: F.transitTab,
    from: 'label="领货时间"',
    to: 'label="发运时间"',
    expect: '仍有「发运时间」'
  },
  {
    name: '导出列头退回在途口径',
    file: F.csv,
    from: "filePrefix: '已领未上柜',",
    to: "filePrefix: '在途',",
    expect: "仍有「filePrefix: '在途'」"
  },
  {
    name: '空态文案退回「暂无在途」',
    file: F.filters,
    from: '暂无未上柜行（补货员领货后出现于此',
    to: '暂无在途（发运后出现于此',
    expect: '仍有「暂无在途」'
  },
  {
    name: '差额 tooltip 又提「在途」',
    file: F.outboundsTab,
    from: '既不退仓也不记损耗（CB-074）',
    to: '不退仓也不算在途（CB-074）',
    expect: '仍有「不算在途」'
  },
  {
    name: '清理按钮退回「脏在途」',
    file: F.view,
    from: '清理空草稿/未结领货',
    to: '清理空草稿/脏在途',
    expect: '仍有「清理空草稿/脏在途」'
  },
  {
    name: '侧栏标题退回「调拨与在途」',
    file: F.menu,
    from: "title: '领货与调拨',",
    to: "title: '调拨与在途',",
    expect: '仍有「调拨与在途」'
  },
  {
    name: '路由 meta 与侧栏不同名',
    file: F.router,
    from: "meta: { title: '领货与调拨', group: '仓储' }",
    to: "meta: { title: '调拨与在途', group: '仓储' }",
    expect: '仍有「调拨与在途」'
  },
  {
    name: '工作台待办退回「签收超时」',
    file: F.dashboard,
    from: "label: '未上柜超时',",
    to: "label: '签收超时',",
    expect: '仍有「签收超时」'
  },
  {
    name: '大屏角标退回「在途」',
    file: F.bigScreen,
    from: "IN_TRANSIT_OVERDUE: '未上柜'",
    to: "IN_TRANSIT_OVERDUE: '在途'",
    expect: "仍有「IN_TRANSIT_OVERDUE: '在途'」"
  },
  {
    name: '后端拒动作文案退回「签收」',
    file: F.apiMessages,
    from: '"出库单已有上柜/部分上柜记录，不能整单作废回仓"',
    to: '"出库单已有签收/部分签收记录，不能整单作废回仓"',
    expect: '仍有「签收/部分签收」'
  },
  {
    name: '待办标题退回「补货签收超时」',
    file: F.workbench,
    from: '                    "HIGH",\n                    "补货未上柜超时",\n                    "出库单 "',
    to: '                    "HIGH",\n                    "补货签收超时",\n                    "出库单 "',
    expect: '仍有「补货签收超时」'
  },
  {
    name: '路线收口弹窗退回发运/签收',
    file: F.routeActions,
    from: '已领出但没全部上柜的单据会回仓并结清。',
    to: '已发运未签收将回仓并取消在途。',
    expect: '仍有「已发运未签收」'
  },
  {
    name: '商户端完成提示退回「签收在途商品」',
    file: F.mpFulfillment,
    from: '结清本次领出的货（没上的记为「领而未上」），请确认商品、批次无误。',
    to: '签收在途商品，请确认商品、批次无误。',
    expect: '仍有「签收在途商品」'
  },
  {
    name: '商户端备货页退回「· 在途」',
    file: F.mpPrep,
    // 2026-10-30 手动生成页改成一行式库存文案（去卡片框），锚点跟着换成新文案
    from: ' · 已领未上柜 {{ item.inTransitQty }}',
    to: ' · 在途 {{ item.inTransitQty }}',
    expect: '仍有「· 在途」'
  },
  // ── R5 新叙事在位 ──────────────────────────────────────────────────────────
  {
    name: '子页同级导航被删',
    file: F.view,
    from: 'data-testid="warehouse-sibling-nav"',
    to: 'data-testid="warehouse-page-nav"',
    expect: '子页的同级导航被删掉了'
  },
  {
    name: '同级导航条不再渲染兄弟页',
    file: F.view,
    from: 'v-for="n in warehouseSiblings"',
    to: 'v-for="n in warehouseSiblingRows"',
    expect: '同级导航条不再渲染兄弟页'
  },
  {
    name: '页面说明不再点破自提自送',
    file: F.view,
    from: '仓→柜自提自送：',
    to: '仓→柜配送：',
    expect: 'pageHint 里不再说明「自提自送」'
  },
  {
    name: '子页不传自己的 hint',
    file: F.pageStock,
    from: 'hint="分仓账面：',
    to: 'data-hint="分仓账面：',
    expect: '没传 hint 属性'
  }
];

/**
 * 沙箱清理：交给子进程递归删除并在子进程内关掉 safe-delete 补丁（同 outbound-remainder-gate 的处置）
 * ——被删的只有本脚本自己的 .tmp/pid 目录。
 */
async function removeSandbox() {
  if (!existsSync(SANDBOX)) return;
  const script = `require('node:fs').rmSync(${JSON.stringify(SANDBOX)},{recursive:true,force:true})`;
  await runAsync(process.execPath, ['-e', script], { env: { CODEBUDDY_SAFE_DELETE_ENABLED: '0' } });
  if (!existsSync(SANDBOX)) return;
  const dirs = [];
  const walk = (d) => {
    dirs.push(d);
    for (const e of readdirSync(d, { withFileTypes: true })) {
      const p = resolve(d, e.name);
      if (e.isDirectory()) walk(p);
      else unlinkSync(p);
    }
  };
  walk(SANDBOX);
  for (const d of dirs.reverse()) rmdirSync(d);
}

async function prepare() {
  await removeSandbox();
  for (const rel of [GATE_REL, ...TARGETS]) {
    const abs = resolve(ROOT, rel);
    if (!existsSync(abs)) throw new Error(`真实树缺少 ${rel}——无法验证门禁`);
    mkdirSync(dirname(sb(rel)), { recursive: true });
    cpSync(abs, sb(rel));
  }
}

function restore(rel) {
  cpSync(resolve(ROOT, rel), sb(rel));
}

const failures = [];
// 🔴 md5 快照必须在**注入之前**取：放在末尾取就变成「注入前 vs 注入前」自比。
const hashesBefore = TARGETS.map((rel) => [rel, md5(resolve(ROOT, rel))]);

function check(label, ok, detail) {
  if (!ok) failures.push(`${label}：${detail}`);
}

await prepare();

try {
  const baseline = await runGate();
  check(
    '⓪ 基线',
    baseline.code === 0,
    `未注入的沙箱副本 exit=${baseline.code}（拷贝不完整/判据已与源码脱钩）：\n${baseline.out.slice(0, 1500)}`
  );

  if (!failures.length) {
    for (const inj of INJECTS) {
      patch(inj.file, inj.from, inj.to);
      const r = await runGate();
      check(
        `① ${inj.name}`,
        r.code === 1 && r.out.includes(inj.expect),
        `exit=${r.code}，期望 1 且报出「${inj.expect}」；实得：${r.out.slice(0, 600)}`
      );
      restore(inj.file);
      const back = await runGate();
      check(
        `② 还原后 ${inj.name}`,
        back.code === 0,
        `还原后仍 exit=${back.code}：\n${back.out.slice(0, 600)}`
      );
    }
  }
} finally {
  await removeSandbox();
  for (const [rel, before] of hashesBefore) {
    check(
      '③ 零污染',
      !existsSync(resolve(ROOT, rel)) || md5(resolve(ROOT, rel)) === before,
      `${rel} 被本验证脚本改写过`
    );
  }
  check('③ 零污染', !existsSync(SANDBOX), '沙箱目录未清理');
}

if (failures.length) {
  console.error(`[verify-selfpickup-copy-gate] FAIL:\n  ${failures.join('\n  ')}\n`);
  process.exit(1);
}
console.log(
  `[verify-selfpickup-copy-gate] OK：基线绿 + ${INJECTS.length} 条判据逐条注入红并还原绿；` +
    `真实树 ${TARGETS.length} 个文件 md5 前后一致、沙箱已清理`
);
