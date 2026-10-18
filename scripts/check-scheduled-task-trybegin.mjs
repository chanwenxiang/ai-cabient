#!/usr/bin/env node
/**
 * check-scheduled-task-trybegin.mjs — AU-173 门禁（2026-10-18 批次 6）
 *
 * 判据：ScheduledTaskRegistry 注册的每个定时任务，其 action 方法体必须包含
 * `taskService.tryBegin(`。缺守卫 ⇒ ① 无 `job:*` 分布式锁（多副本双跑）；
 * ② `scheduled_task.enabled` 停用开关被旁路（运营台停用按钮 + 上线手册「资金任务
 * 保持关」关不住）；③ last_run_at/last_result 不落库（看板恒空）。
 * 实证：platform-fund-daily / device-rental-daily 曾是 39 任务中唯二漏配（AU-173）。
 *
 * 负向自测：--self-test 用内嵌最小样例验证「无 tryBegin ⇒ 报错、有 ⇒ 通过」的判别力
 * （铁律 38：门禁自身要负向验证）。
 */
import { readFileSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
function fail(msg) {
  console.error(`[check-scheduled-task-trybegin] FAIL: ${msg}`);
  process.exit(1);
}

/** 抽取 registry 中 register("key", ..., bean::method) 的 (key, bean, method)。 */
function extractTasks(registrySrc) {
  const tasks = [];
  const re = /register\("([a-z0-9-]+)"[\s\S]*?(\w+)::(\w+)\)/g;
  for (const m of registrySrc.matchAll(re)) {
    tasks.push({ key: m[1], bean: m[2], method: m[3] });
  }
  return tasks;
}

/** 在 bean 源码中定位 `... method(` 声明，按花括号配平截取方法体。 */
function extractMethodBody(beanSrc, method) {
  const declRe = new RegExp(`\\b${method}\\s*\\(`);
  const lines = beanSrc.split('\n');
  for (let i = 0; i < lines.length; i++) {
    if (!declRe.test(lines[i])) continue;
    // 从声明行向下找到首个 `{`，之后做括号配平
    let body = '';
    let depth = 0;
    let started = false;
    for (let j = i; j < lines.length; j++) {
      body += lines[j] + '\n';
      for (const ch of lines[j]) {
        if (ch === '{') {
          depth++;
          started = true;
        } else if (ch === '}') depth--;
      }
      if (started && depth <= 0) break;
    }
    if (started) return body;
  }
  return null;
}

function runCheck(registrySrc, beanSources) {
  const problems = [];
  const tasks = extractTasks(registrySrc);
  if (tasks.length < 30) {
    problems.push(
      `registry 只解析出 ${tasks.length} 个任务（预期 30+）——register 解析正则与源码形态漂移，请同步本脚本`
    );
  }
  for (const t of tasks) {
    const src = beanSources.get(t.bean);
    if (src === undefined) {
      problems.push(
        `${t.key}：action 绑定 ${t.bean}::${t.method}，但找不到 ${t.bean}.java（bean 命名或包位置漂移，请同步本脚本的 beanSources 收集）`
      );
      continue;
    }
    const body = extractMethodBody(src, t.method);
    if (body === null) {
      problems.push(`${t.key}：无法在 ${t.bean}.java 定位方法 ${t.method}() —— 解析器漂移，请同步`);
      continue;
    }
    if (!body.includes('tryBegin(')) {
      problems.push(
        `${t.key}：action ${t.bean}::${t.method} 方法体没有 taskService.tryBegin(...) —— ` +
          `缺执行守卫：无 job:* 分布式锁（多副本双跑）+ scheduled_task.enabled 停用开关被旁路 ` +
          `+ last_run_at 不落库（AU-173 实证）。请在方法首行补 ` +
          `\`if (!taskService.tryBegin("${t.key}", <lease>)) return <skipped>;\` 并配 finish(SUCCESS/FAILED)`
      );
    }
  }
  return problems;
}

// ── --self-test：内嵌样例验证判别力 ─────────────────────────────────────────
if (process.argv.includes('--self-test')) {
  const registrySample = `
    register("with-guard", "有守卫", "T", "d", 600, beanA::guarded);
    register("no-guard", "无守卫", "T", "d", 600, beanB::unguarded);
  `;
  const beans = new Map([
    [
      'beanA',
      'public String guarded() { if (!taskService.tryBegin("with-guard", 600)) return "skipped"; return "ok"; }'
    ],
    ['beanB', 'public String unguarded() { return "do work without guard"; }']
  ]);
  const problems = runCheck(registrySample, beans);
  const noGuard = problems.find((p) => p.startsWith('no-guard'));
  const withGuardClean = !problems.some((p) => p.startsWith('with-guard'));
  if (!noGuard || !withGuardClean) {
    fail(`--self-test 判别力失效：problems=${JSON.stringify(problems)}`);
  }
  console.log('[check-scheduled-task-trybegin] self-test OK（无守卫 ⇒ 红 / 有守卫 ⇒ 绿）');
  process.exit(0);
}

// ── 执行 ─────────────────────────────────────────────────────────────────────
const registryPath = join(
  root,
  'services',
  'trade-service',
  'src',
  'main',
  'java',
  'com',
  'aicabinet',
  'trade',
  'service',
  'ScheduledTaskRegistry.java'
);
if (!existsSync(registryPath)) fail('找不到 ScheduledTaskRegistry.java');
const registrySrc = readFileSync(registryPath, 'utf8');

// 收集 bean 源码：同包优先，找不到再全仓搜（覆盖跨包 bean）
const serviceDir = join(
  root,
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
const beanSources = new Map();
for (const t of extractTasks(registrySrc)) {
  const p = join(serviceDir, `${t.bean}.java`);
  if (existsSync(p)) beanSources.set(t.bean, readFileSync(p, 'utf8'));
}
// 局部变量名反查类型：registry 里可能 `Type varName = provider.getIfAvailable()`
//（如 OpsFeeBillJob feeBillJob），method 引用用变量名 ⇒ 建立 变量名→类型名 映射后再查源码
{
  const varDecl = /([A-Z]\w+)\s+(\w+)\s*=\s*(?:opsFeeBillJobProvider|\w+Provider)\.getIfAvailable/g;
  const varToType = new Map();
  for (const m of registrySrc.matchAll(varDecl)) varToType.set(m[2], m[1]);
  for (const t of extractTasks(registrySrc)) {
    if (!beanSources.has(t.bean) && varToType.has(t.bean)) {
      const typeSrc = join(serviceDir, `${varToType.get(t.bean)}.java`);
      if (existsSync(typeSrc)) beanSources.set(t.bean, readFileSync(typeSrc, 'utf8'));
    }
  }
}
// 跨包兜底：用 registry import 区推断（registry 同包 import 有限，直接全 service 包扫一次）
if ([...beanSources.values()].some((x) => !x)) {
  const { readdirSync, statSync } = await import('node:fs');
  const walk = (dir) => {
    for (const f of readdirSync(dir)) {
      const fp = join(dir, f);
      if (statSync(fp).isDirectory()) walk(fp);
      else if (f.endsWith('.java')) {
        const name = f.replace(/\.java$/, '');
        if (!beanSources.has(name)) beanSources.set(name, readFileSync(fp, 'utf8'));
      }
    }
  };
  walk(serviceDir);
}

const problems = runCheck(registrySrc, beanSources);
if (problems.length) {
  console.error('[check-scheduled-task-trybegin] FAIL：以下任务缺执行守卫（tryBegin）：');
  for (const p of problems) console.error('  - ' + p);
  process.exit(1);
}
console.log(
  `[check-scheduled-task-trybegin] OK：${extractTasks(registrySrc).length} 个 registry 任务全部带 tryBegin 执行守卫`
);
