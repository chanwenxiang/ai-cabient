#!/usr/bin/env node
/**
 * check-flyway-repair-needed 的夹具（S8④）。
 * 测不依赖 DB 的确定性判据：缺环境变量 ⇒ fail-closed 红。
 * DB 相关的漂移检测由真实库实跑覆盖（本夹具不碰 DB）。
 */
import { spawn } from 'node:child_process';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-flyway-repair-needed.test]';
let pass = 0;
let fail = 0;

function check(name, cond, detail = '') {
  if (cond) {
    pass += 1;
    console.log(`  ✓ ${name}`);
  } else {
    fail += 1;
    console.error(`  ✗ ${name}${detail ? ' —— ' + detail : ''}`);
  }
}

function runScript(env) {
  return new Promise((resolveP) => {
    const child = spawn('node', [join(ROOT, 'scripts', 'flyway-repair-precheck.mjs')], {
      cwd: ROOT,
      env: { ...process.env, ...env },
      stdio: ['ignore', 'pipe', 'pipe']
    });
    let out = '';
    let err = '';
    child.stdout.on('data', (d) => (out += d.toString()));
    child.stderr.on('data', (d) => (err += d.toString()));
    child.on('close', (code) => resolveP({ code, out, err, full: out + err }));
  });
}

// 1) 缺 DB 环境变量 ⇒ fail-closed 红
{
  const r = await runScript({
    FLYWAY_REPAIR_DB_HOST: undefined,
    FLYWAY_REPAIR_DB_NAME: undefined,
    FLYWAY_REPAIR_DB_USER: undefined
  });
  check(
    '缺 DB 环境变量=红（fail-closed）',
    r.code !== 0 && r.full.includes('缺 DB 连接环境变量'),
    `code=${r.code} out=${r.full.slice(0, 120)}`
  );
}

// 2) 只给部分环境变量（缺 NAME）⇒ 红
{
  const r = await runScript({
    FLYWAY_REPAIR_DB_HOST: '127.0.0.1',
    FLYWAY_REPAIR_DB_NAME: undefined,
    FLYWAY_REPAIR_DB_USER: 'aicabinet'
  });
  check('缺 NAME=红', r.code !== 0 && r.full.includes('缺 DB 连接环境变量'), `code=${r.code}`);
}

console.log(`\n[check-flyway-repair-needed.test] ${pass} 通过 / ${fail} 失败`);
if (fail > 0) process.exit(1);
