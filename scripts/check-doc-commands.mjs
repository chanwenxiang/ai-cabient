#!/usr/bin/env node
/**
 * 门禁：排障文档命令可执行 + 部署脚本失败必须非零退出（F8 P1）。
 *
 * R1 docs 不得再用错库名 ai_cabinet（实际 POSTGRES_DB=aicabinet）
 * R2 pg_restore 不得出现 --time（PITR 靠 WAL 重放，不是 pg_restore 参数）
 * R3 ValidateCheck 校验失败必须 System.exit(1)
 * R4 deploy-staging 必须先 check-env 再 up，且健康检查超时必须 exit 1
 */
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = process.env.GATE_ROOT
  ? resolve(process.env.GATE_ROOT)
  : resolve(dirname(fileURLToPath(import.meta.url)), '..');
const DOC = 'docs/TROUBLESHOOTING_GUIDE.md';
const VALIDATE = 'scripts/devops/flyway-repair/ValidateCheck.java';
const STAGING = 'scripts/deploy-staging.ps1';

export function evaluate(read) {
  const errors = [];
  const doc = read(DOC);
  if (!doc) return ['R0 排障文档读不到——扫描面塌了'];
  if (/-d\s+ai_cabinet\b/.test(doc)) {
    errors.push('R1 文档仍有 -d ai_cabinet（实际库名 aicabinet）');
  }
  // 只钉「命令行」：说明文字里写「pg_restore 没有 --time」是正确科普，不能判红。
  if (/^\s*pg_restore[^\n]*--time/m.test(doc)) {
    errors.push('R2 pg_restore 没有 --time 参数——照抄必失败，PITR 要 WAL 重放');
  }
  const validate = read(VALIDATE);
  const catchAt = validate.indexOf('VALIDATE=FAIL');
  const tail = catchAt >= 0 ? validate.slice(catchAt, catchAt + 400) : '';
  if (!/System\.exit\s*\(\s*1\s*\)/.test(tail)) {
    errors.push('R3 ValidateCheck 打 VALIDATE=FAIL 却以 0 退出——部署脚本会当成功');
  }
  const staging = read(STAGING);
  const envAt = staging.indexOf('check-env.ps1');
  const upAt = staging.indexOf('docker compose');
  const timeoutAt = staging.indexOf('if (-not $up)');
  if (envAt < 0 || upAt < 0) {
    errors.push('R4 deploy-staging 缺 check-env 或 compose up——扫描面塌了');
  } else {
    if (envAt > upAt) errors.push('R4 check-env 必须早于 docker compose up（fail-fast）');
    if (timeoutAt < 0 || !/exit\s+1/.test(staging.slice(timeoutAt, timeoutAt + 300))) {
      errors.push('R4 健康检查超时未 exit 1——会静默继续跑 e2e');
    }
  }
  return errors;
}

const invokedDirectly =
  process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (invokedDirectly) {
  const errors = evaluate((rel) => {
    const p = join(ROOT, rel);
    try {
      return readFileSync(p, 'utf8');
    } catch {
      return '';
    }
  });
  if (errors.length) {
    console.error(`[check-doc-commands] ${errors.length} 项失败：`);
    for (const e of errors) console.error(`  - ${e}`);
    process.exitCode = 1;
  } else {
    console.log('[check-doc-commands] OK：文档命令与部署失败退出语义均正确');
  }
}
