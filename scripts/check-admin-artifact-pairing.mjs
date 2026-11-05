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
 * 已知误伤（第十一刀撞到，故有下面的通道）：只改上述文件里的**注释**、或只改编译期就被擦除的
 *   类型（`packages/shared-types`）、或只动 mp 侧才用的 `shared-uni` 常量时，产物字节**本来就不会变**
 *   ⇒ 主判据对这类改动结构性不可满足（没东西可提交，塞空提交是撒谎，放宽主判据又会漏掉真事故）。
 * 正解 = 带硬约束的自证豁免：`ADMIN_PAIR_PROVE_INERT=1` 时门禁**自己**重跑 `build-admin.mjs`
 *   并比对 `git status -- static/admin`；构建前工作树已脏 ⇒ 证据不可用直接红，
 *   构建后有任何字节差 ⇒ 照样红。所以这条通道**买不到**「把真改动藏起来」。
 *   配套 4 例负向用例在 --selftest 里（不设变量红 / 0 差异放行 / 产物会变红 / 预脏红）。
 *
 * 自检：`--selftest` 用合成范围验判据会红（含“只改 mp”“只改注释面但带产物”两类放行）。
 */
import { resolve } from 'node:path';
import { gitAsync, runAsync } from './lib/async-spawn.mjs';

const ROOT = process.cwd();

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

/**
 * 豁免通道（纯判定，注入 rebuild/dirty 两个 thunk ⇒ 自检不写真构建）。
 *
 * 为什么需要：本门禁是「提交范围级配对」的**代理**判据，而有一类输入改动**结构性地不可能**
 * 让产物变化（例：只改注释、只改 `packages/shared-types` 里被编译擦除的类型、只删 mp 侧才用的
 * shared-uni 常量）⇒ 主判据对它恒红、且没有任何合法方式满足（产物字节没变，塞空提交是撒谎）。
 * 🔴 不放宽主判据、也不放行「凭感觉说不进 bundle」：只有显式 `ADMIN_PAIR_PROVE_INERT=1`
 *   才走这条路，而这条路**由门禁自己重跑一次 `build-admin.mjs` 并比对工作树**——
 *   只要产物真会变，重建必然把 `static/admin` 弄脏 ⇒ 依旧红。豁免买不到「藏改动」。
 *
 * @param err        pairingError(files) 的结果（null ⇒ 本来就该绿）
 * @param prove      是否请求豁免
 * @param rebuild    async () => void：跑一次真实构建
 * @param dirty      async () => string：`git status --porcelain -- static/admin`
 * @param preDirty   async () => string：构建前先测一次（工作树本来就脏 ⇒ 证据不可用）
 */
export async function resolvePairing({ err, prove, rebuild, dirty, preDirty }) {
  if (!err) return { red: false, note: '' };
  if (!prove) {
    return {
      red: true,
      note:
        err +
        '\n  如认为本批输入**根本不进** admin bundle（只改注释/类型/别的端的常量）：' +
        '用 `ADMIN_PAIR_PROVE_INERT=1` 重跑本门禁——门禁会自己重建产物并逐字节比对，' +
        '产物真变就照样红，藏不住东西。'
    };
  }
  const before = await preDirty();
  if (before.trim()) {
    return {
      red: true,
      note:
        '豁免请求被拒：跑构建**之前** `static/admin` 就已经是脏的，字节比对失去意义。\n' +
        `  先把它处理掉（提交或还原）再来证明：\n${before.slice(0, 600)}`
    };
  }
  await rebuild();
  const after = await dirty();
  if (after.trim()) {
    return {
      red: true,
      note:
        '豁免请求被拒：门禁刚重跑了 `build-admin.mjs`，产物**确实变了**（说明本批输入进了 bundle），' +
        '请把 `static/admin` 一并提交：\n' +
        after.slice(0, 600)
    };
  }
  return {
    red: false,
    note:
      '  （豁免通过：门禁已自己跑过 `build-admin.mjs`，重建后 `static/admin` 与 HEAD **逐字节一致**' +
      ' ⇒ 本批 admin 输入不进产物。CI 的 admin-artifacts job 用同一判据重建，结论相同。）'
  };
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
  // 豁免通道的三条约束，逐条都要能红（证它不是「设了变量就放行」）
  const inputs = ['packages/shared-dict/src/index.ts'];
  const errText = pairingError(inputs);
  const mk = ({ prove, pre = '', post = '' }) =>
    resolvePairing({
      err: errText,
      prove,
      rebuild: async () => {},
      dirty: async () => post,
      preDirty: async () => pre
    });
  const exCases = [
    {
      why: '不设变量 ⇒ 仍然红（豁免不是默认通道）',
      p: mk({ prove: false }),
      wantRed: true
    },
    {
      why: '设了变量且重建后 0 字节差 ⇒ 放行',
      p: mk({ prove: true }),
      wantRed: false
    },
    {
      why: '设了变量但重建后产物真的变了 ⇒ 必须红（藏不住真改动）',
      p: mk({
        prove: true,
        post: ' M services/trade-service/src/main/resources/static/admin/assets/x.js'
      }),
      wantRed: true
    },
    {
      why: '跑构建前工作树就是脏的 ⇒ 证据不可用，红',
      p: mk({
        prove: true,
        pre: ' M services/trade-service/src/main/resources/static/admin/index.html'
      }),
      wantRed: true
    }
  ];
  for (const c of exCases) {
    const r = await c.p;
    const ok = r.red === c.wantRed;
    if (!ok) bad++;
    console.log(`  [${ok ? 'ok' : 'BAD'}] ${c.why}（${r.red ? '红' : '绿'}）`);
  }
  if (bad) {
    console.error('[check-admin-artifact-pairing] FAIL：自检 ' + bad + ' 例不符 ⇒ 判据可能恒绿');
    process.exit(1);
  }
  console.log(
    '[check-admin-artifact-pairing] --selftest 通过（8 例：4 主判据 + 4 豁免约束，含真事故复现）'
  );
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
const ARTIFACT_DIR = ARTIFACT_PREFIX.replace(/\/$/, '');
const prove =
  process.env.ADMIN_PAIR_PROVE_INERT === '1' || process.env.ADMIN_PAIR_PROVE_INERT === 'true';
const verdict = await resolvePairing({
  err,
  prove,
  rebuild: async () => {
    console.log(
      '[check-admin-artifact-pairing] 豁免请求：重跑 build-admin.mjs 以证明产物字节不变…'
    );
    const r = await runAsync(process.execPath, [resolve('scripts/build-admin.mjs')], {
      cwd: ROOT,
      maxBuffer: 64 * 1024 * 1024
    });
    if (r.status !== 0) {
      throw new Error(
        `build-admin.mjs 跑失败（status=${r.status}），无法完成证明：\n` +
          String(r.stderr || r.spawnError || '').slice(0, 800)
      );
    }
  },
  dirty: async () => (await gitAsync(['status', '--porcelain', '--', ARTIFACT_DIR])).out,
  preDirty: async () => (await gitAsync(['status', '--porcelain', '--', ARTIFACT_DIR])).out
});
if (verdict.red) {
  console.error(`[check-admin-artifact-pairing] FAIL：${verdict.note}`);
  process.exit(1);
}
const touchedInputs = files.filter((f) => ADMIN_INPUT_PATTERNS.some((re) => re.test(f))).length;
const touchedArtifacts = files.filter((f) => f.startsWith(ARTIFACT_PREFIX)).length;
const label = !touchedInputs
  ? '（不触 admin 包）'
  : touchedArtifacts
    ? `（含 ${touchedInputs} 个 admin 输入，产物已同批提交）`
    : `（含 ${touchedInputs} 个 admin 输入，经门禁重建自证不进产物）`;
console.log(
  `[check-admin-artifact-pairing] OK：范围 ${mb.out.slice(0, 8)}..${head.slice(0, 8)} ` +
    `共 ${files.length} 文件` +
    label +
    verdict.note
);
