#!/usr/bin/env node
/**
 * 门禁：发布前 Flyway **checksum 漂移预检**——已应用迁移被改写后，目标库的
 * `flyway_schema_history` 存旧 checksum，不 repair 则 Flyway validate 在启动时拒服
 * （2026-11-13 S14 修复批改了 8 个已应用迁移，部署链无 repair 步骤）。
 *
 * 本脚本是**发布前人工步骤**（不进 deploy 脚本自动跑，避免 repair 掩盖漂移——
 * 见 check-migration-immutable 铁律「repair 会掩盖问题」）。
 * 机制：用 Flyway 自己的 validate()（Java helper，权威判据，不自算 checksum）
 *   连目标库跑 validate：
 *     - VALIDATE_OK ⇒ 无漂移，可部署
 *     - VALIDATE_DRIFT ⇒ 红，点名「先对该库跑 flyway repair 再部署」
 *     - DB 不可达 ⇒ fail-closed（枚举不到 ≠ 没有漂移）
 *
 * 用法（环境变量）：
 *   FLYWAY_REPAIR_DB_HOST / FLYWAY_REPAIR_DB_PORT / FLYWAY_REPAIR_DB_NAME
 *   FLYWAY_REPAIR_DB_USER / FLYWAY_REPAIR_DB_PASS
 *   node scripts/check-flyway-repair-needed.mjs
 */
import { existsSync, readFileSync, statSync, readdirSync, mkdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-flyway-repair-needed]';
const HELPER_SRC = join(ROOT, 'scripts', 'FlywayValidateHelper.java');
const MIG_DIR = join(
  'services',
  'trade-service',
  'src',
  'main',
  'resources',
  'db',
  'migration'
);

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

function runCmd(cmd, args, opts = {}) {
  return new Promise((resolveP) => {
    const child = spawn(cmd, args, { cwd: ROOT, stdio: ['ignore', 'pipe', 'pipe'], ...opts });
    let out = '';
    let err = '';
    child.stdout.on('data', (d) => (out += d.toString()));
    child.stderr.on('data', (d) => (err += d.toString()));
    child.on('close', (code) => resolveP({ code, out, err }));
  });
}

/** m2 布局：<group>/<artifact>/<version>/<artifact>-<version>.jar，下钻版本目录取最新 jar */
function findJar(m2Dir, groupPath) {
  const baseDir = join(m2Dir, groupPath);
  if (!existsSync(baseDir)) return null;
  const versions = readdirSync(baseDir)
    .filter((d) => existsSync(join(baseDir, d)) && statSync(join(baseDir, d)).isDirectory())
    .sort();
  for (const v of [...versions].reverse()) {
    const jarDir = join(baseDir, v);
    const jars = readdirSync(jarDir).filter(
      (f) => f.endsWith('.jar') && !f.includes('sources') && !f.includes('javadoc')
    );
    if (jars.length) return join(jarDir, jars[jars.length - 1]);
  }
  return null;
}

function findJars(m2Dir) {
  return [
    findJar(m2Dir, 'org/flywaydb/flyway-core'),
    findJar(m2Dir, 'org/postgresql/postgresql'),
    findJar(m2Dir, 'org/slf4j/slf4j-api'),
    findJar(m2Dir, 'com/google/code/gson/gson'),
  ].filter(Boolean);
}

async function main() {
  const host = process.env.FLYWAY_REPAIR_DB_HOST;
  const port = process.env.FLYWAY_REPAIR_DB_PORT || '5432';
  const db = process.env.FLYWAY_REPAIR_DB_NAME;
  const user = process.env.FLYWAY_REPAIR_DB_USER;
  const pass = process.env.FLYWAY_REPAIR_DB_PASS;

  if (!host || !db || !user) {
    fail(
      '缺 DB 连接环境变量（FLYWAY_REPAIR_DB_HOST / FLYWAY_REPAIR_DB_NAME / FLYWAY_REPAIR_DB_USER / FLYWAY_REPAIR_DB_PASS）——本脚本是发布前人工步骤，需指定目标库'
    );
  }

  // ① 找 m2 jar
  const m2Candidates = [
    process.env.M2_REPO,
    process.env.USERPROFILE && join(process.env.USERPROFILE, '.m2', 'repository'),
    process.env.HOME && join(process.env.HOME, '.m2', 'repository'),
  ].filter(Boolean);
  let m2Dir = null;
  for (const c of m2Candidates) {
    if (existsSync(join(c, 'org/flywaydb/flyway-core'))) {
      m2Dir = c;
      break;
    }
  }
  if (!m2Dir) {
    fail(
      `找不到 .m2 仓库（试过 ${m2Candidates.join(', ')}）——请设 M2_REPO 环境变量指向本地 maven 仓库`
    );
  }
  const jars = findJars(m2Dir);
  if (jars.length < 3) {
    fail(`.m2 里找不到 Flyway/JDBC 依赖（找到 ${jars.length} 个，需 ≥3）——请确认 m2 仓库路径`);
  }

  // ② 编译 helper（class 输出到 .tmp）
  const tmpDir = join(ROOT, '.tmp', 'flyway-repair-check');
  mkdirSync(tmpDir, { recursive: true });
  const cp = jars.join(';');
  const compile = await runCmd('javac', ['-encoding', 'UTF-8', '-cp', cp, '-d', tmpDir, HELPER_SRC]);
  if (compile.code !== 0) {
    fail(`javac 编译 helper 失败：${compile.err.trim() || compile.out.trim()}`);
  }

  // ③ 运行 validate
  const url = `jdbc:postgresql://${host}:${port}/${db}`;
  const locations = `filesystem:${MIG_DIR}`;
  const run = await runCmd(
    'java',
    ['-cp', `${tmpDir};${cp}`, 'FlywayValidateHelper', url, user, pass || '', locations]
  );

  const out = (run.out + run.err).trim();
  if (run.code !== 0 && !out.includes('VALIDATE_')) {
    fail(`java 运行失败（exit=${run.code}）：${out.slice(0, 200)}`);
  }

  if (out.includes('VALIDATE_OK')) {
    console.log(`${TAG} OK：目标库 ${host}:${port}/${db} Flyway validate 通过，无 checksum 漂移，可部署`);
    return;
  }

  if (out.includes('VALIDATE_DRIFT')) {
    fail(
      `目标库 ${host}:${port}/${db} 有 checksum 漂移（Flyway validate 拒绝）：\n` +
        `  ${out}\n` +
        `正解：部署前对该库执行 flyway repair（重算 checksum），再部署。` +
        '本脚本是发布前人工步骤——不自动 repair（repair 会掩盖漂移，见 check-migration-immutable 铁律）。'
    );
  }

  fail(`无法解析 validate 输出：${out.slice(0, 200)}`);
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  await main();
}
