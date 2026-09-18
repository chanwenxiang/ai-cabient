#!/usr/bin/env node
/**
 * 容量一致性门禁：@Scheduled 线程池并发度 ≤ 数据源连接池上限（按 profile 的**有效值**判）。
 *
 * 背景（2026-09-17 CI run 35198060847 实测红）：
 *   主 application.yml 把 `spring.task.scheduling.pool.size` 从 Spring 默认的 1 放大到 8
 *   （本意：本地兜底任务互不阻塞，见该行注释）。测试 profile 并没有声明这个键，
 *   于是**通过继承**一并拿到 8 —— 而测试库 hikari 只有 5。trade-service 有十多个
 *   带 fixedRate/fixedDelay 且**无 initialDelay** 的 @Scheduled，会在上下文启动瞬间同时开跑：
 *   调度并发(8) > 连接池(5) → 连接池打满 → E2E 上下文 30s 拿不到连接直接加载失败
 *   （AdminE2ETest 5/5 ERROR，日志 "total=5, active=5, idle=0, waiting=8"）。
 *   全仓单测从 913/0 退化为 915/5。
 *
 * 为什么判据必须带「继承」这一步（否则恒真的假绿）：
 *   若只看 application-test.yml，把测试里的 pin 删掉后该文件根本不含 scheduling 键，
 *   按 Spring 默认 1 代入 → 1 ≤ 5 → 判绿。可实际生效值是 main yml 的 8 —— 正是本次事故。
 *   所以这里对测试 profile 做**两层回退**：test 声明值 → main 声明值 → Spring 默认值。
 *
 * 边界（诚实声明）：
 *   - 只校验**声明值**（含 `${VAR:默认}` 的默认值）。部署期若用环境变量把池改小、
 *     把并发调大，本门禁看不到 —— 那一侧由**启动期自检**
 *     `SchedulingPoolCapacitySelfCheck.java`（`config/`，判运行时**有效值**）兜底，两侧判据同源、
 *     取值口径不同。改判据时**必须同时改两侧**，否则会留下一个只有一侧能发现的盲区。
 *   - `并发 ≤ 连接池` 是**必要条件而非充分条件**：还要求任务不长期持锁握连接。
 *
 *   node scripts/check-scheduling-vs-db-pool.mjs
 */
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-scheduling-vs-db-pool]';

// Spring Boot 默认值（未声明时的兜底）
const DEFAULT_SCHEDULING_POOL = 1; // spring.task.scheduling.pool.size
const DEFAULT_HIKARI_POOL = 10; // spring.datasource.hikari.maximum-pool-size

const SCHED_PATH = ['spring', 'task', 'scheduling', 'pool', 'size'];
const HIKARI_PATH = ['spring', 'datasource', 'hikari', 'maximum-pool-size'];

/** `${VAR:默认值}` / `${VAR}` / 字面量 → 数值；解析不出返回 undefined（由调用方兜底） */
function toNumber(raw) {
  if (raw == null) return undefined;
  let text = String(raw).trim();
  const env = text.match(/^\$\{([^:}]+)(?::([^}]*))?\}$/);
  if (env) {
    if (env[2] === undefined || env[2] === '') return undefined;
    text = env[2].trim();
  }
  text = text.replace(/^['"]|['"]$/g, '').trim();
  const n = Number(text);
  return Number.isFinite(n) && n > 0 ? n : undefined;
}

/**
 * 极简缩进式 YAML 取值：只支持「映射套映射 + 标量叶子」，够用且无第三方依赖。
 * 返回该路径的原始字符串值；不存在返回 undefined。
 */
function yamlGet(text, path) {
  const stack = []; // [{ indent, key }]
  for (const rawLine of text.split(/\r?\n/)) {
    if (!rawLine.trim() || /^\s*#/.test(rawLine)) continue;
    const indent = rawLine.match(/^ */)[0].length;
    const line = rawLine.replace(/\s+#.*$/, '');
    const m = line.match(/^\s*([A-Za-z0-9_.-]+)\s*:\s*(.*)$/);
    if (!m) continue;
    const key = m[1];
    const value = m[2].trim();
    while (stack.length && stack[stack.length - 1].indent >= indent) stack.pop();
    if (value === '') {
      stack.push({ indent, key });
      continue;
    }
    const keys = [...stack.map((s) => s.key), key];
    if (keys.length === path.length && keys.every((k, i) => k === path[i])) return value;
  }
  return undefined;
}

function walk(dir, out = []) {
  if (!existsSync(dir)) return out;
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) walk(full, out);
    else if (entry.name.endsWith('.java')) out.push(full);
  }
  return out;
}

/** 哪些服务真正用了 @Scheduled —— 有新服务引入时必须一并纳入判据，否则门禁覆盖不全 */
function servicesWithScheduledTasks() {
  const servicesDir = join(root, 'services');
  const found = [];
  for (const entry of readdirSync(servicesDir, { withFileTypes: true })) {
    if (!entry.isDirectory()) continue;
    const src = join(servicesDir, entry.name, 'src', 'main', 'java');
    if (!existsSync(src)) continue;
    const hit = walk(src).find((f) => readFileSync(f, 'utf8').includes('@Scheduled('));
    if (hit) found.push(entry.name);
  }
  return found.sort();
}

const services = servicesWithScheduledTasks();
if (!services.length) {
  console.error(`${TAG} FAIL: 没有任何服务含 @Scheduled，锚点可能已被重写`);
  process.exit(1);
}

const problems = [];
const report = [];

for (const service of services) {
  const mainRel = `services/${service}/src/main/resources/application.yml`;
  const testRel = `services/${service}/src/test/resources/application-test.yml`;
  const mainPath = join(root, mainRel);
  if (!existsSync(mainPath)) {
    problems.push(`${mainRel} 不存在，无法判定 ${service} 的容量一致性`);
    continue;
  }
  const mainText = readFileSync(mainPath, 'utf8');
  const mainSched = toNumber(yamlGet(mainText, SCHED_PATH));
  const mainPool = toNumber(yamlGet(mainText, HIKARI_PATH));

  // main 自身：声明值（缺省按 Spring 默认）必须满足 并发 ≤ 池
  const mainSchedEff = mainSched ?? DEFAULT_SCHEDULING_POOL;
  const mainPoolEff = mainPool ?? DEFAULT_HIKARI_POOL;
  report.push(
    `  ${mainRel}: scheduling=${mainSchedEff}${mainSched ? '' : '(默认)'} ` +
      `hikari=${mainPoolEff}${mainPool ? '' : '(默认)'}`
  );
  if (mainSchedEff > mainPoolEff) {
    problems.push(
      `${mainRel}: @Scheduled 并发 ${mainSchedEff} > 连接池 ${mainPoolEff} —— ` +
        `上下文启动瞬间多个定时任务会同时抢连接`
    );
  }

  if (!existsSync(join(root, testRel))) continue;
  const testText = readFileSync(join(root, testRel), 'utf8');
  const testSchedDeclared = toNumber(yamlGet(testText, SCHED_PATH));
  const testPoolDeclared = toNumber(yamlGet(testText, HIKARI_PATH));
  // 关键：测试 profile 未声明时**继承 main**，不是回落到 Spring 默认
  const testSchedEff = testSchedDeclared ?? mainSchedEff;
  const testPoolEff = testPoolDeclared ?? mainPoolEff;
  report.push(
    `  ${testRel}: scheduling=${testSchedEff}` +
      `${testSchedDeclared ? '(声明)' : '(继承 main)'} ` +
      `hikari=${testPoolEff}${testPoolDeclared ? '(声明)' : '(继承 main)'}`
  );
  if (testSchedEff > testPoolEff) {
    problems.push(
      `${testRel}: 测试环境下 @Scheduled 并发 ${testSchedEff}` +
        `${testSchedDeclared ? '' : '（继承自 main，未在测试 profile 显式钉住）'} ` +
        `> 连接池上限 ${testPoolEff} —— E2E 上下文会因拿不到连接而加载失败。` +
        `请在测试 profile 显式钉住 spring.task.scheduling.pool.size`
    );
  }
}

if (problems.length) {
  console.error(`${TAG} FAIL:\n  - ${problems.join('\n  - ')}\n\n实际取值：\n${report.join('\n')}`);
  process.exit(1);
}

console.log(`${TAG} OK（${services.length} 个含 @Scheduled 的服务，并发均未超过连接池上限）`);
console.log(report.join('\n'));
