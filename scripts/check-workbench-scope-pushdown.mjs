#!/usr/bin/env node
/**
 * 工作台待办取数门禁：**设备范围必须下推进 SQL，禁止「取全局窗口 → 内存过滤」**。
 *
 * 缺陷类（B13 / 2026-11-10 四端审核 R2 同族，已在仓里复现多次）：
 *   `repo.findByStatusOrderByCreatedAtDesc("OPEN", CAP)` 先按 createdAt **全局**取前 CAP 条，
 *   再 `filter(inDeviceScope(...))`。域受限账号（分仓 / 线长 / 单商户）自己的待办排在窗口之外
 *   ⇒ 面板显示「没有待办」，工单却实实在在挂着。CAP 越小越致命（运营端 CAP=20）。
 *
 * 更隐蔽的一层：**计数**也用同款窗口。`countOpenDisputes` 曾写「取 500 条样本再内存 count」，
 * OPEN 工单超过 500 条时**数字直接封顶** —— 面板上的数字本身就是错的，比列表少几条更误导。
 *
 * 正确形态：过滤条件下推到 Mapper（`device_id IN (…)` / `inSql` / `JOIN shopping_session`），
 * `LIMIT` 落在过滤**之后**；「每状态 Top-N」的形态要保留（范围进 WHERE，N 进 LIMIT）。
 *
 * 判据：
 *   1. 两个工作台服务里 `inDeviceScope(` 必须**零出现**（源码掩掉注释后扫）：
 *      出现即说明某处又把范围搬回内存过滤。
 *   2. 禁止出现下列「全局窗口取数」调用（名字带 `(`，避免命中同名新方法的 `…AndDeviceIdIn…` 前缀）：
 *      争议/下架 `findByStatusOrderByCreatedAtDesc(`、离线 `findByOnlineStatusNot(`、
 *      超时会话 `findByStateInAndUpdatedAtBefore(`、在途 `findByStatusAndCreatedAtBefore(`、
 *      分账 `findTop20ByStatusOrderByCreatedAtAsc(`、补货 `findByStatusInOrderByCreatedAtAsc(`。
 *   3. 每个服务必须出现对应的**带范围**取数方法名（防「把取数整段删掉」也判绿）。
 *   4. 锚点：两文件掩码后长度不足即红（解析退化时「零命中」是假绿）。
 *   5. 第三个落点（MA-298）：商户门户 `MerchantInventoryPortalService` 形态不同——它**刻意**
 *      保留 `inDeviceScope(` 当二道防线（范围下推 + 内存复核），所以不套判据 1/2，改为钉
 *      「分支顺序」与「无范围入口零调用」：低库存的范围分支必须排在全局窗口分支之前。
 *
 * 注释处理：扫源码前用 `stripJavaComments()` 抹成**等长空格**（保行号）。修复处的说明注释
 * 会引用旧写法当反例，不抹就会自己把自己打红（铁律 84①）。
 *
 *   node scripts/check-workbench-scope-pushdown.mjs
 *   node scripts/check-workbench-scope-pushdown.mjs --root=.tmp/wb-gate-123   # 沙箱负向验证用
 */
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { stripJavaComments } from './check-inmemory-paging.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const TAG = '[check-workbench-scope-pushdown]';

const SERVICE_DIR = join(
  'services',
  'trade-service',
  'src',
  'main',
  'java',
  'com',
  'aicabinet',
  'trade',
  'service'
);
const MERCHANT = join(SERVICE_DIR, 'MerchantWorkbenchQueryService.java');
const OPS = join(SERVICE_DIR, 'OpsWorkbenchQueryService.java');

/** 内存过滤判据：出现即红。 */
const IN_MEMORY_SCOPE = 'inDeviceScope(';

/**
 * 全局窗口取数：「全局按时间取上限 + 内存过滤」的入口，全部已下线。
 *
 * 判据必须带**仓储变量名**（`disputeRepository.` 之类）：同名的 `findByStatusOrderByCreatedAtDesc(`
 * 也出现在 `exceptionRepository`（异常单）上——那是仪表盘上「未处理异常总数」这类平台级指标，
 * 本来就没有设备维度，把它算进来是假阳性（铁律 84③：判据要贴真实责任边界）。
 */
const WINDOWED_CALLS = [
  ['disputeRepository.findByStatusOrderByCreatedAtDesc(', '争议'],
  ['pullOffTaskRepository.findByStatusOrderByCreatedAtDesc(', '临期下架'],
  ['deviceRepository.findByOnlineStatusNot(', '柜机离线'],
  ['sessionRepository.findByStateInAndUpdatedAtBefore(', '购物会话超时'],
  ['inTransitRepository.findByStatusAndCreatedAtBefore(', '在途超时'],
  ['splitRepository.findTop20ByStatusOrderByCreatedAtAsc(', '分账待跟进'],
  ['replenishmentTaskRepository.findByStatusInOrderByCreatedAtAsc(', '补货任务']
];

/** 每个服务必须出现的「带范围」取数入口（正向锚点，防整段删除后假绿）。 */
const REQUIRED_CALLS = {
  [MERCHANT]: [
    ['findByStatusAndDeviceIdsOrderByCreatedAtDesc(', '待审核争议（联表下推）'],
    ['findByDeviceIdInAndOnlineStatusNotOrderByUpdatedAtAsc(', '柜机离线'],
    ['findByStatusAndDeviceIdInOrderByCreatedAtDesc(', '临期/过期下架'],
    ['findWorkbenchTasks(', '补货任务']
  ],
  [OPS]: [
    ['findByStatusAndDeviceIdsOrderByCreatedAtDesc(', '待审核争议（联表下推）'],
    ['findDeployedByDeviceIdInAndOnlineStatusNotOrderByUpdatedAtAsc(', '设备离线（含投放柜口径）'],
    ['findByStateInAndDeviceIdInAndUpdatedAtBefore(', '购物会话超时'],
    ['findByStatusAndCreatedAtBeforeAndDeviceIdInOrderByCreatedAtAsc(', '在途超时'],
    ['findTopNByStatusAndDeviceIdInOrderByCreatedAtAsc(', '分账待跟进'],
    ['findWorkbenchTasks(', '补货任务'],
    ['countOpenByDeviceIds(', '待审核争议计数'],
    ['countDistinctOutboundOverdue(', '在途超时计数']
  ]
};

/** 掩码后最小长度：文件被截断/换错文件时必需方法必然缺失，这里再兜一层。 */
const MIN_MASKED_LENGTH = 8000;

/**
 * 商户门户库存服务的落点（MA-298）。形态与两个工作台服务不同：它**保留** `inDeviceScope(`
 * 当二道防线（范围已下推，再内存复核一次），因此不可套「零出现」，改钉下面两条：
 *
 *   ① **分支顺序**：`listInventory` 里按范围取行（`findByIdDeviceIdIn(`）必须出现在全局低库存
 *      窗口（`findLowStockLimit(`）**之前**。反过来就是 B13 本尊：先按全局取 500 条，再用
 *      `inDeviceScope` 过滤，域受限商户自家的低库存行会被别家的行挤出窗口。（MA-298 实证）
 *   ② **临期告警**用带范围入口 `findByStatusAndDeviceIdInOrderByCreatedAtDesc(`，无范围的那个
 *      同名方法在这个文件里必须零调用（同一个瑕疵的第二处，同批修掉）。
 */
const PORTAL = join(SERVICE_DIR, 'MerchantInventoryPortalService.java');
const PORTAL_SCOPED_FETCH = 'findByIdDeviceIdIn(';
const PORTAL_GLOBAL_WINDOW = 'findLowStockLimit(';
const PORTAL_REQUIRED = [
  [PORTAL_SCOPED_FETCH, '按设备范围取行'],
  ['findByStatusAndDeviceIdInOrderByCreatedAtDesc(', '临期告警（范围下推）']
];
const PORTAL_BANNED = [
  ['pullOffTaskRepository.findByStatusOrderByCreatedAtDesc(', '临期告警（全局窗口）']
];

function read(root, rel) {
  const full = join(root, rel);
  if (!existsSync(full)) return null;
  return readFileSync(full, 'utf8');
}

/** 取 `--root=` 参数（负向验证把副本丢进沙箱跑同一套判据）。 */
export function resolveRoot(argv = process.argv.slice(2)) {
  const arg = argv.find((a) => a.startsWith('--root='));
  return arg ? resolve(arg.slice('--root='.length)) : resolve(HERE, '..');
}

export function checkProject(root) {
  const problems = [];
  const stats = [];

  for (const rel of [MERCHANT, OPS]) {
    const label = rel.split(/[\\/]/).pop().replace('.java', '');
    const raw = read(root, rel);
    if (raw == null) {
      problems.push(`找不到 ${rel.replace(/\\/g, '/')} —— 门禁扫不到目标文件时「零命中」是假绿`);
      continue;
    }
    const src = stripJavaComments(raw);
    if (src.length < MIN_MASKED_LENGTH) {
      problems.push(
        `${label} 掩码后只有 ${src.length} 字符（下限 ${MIN_MASKED_LENGTH}）—— 文件被截断或选错？`
      );
    }

    if (src.includes(IN_MEMORY_SCOPE)) {
      problems.push(
        `${label} 又用 ${IN_MEMORY_SCOPE}…) 在内存里过滤设备范围 —— ` +
          '过滤条件必须下推进 SQL，否则全局窗口会把域受限账号的待办挤出结果集（B13）'
      );
    }

    for (const [call, topic] of WINDOWED_CALLS) {
      if (src.includes(call)) {
        problems.push(
          `${label} 仍调用 ${call}…) 取全局窗口（${topic}）—— ` +
            '该入口只按时间取上限、不含设备范围，域受限账号会看不到自己的待办；改用 Mapper 里带范围的同名方法（B13）'
        );
      }
    }

    for (const [call, topic] of REQUIRED_CALLS[rel] ?? []) {
      if (!src.includes(call)) {
        problems.push(
          `${label} 找不到 ${call}…) —— ${topic}的设备范围取数入口缺失（被删掉或被换回窗口取数）`
        );
      }
    }

    stats.push(`${label}: ${src.length} 字符`);
  }

  // ── MA-298 落点：商户门户库存服务（形态不同，单独一套判据，见 PORTAL 注释）──────────
  const portalLabel = PORTAL.split(/[\\/]/).pop().replace('.java', '');
  const portalRaw = read(root, PORTAL);
  if (portalRaw == null) {
    problems.push(`找不到 ${PORTAL.replace(/\\/g, '/')} —— 门禁扫不到目标文件时「零命中」是假绿`);
  } else {
    const src = stripJavaComments(portalRaw);
    if (src.length < MIN_MASKED_LENGTH) {
      problems.push(
        `${portalLabel} 掩码后只有 ${src.length} 字符（下限 ${MIN_MASKED_LENGTH}）—— 文件被截断或选错？`
      );
    }
    const scopedAt = src.indexOf(PORTAL_SCOPED_FETCH);
    const windowAt = src.indexOf(PORTAL_GLOBAL_WINDOW);
    if (windowAt >= 0 && (scopedAt < 0 || windowAt < scopedAt)) {
      problems.push(
        `${portalLabel} 又回到「先取全局低库存窗口、再内存过滤设备范围」：` +
          `${PORTAL_GLOBAL_WINDOW}…) 排在 ${PORTAL_SCOPED_FETCH}…) 之前 —— ` +
          '范围分支必须在前，否则域受限商户的低库存行会被别家的行挤出窗口（B13 / MA-298）'
      );
    }
    for (const [call, topic] of PORTAL_REQUIRED) {
      if (!src.includes(call)) {
        problems.push(
          `${portalLabel} 找不到 ${call}…) —— ${topic}的范围取数入口缺失（被删掉或被换回窗口取数）`
        );
      }
    }
    for (const [call, topic] of PORTAL_BANNED) {
      if (src.includes(call)) {
        problems.push(
          `${portalLabel} 仍调用 ${call}…) 取全局窗口（${topic}）—— 改用带范围的同名方法（B13 / MA-298）`
        );
      }
    }
    stats.push(`${portalLabel}: ${src.length} 字符`);
  }

  return { ok: problems.length === 0, problems, stats };
}

function main() {
  const root = resolveRoot();
  const { ok, problems, stats } = checkProject(root);
  if (!ok) {
    console.error(`${TAG} FAIL: ${problems.length} 处待办取数仍在内存里过滤设备范围：`);
    for (const p of problems) console.error(`  - ${p}`);
    process.exit(1);
  }
  console.log(
    `${TAG} OK：${stats.join('、')}；两工作台服务设备范围全部下推 SQL（` +
      `${WINDOWED_CALLS.length} 个全局窗口入口零调用、${IN_MEMORY_SCOPE}…) 零出现）、` +
      '商户门户库存的范围分支排在全局窗口之前（MA-298）'
  );
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main();
}
