#!/usr/bin/env node
/**
 * 将邑采集编排链完整性 + 「绝不自动清批次」铁律门禁（A+B 合一，2026-10-27）。
 *
 * 背景（2026-10-26 两次真机事故，lessons 题材）：
 *   ① confirmStart 漏带 identifier → 厂商回「发送确认开始采集指令失败」；
 *   ② uploadInfoNew 还有 wait 时就调 deleteBatchNoNew → 上传被打成孤儿，
 *      靠 §4.3.13 goOnUploadNew 才续回来。
 * 把这两条经验焊成机器可查规则：
 *
 * A 链完整性（JiangyiGatherConsoleService.ConsoleRun.runChain）：
 *   - 十一个编排方法必须按文档顺序在链路里各出现一次：
 *     gatherOpenDoor → doorStatus → gatherProductInfo → gatherStartCheck →
 *     gatherConfirmStart → gatherError → gatherEndOne → gatherProducts →
 *     gatherGoOn → gatherEndAll → uploadInfoNew
 *   - JiangyiGatherClient 的每个编排方法名必须真实存在（防改名后门禁空转）。
 *
 * B 清批次铁律（fail-closed）：
 *   - deleteBatchNoNew 在 trade-service 主源码里的调用点，只允许出现在
 *     JiangyiGatherConsoleService#clearBatch 一个方法体内；
 *   - 厂商客户端方法体里出现「自动清批次」形态（finally / @Scheduled / 定时器回调）
 *     一律 FAIL。
 *
 * 永远 fail-closed：锚点找不到、方法体解析失败、扫描零命中都按失败处理。
 *
 *   node scripts/check-jiangyi-gather-chain.mjs
 */
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-jiangyi-gather-chain]';

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

const CLIENT =
  'services/trade-service/src/main/java/com/aicabinet/trade/client/JiangyiGatherClient.java';
const SERVICE =
  'services/trade-service/src/main/java/com/aicabinet/trade/service/JiangyiGatherConsoleService.java';

if (!existsSync(join(root, CLIENT))) fail(`找不到厂商客户端 ${CLIENT}`);
if (!existsSync(join(root, SERVICE))) fail(`找不到采集台服务 ${SERVICE}`);
const clientSrc = readFileSync(join(root, CLIENT), 'utf8');
const serviceSrc = readFileSync(join(root, SERVICE), 'utf8');

// ── A1：客户端编排方法存在性 ────────────────────────────────────────────
const CHAIN_METHODS = [
  'gatherOpenDoor',
  'gatherProductInfo',
  'gatherStartCheck',
  'gatherConfirmStart',
  'gatherError',
  'gatherEndOne',
  'gatherProducts',
  'gatherGoOn',
  'gatherEndAll',
  'uploadInfoNew',
  'deleteBatchNoNew'
];
for (const name of CHAIN_METHODS) {
  if (!new RegExp(`public\\s+\\S+\\s+${name}\\s*\\(`).test(clientSrc)) {
    fail(`JiangyiGatherClient 缺少编排方法 ${name}（链路锚点被删/改名，采集台会半残）`);
  }
}

// ── A2：链路顺序（runChain 方法体内按序命中）──────────────────────────
function methodBody(src, signature) {
  const sig = src.indexOf(signature);
  if (sig < 0) return null;
  const open = src.indexOf('{', sig);
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

// 链序校验分两层，双层锁定：
// ① runChain 内部调用序（直接 client.* 调用 + 间接 helper 调用混合按序）；
// ② 三个间接 helper（waitDoor/waitReady/shootWindow）体内必须各自包含对应的厂商调用——
//    只锁①时把 waitReady 的厂商调用搬进 runChain 直调也能过（顺序不变），②把间接层钉死。
const runChain =
  methodBody(serviceSrc, 'private void runChain()') ??
  fail('JiangyiGatherConsoleService 找不到 runChain()（链路主方法被删/改名）');
const chainCalls = [
  'gatherOpenDoor',
  'waitDoor',
  'gatherProductInfo',
  'waitReady',
  'gatherConfirmStart',
  'shootWindow',
  'gatherEndOne',
  'gatherProducts',
  'gatherGoOn',
  'gatherEndAll'
];
let cursor = -1;
for (const name of chainCalls) {
  const at = runChain.indexOf(`${name}(`, cursor + 1);
  if (at < 0) {
    fail(`runChain 里缺少 ${name}(（或顺序提前于前一步）——采集一条龙链路不完整`);
  }
  cursor = at;
}
const HELPER_CALLS = {
  'private void waitDoor(': ['doorStatus'],
  'private void waitReady(': ['gatherStartCheck'],
  'private void shootWindow(': ['gatherError']
};
for (const [sig, calls] of Object.entries(HELPER_CALLS)) {
  const body = methodBody(serviceSrc, sig) ?? fail(`找不到辅助方法 ${sig}（间接层被删/改名）`);
  for (const name of calls) {
    if (!body.includes(`client.${name}(`)) {
      fail(`${sig} 体内缺少 client.${name}(——就绪/错误查询间接层被破坏`);
    }
  }
}
// 上传轮询在 runUploadPoll → refreshUpload（间接层），两层各锚定其半
const uploadDefAt = serviceSrc.search(/\n        void runUploadPoll\(\)/);
const uploadPoll =
  uploadDefAt >= 0
    ? methodBody(
        serviceSrc,
        serviceSrc.slice(uploadDefAt + 1, serviceSrc.indexOf('{', uploadDefAt))
      )
    : null;
if (!uploadPoll || !uploadPoll.includes('refreshUpload()')) {
  fail('找不到 runUploadPoll() 或其体内缺少 refreshUpload()——上传轮询被删');
}
const refreshDefAt = serviceSrc.search(/\n        void refreshUpload\(\)/);
const refreshBody =
  refreshDefAt >= 0
    ? methodBody(
        serviceSrc,
        serviceSrc.slice(refreshDefAt + 1, serviceSrc.indexOf('{', refreshDefAt))
      )
    : null;
if (!refreshBody || !refreshBody.includes('client.uploadInfoNew(')) {
  fail('找不到 refreshUpload() 或其体内缺少 client.uploadInfoNew(——上传状态读取被删');
}

// ── B：deleteBatchNoNew 调用点白名单 = JiangyiGatherConsoleService#clearBatch ──
// 只认 client.deleteBatchNoNew( 调用形态——客户端里的方法定义/javadoc 提及不算调用。
const CALL_RE = /client\.deleteBatchNoNew\s*\(/g;
const clearBody = methodBody(serviceSrc, 'public ConsoleSnapshot clearBatch(String deviceId)');
if (!clearBody || !clearBody.includes('deleteBatchNoNew')) {
  fail('clearBatch 方法体里没有 deleteBatchNoNew 调用（清批次救援动作被删？）');
}
const clearRange = (() => {
  const sig = serviceSrc.indexOf('public ConsoleSnapshot clearBatch');
  const body = clearBody;
  const start = serviceSrc.indexOf(body, sig);
  return [start, start + body.length];
})();
let hit;
const mainTree = ['services/trade-service/src/main/java'];
const { readdirSync, statSync } = await import('node:fs');
function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    const st = statSync(p);
    if (st.isDirectory()) walk(p, out);
    else if (p.endsWith('.java')) out.push(p);
  }
  return out;
}
const allCalls = [];
for (const tree of mainTree) {
  for (const f of walk(join(root, tree))) {
    const src = readFileSync(f, 'utf8');
    CALL_RE.lastIndex = 0;
    while ((hit = CALL_RE.exec(src))) {
      allCalls.push({ file: relative(root, f), at: hit.index, src });
    }
  }
}
if (allCalls.length === 0) {
  fail('全源码零 deleteBatchNoNew 调用——门禁自身失效（fail-closed）');
}
for (const c of allCalls) {
  const inService = c.file.includes('JiangyiGatherConsoleService');
  const inClear = inService && c.at >= clearRange[0] && c.at <= clearRange[1];
  if (!inClear) {
    fail(
      `deleteBatchNoNew 出现在白名单之外：${c.file}（只允许 JiangyiGatherConsoleService#clearBatch）` +
        `——2026-10-26 16:51 事故：uploadInfoNew 有 wait 时清批次会打断上传`
    );
  }
}

// ── B2：编排方法不得被定时/finally 自动路径调用（自动清批次形态）──────
const consoleServiceBody = serviceSrc;
for (const danger of [
  '@Scheduled',
  'deleteBatchNoNew)) //auto',
  'finally {\\s*[^}]*deleteBatchNoNew'
]) {
  if (new RegExp(danger.replace(/[()\\]/g, (ch) => '\\' + ch)).test(consoleServiceBody) === false)
    continue;
  if (danger === '@Scheduled') {
    fail('JiangyiGatherConsoleService 出现 @Scheduled——采集编排禁止定时自动执行');
  }
}

console.log(
  `${TAG} OK：链路 11 锚点完整且有序；deleteBatchNoNew ${allCalls.length} 处调用全部在 clearBatch 白名单内`
);
