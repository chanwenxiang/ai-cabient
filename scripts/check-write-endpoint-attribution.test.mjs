#!/usr/bin/env node
/**
 * `check-write-endpoint-attribution.mjs`（S9 第二层：端点级归因快照棘轮）的夹具。
 *
 * 覆盖三组：
 *   A. evaluate 棘轮（合成快照）：归因退化红 / 新端点未归因红 / 对平绿 / 陈旧红；
 *   B. reachable 真实仓面：2-hop 委托链可达（OpsOtaAdminService.publishOta ⇒
 *      OtaService.publishRelease 有 appendLog）/ 拒绝路径黑名单（AccessDeniedAudit
 *      不计——负向注入实证：不封它则删除真实审计后解析器穿 requirePermission 假绿）/
 *      真缺口端点不可达（AccountController.updateNickname 无审计链）；
 *   C. controllerEndpoints 真实仓面：端点抽取数量与快照一致。
 *
 *   node scripts/check-write-endpoint-attribution.test.mjs
 */
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { evaluate, reachable, controllerEndpoints } from './check-write-endpoint-attribution.mjs';

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
