#!/usr/bin/env node
/**
 * 门禁（S4①，第一批小刀）：JVM 容器时区必须「双钉相等」——ENV TZ 与 -Duser.timezone
 * 同时存在、取值相等、且钉 Asia/Shanghai（业务日桶 ScheduleZones 硬依赖）。
 *
 * 背景：2026-10-14 全容器 ENV TZ + /etc/localtime；R19-17（2026-11-12）给 JVM 补了
 * -Duser.timezone 作第二根钉（ENV TZ 依赖 tzdata 与符号链接同时正确——换 base image /
 * 精简镜像裁掉 tzdata 时静默退回 UTC，-Duser.timezone 不依赖任何文件）。
 * **本门禁首跑抓到 R19-17 的漏网**：Dockerfile.java-service 双钉了，但
 * infra/docker/trade-service.Dockerfile（full.yml:186 活构建）与 device-service.Dockerfile
 * （apps.yml:235 生产栈）只有 ENV TZ——已补齐；本门禁防再漂。
 *
 * 判定对象：ENTRYPOINT/CMD 里直接执行 `java` 的 Dockerfile（JVM 容器；nginx/github-runner
 * 等不以 java 为主进程的不在范围）。判据四条全 fail-closed：
 *   ① java 启动行必须有 `-Duser.timezone=<zone>`；
 *   ② 文件必须有 `ENV TZ=<zone>`；
 *   ③ 两处取值相等；
 *   ④ 取值必须是 Asia/Shanghai（值棘轮：业务日桶硬依赖，改区先过 allowlist 带理由）。
 *
 *   node scripts/check-tz-pinned.mjs
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-tz-pinned]';
const ALLOWLIST_PATH = join('scripts', 'tz-pinned-allowlist.json');
const EXPECTED_ZONE = 'Asia/Shanghai';
const SCAN_ROOTS = [ROOT, join(ROOT, 'infra'), join(ROOT, 'edge')];

/** 找出 ENTRYPOINT/CMD 里直接跑 java 的 Dockerfile，返回 {rel, javaLine}；readFn 供测试注入 */
export function findJvmDockerfiles(files, readFn = (rel) => readFileSync(join(ROOT, rel), 'utf8')) {
  const jvm = [];
  for (const rel of files) {
    const src = readFn(rel);
    const entry = src
      .split(/\r?\n/)
      .find((l) => /^(ENTRYPOINT|CMD)\s/.test(l.trim()) && /(^|[^\w-])java(\s|["']|$)/.test(l));
    if (entry) jvm.push({ rel, src, javaLine: entry.trim() });
  }
  return jvm;
}

/**
 * 纯判定：JVM 容器时区双钉校验。
 * @param {{rel:string, javaLine:string, src:string}} f
 * @param {{file:string, why:string}[]} allowlist
 */
export function evaluateTzPin(f, allowlist) {
  const problems = [];
  const userTz = f.javaLine.match(/-Duser\.timezone=([A-Za-z0-9_/+.-]+)/);
  const envTz = f.src.match(/^ENV\s+TZ=([A-Za-z0-9_/+.-]+)\s*$/m);
  if (!userTz) {
    problems.push(
      `${f.rel}: java 启动行缺 -Duser.timezone=<zone>（ENV TZ 依赖 tzdata 与符号链接，换 base image 即静默退 UTC——R19-17 第二根钉）`
    );
  }
  if (!envTz) {
    problems.push(`${f.rel}: 缺 ENV TZ=<zone>（容器层时区，日志与 cron 依赖）`);
  }
  if (userTz && envTz && userTz[1] !== envTz[1]) {
    problems.push(
      `${f.rel}: 双钉取值不等：-Duser.timezone=${userTz[1]} vs ENV TZ=${envTz[1]}（两根钉必须同值，否则行为取决于加载顺序）`
    );
  }
  const zone = userTz ? userTz[1] : envTz ? envTz[1] : null;
  if (zone && zone !== EXPECTED_ZONE) {
    problems.push(
      `${f.rel}: 时区=${zone} ≠ ${EXPECTED_ZONE}（业务日桶 ScheduleZones 硬依赖北京时区；确需改区先更新 allowlist 带理由）`
    );
  }
  return problems;
}

function walkDockerfiles(dir, out = []) {
  if (!existsSync(dir)) return out;
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    const st = statSync(full);
    if (st.isDirectory()) {
      if (['node_modules', 'target', '.git', 'dist'].includes(name)) continue;
      walkDockerfiles(full, out);
    } else if (/^Dockerfile/i.test(name) || /\.Dockerfile$/i.test(name)) {
      out.push(relative(ROOT, full).replace(/\\/g, '/'));
    }
  }
  return out;
}

function main() {
  const allowlistFile = join(ROOT, ALLOWLIST_PATH);
  if (!existsSync(allowlistFile)) fail(`allowlist 缺失 ${ALLOWLIST_PATH}`);
  const parsed = JSON.parse(readFileSync(allowlistFile, 'utf8'));
  const allowEntries = Array.isArray(parsed) ? parsed : parsed.entries || [];

  const seen = new Set();
  const files = [];
  for (const root of SCAN_ROOTS) {
    for (const rel of walkDockerfiles(root)) {
      if (!seen.has(rel)) {
        seen.add(rel);
        files.push(rel);
      }
    }
  }
  if (files.length === 0) {
    console.error(`${TAG} FAIL：没有扫到任何 Dockerfile（扫描根失效，零命中=假绿）`);
    process.exit(1);
  }

  const jvmFiles = findJvmDockerfiles(files);
  if (jvmFiles.length === 0) {
    console.error(`${TAG} FAIL：没有识别到任何 JVM 容器（判据退化，零命中=假绿）`);
    process.exit(1);
  }

  const allowed = new Map(allowEntries.map((e) => [e.file, e.why]));
  const problems = [];
  let excused = 0;
  for (const f of jvmFiles) {
    if (allowed.has(f.rel)) {
      excused++;
      continue;
    }
    problems.push(...evaluateTzPin(f, allowEntries));
  }
  for (const [file] of allowed) {
    if (!jvmFiles.some((f) => f.rel === file)) {
      problems.push(`allowlist 陈旧：${file} 已不是 JVM 容器（不再执行 java），豁免条目应删除`);
    }
  }

  if (problems.length > 0) {
    for (const p of problems) console.error(`${TAG} FAIL ${p}`);
    console.error(
      `${TAG} 共 ${problems.length} 项：JVM 容器必须 ENV TZ 与 -Duser.timezone 双钉 Asia/Shanghai`
    );
    process.exit(1);
  }
  console.log(
    `${TAG} OK：${files.length} 个 Dockerfile 中 ${jvmFiles.length} 个 JVM 容器全部双钉 ${EXPECTED_ZONE}` +
      (excused ? `（allowlist 豁免 ${excused}）` : '')
  );
}

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  main();
}
