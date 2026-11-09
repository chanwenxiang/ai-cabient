/**
 * check-outbound-remainder-ledger 门禁的负向验证（铁律 75：门禁判据必须能红）。
 *
 * 三态（与 defect-class-gate 同一形状，全程沙箱，真实树**一个字节都不写**）：
 *   ⓪ 基线：未注入的沙箱副本必须绿 ⇒ 否则下面的「红」可能只是拷贝不完整造成的假象；
 *   ① 红：对每条判据各注入一处真违规（共 INJECTS 项）→ 门禁必须非零退出且报出对应句子；
 *   ② 零污染：真实树全部目标文件 md5 前后一致、沙箱目录已删除。
 *
 * 为什么每条判据都要单独注入而不是「注入一次改很多处」：一次改多处只能证明门禁会红，
 * 证明不了**每条**规则各自有效——某条规则写成恒真（锚点根本匹配不到任何文本）时，
 * 整体仍然红，于是它就被别的注入掩盖了。逐条注入 ⇒ 每条都要单独挣到红。
 *
 * 锚点失配 = 抛错（探针与源码脱钩，必须重新对准，不许放行）。
 * 用法：node scripts/devops/verify-outbound-remainder-gate.mjs
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
const GATE_REL = 'scripts/check-outbound-remainder-ledger.mjs';
const SANDBOX = resolve(ROOT, '.tmp', `outbound-remainder-gate-${process.pid}`);
const sb = (rel) => resolve(SANDBOX, rel);

const TRADE = 'services/trade-service/src/main/java/com/aicabinet/trade';
const DTO = 'services/common/common-core/src/main/java/com/aicabinet/common/dto';
const ADMIN = 'clients/admin-vue/src';

/** 门禁读取的全部文件：沙箱只拷这些，缺一个就按「拷贝不完整」红。 */
const TARGETS = [
  'docs/COMPETITOR_BENCHMARK.md',
  'services/trade-service/src/main/resources/db/migration/V414__outbound_line_remainder_qty.sql',
  `${TRADE}/domain/WarehouseOutboundLine.java`,
  `${TRADE}/service/WarehouseService.java`,
  `${TRADE}/service/ReplenishmentService.java`,
  `${TRADE}/service/DataConsistencyService.java`,
  `${TRADE}/service/WarehouseMonthlyCloseService.java`,
  `${DTO}/WarehouseOutboundLineDto.java`,
  `${DTO}/WarehouseMonthlyCloseRestockLineDto.java`,
  `${ADMIN}/components/warehouse/WarehouseOutboundsTab.vue`,
  `${ADMIN}/components/warehouse/WarehouseMonthlyCloseTab.vue`,
  `${ADMIN}/views/warehouse/WarehouseView.vue`
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

/** 每条规则一处注入：锚点 → 违规形态 → 期望报出的句子片段。 */
const F = {
  benchmark: TARGETS[0],
  migration: TARGETS[1],
  entity: `${TRADE}/domain/WarehouseOutboundLine.java`,
  warehouse: `${TRADE}/service/WarehouseService.java`,
  replenish: `${TRADE}/service/ReplenishmentService.java`,
  consistency: `${TRADE}/service/DataConsistencyService.java`,
  monthly: `${TRADE}/service/WarehouseMonthlyCloseService.java`,
  lineDto: `${DTO}/WarehouseOutboundLineDto.java`,
  closeDto: `${DTO}/WarehouseMonthlyCloseRestockLineDto.java`,
  outbounds: `${ADMIN}/components/warehouse/WarehouseOutboundsTab.vue`,
  monthlyTab: `${ADMIN}/components/warehouse/WarehouseMonthlyCloseTab.vue`,
  view: `${ADMIN}/views/warehouse/WarehouseView.vue`
};

const INJECTS = [
  {
    name: '台账条目丢失',
    file: F.benchmark,
    from: '### CB-074 ',
    to: '### CB-999 ',
    expect: 'CB-074 条目'
  },
  {
    name: '迁移 COMPETITOR_REF 丢失',
    file: F.migration,
    from: '-- COMPETITOR_REF: CB-074',
    to: '-- COMPETITOR_REF: N/A',
    expect: 'V414 迁移头没有 COMPETITOR_REF: CB-074'
  },
  {
    name: '迁移没加列',
    file: F.migration,
    from: 'remainder_qty INT',
    to: 'shelved_qty INT',
    expect: '没有真的给 warehouse_outbound_line 加 remainder_qty 列'
  },
  {
    name: '实体字段被改名',
    file: F.entity,
    from: 'private int remainderQty;',
    to: 'private int remainderQtyRenamed;',
    expect: '实体上没有 private int remainderQty'
  },
  {
    name: '出库行 DTO 丢字段',
    file: F.lineDto,
    from: '        int remainderQty,',
    to: '        int remainderQtyRenamed,',
    expect: 'WarehouseOutboundLineDto.java 的 record 里缺少 int remainderQty'
  },
  {
    name: '月结 DTO 丢上柜量',
    file: F.closeDto,
    from: '        int shelvedQty,',
    to: '        int shelvedQtyRenamed,',
    expect: 'WarehouseMonthlyCloseRestockLineDto.java 的 record 里缺少 int shelvedQty'
  },
  {
    name: '伪签收回归（MAX_VALUE 哨兵）',
    file: F.warehouse,
    from: '    private static String skuKey(String skuId) {',
    to: '    private static int fakeReceiptQty() {\n        return Integer.MAX_VALUE;\n    }\n\n    private static String skuKey(String skuId) {',
    expect: 'Integer.MAX_VALUE'
  },
  {
    name: '实收不再被领货量钳制',
    file: F.warehouse,
    from: '                received = Math.min(left, shipped);',
    to: '                received = left;',
    expect: '实收不再被领货量钳制'
  },
  {
    name: '差额没写回行',
    file: F.warehouse,
    from: '            line.setRemainderQty(shipped - received);',
    to: '            // 差额不再落行（伪全量签收）',
    expect: '差额没写回行上'
  },
  {
    name: '行状态一律盖成 RECEIVED',
    file: F.warehouse,
    from: '            line.setHandoverStatus(received >= shipped ? RECEIVED : PARTIAL);',
    to: '            line.setHandoverStatus(RECEIVED);',
    expect: '行状态不再按实收分满量/未满'
  },
  {
    name: '差额补记柜机库存（违铁律 78）',
    file: F.warehouse,
    from:
      '            line.setHandoverStatus(received >= shipped ? RECEIVED : PARTIAL);\n' +
      '            outboundLineRepository.save(line);\n        }',
    to:
      '            line.setHandoverStatus(received >= shipped ? RECEIVED : PARTIAL);\n' +
      '            outboundLineRepository.save(line);\n' +
      '            inventoryLotService.addRestock(deviceId, line.getSkuId(), shipped - received);\n        }',
    expect: '把差额补记进柜机违反铁律 78'
  },
  {
    name: '差额又写库存流水（违铁律 79）',
    file: F.warehouse,
    from:
      '        List<WarehouseOutboundLine> allLines = outboundLineRepository.findByOutboundIdOrderByLineIdAsc(outboundId);\n' +
      '        boolean allReceived = allLines.stream().allMatch(line -> RECEIVED.equals(line.getHandoverStatus()));',
    to:
      '        movementRepository.insert(buildShipMovement(outboundId, deviceId));\n' +
      '        List<WarehouseOutboundLine> allLines = outboundLineRepository.findByOutboundIdOrderByLineIdAsc(outboundId);\n' +
      '        boolean allReceived = allLines.stream().allMatch(line -> RECEIVED.equals(line.getHandoverStatus()));',
    expect: '出现了库存流水写入'
  },
  {
    name: '签收分支改按实收合计判空',
    file: F.replenish,
    from: '        receiveHandoverPair(task.getOutboundId(), task.getDeviceId(), receivedQtyBySkuOf(taskLines));',
    to: '        if (appliedRestockQty > 0) {\n            receiveHandoverPair(task.getOutboundId(), task.getDeviceId(), receivedQtyBySkuOf(taskLines));\n        }',
    expect: '整单入柜的条件又变成「实收合计 > 0」'
  },
  {
    name: '逐项签收入口消失',
    file: F.replenish,
    from: '        receiveHandoverPair(task.getOutboundId(), task.getDeviceId(), receivedQtyBySkuOf(taskLines));',
    to: '        warehouseService.markDeviceHandoverReceivedFull(task.getOutboundId(), task.getDeviceId());',
    expect: '不再走 receiveHandoverPair / receiveHandoverPairFull 这一对入口'
  },
  {
    name: 'PARTIAL 又被当卡单',
    file: F.consistency,
    from: "WHERE l.handover_status = 'IN_TRANSIT' ",
    to: "WHERE l.handover_status IN ('IN_TRANSIT', 'PARTIAL') ",
    expect: 'PARTIAL 又被当成卡单上报'
  },
  {
    name: '月结上柜列退回领货量',
    file: F.monthly,
    from: '                    acc.qty - acc.remainderQty,',
    to: '                    acc.qty,',
    expect: '月结的上柜件数不再是 qty − remainderQty'
  },
  {
    name: '出库明细列名换掉（差额不可见）',
    file: F.outbounds,
    from: '<span>领而未上</span>',
    to: '<span>其他</span>',
    expect: '出库明细页没有「领而未上」列'
  },
  {
    name: '月结下钻不再渲染上柜量',
    // 🔴 2026-11-10 P2-1 修复把 `{{ row.shelvedQty ?? row.qty }}` 改成了 `{{ shelvedQtyText(row) }}`
    // （兜底方向反了：字段缺失时把领货量当上柜量）。锚点随之对准新形态：把模板里的
    // shelvedQtyText 调用直接换成 row.qty，应触发升级后的判据（模板不再调 shelvedQtyText）。
    file: F.monthlyTab,
    from: '{{ shelvedQtyText(row) }}',
    to: '{{ row.qty }}',
    expect: '月结下钻不再渲染 shelvedQty'
  },
  {
    name: '月结文案口径与代码脱钩',
    file: F.view,
    from: '实盘、领货件数（领货=从本仓带出的量',
    to: '实盘、上柜件数（上柜=进柜的量',
    expect: '月结页的提示文案不再说明「领货」口径'
  }
];

/**
 * 沙箱清理：交给子进程递归删除并在子进程内关掉 safe-delete 补丁（同 defect-class-gate 的处置）——
 * 被删的只有本脚本自己的 .tmp/pid 目录，逐文件 unlink 会把每个文件送回收站、慢到 preflight 不可接受。
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
// 🔴 md5 快照必须在**注入之前**取：放在末尾取就变成「注入前 vs 注入前」自比，
// 真被写脏也照样绿（零污染断言失去意义）。
const hashesBefore = TARGETS.map((rel) => [rel, md5(resolve(ROOT, rel))]);

function check(label, ok, detail) {
  if (!ok) failures.push(`${label}：${detail}`);
}

await prepare();

try {
  // ⓪ 基线必须绿
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
  // ③ 零污染：真实树逐文件 md5 与注入前一致 + 沙箱已删（抛错也要清，别把沙箱留在 .tmp）
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
  console.error(`[verify-outbound-remainder-gate] FAIL:\n  ${failures.join('\n  ')}\n`);
  process.exit(1);
}
console.log(
  `[verify-outbound-remainder-gate] OK：基线绿 + ${INJECTS.length} 条判据逐条注入红并还原绿；` +
    `真实树 ${TARGETS.length} 个文件 md5 前后一致、沙箱已清理`
);
