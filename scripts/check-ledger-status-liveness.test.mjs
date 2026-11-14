#!/usr/bin/env node
/**
 * `check-ledger-status-liveness.mjs` 的负向夹具（S8③/S8④）。
 *
 * 判据类门禁必须能红。这里喂**内存里的假台账**（evaluate 接受 readDoc/registry 注入，
 * 不碰真实文档 ⇒ 与台账内容解耦），逐条钉：
 *   R1 同键状态冲突能红；R2 死锚点能红；
 *   以及四道误报控制各自「不该红的时候确实不红」：「」引用的旧状态、~~删除线~~、
 *   对端主语分句（「设备侧未实现」≠ 我方未接）、`X.java` 类名不参与能力键；
 *   外加固件侧成员跳过、豁免条目陈旧必须销账、maxEntries 只减不增。
 *
 *   node scripts/check-ledger-status-liveness.test.mjs
 */
import { evaluate } from './check-ledger-status-liveness.mjs';

const TAG = '[check-ledger-status-liveness.test]';
let checks = 0;
function assert(cond, msg) {
  checks++;
  if (!cond) throw new Error(`${TAG} 断言失败：${msg}`);
}
function mk(docs) {
  return (rel) => (rel in docs ? docs[rel] : null);
}
function probs(docs, registry) {
  return evaluate(Object.keys(docs), mk(docs), registry || { maxEntries: 0, anchors: [] }).problems;
}

const REAL = 'scripts/check-ledger-status-liveness.mjs';
const D = 'docs/Fake.md';

// 1) R1：同一端点路径一行说已接、一行说未接 ⇒ 必须红
const conflict = {
  [D]: [
    '| §1 拉取视频 | `/jiangyi/api/order/videos` | ✅ 已接 |',
    '| §2 别的路径 | `/jiangyi/api/order/nope` | ✅ 已接 |',
    '| §3 同一个端点 | `/jiangyi/api/order/videos` | ❌ 未接 |'
  ].join('\n')
};
const c1 = probs(conflict);
assert(
  c1.some((p) => p.includes('能力键「/jiangyi/api/order/videos」')),
  `同键冲突必须点名该键：${c1}`
);

// 2) R2：以「已做」语气引用本仓不存在的类 ⇒ 必须红
const dead = { [D]: '| §4 | ✅ 已落地 `com.aicabinet.nope.NeverThere.java:12` |' };
assert(
  probs(dead).some((p) => p.includes('锚点已死或从未存在')),
  '死锚点必须红（说做了、证据不在）'
);

// 3) 正例：锚点真实存在 ⇒ 不红（同时证明第 2 条不是「一律红」）
const live = { [D]: `| §5 | ✅ 已落地 \`${REAL}\`（本夹具自身） |` };
assert(probs(live).length === 0, `真实锚点不该红：${probs(live)}`);

// 4) 误报控制：「」里的旧状态、~~删除线~~、对端主语分句都不算当前状态
const quoted = {
  [D]: [
    '| §6 | ✅ 已接（旧记「❌ 未接」已过期） |',
    '| §7 | ~~❌ 未接~~ 已作废 |',
    '| §8 | 🟡 已实现我方下发端；设备侧未实现该可选接口 |'
  ].join('\n')
};
assert(probs(quoted).length === 0, `引用/作废/对端陈述不得判成自相矛盾：${probs(quoted)}`);

// 5) 能力键只认端点与成员：同一 Controller 文件服务两个端点不算冲突
const byClass = {
  [D]: [
    '| §9 | `OrderVideoReportController.java` | ✅ 已接 `/a/one` |',
    '| §10 | `OrderVideoReportController.java` | ❌ 未接 `/a/two` |'
  ].join('\n')
};
assert(probs(byClass).length === 0, `按类名聚合会把不同端点误判成矛盾：${probs(byClass)}`);

// 6) 固件侧成员锚点跳过（那些类在厂商固件里，不拿本仓目录树要求别人）
const firmware = {
  [D]: `| §11 | ✅ 已实测，固件 \`DealSocketMsg.uploadDoorState\` 0 处分支 |`
};
assert(probs(firmware).length === 0, `固件成员不该按本仓存在性判红：${probs(firmware)}`);

// 7) 台账文件读不到 ⇒ 红（防「清单过期→扫描面为空→假绿」）
assert(
  probs({ 'docs/Gone.md': null }).some((p) => p.includes('读不到')),
  '台账缺失必须红'
);

// 8) 豁免：在册锚点确实失效时放行，但条目内容与文档不符（已修却没销账）必须红
const registry = { maxEntries: 1, anchors: [{ doc: D, anchor: 'GhostService.java:1' }] };
const deadRegistered = { [D]: '| §13 | ✅ 已落地 `GhostService.java:1`（类已删，见登记理由） |' };
assert(probs(deadRegistered, registry).length === 0, '已登记且命中的陈旧锚点应放行');
assert(
  probs({ [D]: '| §12 | ✅ 已落地 |' }, registry).some((p) => p.includes('必须销掉')),
  '豁免条目对应的死锚点已不在文档里 ⇒ 必须红（免检区不能留在册上）'
);
assert(
  probs(live, { maxEntries: 0, anchors: [{ doc: D, anchor: 'GhostService.java:1' }] }).some((p) =>
    p.includes('只许减不许增')
  ),
  '登记数超过 maxEntries 必须红（双向棘轮）'
);

console.log(`${TAG} OK：${checks} 项断言全过`);
