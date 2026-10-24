#!/usr/bin/env node
/**
 * Kafka 消费失败安全门禁：**catch 必须重抛 + 有限重试 + DLT 落地 + poll 上限**。
 *
 * 缺陷类（lessons #19 / #75 / #95，已真实复现）：Spring Kafka 在监听方法**正常返回**时
 * 照常提交偏移。业务代码 `try { … } catch (Exception e) { log.error(…); }` 之后返回，
 * 消息既不会被重投、也不会进死信 —— 表现为「消费成功但通知没发出去」，静默丢失比抛错
 * 更难发现（没有失败指标、没有堆积、没有告警）。
 *
 * 判据（缺一即红）：
 *   1. 每个 `@KafkaListener` 方法体内的**每一个** catch 块都必须含 `throw`。
 *   2. 存在 `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`，且
 *      `setFailIfSendResultIsError(true)`（否则 DLT 发布失败会被静默吞掉）+ 超时等待；
 *      重试必须有限（`FixedBackOff`，禁止 `UNLIMITED_ATTEMPTS` / `Long.MAX_VALUE`）。
 *   3. `KafkaTopics` 里每个业务主题都要有 `${NAME}_DLT = <NAME> + ".DLT"` 常量，
 *      且**两个主题都有** `TopicBuilder.name(KafkaTopics.X)` 的 NewTopic 声明 ——
 *      否则失败消息会被投到一个没人声明、靠 broker 自动建的主题上。
 *   4. 有 listener 的模块，其 `application.yml` 必须有 `max-poll-records` 与
 *      `max.poll.interval.ms`（单批过大/处理超时 ⇒ rebalance 循环 + 重复消费）。
 *
 * 永远 fail-closed：一个 listener 都没扫到、KafkaTopics 解析不到、找不到错误处理器，
 * 都按失败处理 —— 那是门禁锚点失效，不是「没有问题」。
 *
 *   node scripts/check-kafka-consumer-safety.mjs
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-kafka-consumer-safety]';
const SERVICES = join(root, 'services');
const KAFKA_TOPICS = join(
  SERVICES,
  'common/common-core/src/main/java/com/aicabinet/common/constants/KafkaTopics.java'
);

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

/* ------------------------------------------------------------------ *
 * 代码结构工具：注释/字符串掩成空格（保留换行与列），便于正则与括号配对
 * ------------------------------------------------------------------ */
function maskNonCode(src) {
  const out = [];
  let state = 'code';
  let i = 0;
  while (i < src.length) {
    const c = src[i];
    const n = src[i + 1];
    if (state === 'code') {
      if (c === '/' && n === '/') {
        out.push('  ');
        i += 2;
        state = 'line';
        continue;
      }
      if (c === '/' && n === '*') {
        out.push('  ');
        i += 2;
        state = 'block';
        continue;
      }
      if (c === '"' && src.startsWith('"""', i)) {
        out.push('   ');
        i += 3;
        state = 'text';
        continue;
      }
      if (c === '"') {
        out.push(' ');
        i += 1;
        state = 'str';
        continue;
      }
      if (c === "'") {
        out.push(' ');
        i += 1;
        state = 'chr';
        continue;
      }
      out.push(c);
      i += 1;
      continue;
    }
    if (state === 'line') {
      if (c === '\n') {
        out.push('\n');
        state = 'code';
      } else out.push(' ');
      i += 1;
      continue;
    }
    if (state === 'block') {
      if (c === '*' && n === '/') {
        out.push('  ');
        i += 2;
        state = 'code';
      } else {
        out.push(c === '\n' ? '\n' : ' ');
        i += 1;
      }
      continue;
    }
    if (state === 'str') {
      if (c === '\\') {
        out.push('  ');
        i += 2;
      } else if (c === '"') {
        out.push(' ');
        i += 1;
        state = 'code';
      } else {
        out.push(c === '\n' ? '\n' : ' ');
        i += 1;
      }
      continue;
    }
    if (state === 'chr') {
      if (c === '\\') {
        out.push('  ');
        i += 2;
      } else if (c === "'") {
        out.push(' ');
        i += 1;
        state = 'code';
      } else {
        out.push(' ');
        i += 1;
      }
      continue;
    }
    // text block
    if (c === '"' && src.startsWith('"""', i)) {
      out.push('   ');
      i += 3;
      state = 'code';
      continue;
    }
    out.push(c === '\n' ? '\n' : ' ');
    i += 1;
  }
  return out.join('');
}

function matchPair(s, open) {
  const openCh = s[open];
  const closeCh = openCh === '{' ? '}' : ')';
  let depth = 0;
  for (let i = open; i < s.length; i++) {
    if (s[i] === openCh) depth++;
    else if (s[i] === closeCh) {
      depth--;
      if (depth === 0) return i;
    }
  }
  return -1;
}

const lineOf = (s, index) => s.slice(0, index).split('\n').length;

function walk(dir, out = []) {
  if (!existsSync(dir)) return out;
  for (const name of readdirSync(dir)) {
    if (name === 'node_modules' || name === 'target' || name === 'build') continue;
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (name.endsWith('.java')) out.push(p);
  }
  return out;
}

/** 从 `@KafkaListener` 注解位置解析出 (注解原文, 方法体原文)。 */
function extractListener(sanitized, raw, at) {
  const parenOpen = sanitized.indexOf('(', at);
  if (parenOpen < 0) return null;
  const parenClose = matchPair(sanitized, parenOpen);
  if (parenClose < 0) return null;
  const bodyOpen = sanitized.indexOf('{', parenClose);
  if (bodyOpen < 0) return null;
  const bodyClose = matchPair(sanitized, bodyOpen);
  if (bodyClose < 0) return null;
  return {
    annotation: raw.slice(at, parenClose + 1),
    body: sanitized.slice(bodyOpen, bodyClose + 1),
    bodyStart: bodyOpen
  };
}

/** 方法体内所有 catch 块；返回每个 catch 体是否含 throw。 */
function inspectCatches(body) {
  const results = [];
  const re = /catch\s*\(([^)]*)\)\s*\{/g;
  let m;
  while ((m = re.exec(body))) {
    const open = m.index + m[0].length - 1;
    const close = matchPair(body, open);
    if (close < 0) {
      results.push({ ok: false, param: m[1].trim(), at: open, reason: '括号不配对' });
      continue;
    }
    const inner = body.slice(open + 1, close);
    results.push({ ok: /\bthrow\b/.test(inner), param: m[1].trim(), at: open });
    re.lastIndex = close + 1;
  }
  return results;
}

/* ------------------------------------------------------------------ *
 * 自检：先证明检测器本身是对的
 * ------------------------------------------------------------------ */
function selfCheck() {
  const swallow = maskNonCode(
    '@KafkaListener(topics = KafkaTopics.X)\npublic void on(String p) {\n' +
      '  try { a(); } catch (Exception e) { log.error("x", e); }\n}\n'
  );
  const at = swallow.indexOf('@KafkaListener');
  const swallowed = extractListener(swallow, swallow, at);
  if (!swallowed) fail('自检失败：无法解析 listener 方法体');
  const badCatches = inspectCatches(swallowed.body);
  if (badCatches.length !== 1 || badCatches[0].ok) {
    fail('自检失败：catch 内无 throw 的静默吞异常未被识别');
  }

  const rethrow = maskNonCode(
    '@KafkaListener(topics = KafkaTopics.X)\npublic void on(String p) {\n' +
      '  try { a(); } catch (IOException e) { throw new IllegalStateException(e); }\n}\n'
  );
  const rethrowBody = extractListener(rethrow, rethrow, rethrow.indexOf('@KafkaListener'));
  const goodCatches = inspectCatches(rethrowBody.body);
  if (goodCatches.length !== 1 || !goodCatches[0].ok) {
    fail('自检失败：catch 内已 throw 被误报');
  }

  // 字符串/注释里的 `{` `}` 与 catch 字样不得影响配对
  const noise = maskNonCode(
    'void f() {\n  String s = "catch (E e) { }"; // catch (E e) { }\n  /* } */\n}\n'
  );
  if (inspectCatches(noise).length !== 0) {
    fail('自检失败：字符串/注释里的 catch 被当成真实 catch');
  }
}
selfCheck();

/* ------------------------------------------------------------------ *
 * 扫描
 * ------------------------------------------------------------------ */
const javaFiles = walk(SERVICES).filter((f) => f.includes(`${join('src', 'main', 'java')}`));
if (javaFiles.length === 0) fail('services/**/src/main/java 下没有 java 文件，门禁锚点失效');

const listeners = [];
const mainTextByFile = new Map();
for (const file of javaFiles) {
  const raw = readFileSync(file, 'utf8');
  mainTextByFile.set(file, raw);
  if (!raw.includes('@KafkaListener')) continue;
  const sanitized = maskNonCode(raw);
  for (const hit of sanitized.matchAll(/@KafkaListener\b/g)) {
    const parsed = extractListener(sanitized, raw, hit.index);
    if (!parsed) {
      fail(
        `${relative(root, file)}:${lineOf(sanitized, hit.index)} 无法解析 @KafkaListener 方法体`
      );
    }
    listeners.push({ file, at: hit.index, ...parsed });
  }
}

if (listeners.length === 0) {
  fail('一个 @KafkaListener 都没扫到 —— 监听器被改名/移走时门禁会变假绿，请同步更新本门禁');
}

const problems = [];

// ── 规则 1：catch 必须重抛 ────────────────────────────────────────────────
for (const l of listeners) {
  const rel = relative(root, l.file).replace(/\\/g, '/');
  for (const c of inspectCatches(l.body)) {
    if (!c.ok) {
      const line = lineOf(maskNonCode(readFileSync(l.file, 'utf8')), l.bodyStart + c.at);
      problems.push(
        `${rel}:${line} catch (${c.param}) 内没有 throw —— 正常返回会提交偏移，消息静默丢失` +
          `（lessons #19/#75/#95）。要么重抛交给 DefaultErrorHandler，要么显式投 DLT。`
      );
    }
  }
}

// ── 规则 2：错误处理器（有限重试 + 同步 DLT 结果） ────────────────────────
const errorHandlerFiles = [...mainTextByFile].filter(
  ([, text]) =>
    text.includes('DeadLetterPublishingRecoverer') && text.includes('new DefaultErrorHandler')
);
if (errorHandlerFiles.length === 0) {
  problems.push(
    '找不到 `DeadLetterPublishingRecoverer` + `new DefaultErrorHandler(...)` 的容错配置 —— ' +
      '监听器重抛后会无限重投，且失败消息没有死信出口'
  );
} else {
  // 逐个校验：将来第二个服务再写一套 recoverer 时，不能因为「第一份合规」就放过它。
  for (const [file, text] of errorHandlerFiles) {
    const rel = relative(root, file).replace(/\\/g, '/');
    const sanitized = maskNonCode(text);
    if (!/setFailIfSendResultIsError\s*\(\s*true\s*\)/.test(text)) {
      problems.push(
        `${rel} 未开启 recoverer.setFailIfSendResultIsError(true) —— DLT 发布失败不会被感知，` +
          '容器照常提交偏移，等于「以为进了死信、其实消息已丢」'
      );
    }
    if (!/setWaitForSendResultTimeout\s*\(/.test(text)) {
      problems.push(
        `${rel} 未设置 setWaitForSendResultTimeout(...) —— 同步等待 DLT 结果必须有超时上限`
      );
    }
    if (!/new\s+FixedBackOff\s*\(/.test(text)) {
      problems.push(`${rel} 重试策略不是 FixedBackOff —— 必须显式声明有限次数`);
    }
    if (/UNLIMITED_ATTEMPTS|Long\.MAX_VALUE/.test(sanitized)) {
      problems.push(
        `${rel} 重试次数无上限（UNLIMITED_ATTEMPTS / Long.MAX_VALUE）—— 毒消息会永久占住分区`
      );
    }
    // 配置必须挂在开关上：@EnableKafka 关闭时不要留下无主的 NewTopic/错误处理器
    if (!/@ConditionalOnProperty/.test(sanitized)) {
      problems.push(
        `${rel} 未加 @ConditionalOnProperty —— 与 @EnableKafka 的开关不一致会留下半套 Kafka 配置`
      );
    }
  }
}

// ── 规则 3：主题 ↔ DLT 常量 ↔ NewTopic 三向一致 ──────────────────────────
if (!existsSync(KAFKA_TOPICS)) fail('找不到 KafkaTopics.java，主题/DLT 映射无法校验');
const topicsSrc = readFileSync(KAFKA_TOPICS, 'utf8');
const constants = new Map();
for (const m of topicsSrc.matchAll(
  /public\s+static\s+final\s+String\s+([A-Z0-9_]+)\s*=\s*([^;]+);/g
)) {
  constants.set(m[1], m[2].replace(/\s+/g, ' ').trim());
}
if (constants.size === 0) fail('KafkaTopics.java 里解析不到任何主题常量，门禁锚点失效');

const baseTopics = [...constants.keys()].filter((n) => !n.endsWith('_DLT'));
if (baseTopics.length === 0) fail('KafkaTopics.java 里没有业务主题常量，门禁锚点失效');

const allMainText = [...mainTextByFile.values()].join('\n');
for (const base of baseTopics) {
  const dlt = `${base}_DLT`;
  const expr = constants.get(dlt);
  if (!expr) {
    problems.push(`KafkaTopics 缺少 ${dlt} —— 失败消息会投到未声明的 <topic>.DLT`);
    continue;
  }
  const expected = new RegExp(`^${base}\\s*\\+\\s*"\\.DLT"$`);
  if (!expected.test(expr)) {
    problems.push(
      `${dlt} 的取值必须是 ${base} + ".DLT"（当前 ${expr}）—— DeadLetterPublishingRecoverer 默认按此规则寻址`
    );
  }
  for (const name of [base, dlt]) {
    if (!new RegExp(`TopicBuilder\\.name\\(\\s*KafkaTopics\\.${name}\\s*\\)`).test(allMainText)) {
      problems.push(`没有 NewTopic 声明 KafkaTopics.${name} —— 生产环境可能根本没建这个主题`);
    }
  }
}

// ── 监听器必须用常量（字面量无法映射到 DLT） ──────────────────────────────
for (const l of listeners) {
  const rel = relative(root, l.file).replace(/\\/g, '/');
  const line = lineOf(maskNonCode(readFileSync(l.file, 'utf8')), l.at);
  const refs = [...l.annotation.matchAll(/KafkaTopics\.([A-Z0-9_]+)/g)].map((m) => m[1]);
  if (refs.length === 0) {
    problems.push(
      `${rel}:${line} @KafkaListener 没有引用 KafkaTopics 常量 —— 字面量主题无法校验 DLT 映射`
    );
  }
  for (const ref of refs) {
    if (!baseTopics.includes(ref)) {
      problems.push(`${rel}:${line} 监听的主题 KafkaTopics.${ref} 不是业务主题常量（或不存在）`);
    }
  }
}

// ── 规则 4：有 listener 的模块必须有 poll 上限 ────────────────────────────
const modules = new Set(
  listeners.map((l) => relative(join(SERVICES), l.file).replace(/\\/g, '/').split('/')[0])
);
for (const mod of modules) {
  const yml = join(SERVICES, mod, 'src/main/resources/application.yml');
  if (!existsSync(yml)) {
    problems.push(`services/${mod} 有 @KafkaListener 但找不到 application.yml`);
    continue;
  }
  const text = readFileSync(yml, 'utf8');
  for (const key of ['max-poll-records', 'max.poll.interval.ms']) {
    if (!text.includes(`${key}:`)) {
      problems.push(
        `services/${mod}/src/main/resources/application.yml 缺少 ${key} —— ` +
          '单批过大或处理超时会让 consumer 反复 rebalance / 重复消费'
      );
    }
  }
}

if (problems.length) {
  fail(`\n  ${problems.join('\n  ')}\n`);
}

console.log(
  `${TAG} OK：${listeners.length} 个 @KafkaListener 的 catch 全部重抛；` +
    `错误处理器为有限重试 + 同步 DLT；${baseTopics.length} 个主题均有 DLT 常量与 NewTopic；` +
    `模块 (${[...modules].join(', ')}) 均有 poll 上限`
);
