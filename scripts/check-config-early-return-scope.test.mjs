#!/usr/bin/env node
/**
 * `check-config-early-return-scope.mjs` 的负向用例：闸门必须**能红**（S8④ 自证）。
 *
 *   node scripts/check-config-early-return-scope.test.mjs
 */
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  extractMethodBodies,
  scanMethodBody,
  stripCommentsAndStrings,
  evaluate
} from './check-config-early-return-scope.mjs';

const TAG = '[check-config-early-return-scope.test]';
let checks = 0;
function assert(cond, message) {
  checks++;
  if (!cond) throw new Error(`${TAG} 断言失败：${message}`);
}
const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const scan = (src) =>
  extractMethodBodies(stripCommentsAndStrings(src)).flatMap((b) => scanMethodBody(b.src));

// 1) R22-6 缺陷本体（23c57e09 修前形态）：配置零值早退把独立巡检腿搭车关掉 ⇒ 必须红
{
  const defect = `public int autoCancelExpired() {
    int hours = systemConfigService.getInt(SystemConfigService.UNPAID_AUTO_CANCEL_HOURS, 48);
    if (hours <= 0) { return 0; }
    int n = mapper.expireOlderThan(hours);
    try { alertStaleChargePendingOps(); } catch (Exception ex) { log.warn("x", ex); }
    return n;
  }`;
  const hits = scan(defect);
  assert(hits.length === 1, `R22-6 缺陷形态应命中 1，实际 ${hits.length}`);
  assert(hits[0].guard === 'hours <= 0', `守卫条件应原样记录：${hits[0].guard}`);
  assert(hits[0].call.includes('alertStaleChargePendingOps'), `应点名搭车调用：${hits[0].call}`);
}

// 2) R22-6 修后正样本（独立腿挪到早退之前）⇒ 不红
{
  const fixed = `public int autoCancelExpired() {
    try { alertStaleChargePendingOps(); } catch (Exception ex) { log.warn("x", ex); }
    int hours = systemConfigService.getInt(SystemConfigService.UNPAID_AUTO_CANCEL_HOURS, 48);
    if (hours <= 0) { return 0; }
    int n = mapper.expireOlderThan(hours);
    return n;
  }`;
  assert(scan(fixed).length === 0, `修后形态不应红：${JSON.stringify(scan(fixed))}`);
}

// 3) 布尔开关整体门（0/关=整个方法就干这一件事）⇒ 不红
{
  const wholeGate = `public void sweep() {
    if (!enabled) { return; }
    notifier.notifyDeviceOffline("D1");
  }`;
  assert(scan(wholeGate).length === 0, `布尔整体门不应红`);
}

// 4) CAS 行数判据不是配置门（ReplenishmentTimeoutScheduler.expireOne 实测误报形态）：
//    STATUS_IN_PROGRESS 是业务常量，没有 config 接收者 ⇒ 不红
{
  const cas = `private boolean expireOne(ReplenishmentTask task) {
    String note = "签到超过" + STALE_CHECK_IN_HOURS + "小时未完成";
    int rows = taskRepository.cancelIfStatus(taskId, STATUS_IN_PROGRESS,
        TextTruncate.safeTruncate(mergedNotes, 256));
    if (rows <= 0) { return false; }
    sessionService.closeRestockSessionsForTask(taskId, "x");
    opsExceptionService.report("RESTOCK_TASK_TIMEOUT", "HIGH", refs, "补货任务超时", note);
    return true;
  }`;
  assert(
    scan(cas).length === 0,
    `CAS 行数判据不应红（裸常量非配置证据）：${JSON.stringify(scan(cas))}`
  );
}

// 5) else 链不算被短路：`else { notify… }` 里的是条件不满足时的正常路径
{
  const withElse = `public int run(int hours) {
    int hours = systemConfigService.getInt(SOME_CONFIG_KEY_HOURS, 48);
    if (hours <= 0) { return 0; } else { return -1; }
  }`;
  assert(scan(withElse).length === 0, `else 分支形态不应红`);
}

// 6) 注释里贴反例形态不得假红
{
  const commented = `public int run() {
    // 历史（R22-6 前）：if (hours <= 0) { return 0; } 之后才 alertStaleChargePendingOps();
    int hours = systemConfigService.getInt(KEY_HOURS_VALUE, 48);
    if (hours <= 0) { return 0; }
    alertStaleChargePendingOps();
    return 0;
  }`;
  // 注意：真实代码里 if 与 alert 都在，这条只验注释不计数——命中的是活代码那个 if
  const hits = scan(commented);
  assert(hits.length === 1, `活代码命中 1（注释形态不额外计数），实际 ${hits.length}`);
}

// 7) evaluate：命中被豁免覆盖 ⇒ 绿；未覆盖 ⇒ 红；陈旧豁免条目 ⇒ 红（双向棘轮）
{
  const findings = [{ file: 'A.java', guard: 'hours <= 0', call: 'alertX(' }];
  assert(
    evaluate(findings, [{ file: 'A.java', guard: 'hours <= 0', why: 'w' }]).length === 0,
    '覆盖后应绿'
  );
  const uncovered = evaluate(findings, []);
  assert(
    uncovered.length === 1 && uncovered[0].includes('R22-6 形态'),
    `未覆盖须红：${uncovered.join('|')}`
  );
  const stale = evaluate([], [{ file: 'A.java', guard: 'hours <= 0', why: 'w' }]);
  assert(stale.length === 1 && stale[0].includes('陈旧'), `陈旧豁免须红：${stale.join('|')}`);
}

// 8) 真实仓面：allowlist 两条必须仍与真实命中一一对应（改名/挪腿都会在这里红）
{
  const registry = JSON.parse(
    readFileSync(resolve(ROOT, 'scripts/config-early-return-allowlist.json'), 'utf8')
  );
  const presence = readFileSync(
    resolve(
      ROOT,
      'services/trade-service/src/main/java/com/aicabinet/trade/service/DevicePresenceService.java'
    ),
    'utf8'
  );
  const hits = scan(presence).map((h) => ({
    file: 'services/trade-service/src/main/java/com/aicabinet/trade/service/DevicePresenceService.java',
    ...h
  }));
  assert(
    hits.length === registry.entries.length,
    `真实命中 ${hits.length} 应与豁免 ${registry.entries.length} 相等`
  );
  assert(evaluate(hits, registry.entries).length === 0, `真实命中应被豁免全量覆盖`);
  assert(
    registry.maxEntries === registry.entries.length,
    '基线钉死：条目数必须等于 maxEntries（收紧后同步降 maxEntries）'
  );
}

console.log(`${TAG} OK：${checks} 项断言全过`);
