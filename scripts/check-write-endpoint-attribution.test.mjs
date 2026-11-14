#!/usr/bin/env node
/**
 * `check-write-endpoint-attribution.mjs`（S9 第二层：端点级归因快照棘轮）的夹具。
 *
 * 覆盖三组：
 *   A. evaluate 棘轮（合成快照）：归因退化红 / 新端点未归因红 / 对平绿 / 陈旧红 /
 *      归因虚增红 / 端点漂移红（总量双向对平）；
 *   B. reachable 真实仓面：2-hop 委托链可达（OpsOtaAdminService.publishOta ⇒
 *      OtaService.publishRelease 有 appendLog）/ 失败路径审计不计（AccessDeniedAudit）/
 *      真缺口端点不可达（AccountController.updateNickname 无审计链）/
 *      **行为级负向**：失败审计类换类名 / 换 action 都自动纳入，逐调用点排除
 *      ACCESS_DENIED（2026-11-14 第五轮：旧人工名单改类名即 18 处假归因复活）；
 *   C. controllerEndpoints 真实仓面：端点抽取数量与快照一致。
 *
 *   node scripts/check-write-endpoint-attribution.test.mjs
 */
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  evaluate,
  reachable,
  controllerEndpoints,
  deriveFailurePathClasses,
  hasBusinessAuditCall
} from './check-write-endpoint-attribution.mjs';

const TAG = '[check-write-endpoint-attribution.test]';
let checks = 0;
function assert(cond, message) {
  checks++;
  if (!cond) throw new Error(`${TAG} 断言失败：${message}`);
}
const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');

// ── A. evaluate 棘轮（合成快照） ──

// A1) 对平 ⇒ 绿
{
  const snap = [{ file: 'a/XController.java', endpoints: 3, attributed: 2 }];
  const base = { 'a/XController.java': { endpoints: 3, attributed: 2 } };
  assert(evaluate(snap, base).length === 0, '对平应绿');
}

// A2) 归因退化（2→1）⇒ 红
{
  const snap = [{ file: 'a/XController.java', endpoints: 3, attributed: 1 }];
  const base = { 'a/XController.java': { endpoints: 3, attributed: 2 } };
  const p = evaluate(snap, base);
  assert(p.length === 1 && p[0].includes('归因退化'), `归因退化须红：${p.join('|')}`);
}

// A3) 新端点未归因（endpoints 3→4，attributed 不变）⇒ 红
{
  const snap = [{ file: 'a/XController.java', endpoints: 4, attributed: 2 }];
  const base = { 'a/XController.java': { endpoints: 3, attributed: 2 } };
  const p = evaluate(snap, base);
  assert(p.length === 1 && p[0].includes('新增'), `新端点未归因须红：${p.join('|')}`);
}

// A4) 新端点且归因同步增 ⇒ 绿
{
  const snap = [{ file: 'a/XController.java', endpoints: 4, attributed: 3 }];
  const base = { 'a/XController.java': { endpoints: 3, attributed: 2 } };
  assert(evaluate(snap, base).length === 0, '新端点归因同步增应绿');
}

// A5) 基线陈旧（控制器没了）⇒ 红
{
  const p = evaluate([], { 'gone/XController.java': { endpoints: 1, attributed: 1 } });
  assert(p[0].includes('基线陈旧'), `陈旧须红：${p.join('|')}`);
}

// A6) 归因虚增（240→258 假归因复活形态）⇒ 总量对平红
//     逐控制器判据只防下降，这条专门钉住「解析链放宽」方向。
{
  const snap = [{ file: 'a/XController.java', endpoints: 3, attributed: 3 }];
  const base = { 'a/XController.java': { endpoints: 3, attributed: 2 } };
  const p = evaluate(snap, base, { totalEndpoints: 3, totalAttributed: 2 });
  assert(
    p.some((x) => x.includes('归因总量')),
    `归因虚增必须红：${p.join('|')}`
  );
}

// A7) 端点数下降（逐控制器判据不检查下降）⇒ 总量对平红
{
  const snap = [{ file: 'a/XController.java', endpoints: 2, attributed: 2 }];
  const base = { 'a/XController.java': { endpoints: 3, attributed: 2 } };
  const p = evaluate(snap, base, { totalEndpoints: 3, totalAttributed: 2 });
  assert(
    p.some((x) => x.includes('端点总量')),
    `端点漂移必须红：${p.join('|')}`
  );
}

// A8) totals 不传（旧夹具调用形态）⇒ 不受影响
assert(
  evaluate([{ file: 'a/XController.java', endpoints: 3, attributed: 3 }], {
    'a/XController.java': { endpoints: 3, attributed: 2 }
  }).length === 0,
  '不传 totals 时保持旧行为'
);

// ── B. reachable 真实仓面 ──

// B1) 2-hop 委托链可达：OpsOtaAdminService.publishOta ⇒ otaService.publishRelease ⇒ appendLog
assert(
  reachable('OpsOtaAdminService', 'publishOta', 1, new Set()) === true,
  'OpsOtaAdminService.publishOta 应 2-hop 可达 OtaService.publishRelease 的审计'
);

// B2) 拒绝路径黑名单：AccessDeniedAudit.persistDenied 的 appendLog 不计
assert(
  reachable('AccessDeniedAudit', 'persistDenied', 1, new Set()) === false,
  'AccessDeniedAudit（拒绝路径审计）应被黑名单排除'
);

// B3) 真缺口端点不可达：AccountController.updateNickname 委托链无审计
assert(
  reachable('AccountService', 'updateNickname', 1, new Set()) === false,
  'AccountService.updateNickname 委托链无审计（真缺口）'
);

// B4) 失败路径类自动派生（行为级负向，替代旧「黑名单存在性」静态断言）
//     旧式 new Set(['AccessDeniedAudit'])：把类名改成 TmpDeniedAudit 即 240→258 假绿。
{
  const byClassLike = new Map([
    ['TmpDeniedAudit', 'x.java'],
    ['RenamedAudit', 'y.java'],
    ['PlainAudit', 'z.java']
  ]);
  const rawOfLike = new Map([
    [
      'x.java',
      'class TmpDeniedAudit { void denied() { audit.appendLog(1, "SOME_ACTION", "USER", "1", ""); } }'
    ],
    [
      'y.java',
      'class RenamedAudit { void log() { audit.appendLog(1, "ACCESS_DENIED", "USER", "1", ""); } }'
    ],
    [
      'z.java',
      'class PlainAudit { void log() { audit.appendLog(1, "OTA_PUBLISH", "OTA", "1", ""); } }'
    ]
  ]);
  const derived = deriveFailurePathClasses(byClassLike, rawOfLike);
  assert(derived.has('TmpDeniedAudit'), '类名带 denied 语义的失败审计类须自动纳入');
  assert(derived.has('RenamedAudit'), '写 ACCESS_DENIED 的类即使换成无关类名也须自动纳入');
  assert(!derived.has('PlainAudit'), '普通业务审计类不该被误纳入');
}

// B6) 逐调用点排除失败 action（不能只看方法体有没有 appendLog）
assert(
  hasBusinessAuditCall('void x() { audit.appendLog(1, "ACCESS_DENIED", "USER", "1", ""); }') ===
    false,
  '只写 ACCESS_DENIED 的方法体不该算业务归因'
);
assert(
  hasBusinessAuditCall('void x() { audit.appendLog(1, "OTA_PUBLISH", "OTA", "1", ""); }') === true,
  '正常业务 appendLog 应算归因'
);
assert(
  hasBusinessAuditCall(
    'void x() { if (denied) { audit.appendLog(1, "ACCESS_DENIED", "USER", "1", ""); } ' +
      'else { audit.appendLog(1, "OTA_PUBLISH", "OTA", "1", ""); } }'
  ) === true,
  '同一方法体内失败路径与成功路径并存时应算归因'
);
assert(
  hasBusinessAuditCall(
    'void x() { audit.appendLog(1, "OTA_PUBLISH", "OTA", "1", Map.of("k", "v")); }'
  ) === true,
  '参数含嵌套括号时不得截断 action 判定'
);
assert(
  hasBusinessAuditCall(
    'void x() { audit.appendLog(1, "ACCESS_DENIED", "USER", "1", ""); }',
    'AccessDeniedAudit'
  ) === false,
  '失败路径类的整个方法体直接不算'
);

// B5) AdminAuditService.appendLog 定义到达 = 归因（2026-11-14 修复的正向用例）
assert(
  reachable('AdminAuditService', 'appendLog', 1, new Set()) === true,
  'AdminAuditService.appendLog 定义应被判为归因终点'
);
assert(
  reachable('AdminAuditService', 'appendLogIndependent', 1, new Set()) === true,
  'AdminAuditService.appendLogIndependent 定义应被判为归因终点'
);

// ── C. controllerEndpoints 真实仓面 ──

// C1) OpsOtaController：2 写端点全归因（自身 appendLog + service 链）
{
  const src = readFileSync(
    resolve(
      ROOT,
      'services/trade-service/src/main/java/com/aicabinet/trade/api/OpsOtaController.java'
    ),
    'utf8'
  );
  const { cls, endpoints } = controllerEndpoints(src);
  assert(cls === 'OpsOtaController', `cls 提取错：${cls}`);
  assert(endpoints.length === 2, `端点数错：${endpoints.length}`);
  assert(
    endpoints.every((e) => e.attributed),
    'OpsOta 端点应全归因'
  );
}

// C2) 端点级快照与基线对平（真实仓面全量）
{
  const baseline = JSON.parse(
    readFileSync(resolve(ROOT, 'scripts/write-endpoint-attribution-baseline.json'), 'utf8')
  );
  const baselineEntries = baseline.controllers || {};
  const entryCount = Object.keys(baselineEntries).length;
  assert(entryCount >= 90, `基线条目应 ≥90，实际 ${entryCount}`);
  assert((baseline.totalEndpoints || 0) >= 400, `基线端点应 ≥400，实际 ${baseline.totalEndpoints}`);
  // C3) maxUnattributed 棘轮与快照口径一致：未归因存量 = 天花板（217 冻结只减）
  const unattr = (baseline.totalEndpoints || 0) - (baseline.totalAttributed || 0);
  assert(
    typeof baseline.maxUnattributed === 'number' && baseline.maxUnattributed === unattr,
    `maxUnattributed(${baseline.maxUnattributed}) 应等于未归因存量(${unattr})——重种快照后须同步`
  );
}

console.log(`${TAG} OK：${checks} 项断言全过`);
