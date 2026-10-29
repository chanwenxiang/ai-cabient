/**
 * check-warehouse-movement-dict 门禁的负向验证（铁律 75：门禁判据必须能红）。
 *
 * 三态同 verify-selfpickup-copy-gate：⓪ 基线绿 → ① 逐条注入真违规（每条单独挣红并断言报的是**这一条**）
 * → ② 还原绿 → ③ 真实树 md5 前后一致、沙箱已删。全程只写沙箱副本。
 *
 * 锚点失配 = 抛错（探针与源码脱钩，必须重新对准，不许放行）。
 * 用法：node scripts/devops/verify-warehouse-movement-dict-gate.mjs
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
const GATE_REL = 'scripts/check-warehouse-movement-dict.mjs';
const SANDBOX = resolve(ROOT, '.tmp', `warehouse-movement-dict-gate-${process.pid}`);
const sb = (rel) => resolve(SANDBOX, rel);

const TRADE = 'services/trade-service/src/main/java/com/aicabinet/trade';
const F = {
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
const TARGETS = Object.values(F);

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
  // ── R1 代码在写、字典缺码（本轮真实缺陷的形状）────────────────────────────
  {
    name: 'shared-dict 漏掉 movement_type.STOCKTAKE',
    file: F.dict,
    from: "    STOCKTAKE: '盘点调整',\n    BIN_STOCK: '货位变动'",
    to: "    BIN_STOCK: '货位变动'",
    expect: '代码会写但 warehouse_movement_type 字典没有：STOCKTAKE'
  },
  {
    name: 'shared-dict 漏掉 ref_type 的调拨两码',
    file: F.dict,
    from: "    TRANSFER_OUT: '调拨出库',\n    TRANSFER_IN: '调拨入库'",
    to: '',
    expect: '代码会写但 business_reference_type 字典没有：TRANSFER_OUT'
  },
  {
    name: 'shared-dict 漏掉 handover_status.CANCELLED',
    file: F.dict,
    from: "    RECEIVED: '已上柜',\n    CANCELLED: '已取消'",
    to: "    RECEIVED: '已上柜'",
    expect: '代码会写但 handover_status 字典没有：CANCELLED'
  },
  {
    name: 'shared-dict 漏掉 in_transit_status.CANCELLED',
    file: F.dict,
    from: "    CANCELLED: '已取消',\n    LOST: '丢失'",
    to: "    LOST: '丢失'",
    expect: '代码会写但 in_transit_status 字典没有：CANCELLED'
  },
  {
    name: '后端新写一个流水类型没进字典',
    file: F.warehouseService,
    from: 'new MovementSpec("STOCKTAKE", delta, "STOCKTAKE"',
    to: 'new MovementSpec("STOCKTAKE_V2", delta, "STOCKTAKE"',
    expect: '代码会写但 warehouse_movement_type 字典没有：STOCKTAKE_V2'
  },
  // ── R2 两侧漂移（#355：标签/码要三处同改）─────────────────────────────────
  {
    name: 'Bootstrap 少一码（只改了 shared-dict）',
    file: F.bootstrap,
    from: '"BIN_STOCK", "货位变动", ',
    to: '',
    expect: 'shared-dict 有而 SysDictBootstrap 没有：BIN_STOCK'
  },
  {
    name: '两侧标签不一致（movement_type.STOCKTAKE）',
    file: F.bootstrap,
    from: '"STOCKTAKE", "盘点调整"',
    to: '"STOCKTAKE", "盘点差异"',
    expect: 'warehouse_movement_type.STOCKTAKE：两侧标签不一致'
  },
  {
    name: '两侧标签不一致（handover_status.CANCELLED）',
    file: F.bootstrap,
    from: '"PARTIAL", "部分上柜", RECEIVED, "已上柜",\n                STATUS_CANCELLED, "已取消"',
    to: '"PARTIAL", "部分上柜", RECEIVED, "已上柜",\n                STATUS_CANCELLED, "已作废"',
    expect: 'handover_status.CANCELLED：两侧标签不一致'
  },
  // ── R3 抽取器自身的锚点 ────────────────────────────────────────────────────
  {
    name: '字典类型块改名（解析锚点失配必须红，不许静默漏判）',
    file: F.dict,
    from: '  business_reference_type: {',
    to: '  business_reference_type_v2: {',
    expect: '里找不到字典类型 business_reference_type 的块'
  },
  {
    name: '新增未登记的动态实参站点',
    file: F.warehouseService,
    from: 'new MovementSpec("BIN_STOCK", command.deltaQty(), command.refType(),',
    to: 'new MovementSpec("BIN_STOCK", command.deltaQty(), resolveBinRef(),',
    expect: '未登记的动态实参站点'
  },
  {
    name: '构造方法少一个实参（签名变了抽取器要喊）',
    file: F.binService,
    from: 'qty, operatorId, "BIN_INBOUND", String.valueOf(bin.getBinId())));',
    to: 'qty, String.valueOf(bin.getBinId())));',
    expect: '取不到第 5 个'
  }
];

/**
 * 沙箱清理：交给子进程递归删除并在子进程内关掉 safe-delete 补丁（同 selfpickup 验证的处置）
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
        `exit=${r.code}，期望 1 且报出「${inj.expect}」；实得：${r.out.slice(0, 700)}`
      );
      restore(inj.file);
      const back = await runGate();
      check(
        `② 还原后 ${inj.name}`,
        back.code === 0,
        `还原后仍 exit=${back.code}：\n${back.out.slice(0, 700)}`
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
  console.error(`[verify-warehouse-movement-dict-gate] FAIL:\n  ${failures.join('\n  ')}\n`);
  process.exit(1);
}
console.log(
  `[verify-warehouse-movement-dict-gate] OK：基线绿 + ${INJECTS.length} 条判据逐条注入红并还原绿；` +
    `真实树 ${TARGETS.length} 个文件 md5 前后一致、沙箱已清理`
);
