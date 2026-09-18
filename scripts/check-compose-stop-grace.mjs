#!/usr/bin/env node
/**
 * 停机宽限门禁 —— 每个 compose service **必须**声明 `stop_grace_period`，且不得低于所属分层的下限。
 *
 * 背景（2026-09-18 实测，非推演）
 * -----------------------------
 * 本机 Docker（29.3.1 / Docker Desktop）**没有** 10 秒的引擎默认值：不带 `stop_grace_period`
 * 起出来的容器 `Config.StopTimeout` 一律是 **1**，实测有效宽限也确实是 1 秒：
 *
 *   容器不理会 SIGTERM（本探针故意如此）  → docker stop 等 ~1s 后 SIGKILL，exit 137
 *   `--stop-timeout 10` 同样探针           → 等 ~10s 后 SIGKILL
 *   compose 写 `stop_grace_period: 30s`     → StopTimeout=30，docker stop 真的等满 32s
 *
 * ⇒ 1 秒宽限意味着：**任何一次正常停机里，只要进程的清理动作超过 1 秒就被 SIGKILL**。
 *   对被强杀的落盘型服务，代价是下次启动走崩溃恢复（`pg` 的 redo、`mysql` 的 InnoDB recovery）；
 *   对在飞请求的服务，代价是连接被硬断。而这一切**在开发环境完全静默**——`docker stop` 照常返回，
 *   只有退出码 137 和下次启动的 recovery 日志留痕，谁都不会去看。
 *
 * 顺带一条**必须配套**的坑（本门禁无法覆盖，故写在这里给读者）
 * ----------------------------------------------------------
 * 容器里进程是 **PID 1**，而内核会**忽略 PID 1 对「未安装 handler 的信号」**。
 * 所以「装了宽限」≠「能优雅停机」：`infra/monitoring/feishu-relay.py` 原先只 `serve_forever()`
 * 不装 handler，实测 `stop_grace_period: 30s` 下 `docker stop` 仍是**等满 33 秒后 exit 137**
 * ——宽限一秒不差地全浪费了。给它装上 SIGTERM handler 后才变成 3 秒 exit 0。
 * ⇒ **加宽限的同时要确认目标进程真的处理 SIGTERM**（PID 1 尤甚）。
 *
 * 分层（为什么不是一律一个值）
 * ---------------------------
 *   落盘型（postgres/xxl-job-mysql/redpanda/minio/sonarqube-db）  ≥ 60s
 *   常驻型（其余服务）                                            ≥ 30s
 *   一次性（minio-init）                                          ≥ 10s
 * 宽限是**上限而非延时**：进程退出后 `docker stop` 立刻返回（实测 mysql 0.57s 退出、redis 4ms
 * 落盘完成，都没有多等），所以给宽了不花钱，给窄了会丢数据。
 *
 * 判据只设**下限**、不钉死具体值：把 30s 调成 45s 是合法改动，不该假红；
 * 把 postgres 从 60s 调回 5s 才是要拦的回归。
 *
 * 防恒真（三条锚点，任一漂移即红）
 * -------------------------------
 *   - compose 文件数 ≥ MIN_FILES
 *   - 扫到的 service 总数 ≥ MIN_SERVICES
 *   - STATEFUL 里的名字必须在文件中真的出现（否则分层表已与文件脱节，规则静默失效）
 *
 * 用法
 * ----
 *   node scripts/check-compose-stop-grace.mjs
 *   node scripts/check-compose-stop-grace.mjs --list   # 打印 文件/服务/宽限 全表
 */
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-compose-stop-grace]';
const LIST = process.argv.includes('--list');

const fail = (msg) => {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
};

/** 落盘型：被 SIGKILL 的代价是下次启动走崩溃恢复，故给最宽的宽限。 */
const STATEFUL = new Set(['postgres', 'xxl-job-mysql', 'redpanda', 'minio', 'sonarqube-db']);
/** 一次性任务容器：起来干完就退，没有需要收尾的常驻状态。 */
const ONE_SHOT = new Set(['minio-init']);

const MIN_STATEFUL = 60;
const MIN_LONG_RUNNING = 30;
const MIN_ONE_SHOT = 10;

const MIN_FILES = 8;
const MIN_SERVICES = 30;

const minFor = (name) => {
  if (STATEFUL.has(name)) return MIN_STATEFUL;
  if (ONE_SHOT.has(name)) return MIN_ONE_SHOT;
  return MIN_LONG_RUNNING;
};

/** `30s` / `1m` / `1m30s` / `90` → 秒。返回 null 表示无法解析。 */
function toSeconds(v) {
  const s = String(v)
    .trim()
    .replace(/^["']|["']$/g, '');
  if (/^\d+$/.test(s)) return Number(s);
  let total = 0;
  let matched = false;
  const re = /(\d+(?:\.\d+)?)(h|m|s|ms)/g;
  let m;
  while ((m = re.exec(s)) !== null) {
    matched = true;
    const n = Number(m[1]);
    total += m[2] === 'h' ? n * 3600 : m[2] === 'm' ? n * 60 : m[2] === 'ms' ? n / 1000 : n;
  }
  return matched ? total : null;
}

/**
 * 逐文件解析 service 块（缩进法，不引 YAML 依赖：CI 上不保证有 docker，也不该为门禁装库）。
 * service 键在 2 空格缩进、其属性在 4 空格缩进；遇到下一个 2 空格键或顶层键即本块结束。
 */
function parseServices(text) {
  const lines = text.split('\n');
  const out = [];
  let inServices = false;
  let current = null;

  for (const line of lines) {
    if (!inServices) {
      if (/^services:\s*$/.test(line)) inServices = true;
      continue;
    }
    if (/^[A-Za-z_]/.test(line)) break; // 顶层键（volumes: / networks: / x-…）⇒ services 段结束

    const svc = line.match(/^ {2}([A-Za-z0-9_.-]+):\s*$/);
    if (svc) {
      current = { name: svc[1], grace: null, line: null };
      out.push(current);
      continue;
    }
    if (current) {
      const g = line.match(/^ {4}stop_grace_period:\s*(.+?)\s*$/);
      if (g) {
        current.grace = g[1];
        current.line = line;
      }
    }
  }
  return out;
}

const files = readdirSync(join(root, 'infra'))
  .filter((f) => /^docker-compose.*\.ya?ml$/.test(f))
  .sort();
if (files.length < MIN_FILES) {
  fail(
    `只找到 ${files.length} 个 infra/docker-compose*.yml（期望 ≥ ${MIN_FILES}）：命名规则可能已变`
  );
}

const problems = [];
const seen = new Set();
const table = [];
let total = 0;

for (const f of files) {
  const text = readFileSync(join(root, 'infra', f), 'utf8');
  const services = parseServices(text);
  if (!services.length) {
    fail(`${f} 里一个 service 都没解析到（anchor 漂了即失去判别力）`);
  }
  for (const s of services) {
    total++;
    seen.add(s.name);
    table.push({ file: f, name: s.name, grace: s.grace });

    if (s.grace === null) {
      problems.push(`${f} :: ${s.name} 未声明 stop_grace_period（默认会吃本机 1 秒宽限）`);
      continue;
    }
    const secs = toSeconds(s.grace);
    if (secs === null) {
      problems.push(`${f} :: ${s.name} 的 stop_grace_period 无法解析：${s.grace}`);
      continue;
    }
    const need = minFor(s.name);
    if (secs < need) {
      problems.push(
        `${f} :: ${s.name} 的 stop_grace_period=${s.grace}（${secs}s）低于下限 ${need}s` +
          (STATEFUL.has(s.name) ? '　← 落盘型被强杀会导致下次启动崩溃恢复' : '')
      );
    }
  }
}

if (total < MIN_SERVICES) {
  fail(`只解析出 ${total} 个 service（期望 ≥ ${MIN_SERVICES}）：解析逻辑可能已与文件结构脱节`);
}

const missingStateful = [...STATEFUL].filter((n) => !seen.has(n));
if (missingStateful.length) {
  fail(
    `分层表里的落盘型服务在实际文件中一个都没出现：${missingStateful.join(', ')}　` +
      '⇒ 分层规则已静默失效（改名或删掉了？请同步本脚本的 STATEFUL）'
  );
}

if (LIST) {
  for (const r of table) {
    console.log(`${String(r.grace ?? '(缺失)').padEnd(8)} ${r.file}  ${r.name}`);
  }
  process.exit(0);
}

if (problems.length) {
  console.error(`${TAG} FAIL: ${problems.length} 个 service 的停机宽限不合规：`);
  for (const p of problems) console.error(`  - ${p}`);
  console.error(
    `\n  下限：落盘型 ${MIN_STATEFUL}s、常驻型 ${MIN_LONG_RUNNING}s、一次性 ${MIN_ONE_SHOT}s。\n` +
      '  本机 Docker 没有 10s 引擎默认值，实测不带该键就是 1 秒宽限（超时即 SIGKILL，exit 137）。'
  );
  process.exit(1);
}

console.log(
  `${TAG} OK: ${files.length} 个 compose 文件、${total} 个 service 均已声明 stop_grace_period` +
    `（落盘型 ≥${MIN_STATEFUL}s / 常驻型 ≥${MIN_LONG_RUNNING}s / 一次性 ≥${MIN_ONE_SHOT}s）`
);
