#!/usr/bin/env node
/**
 * 门禁：消费者端「开门等待上限」必须与后端 `SESSION_OPENING_EXPIRE_SECONDS` 默认值一致。
 *
 * 背景（R19-P3-7，2026-11-12）：后端 `application.yml` 的
 * `opening-seconds: ${SESSION_OPENING_EXPIRE_SECONDS:60}` 到点即判开门超时并清扫会话，
 * 而消费端 index.vue 硬编码 90s 倒计时——60~90s 之间还在提示「已等待 N 秒，可安全取消」，
 * 服务端早已判定超时。纯前端用例护不住这种跨端契约（铁律 75），故用静态比对。
 *
 * 判据：
 *   ① 从后端 application.yml 取出 opening-seconds 的默认值（`${ENV:60}` 的 60）
 *   ② 从 consumer-mp 的 index.vue 取出 `OPENING_EXPIRE_SECONDS = <n>`
 *   ③ 两者必须相等；任一侧取不到也判红（fail-closed，别让改名静默失效）
 *
 * 用法：node scripts/check-open-timeout-contract.mjs [--self-test]
 */
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = process.env.GATE_ROOT
  ? resolve(process.env.GATE_ROOT)
  : join(dirname(fileURLToPath(import.meta.url)), '..');
const BACKEND_YML = 'services/trade-service/src/main/resources/application.yml';
const FRONTEND_VUE = 'clients/consumer-mp/src/pages/index/index.vue';

export function parseBackendSeconds(src) {
  const m = src.match(/opening-seconds:\s*\$\{[A-Z0-9_]+:(\d+)\}/);
  return m ? Number(m[1]) : null;
}

export function parseFrontendSeconds(src) {
  const m = src.match(/const OPENING_EXPIRE_SECONDS\s*=\s*(\d+)\s*;/);
  return m ? Number(m[1]) : null;
}

function runReal() {
  const backend = parseBackendSeconds(readFileSync(join(ROOT, BACKEND_YML), 'utf8'));
  const frontend = parseFrontendSeconds(readFileSync(join(ROOT, FRONTEND_VUE), 'utf8'));
  const problems = [];
  if (backend == null) {
    problems.push(`${BACKEND_YML} 找不到 opening-seconds: \${ENV:<n>} 默认值`);
  }
  if (frontend == null) {
    problems.push(`${FRONTEND_VUE} 找不到 const OPENING_EXPIRE_SECONDS = <n>;`);
  }
  if (!problems.length && backend !== frontend) {
    problems.push(
      `开门超时不一致：后端 ${backend}s vs 消费端 ${frontend}s —— ` +
        `后端到点即清扫会话，前端多出来的 ${Math.abs(backend - frontend)}s 会持续骗用户「可安全取消」`
    );
  }
  if (problems.length) {
    for (const p of problems) console.error(`[check-open-timeout-contract] ${p}`);
    process.exit(1);
  }
  console.log(`[check-open-timeout-contract] OK：前后端开门超时均为 ${backend}s`);
}

function runSelfTest() {
  let failed = 0;
  const be = parseBackendSeconds('    opening-seconds: ${SESSION_OPENING_EXPIRE_SECONDS:60}');
  const fe = parseFrontendSeconds(
    'const OPENING_EXPIRE_SECONDS = 60;\nconst openingSeconds = ref(OPENING_EXPIRE_SECONDS);'
  );
  if (be !== 60 || fe !== 60) {
    console.error('✗ 自测失败：正常形态解析不对', be, fe);
    failed++;
  }
  if (parseBackendSeconds('opening-seconds: 60') !== null) {
    console.error('✗ 自测失败：裸字面量不应被接受（须走 ${ENV:default} 便于生产覆盖）');
    failed++;
  }
  if (parseFrontendSeconds('const OPENING_SECONDS = 90;') !== null) {
    console.error('✗ 自测失败：改名后仍被当命中（fail-closed 失效）');
    failed++;
  }
  if (failed) {
    console.error(`check-open-timeout-contract --self-test: ${failed} 项失败`);
    process.exit(1);
  }
  console.log(
    'check-open-timeout-contract --self-test: 解析三态（正常/后端裸值/前端改名）全部生效'
  );
}

const invokedDirectly =
  process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1]);

if (invokedDirectly) {
  if (process.argv.includes('--self-test')) {
    runSelfTest();
  } else {
    runReal();
  }
}
