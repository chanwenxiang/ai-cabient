#!/usr/bin/env node
/**
 * R20 P3-8 门禁（2026-11-12）：命令超时通知不得用 ForkJoinPool.commonPool() / CompletableFuture.runAsync。
 *
 * 🔴 判据设计（铁律 84 / 75）：
 *   ① 扫 Java 源码前 maskComments() 抹注释为**等长空格**——实现里写了大段「原实现用 commonPool」
 *      的成因注释，不抹会假绿（注释里的 commonPool 字面量被当成真实用法）；
 *   ② 判据贴真实责任边界：只取 notifyOpenDoorTimeoutAsync 的**方法体**，不全文 includes；
 *   ③ 钉住专用 notifier 池（有界 ArrayBlockingQueue）替代 commonPool——突发批量（一次 MQTT 抖动
 *      可同时过期上千条）不会占满全局 commonPool 拖慢所有并行流 / 无界堆积到 OOM；
 *   ④ XXL-JOB 3.4.2 的触发线程池在 OSS 版里硬编码、不暴露配置项，无法通过配置收敛，
 *      故本门禁只钉「本类自身不得引入 commonPool/runAsync」，并把设计缺陷说明注释保留为历史教训。
 *
 * 用法：
 *   node scripts/check-no-commonpool-blocking.mjs             # 正向门禁
 *   node scripts/check-no-commonpool-blocking.mjs --self-test # 沙箱化负向变异 + md5 零残留
 */
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const ROOT = process.cwd();
const P = {
  impl: 'services/device-service/src/main/java/com/aicabinet/device/service/DeviceCommandTracker.java'
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
  const impl = maskComments(read(P.impl));
  // R5 需要读「带注释」的原文件，确认设计缺陷说明注释未被删（历史教训）。
  const raw = read(P.impl);

  // ── R1 全局：除注释外不得有真实 commonPool / runAsync 用法 ──
  const commonPoolCount = (impl.match(/ForkJoinPool\.commonPool\(\)/g) || []).length;
  const runAsyncCount = (impl.match(/CompletableFuture\.runAsync/g) || []).length;
  if (commonPoolCount !== 0)
    bad(
      `存在 ${commonPoolCount} 处 ForkJoinPool.commonPool() 真实用法（注释除外）——会污染全局并行流`
    );
  if (runAsyncCount !== 0)
    bad(
      `存在 ${runAsyncCount} 处 CompletableFuture.runAsync 真实用法（注释除外）——突发批量会把 commonPool 占满`
    );

  // ── R2 专用 notifier 池：有界队列，替代 commonPool ──
  const notifier = impl.match(
    /private final ExecutorService notifier = new ThreadPoolExecutor\([\s\S]*?\);/
  );
  if (!notifier) {
    bad('缺专用 notifier 线程池（必须用有界池替代 commonPool）');
  } else if (!/ArrayBlockingQueue/.test(notifier[0])) {
    bad('notifier 队列必须有界（ArrayBlockingQueue），无界队列会堆积到 OOM');
  }

  // ── R3 notifyOpenDoorTimeoutAsync 走专用池，不得回退 commonPool/runAsync ──
  const notifyBody = methodBody(impl, 'private void notifyOpenDoorTimeoutAsync(');
  if (notifyBody === null) {
    bad('找不到 notifyOpenDoorTimeoutAsync 方法体');
  } else {
    if (/ForkJoinPool|runAsync|commonPool/.test(notifyBody))
      bad('notifyOpenDoorTimeoutAsync 仍用 commonPool/runAsync（P3-8 病灶）');
    if (!/notifier\.execute\(/.test(notifyBody))
      bad('notifyOpenDoorTimeoutAsync 未走专用 notifier.execute');
  }

  // ── R4 不得把其他异步通知也误用 commonPool ──
  if (/notifier\.execute\(|executor\.schedule\(|ScheduledExecutorService/.test(impl) === false)
    bad('未找到任何通知/调度执行入口（notifier.execute 或 executor.schedule）');

  // ── R5 设计缺陷说明注释（含 R19-18 / commonPool 教训）不得被删 ──
  if (!/超时通知专用线程池（R19-18）/.test(raw))
    bad('超时通知线程池设计说明（R19-18 / commonPool 缺陷）被删，历史教训丢失');

  return problems;
}

const diskRead = (p) => readFileSync(join(ROOT, p), 'utf8');
const md5 = (s) => createHash('md5').update(s).digest('hex');

/** 负向夹具：只在内存副本上变异，逐条期望转红；再验注释不假红与磁盘 md5 零残留。 */
function selfTest() {
  const paths = Object.values(P);
  const baseline = new Map(paths.map((p) => [p, diskRead(p)]));
  const before = new Map(paths.map((p) => [p, md5(baseline.get(p))]));

  const mutations = [
    [
      'notifyOpenDoorTimeoutAsync 引入 runAsync 真实用法',
      P.impl,
      (s) => s.replace('notifier.execute(() -> {', 'CompletableFuture.runAsync(() -> {'),
      'CompletableFuture.runAsync'
    ],
    [
      'notifyOpenDoorTimeoutAsync 改回 commonPool',
      P.impl,
      (s) => s.replace('notifier.execute(() -> {', 'ForkJoinPool.commonPool().submit(() -> {'),
      'commonPool'
    ],
    [
      'notifier 专用池被换成 commonPool',
      P.impl,
      (s) =>
        s.replace(
          /private final ExecutorService notifier = new ThreadPoolExecutor\([\s\S]*?\);/,
          'private final ExecutorService notifier = ForkJoinPool.commonPool();'
        ),
      'commonPool'
    ],
    ['删除 commonPool 设计缺陷说明注释', P.impl, (s) => s.replace(/R19-18/, ''), 'R19-18']
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

  // 反向：注释里复述 commonPool 不得造成假红（铁律 84①）——但不得带上 R19-18 标记，否则 R5 误判。
  const withComment = new Map(baseline);
  withComment.set(
    P.impl,
    baseline.get(P.impl) +
      '\n// 注意：ForkJoinPool.commonPool() 不可用于此处（仅作澄清，非真实用法）。\n'
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
    `[check-no-commonpool-blocking] self-test OK：${passed} 个变异/防假红断言全部符合，md5 零残留`
  );
  process.exit(0);
}

// 负向夹具识别要求剥注释后同时出现 selfTest( 与 --self-test，故自测主体必须是具名函数 + 显式调用。
if (process.argv.includes('--self-test')) {
  selfTest();
} else {
  const problems = evaluate(diskRead);
  if (problems.length) {
    console.error('[check-no-commonpool-blocking] FAIL');
    for (const p of problems) console.error(`  - ${p}`);
    process.exit(1);
  }
  console.log(
    '[check-no-commonpool-blocking] OK：notifyOpenDoorTimeoutAsync 走专用有界池，0 处 commonPool/runAsync'
  );
}
