#!/usr/bin/env node
/**
 * 负向验证：微信支付分文案门禁**真能变红**（2026-10-26 铁律 75）。
 *
 * 🔴 为什么要单独做：文案门禁最容易被「判据写太宽」退化成恒绿（本次开发中真实踩过两次：
 *   ① 用「同句含支付分+免密」判 ⇒ 把合法的渠道并列/渠道枚举也判红 → 判据过严；
 *   ② 收紧后 `staticTexts` 的单行正则漏掉跨行+含插值的模板文本 ⇒ 判据过松、恒绿。
 *   本脚本把这两种退化都做成**可复现的负向用例**。
 *
 * 用法：node scripts/devops/verify-mp-payscore-copywriting-gate.mjs
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
const TAG = '[verify-mp-payscore-copywriting-gate]';
const GATE_NAME = 'check-mp-payscore-copywriting.mjs';
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

/** 🔴 必须跑**该目录下**的门禁副本：门禁 ROOT 由脚本自身位置推导，跑真实树的门禁 ⇒ 扫的是真实树。 */
function runGate(cwd) {
  const gate = join(cwd, 'scripts', GATE_NAME);
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

// ── 0. 真实树当前必须绿 ──
const before = runGate(ROOT);
if (before.code !== 0) fail(`真实树在注入前就不绿：\n${before.out}`);

// ── 1. 真实树 md5 基线 ──
const baseline = new Map();
for (const app of MP_APPS) {
  for (const f of collect(join(ROOT, app))) baseline.set(f, md5(f));
}
console.log(`${TAG} 基线：真实树 ${baseline.size} 个源码文件已记录 md5`);

// ── 2. 沙箱 ──
const sandbox = mkdtempSync(join(tmpdir(), 'mp-payscore-copy-neg-'));
/** 负向用例：[用例名, 文件相对路径, 注入片段, 期望在输出中出现的关键词] */
const CASES = [
  {
    name: '禁词「无需每次确认」',
    file: 'clients/consumer-mp/src/components/open-prep-drawer.vue',
    from: '关门后自动按实际取货金额扣款。',
    to: '关门后自动扣款，无需每次确认。',
    expect: '无需每次确认'
  },
  {
    name: '渠道语义混用（跨行+含插值的模板文本）',
    file: 'clients/consumer-mp/src/pages/verify/verify.vue',
    from: '开通后扫码即开门，关门后自动按实际取货金额扣款。',
    to: '推荐开通支付分 / 免密代扣；',
    expect: '支付分'
  }
];

try {
  for (const app of MP_APPS) {
    const src = join(ROOT, app);
    try {
      if (!statSync(src).isDirectory()) continue;
    } catch {
      continue;
    }
    mkdirSync(join(sandbox, app, '..'), { recursive: true });
    cpSync(src, join(sandbox, app), { recursive: true });
  }
  mkdirSync(join(sandbox, 'scripts'), { recursive: true });
  cpSync(join(ROOT, 'scripts', GATE_NAME), join(sandbox, 'scripts', GATE_NAME));
  console.log(`${TAG} 沙箱：${sandbox}`);

  for (const c of CASES) {
    const target = join(sandbox, c.file);
    const original = readFileSync(target, 'utf8');
    if (!original.includes(c.from)) fail(`用例「${c.name}」注入锚点未找到：${c.from}`);
    writeFileSync(target, original.replace(c.from, c.to), 'utf8');

    const r = runGate(sandbox);
    if (r.code === 0) {
      fail(`🔴 用例「${c.name}」注入后门禁未判红（恒绿）——判据退化：\n${r.out}`);
    }
    if (!r.out.includes(c.expect)) {
      fail(`用例「${c.name}」判红但输出未含关键词「${c.expect}」，诊断不合格：\n${r.out}`);
    }
    console.log(`${TAG} 用例「${c.name}」：门禁按预期判红 ✔`);

    // 还原后必须恢复绿
    writeFileSync(target, original, 'utf8');
    const back = runGate(sandbox);
    if (back.code !== 0) fail(`用例「${c.name}」还原后门禁仍判红：\n${back.out}`);
  }
  console.log(`${TAG} 全部用例：注入红 → 还原绿 ✔`);
} finally {
  rmSync(sandbox, { recursive: true, force: true });
}

// ── 3. 真实树零残留 ──
const now = new Map();
for (const app of MP_APPS) {
  for (const f of collect(join(ROOT, app))) now.set(f, md5(f));
}
if (now.size !== baseline.size)
  fail(`真实树文件数变化（${baseline.size} → ${now.size}），存在残留`);
for (const [f, h] of baseline) {
  if (!now.has(f)) fail(`真实树文件缺失：${relative(ROOT, f)}`);
  if (now.get(f) !== h) fail(`真实树文件被改动（md5 不一致）：${relative(ROOT, f)}`);
}
console.log(`${TAG} 真实树零残留：${now.size} 个文件 md5 全部一致 ✔`);
console.log(`${TAG} OK：文案门禁具备真实判红能力（禁词 + 渠道混用两用例）。`);
