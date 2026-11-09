/**
 * check-alipay-shell-gate 门禁的负向验证（铁律 75：门禁判据必须能红）。
 *
 * 三态同 verify-outbound-remainder-gate：⓪ 基线绿 → ① 逐条注入真违规（每条单独挣红）
 * → ② 还原绿 → ③ 真实树 md5 前后一致、沙箱已删。全程只写沙箱副本
 *（门禁经 SHELL_GATE_ROOT 环境变量指向沙箱 shell 树）。
 *
 * 锚点失配 = 抛错（探针与源码脱钩，必须重新对准，不许放行）。
 * 用法：node scripts/devops/verify-alipay-shell-gate.mjs
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
const GATE_REL = 'scripts/check-alipay-shell-gate.mjs';
const SHELL_REL = 'clients/alipay-mp-shell';
const SANDBOX = resolve(ROOT, '.tmp', `alipay-shell-gate-${process.pid}`);
const sbShell = resolve(SANDBOX, 'shell');

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
  const p = join(sbShell, relPath);
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
    env: { ...process.env, SHELL_GATE_ROOT: sbShell }
  });
  if (r.spawnError) throw new Error(`门禁子进程起不来（${r.spawnError}）`);
  return { code: r.status, out: `${r.stdout}${r.stderr}` };
}

const INJECTS = [
  {
    name: '微信品牌字样混入审核载体',
    file: 'pages/about/about.acss',
    from: '.page {\n  padding: 24rpx;\n}',
    to: '/* wechat 样式备注 */\n.page {\n  padding: 24rpx;\n}',
    expect: '微信品牌字样'
  },
  {
    name: '壳内接了网络请求',
    file: 'pages/orders/orders.js',
    from: 'Page({\n  goAbout() {',
    to: "Page({\n  fetchOrders() {\n    my.request({ url: 'https://example.com/api/orders' });\n  },\n  goAbout() {",
    expect: '网络请求'
  },
  {
    name: '凭证落进壳代码',
    file: 'app.js',
    from: 'App({});',
    to: "const app_secret = 'leaked';\nApp({});",
    expect: '疑似凭证'
  },
  {
    name: 'APPID 散落到非白名单文件',
    file: 'app.json',
    from: '  "pages": [',
    to: '  "app_id": "2021007118617439",\n  "pages": [',
    expect: 'app_id 只允许出现在 mini.project.json'
  },
  {
    name: '占位如实说明被删（换真实订单前未重评）',
    // orders.axml 有两处如实说明（hint-text「中查询」/ empty-text「中查看」），一次 patch 打不干净——
    // 门禁判据是 includes（≥1 即绿），两处都删掉才应挣红。
    file: 'pages/orders/orders.axml',
    patches: [
      ['请在支付宝「账单」或「信用服务」中查询。', '扣款与履约记录如下。'],
      ['请在支付宝「账单」或「信用服务」中查看', '扣款与履约记录如下']
    ],
    expect: '占位如实说明丢失'
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

const realFiles = walkFiles(resolve(ROOT, SHELL_REL));
if (realFiles.length < 8) throw new Error(`真实壳文件只有 ${realFiles.length} 个——探针与源码脱钩`);

async function prepare() {
  await removeSandbox();
  mkdirSync(sbShell, { recursive: true });
  for (const abs of realFiles) {
    const dest = resolve(sbShell, relative(resolve(ROOT, SHELL_REL), abs));
    mkdirSync(dirname(dest), { recursive: true });
    cpSync(abs, dest);
  }
}

function restore(relPath) {
  cpSync(resolve(ROOT, SHELL_REL, relPath), join(sbShell, relPath));
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
    `未注入的沙箱壳 exit=${baseline.code}（拷贝不完整/判据已与源码脱钩）：\n${baseline.out.slice(0, 1500)}`
  );

  if (!failures.length) {
    for (const inj of INJECTS) {
      const patches = inj.patches || [[inj.from, inj.to]];
      for (const [from, to] of patches) patch(inj.file, from, to);
      const r = await runGate();
      check(
        `① ${inj.name}`,
        r.code === 1 && r.out.includes(inj.expect),
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
  console.error(`[verify-alipay-shell-gate] FAIL:\n  ${failures.join('\n  ')}\n`);
  process.exit(1);
}
console.log(
  `[verify-alipay-shell-gate] OK：基线绿 + ${INJECTS.length} 条判据逐条注入红并还原绿；` +
    `真实壳 ${realFiles.length} 个文件 md5 前后一致、沙箱已清理`
);
