#!/usr/bin/env node
/**
 * 门禁：`dist/build/mp-weixin` 必须是「真·发布包」，不是 dev 包。
 *
 * 背景（2026-11-19 线上事故，用户报「扫码后提示真机访问不了电脑的 localhost」）：
 * `build:mp-weixin` 与 `build:mp-weixin:dev` **共用同一个输出目录**
 * `dist/build/mp-weixin`，而 `--mode development` 会把 API 基址编译成
 * `http://127.0.0.1:8080`（见 packages/shared-uni/src/api-base.ts 的 devFallback）。
 * 于是「跑 dev 构建做本地回归 → 顺手从同一目录上传」就会把 dev 包发到线上：
 * 线上所有请求打回环地址，真机必然失败，且**构建日志全绿**。
 *
 * 判据（三条，任一不满足即红）：
 *  1. 构建类型标记 `dist/build/.build-kind` 的 kind 必须是 `release`
 *     （由 scripts/mark-mp-build-kind.mjs 在构建末尾写入）。这条能识别
 *     「目录里躺着的其实是 dev 包」，且不依赖启发式——按 LAN 基址 grep 不可靠，
 *     因为提示文案里本来就写着 `http://192.168.1.8`。
 *  2. 产物里不得出现**带 scheme 的回环地址**（`http(s)://127.0.0.1|localhost`）。
 *     文案里的 `localhost` 字样、正则 `/localhost|127\.0\.0\.1/` 都不带 scheme，不误判。
 *  3. `project.config.json` 的 `urlCheck` 必须为 true、appid 必须是真 appid。
 *
 * 用法：
 *   node scripts/check-mp-release-bundle.mjs [distDir]
 *     - 传 distDir（构建脚本内联调用，cwd=包目录）：目录缺失即红，判据全严格；
 *     - 不传（聚合门禁链）：依次检查两端 dist/build/mp-weixin，**没有产物就跳过**
 *       ——CI 不构建小程序，硬红会把「没产物」误报成「产物是 dev 包」。
 *
 * 夹具：scripts/check-mp-release-bundle.test.mjs（S8④）。
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const TAG = '[check-mp-release-bundle]';
const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');

const DEFAULT_ROOTS = [
  'clients/consumer-mp/dist/build/mp-weixin',
  'clients/merchant-mp/dist/build/mp-weixin'
];

const LOOPBACK_URL_RE = /https?:\/\/(?:127\.0\.0\.1|localhost)(?=[:/\s"'`]|$)/i;
const SCAN_EXT = ['.js', '.json', '.wxml', '.wxss'];

/**
 * 纯判定：给已收集好的材料，返回问题清单（空数组=绿）。
 *
 * @param {{hasMarker:boolean, marker:object|null, files:{rel:string,src:string}[], config:object|null}} input
 * @returns {string[]}
 */
export function evaluateBundle(input) {
  const failures = [];

  // 1) 构建类型标记
  if (!input.hasMarker) {
    failures.push(
      '缺少构建类型标记 dist/build/.build-kind，无法确认这是发布包',
      '请用 pnpm build:mp-weixin 重新构建（它会写标记）后再上传'
    );
  } else if (!input.marker) {
    failures.push('无法解析 dist/build/.build-kind，删除后重新构建');
  } else if (input.marker.kind !== 'release') {
    failures.push(
      `当前产物是 ${input.marker.kind} 包（${input.marker.markedAt || '未知时间'}），不是发布包`,
      'dev 构建与 release 构建共用 dist/build/mp-weixin，dev 包的 API 基址是 127.0.0.1',
      '上传它会让线上所有请求打回环地址（真机报「访问不了电脑的 localhost」）',
      '请跑 pnpm build:mp-weixin（release）后再上传'
    );
  }

  // 2) 产物内不得有带 scheme 的回环基址
  const offenders = [];
  for (const f of input.files) {
    const m = f.src.match(LOOPBACK_URL_RE);
    if (m) offenders.push(`${f.rel} -> ${m[0]}`);
  }
  if (offenders.length) {
    failures.push(
      `产物里出现回环 API 基址（${offenders.length} 处）`,
      ...offenders.slice(0, 5),
      '这不是发布包：请跑 pnpm build:mp-weixin 重新构建'
    );
  }

  // 3) 产物配置
  if (!input.config) {
    failures.push('缺少 project.config.json');
  } else {
    const appid = String(input.config.appid || '').trim();
    if (!appid || appid === 'touristappid') {
      failures.push(`appid 无效：${appid || '(空)'}`);
    }
    if (input.config.setting?.urlCheck !== true) {
      failures.push(
        `urlCheck=${input.config.setting?.urlCheck}，发布包必须为 true（合法域名校验开启）`,
        '跑 build:mp-weixin 时 --mode release 会把产物改成 true'
      );
    }
  }

  return failures;
}

/** 收集一个产物的材料（不做判定）。 */
export function collectBundle(distDir) {
  const markerFile = join(distDir, '..', '.build-kind');
  const hasMarker = existsSync(markerFile);
  let marker = null;
  if (hasMarker) {
    try {
      marker = JSON.parse(readFileSync(markerFile, 'utf8'));
    } catch {
      marker = null;
    }
  }

  const files = [];
  function walk(dir) {
    for (const name of readdirSync(dir)) {
      const p = join(dir, name);
      if (statSync(p).isDirectory()) {
        walk(p);
        continue;
      }
      if (!SCAN_EXT.some((ext) => name.endsWith(ext))) continue;
      files.push({ rel: p.slice(distDir.length + 1), src: readFileSync(p, 'utf8') });
    }
  }
  walk(distDir);

  const configFile = join(distDir, 'project.config.json');
  let config = null;
  if (existsSync(configFile)) {
    try {
      config = JSON.parse(readFileSync(configFile, 'utf8'));
    } catch {
      config = null;
    }
  }

  return { hasMarker, marker, files, config };
}

function runOne(label, distDir) {
  const failures = evaluateBundle(collectBundle(distDir));
  if (failures.length) {
    console.error(`${TAG} FAIL（${label}）：${failures[0]}`);
    for (const line of failures.slice(1)) console.error(`  ${line}`);
    console.error(`  （产物目录：${distDir}）`);
    return false;
  }
  return true;
}

function main() {
  const arg = process.argv[2];

  // 构建脚本内联调用：目录必须存在，判据全严格
  if (arg) {
    const distDir = resolve(process.cwd(), arg);
    if (!existsSync(distDir)) {
      console.error(`${TAG} FAIL：产物目录不存在：${distDir}`);
      console.error('  先跑 build:mp-weixin');
      process.exit(1);
    }
    const bundle = collectBundle(distDir);
    const failures = evaluateBundle(bundle);
    if (failures.length) {
      console.error(`${TAG} FAIL：${failures[0]}`);
      for (const line of failures.slice(1)) console.error(`  ${line}`);
      process.exit(1);
    }
    console.log(`${TAG} OK：release 包（appid=${bundle.config.appid} urlCheck=true 无回环基址）`);
    return;
  }

  // 聚合链：没有产物就跳过（CI 不构建小程序）
  const present = DEFAULT_ROOTS.map((rel) => ({ rel, dir: join(ROOT, rel) })).filter((x) =>
    existsSync(x.dir)
  );
  if (present.length === 0) {
    console.log(`${TAG} OK：本机/CI 无小程序产物，跳过（构建时会由 build:mp-weixin 内联校验）`);
    return;
  }
  const bad = present.filter((x) => runOne(x.rel, x.dir) === false);
  if (bad.length) process.exit(1);
  console.log(`${TAG} OK：${present.length} 个产物均为 release 包`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main();
}
