/**
 * check-merchant-me-gated-load 门禁的负向验证（铁律 75：门禁判据必须能红）。
 *
 * 三态同 verify-alipay-shell-gate：⓪ 基线绿 → ① 逐条注入真违规（每条单独挣红）
 * → ② 还原绿 → ③ 真实树 md5 前后一致、沙箱已删。全程只写沙箱副本
 *（门禁经 MERCHANT_ME_GATE_ROOT 环境变量指向沙箱 pages 树）。
 *
 * 锚点失配 = 抛错（探针与源码脱钩，必须重新对准，不许放行）。
 * 用法：node scripts/devops/verify-merchant-me-gated-load.mjs
 */
import {
  cpSync,
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  rmdirSync,
  unlinkSync,
  writeFileSync
} from 'node:fs';
import { createHash } from 'node:crypto';
import { dirname, join, relative, resolve } from 'node:path';
import { runAsync } from '../lib/async-spawn.mjs';

const ROOT = resolve(import.meta.dirname, '..', '..');
const GATE_REL = 'scripts/check-merchant-me-gated-load.mjs';
const PAGES_REL = join('clients', 'merchant-mp', 'src', 'pages');
const SANDBOX = resolve(ROOT, '.tmp', `merchant-me-gate-${process.pid}`);
const sbPages = resolve(SANDBOX, 'pages');

function walkFiles(dir, out = []) {
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name);
    if (e.isDirectory()) walkFiles(p, out);
    else out.push(p);
  }
  return out;
}

function md5(p) {
  return createHash('md5').update(readFileSync(p)).digest('hex');
}

function patch(relPath, from, to) {
  const p = join(sbPages, relPath);
  const s = readFileSync(p, 'utf8');
  const eol = s.includes('\r\n') ? '\r\n' : '\n';
  const f = from.replaceAll('\n', eol);
  const t = to.replaceAll('\n', eol);
  const hits = s.split(f).length - 1;
  if (hits !== 1) {
    throw new Error(`注入锚点在 ${relPath} 里出现 ${hits} 次（期望 1）：${from.slice(0, 70)}`);
  }
  writeFileSync(p, s.replace(f, t), 'utf8');
}

async function runGate() {
  const r = await runAsync(process.execPath, [resolve(ROOT, GATE_REL)], {
    cwd: ROOT,
    env: { ...process.env, MERCHANT_ME_GATE_ROOT: sbPages }
  });
  if (r.spawnError) throw new Error(`门禁子进程起不来（${r.spawnError}）`);
  return { code: r.status, out: `${r.stdout}${r.stderr}` };
}

const INJECTS = [
  {
    name: 'bills 的 await 被改成 fire-and-forget（首屏仍先于 me 到达）',
    file: join('bills', 'bills.vue'),
    from: 'await refreshMe();',
    to: 'refreshMe();',
    expect: 'bills/bills.vue'
  },
  {
    name: 'orders 的 ensure 先例被 void 化',
    file: join('orders', 'orders.vue'),
    from: 'await refreshMe();',
    to: 'void refreshMe();',
    expect: 'orders/orders.vue'
  },
  {
    name: '新页面加了权限拒绝却没拉 me（home 无 refreshMe）',
    file: join('home', 'home.vue'),
    from: '<script setup lang="ts">',
    to: '<script setup lang="ts">\nshowError(\'无注入测试权限\');',
    expect: 'home/home.vue'
  }
];

async function removeSandbox() {
  if (!existsSync(SANDBOX)) return;
  const script = `require('node:fs').rmSync(${JSON.stringify(SANDBOX)},{recursive:true,force:true})`;
  await runAsync(process.execPath, ['-e', script], { env: { CODEBUDDY_SAFE_DELETE_ENABLED: '0' } });
  if (!existsSync(SANDBOX)) return;
  const dirs = [];
  const walk = (d) => {
    dirs.push(d);
    for (const e of readdirSync(d, { withFileTypes: true })) {
      const p = resolve(d, e.name);
      if (e.isDirectory()) walk(p);
      else unlinkSync(p);
    }
  };
  walk(SANDBOX);
  for (const d of dirs.reverse()) rmdirSync(d);
}

const realFiles = walkFiles(resolve(ROOT, PAGES_REL)).filter((p) => /\.vue$/.test(p));
if (realFiles.length < 20)
  throw new Error(`真实页面文件只有 ${realFiles.length} 个——探针与源码脱钩`);

async function prepare() {
  await removeSandbox();
  mkdirSync(sbPages, { recursive: true });
  for (const abs of realFiles) {
    const dest = resolve(sbPages, relative(resolve(ROOT, PAGES_REL), abs));
    mkdirSync(dirname(dest), { recursive: true });
    cpSync(abs, dest);
  }
}

function restore(relPath) {
  cpSync(resolve(ROOT, PAGES_REL, relPath), join(sbPages, relPath));
}

const failures = [];
const hashesBefore = realFiles.map((p) => [p, md5(p)]);

function check(label, ok, detail) {
  if (!ok) failures.push(`${label}：${detail}`);
}

await prepare();

try {
  const baseline = await runGate();
  check(
    '⓪ 基线',
    baseline.code === 0,
    `未注入的沙箱 pages exit=${baseline.code}（拷贝不完整/判据已与源码脱钩）：\n${baseline.out.slice(0, 1500)}`
  );

  if (!failures.length) {
    for (const inj of INJECTS) {
      patch(inj.file, inj.from, inj.to);
      const r = await runGate();
      const expectPath = inj.expect.replaceAll('/', '\\');
      const reported = r.out.includes(inj.expect) || r.out.includes(expectPath);
      check(
        `① ${inj.name}`,
        r.code === 1 && reported,
        `exit=${r.code}，期望 1 且报出「${inj.expect}」；实得：${r.out.slice(0, 600)}`
      );
      restore(inj.file);
      const back = await runGate();
      check(
        `② 还原后 ${inj.name}`,
        back.code === 0,
        `还原后仍 exit=${back.code}：\n${back.out.slice(0, 600)}`
      );
    }
  }
} finally {
  await removeSandbox();
  for (const [p, before] of hashesBefore) {
    check('③ 零污染', existsSync(p) && md5(p) === before, `${p} 被本验证脚本改写或丢失`);
  }
  check('③ 零污染', !existsSync(SANDBOX), '沙箱目录未清理');
}

if (failures.length) {
  console.error(`[verify-merchant-me-gated-load] FAIL:\n  ${failures.join('\n  ')}\n`);
  process.exit(1);
}
console.log(
  `[verify-merchant-me-gated-load] OK：基线绿 + ${INJECTS.length} 条判据逐条注入红并还原绿；` +
    `真实页面 ${realFiles.length} 个文件 md5 前后一致、沙箱已清理`
);
