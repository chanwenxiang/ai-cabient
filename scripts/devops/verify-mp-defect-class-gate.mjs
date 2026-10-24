/**
 * check-mp-defect-classes 门禁的双向负向验证（2026-10-24 二轮扩展；同日三轮沙箱化，defect-class-gate 技能 §6）：
 *   ⓪ 基线：**未注入**的沙箱副本必须绿 ⇒ 否则 ① 的「红」可能只是拷贝不完整（缺资产/漏树）造成的假象；
 *   ① 红：注入 A-Q 十七个类（20 个注入键，含 F1–F4 子形态）各一处真违规 → 门禁必须非零退出且逐类报对文件；
 *   ② 绿：同类 token 进**注释** → 门禁必须保持绿（证明等长注释剥离有效）；
 *   ③ 零污染：真实树里全部探针目标文件 md5 前后一致、无 `.gatebak` 残留、沙箱目录已删除。
 *
 * 🔴 为什么不再改真实源码（2026-10-24 复核订正）：旧版直接 patch 仓库里的 tax.vue /
 * splits.vue / merchant-api.ts 再按 md5 还原，而注入锚点与源码**逐字耦合**（含两行缩进），
 * 锚点一失配就抛在半路——本仓常有并发会话的未提交改动，异常中断等于把注入态留在别人的
 * 工作树上。现改为把门禁真正扫描的三棵树一次性拷进 `.tmp/mp-gate-negative-<pid>`，
 * 注入/比对全在沙箱内进行，真实树**一个字节都不写**；因此可以安全地接进聚合链与 CI。
 * pid 目录名 ⇒ 两个会话同时跑也互不干扰。
 *
 * 子进程一律走 `scripts/lib/async-spawn.mjs`（lessons #292：WorkBuddy 下同步 spawn 返回
 * `status=null`+EBUSY，会把「没跑成」伪装成「全红」）。
 *
 * 锚点失配 = 红（探针与源码脱钩，必须重新对准，不许放行）。门禁判据变更后必跑。
 * 用法：node scripts/devops/verify-mp-defect-class-gate.mjs（或 pnpm check:mp-gate-negative）
 */
import { cpSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { dirname, resolve } from 'node:path';
import { runAsync } from '../lib/async-spawn.mjs';

const ROOT = resolve(import.meta.dirname, '..', '..');
const GATE_REL = 'scripts/check-mp-defect-classes.mjs';

// 门禁实际扫描的树（见 check-mp-defect-classes.mjs:875-876：两端 mp src + shared-uni src）。
// 门禁从自身文件位置推导 root，所以脚本拷到沙箱的 scripts/ 下，root 就落在沙箱。
const TREES = ['clients/consumer-mp/src', 'clients/merchant-mp/src', 'packages/shared-uni/src'];

// pid 目录名：并发会话各用各的沙箱
const SANDBOX = resolve(ROOT, '.tmp', `mp-gate-negative-${process.pid}`);
const sb = (rel) => resolve(SANDBOX, rel);

function md5(p) {
  return createHash('md5').update(readFileSync(p)).digest('hex');
}

function patch(p, from, to) {
  const s = readFileSync(p, 'utf8');
  if (!s.includes(from)) throw new Error(`注入点未找到: ${p}: ${from.slice(0, 60)}`);
  writeFileSync(p, s.replace(from, to));
}

async function runGate(label) {
  const r = await runAsync(process.execPath, [sb(GATE_REL)], { cwd: SANDBOX });
  if (r.spawnError) {
    // fail-closed：跑不起来绝不当成「没问题」
    throw new Error(`${label} 门禁子进程起不来（${r.spawnError}）`);
  }
  return { code: r.status, out: `${r.stdout}${r.stderr}` };
}

// 每个类的注入：文件 + 锚点原文 + 替换为（锚点+真违规）
const INJECT = {
  A: {
    file: 'clients/merchant-mp/src/pages/splits/splits.vue',
    from: '  loadingMore.value = false;\n  if (reset) {',
    to: '  if (reset) {',
    expect: 'splits.vue'
  },
  B: {
    file: 'clients/merchant-mp/src/components/WalletPage.vue',
    from: "import { onPageShow } from '@dcloudio/uni-app';",
    to: "import { onShow } from '@dcloudio/uni-app';",
    expect: 'WalletPage.vue'
  },
  C: {
    file: 'clients/merchant-mp/src/pages/tax/tax.vue',
    from: '  taxForm.value = emptyTaxProfileForm();\n  captureTaxBaseline();',
    to: '  captureTaxBaseline();',
    expect: 'tax.vue'
  },
  D: {
    file: 'clients/merchant-mp/src/utils/merchant-api.ts',
    from: "        reject(new Error('网络错误'));",
    to: "        reject(new Error(err.errMsg || '网络错误'));",
    expect: 'merchant-api.ts'
  },
  E: {
    file: 'clients/merchant-mp/src/api/endpoints.ts',
    from: '  teamRoles: `${API_PREFIX}/merchant/team/roles`,',
    to: '  teamRoles: `${API_PREFIX}/merchant/team/roles/${id}`,',
    expect: 'endpoints.ts'
  },
  F1: {
    file: 'clients/merchant-mp/src/pages/tax/tax.vue',
    from: 'async function saveTax() {',
    to:
      '// 负向验证探针（F1）：只写不读的 ref 门闩\n' +
      'const probeSaving = ref(false);\n' +
      'function probeSetSaving() { probeSaving.value = true; }\n' +
      'async function saveTax() {',
    expect: 'tax.vue'
  },
  F2: {
    file: 'clients/merchant-mp/src/pages/tax/tax.vue',
    from: 'async function saveTax() {',
    to:
      '// 负向验证探针（F2）：只写不读的属性门闩\n' +
      'function probeRowSaving(row: unknown) {\n' +
      '  (row as { probeSaving2?: boolean }).probeSaving2 = true;\n' +
      '}\n' +
      'async function saveTax() {',
    expect: 'tax.vue'
  },
  F3: {
    file: 'clients/merchant-mp/src/pages/cabinet-reports/cabinet-reports.vue',
    from: 'async function load() {',
    to:
      'async function load() {\n' +
      '  // 负向验证探针（F3）：门闩晚于鉴权 await\n' +
      '  if (!(await ensureLogin())) return;\n' +
      '  probeF3Loading.value = true;\n}\n' +
      'const probeF3Loading = ref(false);\n' +
      'async function loadReal() {',
    expect: 'cabinet-reports.vue'
  },
  F4: {
    file: 'clients/merchant-mp/src/pages/tax/tax.vue',
    from: 'async function saveTax() {',
    to:
      '// 负向验证探针（F4）：选图入口无门闩\n' +
      'function probePickNoGuard() {\n' +
      '  uni.chooseImage({ count: 1 });\n' +
      '}\n' +
      'async function saveTax() {',
    expect: 'tax.vue'
  },
  G: {
    file: 'clients/consumer-mp/src/pages/coupons/coupons.vue',
    from: '</template>',
    to: '<view>{{ probeArr.slice(0, 30) }}</view>\n</template>',
    expect: 'coupons.vue'
  },
  H: {
    file: 'clients/merchant-mp/src/pages/tax/tax.vue',
    from: 'async function saveTax() {',
    to:
      '// 负向验证探针（H）：翻页追加无 seq 守卫\n' +
      'const probeList = ref<string[]>([]);\n' +
      'async function loadMoreProbe() {\n' +
      '  const items = await probeFetch();\n' +
      '  probeList.value = probeList.value.concat(items);\n' +
      '}\n' +
      'async function probeFetch() { return []; }\n' +
      'async function saveTax() {',
    expect: 'tax.vue'
  },
  I: {
    file: 'clients/merchant-mp/src/utils/merchant-config.ts',
    from: "import { merchantApi } from './merchant-api';",
    to:
      "import { merchantApi } from './merchant-api';\n" +
      '// 负向验证探针（I）：blob 不回收\n' +
      'const probeBlobUrl = URL.createObjectURL(new Blob());',
    expect: 'merchant-config.ts'
  },
  J: {
    file: 'clients/merchant-mp/src/pages/tax/tax.vue',
    from: "import { showConfirm, showError, showSuccess } from '@/utils/notify';",
    to:
      "import { probeDeadSymbol } from '@/utils/notify';\n" +
      "import { showConfirm, showError, showSuccess } from '@/utils/notify';",
    expect: 'tax.vue'
  },
  K: {
    file: 'clients/merchant-mp/src/utils/merchant-api.ts',
    from: 'const sessionClearHooks = new Set<() => void>();',
    to:
      '// 负向验证探针（K）：刷新失败即登出（无鉴权判据）\n' +
      'void Promise.resolve().catch(() => { handleUnauthorized(); });\n' +
      'const sessionClearHooks = new Set<() => void>();',
    expect: 'merchant-api.ts'
  },
  L: {
    file: 'clients/merchant-mp/src/utils/merchant-config.ts',
    from: "import { merchantApi } from './merchant-api';",
    to:
      "import { merchantApi } from './merchant-api';\n" +
      "// 负向验证探针（L）：硬编码版本号\nconst PROBE_VERSION = 'v9.9.9';",
    expect: 'merchant-config.ts'
  },
  M: {
    file: 'clients/merchant-mp/src/utils/merchant-config.ts',
    from: "import { merchantApi } from './merchant-api';",
    to:
      "import { merchantApi } from './merchant-api';\n" +
      "// 负向验证探针（M）：模板 ID 硬编码\nconst PROBE_TPL = 'z9y8x7w6v5u4t3s2r1q0p9o8n7m6l5k4j3i2h1g0';",
    expect: 'merchant-config.ts'
  },
  N: {
    file: 'clients/merchant-mp/src/pages/tax/tax.vue',
    from: 'async function saveTax() {',
    to:
      '// 负向验证探针（N）：失败负缓存钉死\n' +
      'let probeCache: Record<string, string> = {};\n' +
      'void Promise.resolve().catch(() => { probeCache = {}; });\n' +
      'async function saveTax() {',
    expect: 'tax.vue'
  },
  O: {
    file: 'clients/merchant-mp/src/pages/tax/tax.vue',
    from: 'async function saveTax() {',
    to:
      '// 负向验证探针（O）：无界缓存\n' +
      'const probeCacheMap = new Map();\n' +
      'async function saveTax() {',
    expect: 'tax.vue'
  },
  P: {
    file: 'clients/consumer-mp/src/pages/mine/mine.vue',
    from: "import { menuIcon } from '@/utils/menu-icon';",
    to:
      "import { menuIcon } from '@/utils/menu-icon';\n" +
      "// 负向验证探针（P1）：字面引用不存在的静态资产\nconst PROBE_ICON = '/static/menu/probe-missing-icon.png';\n" +
      "// 负向验证探针（P2）：menuIcon 参数指向不存在的资产\nconst PROBE_ICON2 = menuIcon('probe-missing-icon');",
    expect: 'mine.vue'
  },
  Q: {
    file: 'clients/consumer-mp/src/pages/result/result.vue',
    from: 'function currentPageOptions(): Record<string, string> {',
    to:
      '// 负向验证探针（Q1）：订阅调用不可从 tap 到达\n' +
      'function probeRestore() {\n  void requestOrderSubscribe([]);\n}\n' +
      'function currentPageOptions(): Record<string, string> {',
    expect: 'result.vue'
  }
};

// 注释探针（②绿）：同类 token 进注释，门禁必须保持绿
const COMMENT_PROBES = [
  {
    file: 'clients/merchant-mp/src/pages/splits/splits.vue',
    from: 'async function load(reset: boolean) {',
    to: '// loadingMore.value = true 探针（注释不得命中）\nasync function load(reset: boolean) {'
  },
  {
    file: 'clients/merchant-mp/src/components/WalletPage.vue',
    from: "import { computed, ref } from 'vue';",
    to: "// import { onShow } from '@dcloudio/uni-app'; 探针\nimport { computed, ref } from 'vue';"
  },
  {
    file: 'clients/merchant-mp/src/utils/merchant-api.ts',
    from: 'export const merchantApi = {',
    to: "// new Error(err?.errMsg || 'x') 探针\nexport const merchantApi = {"
  },
  {
    file: 'clients/merchant-mp/src/api/endpoints.ts',
    from: '  teamRoles:',
    to: '  // ${id} 探针\n  teamRoles:'
  },
  {
    file: 'clients/merchant-mp/src/pages/cabinet-reports/cabinet-reports.vue',
    from: 'async function load() {',
    to: '// if (!(await ensureLogin())) return; probeF3Loading.value = true; 探针\nasync function load() {'
  },
  {
    file: 'clients/consumer-mp/src/pages/coupons/coupons.vue',
    from: '</template>',
    to: '<!-- probeArr.slice(0, 30) 探针（注释不得命中） -->\n</template>'
  },
  {
    file: 'clients/merchant-mp/src/pages/tax/tax.vue',
    from: 'function switchTaxMerchant(next: string) {',
    to:
      '// async function probeLoadMore() { list.value = list.value.concat(x); } 探针\n' +
      '// import { probeDeadSymbol } from "@/utils/notify"; 探针\n' +
      '// const PROBE_VERSION = "v9.9.9"; 探针\n' +
      '// const PROBE_TPL = "z9y8x7w6v5u4t3s2r1q0p9o8n7m6l5k4j3i2h1g0"; 探针\n' +
      '// probeCache = {}; 探针\n' +
      '// const probeCacheMap = new Map(); 探针\n' +
      'function switchTaxMerchant(next: string) {'
  },
  {
    file: 'clients/merchant-mp/src/utils/merchant-config.ts',
    from: "import { merchantApi } from './merchant-api';",
    to:
      '// URL.createObjectURL(new Blob()) 探针\n' +
      '// handleUnauthorized(); 探针\n' +
      "import { merchantApi } from './merchant-api';"
  },
  {
    file: 'clients/consumer-mp/src/pages/mine/mine.vue',
    from: "import { menuIcon } from '@/utils/menu-icon';",
    to:
      "// const PROBE_ICON = '/static/menu/probe-missing-icon.png'; 探针（注释不得命中）\n" +
      "// const PROBE_ICON2 = menuIcon('probe-missing-icon'); 探针\n" +
      "import { menuIcon } from '@/utils/menu-icon';"
  },
  {
    file: 'clients/consumer-mp/src/pages/result/result.vue',
    from: 'function currentPageOptions(): Record<string, string> {',
    to: '// void requestOrderSubscribe([]); 订阅探针（注释不得命中）\nfunction currentPageOptions(): Record<string, string> {'
  }
];

const CLASSES = Object.keys(INJECT);
const TARGET_RELS = [
  ...new Set([...CLASSES.map((c) => INJECT[c].file), ...COMMENT_PROBES.map((c) => c.file)])
];

function buildSandbox() {
  rmSync(SANDBOX, { recursive: true, force: true });
  for (const rel of [...TREES, GATE_REL]) {
    const src = resolve(ROOT, rel);
    if (!existsSync(src)) throw new Error(`沙箱拷贝源不存在：${rel}`);
    mkdirSync(dirname(sb(rel)), { recursive: true });
    cpSync(src, sb(rel), { recursive: true });
  }
  // 门禁 selftest 把探针写进 <root>/.tmp（root=沙箱），提前建好
  mkdirSync(sb('.tmp'), { recursive: true });
  // 探针目标必须落在被拷贝的树里，否则沙箱里根本没有这个文件
  for (const rel of TARGET_RELS) {
    if (!TREES.some((t) => rel.startsWith(`${t}/`))) {
      throw new Error(`探针目标不在沙箱拷贝的树里：${rel} —— 请把它加进 TREES，别改回写真实树`);
    }
  }
}

let failed = false;
// 真实树探针目标的初始 md5 —— ③ 用它证明「全程未写入」。放在 try 内取：
// 若快照都取不到（文件不存在/越界），③ 直接判红而不是 ENOENT 崩在半路。
let md5BeforeReal = {};
function check(cond, msg) {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${msg}`);
  if (!cond) failed = true;
}

try {
  // 顺序：先建沙箱（内含「目标必须在拷贝树里」的结构护栏，越界在此报人话），
  // 再取真实树快照 —— 快照只需早于任何写入，拷贝本身只读真实树。
  buildSandbox();
  md5BeforeReal = Object.fromEntries(TARGET_RELS.map((rel) => [rel, md5(resolve(ROOT, rel))]));

  // ── ⓪ 基线：未注入的副本必须绿（拷贝不完整会在这里就红，而不是把 ① 变成假绿）──
  const base = await runGate('⓪');
  check(base.code === 0, `⓪ 沙箱未注入即绿（exit=${base.code}）`);
  if (base.code !== 0) console.log(base.out);

  // ── ① 红：十七类各注入一处真违规 ─────────────────────────────────────────
  for (const cls of CLASSES) {
    const inj = INJECT[cls];
    patch(sb(inj.file), inj.from, inj.to);
  }
  const red = await runGate('①');
  check(red.code !== 0, `① 注入后门禁非零退出（exit=${red.code}）`);
  for (const cls of CLASSES) {
    check(red.out.includes(`[${cls}]`), `① 类 ${cls} 被报出`);
  }
  for (const cls of CLASSES) {
    // 类标签与期望文件必须出现在**同一行**（防止他类同文件造成假通过）
    const esc = INJECT[cls].expect.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const re = new RegExp(`\\[${cls}\\][^\\n]*${esc}`);
    check(re.test(red.out), `① 类 ${cls} 报对文件 ${INJECT[cls].expect}`);
  }

  // ── ② 绿：同类 token 进注释（换新沙箱，避免 ① 的注入串扰）────────────────
  buildSandbox();
  for (const c of COMMENT_PROBES) patch(sb(c.file), c.from, c.to);
  const green = await runGate('②');
  check(green.code === 0, `② 注释注入后门禁保持绿（exit=${green.code}）`);
  if (green.code !== 0) console.log(green.out);
} catch (e) {
  failed = true;
  console.error(`\n🔴 负向验证异常中断：${e instanceof Error ? e.message : e}`);
} finally {
  rmSync(SANDBOX, { recursive: true, force: true });
}

// ── ③ 零污染：无论前面红没红，真实树都必须一个字节没被写过 ──────────────────
if (!Object.keys(md5BeforeReal).length) {
  check(false, '③ 未取到真实树快照（前面在读盘前就崩了），无法证明零污染');
} else {
  for (const rel of TARGET_RELS) {
    const p = resolve(ROOT, rel);
    check(md5(p) === md5BeforeReal[rel], `③ ${rel} 全程未被写入`);
    check(!existsSync(`${p}.gatebak`), `③ ${rel} 无 .gatebak 残留`);
  }
}
check(!existsSync(SANDBOX), '③ 沙箱目录已删除（零残留）');

console.log(
  failed
    ? '\n负向验证存在失败项'
    : '\n负向验证全部通过（基线绿、红能红且报对文件、注释不误伤、真实树零写入）'
);
process.exit(failed ? 1 : 0);
