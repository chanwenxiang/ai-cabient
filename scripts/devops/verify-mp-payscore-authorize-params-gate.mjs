#!/usr/bin/env node
/**
 * 负向验证：小程序端授权页参数门禁**真能变红**（2026-10-26 铁律 75）。
 *
 * 🔴 为什么必须做：门禁若恒绿（判据写错 / 解析器扫不到真实文件 / 异常吞掉），
 * 则「已修」只是自我报告——本脚本把真实树**拷进沙箱**再注入违规，验证：
 *   ① 注入 queryString 形态 ⇒ 门禁**必须判红**且指出文件；
 *   ② 还原后 ⇒ 门禁**必须恢复绿**；
 *   ③ 真实树**零残留**（md5 断言逐文件比对）。
 *
 * 沙箱化理由：直接在真实树上注入会污染工作区（并发会话可能同时改这些文件）；
 * 且本仓有 safe-delete 保护，直接删改真实文件风险更高。
 *
 * 用法：node scripts/devops/verify-mp-payscore-authorize-params-gate.mjs
 */
import { createHash } from 'node:crypto';
import {
  cpSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  rmSync,
  statSync,
  writeFileSync
} from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const TAG = '[verify-mp-payscore-authorize-params-gate]';
const GATE = join(ROOT, 'scripts', 'check-mp-payscore-authorize-params.mjs');
const MP_APPS = ['clients/consumer-mp/src', 'clients/merchant-mp/src'];
const EXT = new Set(['.vue', '.ts', '.js', '.tsx', '.jsx']);

function fail(m) {
  console.error(`${TAG} FAIL: ${m}`);
  process.exit(1);
}

function collect(dir, out = []) {
  let entries;
  try {
    entries = readdirSync(dir);
  } catch {
    return out;
  }
  for (const name of entries) {
    if (name === 'node_modules' || name === 'dist' || name === 'unpackage') continue;
    const full = join(dir, name);
    let st;
    try {
      st = statSync(full);
    } catch {
      continue;
    }
    if (st.isDirectory()) collect(full, out);
    else if (EXT.has(name.slice(name.lastIndexOf('.')))) out.push(full);
  }
  return out;
}

function md5(file) {
  return createHash('md5').update(readFileSync(file)).digest('hex');
}

/**
 * 在指定根目录下跑门禁。
 * 🔴 必须跑**该目录下**的门禁副本：门禁的 ROOT 由「脚本自身位置」推导（scripts/..），
 * 跑真实树的门禁脚本 ⇒ 它扫的是真实树，沙箱注入永远看不见（恒绿假象）。
 */
function runGate(cwd) {
  const gate = join(cwd, 'scripts', 'check-mp-payscore-authorize-params.mjs');
  try {
    const out = execFileSync(process.execPath, [gate], {
      cwd,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe']
    });
    return { code: 0, out };
  } catch (e) {
    return { code: e.status ?? 1, out: `${e.stdout ?? ''}${e.stderr ?? ''}` };
  }
}

// ── 0. 真实树当前必须是绿的（否则前置状态就不对） ──
const before = runGate(ROOT);
if (before.code !== 0) fail(`真实树在注入前就不绿，无法做负向验证：\n${before.out}`);

// ── 1. 记录真实树 md5 基线 ──
const baseline = new Map();
for (const app of MP_APPS) {
  for (const f of collect(join(ROOT, app))) baseline.set(f, md5(f));
}
console.log(`${TAG} 基线：真实树 ${baseline.size} 个源码文件已记录 md5`);

// ── 2. 建沙箱，拷入真实树 ──
const sandbox = mkdtempSync(join(tmpdir(), 'mp-payscore-gate-neg-'));
try {
  for (const app of MP_APPS) {
    const src = join(ROOT, app);
    if (!existsDir(src)) continue;
    // ⚠️ 必须先建 app 的父目录再拷 app 本身，否则目录层级错位（app 本身含 'clients/xxx/src' 多段）
    mkdirSync(join(sandbox, app, '..'), { recursive: true });
    cpSync(src, join(sandbox, app), { recursive: true });
  }
  // 门禁脚本按 ROOT=scripts/.. 解析相对路径 ⇒ 沙箱里也放一份
  mkdirSync(join(sandbox, 'scripts'), { recursive: true });
  cpSync(GATE, join(sandbox, 'scripts', 'check-mp-payscore-authorize-params.mjs'));
  console.log(`${TAG} 沙箱：${sandbox}`);

  // ── 3. 注入违规：把合法 extraData 形态改回 queryString ──
  const target = join(sandbox, 'clients/consumer-mp/src/pages/verify/verify.vue');
  if (!existsFile(target)) fail('沙箱里找不到注入目标文件');
  const original = readFileSync(target, 'utf8');
  const broken = original.replace(
    /extraData:\s*\{\s*apply_permissions_token:\s*res\.signFormHtml\s*\}/,
    'queryString: `apply_permissions_token=${res.signFormHtml}`'
  );
  if (broken === original) fail('注入失败：未能在目标文件里替换 extraData 为 queryString');
  if (!broken.includes('queryString')) fail('注入后文件未含 queryString，注入本身无效');
  writeFileSync(target, broken, 'utf8');
  console.log(`${TAG} 已注入违规形态（queryString）`);

  // ── 4. 沙箱内门禁必须判红 ──
  const during = runGate(sandbox);
  if (during.code === 0) {
    fail(`🔴 门禁对注入的违规形态没有判红（恒绿）——判据无效：\n${during.out}`);
  }
  if (!/verify\.vue/.test(during.out)) {
    fail(`门禁虽判红但未指出违规文件，输出不精确：\n${during.out}`);
  }
  if (!/queryString/.test(during.out)) {
    fail(`门禁判红原因未指出 queryString，诊断信息不合格：\n${during.out}`);
  }
  console.log(`${TAG} 注入态：门禁按预期判红并指出 verify.vue ✔`);

  // ── 5. 还原沙箱 ⇒ 必须恢复绿 ──
  writeFileSync(target, original, 'utf8');
  const after = runGate(sandbox);
  if (after.code !== 0) {
    fail(`还原后门禁仍判红（判据不可逆或沙箱被污染）：\n${after.out}`);
  }
  console.log(`${TAG} 还原态：门禁恢复绿 ✔`);
} finally {
  rmSync(sandbox, { recursive: true, force: true });
}

// ── 6. 真实树零残留断言（逐文件 md5 比对） ──
const now = new Map();
for (const app of MP_APPS) {
  for (const f of collect(join(ROOT, app))) now.set(f, md5(f));
}
if (now.size !== baseline.size) {
  fail(`真实树文件数变化（${baseline.size} → ${now.size}），存在残留`);
}
for (const [f, h] of baseline) {
  if (!now.has(f)) fail(`真实树文件缺失：${relative(ROOT, f)}`);
  if (now.get(f) !== h) fail(`真实树文件被改动（md5 不一致）：${relative(ROOT, f)}`);
}
console.log(`${TAG} 真实树零残留：${now.size} 个文件 md5 全部一致 ✔`);

console.log(`${TAG} OK：门禁具备真实判红能力（注入红 → 还原绿 + 零残留）。`);

function existsDir(p) {
  try {
    return statSync(p).isDirectory();
  } catch {
    return false;
  }
}
function existsFile(p) {
  try {
    return statSync(p).isFile();
  } catch {
    return false;
  }
}
