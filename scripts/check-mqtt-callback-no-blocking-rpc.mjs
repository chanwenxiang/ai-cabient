#!/usr/bin/env node
/**
 * R20-10 后半门禁（2026-11-12）：Paho 回调线程不得执行任何出站 trade HTTP。
 *
 * 🔴 判据设计（铁律 84 / 75）：
 *   ① 扫 Java 源码前 maskComments() 抹注释为**等长空格**——实现里写了大段成因注释，
 *      注释复述了 CallerRunsPolicy、旧同步调用等字面量，不抹会假红/假绿；
 *   ② 判据贴真实责任边界：只取 messageArrived / 三个 handler 的**方法体**，
 *      不全文 includes（否则「某处出现过 submitForward」就算绿）；
 *   ③ 修的是「这一类」而非一处：门磁 / 心跳 / 告警三条出站链一起钉；
 *   ④ 必须同时钉生产配置（有界队列 + AbortPolicy）与测试判据本身——
 *      只跑注入 direct executor 的旧用例时，改回同步转发照样全绿。
 *
 * 用法：
 *   node scripts/check-mqtt-callback-no-blocking-rpc.mjs             # 正向门禁
 *   node scripts/check-mqtt-callback-no-blocking-rpc.mjs --self-test # 沙箱化负向变异 + md5 零残留
 */
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const ROOT = process.cwd();
const P = {
  listener:
    'services/device-service/src/main/java/com/aicabinet/device/mqtt/MqttEventListener.java',
  metrics:
    'services/device-service/src/main/java/com/aicabinet/device/metrics/DeviceMqttMetrics.java',
  asyncTest:
    'services/device-service/src/test/java/com/aicabinet/device/mqtt/MqttEventListenerAsyncForwardTest.java',
  doorTest:
    'services/device-service/src/test/java/com/aicabinet/device/mqtt/MqttEventListenerDoorTest.java'
};

/** 注释替换为等长空格：保行号/列偏移，注释里的旧写法不得被当成实现。 */
function maskComments(src) {
  return src.replace(/\/\*[\s\S]*?\*\/|\/\/[^\n]*/g, (m) => m.replace(/[^\n]/g, ' '));
}

/** 从 openIdx（开括号后一个字符）起截取配平内容；字符串里的括号不计数。 */
function balanced(src, openIdx) {
  let depth = 1;
  let inStr = null;
  for (let i = openIdx; i < src.length; i++) {
    const c = src[i];
    if (inStr) {
      if (c === '\\') {
        i++;
        continue;
      }
      if (c === inStr) inStr = null;
      continue;
    }
    if (c === '"' || c === "'") {
      inStr = c;
      continue;
    }
    if (c === '{') depth++;
    else if (c === '}' && --depth === 0) return src.slice(openIdx, i);
  }
  return '';
}

/** 取方法签名后的完整方法体；找不到返回 null（调用方必须判红，不能当空串放过）。 */
function methodBody(src, signature) {
  const i = src.indexOf(signature);
  if (i < 0) return null;
  const open = src.indexOf('{', i + signature.length);
  if (open < 0) return null;
  return balanced(src, open + 1);
}

/** 纯函数判据：read(relPath) → 源码文本。正向从磁盘读，自测从内存副本读。 */
function evaluate(read) {
  const problems = [];
  const bad = (msg) => problems.push(msg);
  const java = (p) => maskComments(read(p));
  const listener = java(P.listener);

  // ── R1 回调线程零出站 HTTP ──
  const arrived = methodBody(
    listener,
    'public void messageArrived(String topic, MqttMessage message)'
  );
  if (arrived === null) {
    bad('找不到 MqttEventListener.messageArrived 方法体');
  } else if (/tradeServiceClient\./.test(arrived)) {
    bad('Paho 回调线程 messageArrived 仍在同步调用 tradeServiceClient');
  }

  // ── R2 三个 handler 同族覆盖：零 HTTP + 都走唯一提交口 ──
  const handlers = [
    'private void handleDoorEvent(',
    'private void handleHeartbeat(',
    'private void handleAlert('
  ];
  for (const sig of handlers) {
    const body = methodBody(listener, sig);
    if (body === null) {
      bad(`找不到 ${sig} 方法体`);
      continue;
    }
    if (/tradeServiceClient\./.test(body))
      bad(`${sig} 方法体仍在回调线程直调出站 HTTP（R20-10 病灶）`);
    if (!/submitForward\(/.test(body)) bad(`${sig} 没有把转发提交到专用 executor`);
  }

  // ── R3 三处 HTTP 只能待在 forward* 工作线程方法里 ──
  const forwarders = [
    ['forwardDoorEvent', 'notifyDoorEvent('],
    ['forwardHeartbeat', 'notifyHeartbeat('],
    ['forwardAlert', 'notifyOpsAlert(']
  ];
  for (const [sig, call] of forwarders) {
    const body = methodBody(listener, `private void ${sig}(`);
    if (body === null) {
      bad(`找不到工作线程方法 ${sig}`);
      continue;
    }
    if (!body.includes(`tradeServiceClient.${call}`)) bad(`${sig} 未承载对应的出站调用 ${call}`);
  }
  const callCount = (listener.match(/tradeServiceClient\./g) || []).length;
  if (callCount !== 3)
    bad(`MqttEventListener 出站调用点应恰好 3 处（门磁/心跳/告警），实得 ${callCount}`);

  // ── R4 生产池：有界 + AbortPolicy（CallerRuns 会把 HTTP 压回回调线程） ──
  const pool = methodBody(listener, 'static ExecutorService defaultForwardExecutor()');
  if (pool === null) {
    bad('找不到 defaultForwardExecutor 生产池工厂');
  } else {
    if (!/new ArrayBlockingQueue<>\(/.test(pool)) bad('转发池队列必须有界（ArrayBlockingQueue）');
    if (!/new ThreadPoolExecutor\.AbortPolicy\(\)/.test(pool))
      bad('转发池拒绝策略必须是 AbortPolicy');
    if (/CallerRunsPolicy/.test(pool))
      bad('CallerRunsPolicy 会把 HTTP 压回 Paho 回调线程，等于没修');
  }

  // ── R5 队列满＝背压：不 ACK、不计失败、有指标 ──
  const submit = methodBody(listener, 'private void submitForward(');
  if (submit === null) {
    bad('找不到唯一转发提交口 submitForward');
  } else {
    if (!/forwardExecutor\.execute\(task\)/.test(submit))
      bad('submitForward 必须真的把任务交给 executor，不能用 task.run() 同步执行');
    if (!/catch\s*\(\s*RejectedExecutionException/.test(submit))
      bad('submitForward 未捕获 RejectedExecutionException（队列满会抛到外层被当处理失败）');
    if (!/metrics\.recordForwardRejected\(\)/.test(submit))
      bad('队列满没有 rejected 指标，背压不可观测');
    if (/acknowledgeMessage\(/.test(submit)) bad('队列满不得 ACK：必须留作未 ACK 等 broker 重投');
    if (/onProcessingFailure\(|deliveryFailures/.test(submit))
      bad('背压不得计入 deliveryFailures，否则连续 3 次会把消息 ACK 丢弃');
  }

  // ── R6 C11/M11 语义：ACK 严格排在 HTTP 成功与幂等键之后 ──
  const door = methodBody(listener, 'private void forwardDoorEvent(');
  if (door === null) {
    bad('找不到 forwardDoorEvent');
  } else {
    const iHttp = door.indexOf('tradeServiceClient.notifyDoorEvent(');
    const iMark = door.indexOf('deduplicator.mark(');
    const iAck = door.indexOf('acknowledgeMessage(');
    if (!(iHttp >= 0 && iMark > iHttp && iAck > iMark))
      bad('门磁 ACK 必须排在「HTTP 成功 → 写幂等键」之后，不能先 ACK 后转发');
    if (!/onProcessingFailure\(/.test(door)) bad('门磁转发失败未走 C11 不 ACK 路径');
  }

  // ── R7 背压指标真实存在 ──
  const metrics = java(P.metrics);
  if (!/recordForwardRejected\(\)/.test(metrics))
    bad('DeviceMqttMetrics 缺 recordForwardRejected()');
  if (!/device\.mqtt\.forward/.test(metrics))
    bad('DeviceMqttMetrics 缺 device.mqtt.forward 计数器');

  // ── R8 判据本身：异步/线程边界用例必须存在（防「只钉语义不钉线程」） ──
  const asyncTest = java(P.asyncTest);
  for (const needle of [
    'callback.get(1, TimeUnit.SECONDS)',
    'ackOnlyAfterSuccessfulDoorForward',
    'forwardFailureLeavesMessageUnacked',
    'queueFullLeavesUnackedWithoutFailureCounting',
    'doorHeartbeatAlertAllSubmitToForwardExecutor',
    'defaultForwardExecutorIsBoundedAndAborts'
  ]) {
    if (!asyncTest.includes(needle))
      bad(`异步判据缺 ${needle}——只跑 direct executor 的旧用例护不住线程边界`);
  }
  if (!java(P.doorTest).includes('Runnable::run'))
    bad('MqttEventListenerDoorTest 未注入 direct executor，原有语义用例会与工作线程竞态');

  return problems;
}

const diskRead = (p) => readFileSync(join(ROOT, p), 'utf8');
const md5 = (s) => createHash('md5').update(s).digest('hex');

/**
 * 负向夹具：只在内存副本上变异，逐条期望转红；再验「注释里的旧写法不假红」
 * 与「磁盘 md5 零残留」（铁律 4 / 44）。
 */
function selfTest() {
  const paths = Object.values(P);
  const baseline = new Map(paths.map((p) => [p, diskRead(p)]));
  const before = new Map(paths.map((p) => [p, md5(baseline.get(p))]));

  // 每条变异都带「必须命中的判据关键词」——只要求「有红」会被「源码改崩导致 balanced 失衡」骗过去。
  // 变异本身要保持语法完整，模拟真实回归而非删半行代码（铁律 3）。
  const mutations = [
    [
      '门磁转发退回回调线程同步调用',
      P.listener,
      (s) =>
        s.replace(
          /submitForward\(topic, message, \(\) -> forwardDoorEvent\([\s\S]*?\);/,
          'tradeServiceClient.notifyDoorEvent(request);'
        ),
      'handleDoorEvent'
    ],
    [
      '生产池改用 CallerRunsPolicy',
      P.listener,
      (s) =>
        s.replace(
          'new ThreadPoolExecutor.AbortPolicy()',
          'new ThreadPoolExecutor.CallerRunsPolicy()'
        ),
      'AbortPolicy'
    ],
    [
      '队列改成无界 LinkedBlockingQueue',
      P.listener,
      (s) =>
        s.replace(
          'new ArrayBlockingQueue<>(FORWARD_QUEUE_CAPACITY)',
          'new java.util.concurrent.LinkedBlockingQueue<>(FORWARD_QUEUE_CAPACITY)'
        ),
      '有界'
    ],
    [
      '提交口改成 task.run() 同步执行',
      P.listener,
      (s) => s.replace('forwardExecutor.execute(task);', 'task.run();'),
      '不能用 task.run()'
    ],
    [
      '队列满时错误地 ACK 丢弃',
      P.listener,
      (s) =>
        s.replace(
          'metrics.recordForwardRejected();',
          'metrics.recordForwardRejected();\n            acknowledgeMessage(topic, message);'
        ),
      '队列满不得 ACK'
    ],
    [
      '队列满缺 rejected 指标',
      P.listener,
      (s) => s.replace('metrics.recordForwardRejected();', ''),
      'rejected 指标'
    ],
    [
      '门磁先 ACK 后转发',
      P.listener,
      (s) =>
        s.replace(
          'tradeServiceClient.notifyDoorEvent(request);',
          'acknowledgeMessage(topic, message);\n            tradeServiceClient.notifyDoorEvent(request);'
        ),
      'ACK 必须排'
    ],
    [
      '告警 HTTP 从工作线程方法消失',
      P.listener,
      (s) => s.replace('tradeServiceClient.notifyOpsAlert(alertType, message, deviceId);', ''),
      'forwardAlert'
    ],
    [
      '心跳退回回调线程同步转发',
      P.listener,
      (s) =>
        s.replace(
          /submitForward\(topic, mqttMessage, \(\) -> \{[\s\S]*?\n        \}\);/,
          'forwardHeartbeat(deviceId, appVersion, firmwareVersion, currentTempC,\n' +
            '                humidityPct, voltageV, powerW);\n' +
            '        acknowledgeMessage(topic, mqttMessage);'
        ),
      'handleHeartbeat'
    ],
    [
      '异步核心判据被删',
      P.asyncTest,
      (s) => s.replace('callback.get(1, TimeUnit.SECONDS)', 'callback.get(5, TimeUnit.MINUTES)'),
      '异步判据缺'
    ],
    [
      '背压指标方法被删',
      P.metrics,
      (s) => s.replace('public void recordForwardRejected() { forwardRejected.increment(); }', ''),
      'recordForwardRejected'
    ]
  ];

  let passed = 0;
  for (const [name, path, mutate, expect] of mutations) {
    const copy = new Map(baseline);
    const mutated = mutate(baseline.get(path));
    if (mutated === baseline.get(path)) {
      console.error(`[self-test] FAIL 变异未生效（源串没匹配上）：${name}`);
      process.exit(1);
    }
    copy.set(path, mutated);
    const problems = evaluate((p) => copy.get(p));
    if (!problems.length) {
      console.error(`[self-test] FAIL 变异未被咬住：${name}`);
      process.exit(1);
    }
    if (!problems.some((p) => p.includes(expect))) {
      console.error(`[self-test] FAIL 变异命中的不是预期判据「${expect}」：${name}`, problems);
      process.exit(1);
    }
    passed++;
  }

  // 反向：注释里复述旧写法 / 禁用策略，不得造成假红（铁律 84①）
  const withComment = new Map(baseline);
  withComment.set(
    P.listener,
    baseline.get(P.listener) +
      '\n// 旧实现：tradeServiceClient.notifyDoorEvent(...) + CallerRunsPolicy + LinkedBlockingQueue\n' +
      '/* handleDoorEvent 曾直调 tradeServiceClient.notifyHeartbeat(...) */\n'
  );
  const commentProblems = evaluate((p) => withComment.get(p));
  if (commentProblems.length) {
    console.error('[self-test] FAIL 注释里的旧写法造成假红：', commentProblems);
    process.exit(1);
  }
  passed++;

  // md5 零残留：自测全程只在内存副本上变异
  for (const p of paths) {
    if (md5(diskRead(p)) !== before.get(p)) {
      console.error(`[self-test] FAIL 磁盘被改动：${p}`);
      process.exit(1);
    }
  }
  console.log(
    `[check-mqtt-callback-no-blocking-rpc] self-test OK：${passed} 个变异/防假红断言全部符合，md5 零残留`
  );
  process.exit(0);
}

// 负向夹具识别（check-audit-gates-wiring 规则 3）要求剥注释后同时出现 selfTest( 与 --self-test，
// 所以自测主体必须是具名函数 + 显式调用，不能写成一段匿名 if 分支。
if (process.argv.includes('--self-test')) {
  selfTest();
} else {
  const problems = evaluate(diskRead);
  if (problems.length) {
    console.error('[check-mqtt-callback-no-blocking-rpc] FAIL');
    for (const p of problems) console.error(`  - ${p}`);
    process.exit(1);
  }
  console.log('[check-mqtt-callback-no-blocking-rpc] OK：回调线程零出站 HTTP，8 组链路判据全绿');
}
