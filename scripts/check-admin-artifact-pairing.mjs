#!/usr/bin/env node
/**
 * 门禁：待推范围内改了「会进 admin 包」的源，就必须同范围内也有 static/admin 产物提交。
 *
 * 为什么需要它（2026-11-05 实测踩坑，lessons #416）：
 *   第九刀只改了 packages/shared-dict 的一个字典标签（AISLE_AUDIT「货道巡检」→「盘点巡检」），
 *   该字符串被 **打进 admin bundle** ⇒ CI `admin-artifacts` job 重跑 `node scripts/build-admin.mjs`
 *   后 `git status --porcelain -- static/admin` 非空 ⇒ 远端直接红。
 *   而本地 pre-push 预检的 96 个门禁**一条都不看产物同步**，所以我那句「96/0 真绿」是盲区。
 *   CI 的判据是「字节级重建比对」，本地要复刻就得跑容器级 fidelity 构建（约 8 分钟）——
 *   那不合适的每次预检的常态；本门禁改判「**提交范围级配对**」：改动面与产物面必须同时出现。
 *
 * 判据（刻意窄，宁可漏报不误伤）：
 *   - 输入面：clients/admin-vue/src/ 下任意文件、packages/<pkg>/src/ 下任意文件、
 *     packages/shared-dict/dist/ 下任意文件
 *     （admin 构建实际会读的东西；mp 自己的 clients/merchant-mp、consumer-mp 不算）
 *   - 产物面：services/trade-service/src/main/resources/static/admin/**
 *   - 范围 = merge-base(BASE, HEAD)..HEAD，BASE 默认 origin/dev（可用 ADMIN_PAIR_BASE 覆盖）
 *   - 输入面非空 且 产物面为空 ⇒ FAIL
 *
 * 已知误伤：只改上述文件里的**注释**也可能被拦——但「注释改不改 bundle」本地无法便宜证明
 * （本轮那次改的正是标签字符串旁边一行的语义），所以选择让跑一次 `build-admin-ci-fidelity.sh`
 * 用结果说话，而不是留一个永远查不到的洞。
 *
 * 自检：`--selftest` 用合成范围验判据会红（含“只改 mp”“只改注释面但带产物”两类放行）。
 */
import { gitAsync } from './lib/async-spawn.mjs';

const ADMIN_INPUT_PATTERNS = [
  /^clients\/admin-vue\/src\//,
  /^packages\/[^/]+\/src\//,
  /^packages\/shared-dict\/dist\//
];
const ARTIFACT_PREFIX = 'services/trade-service/src/main/resources/static/admin/';

/** 纯判据：喂文件清单，返回违规信息（null = 通过）。 */
export function pairingError(files) {
  const inputs = files.filter((f) => ADMIN_INPUT_PATTERNS.some((re) => re.test(f)));
  const artifacts = files.filter((f) => f.startsWith(ARTIFACT_PREFIX));
  if (!inputs.length || artifacts.length) return null;
  return (
    `待推范围改了 ${inputs.length} 个会进 admin 包的源，却没有 static/admin 产物提交。\n` +
    `  改动样例：${inputs.slice(0, 5).join(', ')}${inputs.length > 5 ? ' …' : ''}\n` +
    `  修法：bash scripts/build-admin-ci-fidelity.sh（容器内检出 HEAD 干净重建），` +
    `把产物变化一并提交；CI 会用同一判据重跑 build-admin.mjs 并比对字节。`
  );
}

if (process.argv.includes('--selftest')) {
  const cases = [
    {
      why: '改 shared-dict 标签但没重打产物 ⇒ 必须红（本轮真实事故）',
      files: ['packages/shared-dict/src/index.ts', 'services/trade-service/src/main/java/X.java'],
      wantRed: true
    },
    {
      why: '改 admin 源且同范围带产物 ⇒ 放行',
      files: [
        'clients/admin-vue/src/views/a/A.vue',
        `${ARTIFACT_PREFIX}assets/a-1.js`,
        `${ARTIFACT_PREFIX}index.html`
      ],
      wantRed: false
    },
    {
      why: '只改小程序端（不进 admin 包）⇒ 放行',
      files: ['clients/merchant-mp/src/pages/x/y.vue', 'clients/consumer-mp/src/utils/z.ts'],
      wantRed: false
    },
    { why: '纯后端/文档改动 ⇒ 放行', files: ['services/a/B.java', 'docs/C.md'], wantRed: false }
  ];
  let bad = 0;
  for (const c of cases) {
    const red = pairingError(c.files) !== null;
    const ok = red === c.wantRed;
    if (!ok) bad++;
    console.log(`  [${ok ? 'ok' : 'BAD'}] ${c.why}（${red ? '红' : '绿'}）`);
  }
  if (bad) {
    console.error('[check-admin-artifact-pairing] FAIL：自检 ' + bad + ' 例不符 ⇒ 判据可能恒绿');
    process.exit(1);
  }
  console.log('[check-admin-artifact-pairing] --selftest 通过（4 例，含真事故复现）');
  process.exit(0);
}

const base = process.env.ADMIN_PAIR_BASE || 'origin/dev';
const mb = await gitAsync(['merge-base', base, 'HEAD']);
if (!mb.ok) {
  console.log(
    `[check-admin-artifact-pairing] SKIP：取不到 ${base} 与 HEAD 的 merge-base（CI 干净检出/首次推送）`
  );
  process.exit(0);
}
const head = (await gitAsync(['rev-parse', 'HEAD'])).out;
if (mb.out === head) {
  console.log('[check-admin-artifact-pairing] OK：无待推提交');
  process.exit(0);
}
const listed = await gitAsync(['diff', '--name-only', `${mb.out}..${head}`]);
const files = listed.ok && listed.out ? listed.out.split('\n') : [];
const err = pairingError(files);
if (err) {
  console.error(`[check-admin-artifact-pairing] FAIL：${err}`);
  process.exit(1);
}
const touchedInputs = files.filter((f) => ADMIN_INPUT_PATTERNS.some((re) => re.test(f))).length;
console.log(
  `[check-admin-artifact-pairing] OK：范围 ${mb.out.slice(0, 8)}..${head.slice(0, 8)} ` +
    `共 ${files.length} 文件` +
    (touchedInputs ? `（含 ${touchedInputs} 个 admin 输入，产物已同批提交）` : '（不触 admin 包）')
);
