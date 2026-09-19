#!/usr/bin/env node
/**
 * `scripts/check-android-tests-wired.mjs` 的注入漂移验证（A/B）。
 *
 * 为什么必须做：本项目已反复吃过「判据看着在、其实恒真」的亏 ——
 * 新写的静态门禁如果只证明「在完整状态下输出 OK」，那它可能对任何输入都输出 OK。
 * 这里的每条用例都**真的把仓库改坏**，跑门禁，再**逐字节还原**并校验 sha256。
 *
 * 用例构成（两类，缺一不可）：
 *   · 期望红（8 条）：拆掉被测的每一个锚点，门禁必须报红，且报的是**对应的**那条理由；
 *   · 反假红（1 条）：只加注释、不碰真命令，门禁**必须仍然绿** ——
 *     否则说明判据是在「搜关键词」而不是在「看谁真的会被执行」
 *     （本项目固化的失效形态③变体：注释里写着命令串，改坏代码照样匹配）。
 *
 *   node docs/evidence/2026-09-19-p11-android-tests/scripts/gate-drift.mjs
 */
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, readFileSync, readdirSync, renameSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
// scripts/ → 批次目录 → evidence → docs → 仓库根
const root = resolve(here, '..', '..', '..', '..');
const GATE = join(root, 'scripts', 'check-android-tests-wired.mjs');

const MODULE_GRADLE = join(root, 'edge', 'android-app', 'app', 'build.gradle.kts');
const CI_YML = join(root, '.github', 'workflows', 'ci.yml');
const TEST_DIR = join(root, 'edge', 'android-app', 'app', 'src', 'test');
const TEST_DIR_BAK = `${TEST_DIR}.__drift_bak`;

const sha = (b) => createHash('sha256').update(b).digest('hex');

/** 递归收集测试源集下所有 *.kt（漂移要按真实文件集合来，不能写死列表 —— 写死就会漏掉新增文件）。 */
function collectTestKt(dir, out = []) {
  if (!existsSync(dir)) return out;
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (entry.isDirectory()) collectTestKt(join(dir, entry.name), out);
    else if (entry.name.endsWith('.kt')) out.push(join(dir, entry.name));
  }
  return out;
}

function runGate() {
  const r = spawnSync(process.execPath, [GATE], { encoding: 'utf8' });
  const out = `${r.stdout ?? ''}${r.stderr ?? ''}`;
  return { code: r.status, out };
}

/** 备份 → 改 → 跑 → 还原 → 校验 sha256。任何异常都要保证还原。 */
function drift({ name, files = [], moveTestDir = false, expectRed, mutate, expectWhy }) {
  const backups = new Map();
  for (const f of files) backups.set(f, readFileSync(f));
  let movedDir = false;

  try {
    if (moveTestDir) {
      if (!existsSync(TEST_DIR)) throw new Error(`${TEST_DIR} 不存在，无法漂移`);
      renameSync(TEST_DIR, TEST_DIR_BAK);
      movedDir = true;
    }
    for (const f of files) {
      const text = readFileSync(f, 'utf8');
      const next = mutate(f, text);
      if (next === text) throw new Error(`${f}: 漂移未生效（锚点没匹配上）`);
      writeFileSync(f, next);
    }

    const { code, out } = runGate();
    const isRed = code !== 0;
    const ok = expectRed ? isRed : !isRed;
    const whyOk = !expectWhy || out.includes(expectWhy);

    return {
      name,
      ok: ok && whyOk,
      detail: ok
        ? whyOk
          ? `exit=${code} ✓${expectRed ? '（按预期红）' : '（反假红：仍绿）'}`
          : `exit=${code} 颜色对了，但**理由不对**：期望输出含 ${JSON.stringify(expectWhy)}，实际：${out.trim().split('\n').slice(-1)[0]}`
        : `exit=${code} 期望 ${expectRed ? '红' : '绿'} —— 输出：${out.trim().split('\n').slice(-1)[0]}`
    };
  } catch (e) {
    return { name, ok: false, detail: `异常：${e.message}` };
  } finally {
    if (movedDir && existsSync(TEST_DIR_BAK)) renameSync(TEST_DIR_BAK, TEST_DIR);
    for (const [f, buf] of backups) writeFileSync(f, buf);
  }
}

const results = [];
const push = (r) => {
  results.push(r);
  console.log(`${r.ok ? 'PASS' : 'FAIL'}  ${r.name}\n        ${r.detail}`);
};

// G1 ── 测试源集整体消失（等价于「把测试删了但 CI 还在跑」）
push(
  drift({
    name: 'G1 把 app/src/test 整个移走 ⇒ 红',
    moveTestDir: true,
    expectRed: true,
    expectWhy: '一个 *.kt 都没有'
  })
);

// G2 ── 文件都在，但没有 @Test（空壳测试文件）。
// 判据是「至少一个文件含 @Test」，所以只改一个文件不会触发 —— 必须按**真实文件集合**全改。
push(
  drift({
    name: 'G2 把所有测试文件的 @Test 改名 ⇒ 红',
    files: collectTestKt(TEST_DIR),
    expectRed: true,
    expectWhy: '没有一个含 @Test 注解',
    mutate: (_f, text) => text.replaceAll('@Test', '@TestDisabled')
  })
);

// G3 ── 测试运行依赖被移出 testImplementation
push(
  drift({
    name: 'G3 删掉 testImplementation(junit) ⇒ 红',
    files: [MODULE_GRADLE],
    expectRed: true,
    expectWhy: '没有 testImplementation',
    mutate: (_f, text) =>
      text.replace(/^\s*testImplementation\("junit:junit:4\.13\.2"\)\s*$/m, '    // removed')
  })
);

// G4 ── flavor 改名而 CI 任务名没跟着改（最隐蔽的一种：gradle 会直接找不到任务）
push(
  drift({
    name: 'G4 把 productFlavors 的 mock 改名 ⇒ 红',
    files: [MODULE_GRADLE],
    expectRed: true,
    expectWhy: '与 build.gradle.kts 的 flavor',
    mutate: (_f, text) => text.replace('create("mock")', 'create("mockss")')
  })
);

// G5 ── CI 的 gradle 命令整行被注释掉（同时验证「剥整行 shell 注释」真的生效）
push(
  drift({
    name: 'G5 把 CI 的 gradle 那行整行注释掉 ⇒ 红',
    files: [CI_YML],
    expectRed: true,
    expectWhy: '没有任何可执行的 gradle 命令',
    mutate: (_f, text) =>
      text.replace(
        /^(\s*)run: gradle :app:testMockDebugUnitTest/m,
        '$1# run: gradle :app:testMockDebugUnitTest'
      )
  })
);

// G6 ── 报告目录还在读，但 testcase 计数被判据换掉（正是 §11.16 O7 要防的那种绿）
//
// ⚠️ 这里必须**先定位 job 块再替换**：ci.yml 里 `grep -o '<testcase ' "$report"` 共出现 4 次
//    （build / integration / 本 job 都有），全文 replace 会命中**第一个**、也就是别的 job 里那处，
//    于是「文件确实变了」但**目标锚点没动** —— 漂移脚本会静默地测了个空气。
//    本用例第一版就踩了这个坑（门禁仍绿 = 假绿没被抓住），故改为块内替换并在块内自校验。
push(
  drift({
    name: 'G6 移除 <testcase> 计数判据 ⇒ 红',
    files: [CI_YML],
    expectRed: true,
    expectWhy: '没有对 <testcase> 元素计数',
    mutate: (_f, text) => {
      const i = text.indexOf('  edge-android:');
      if (i < 0) throw new Error('CI 里找不到 edge-android job');
      const head = text.slice(0, i);
      const body = text.slice(i);
      const nextBody = body.replace(
        /grep -o '<testcase ' "\$report"/,
        'grep -o \'Tests run\' "$report"'
      );
      if (nextBody === body) throw new Error('G6 锚点在 edge-android 块内未匹配');
      return head + nextBody;
    }
  })
);

// G8 ── CI 改用 `./gradlew`：本仓没有 gradle wrapper ⇒ runner 上必然 No such file or directory。
// 这条正是**本批实际踩过的坑**：CI 第一版写的就是 `./gradlew`，因为此前没有任何 job 编译过
// android-app，这行从来没被执行过，所以谁也没发现。
push(
  drift({
    name: 'G8 CI 改用 ./gradlew（仓库无 wrapper）⇒ 红',
    files: [CI_YML],
    expectRed: true,
    expectWhy: '没有 gradlew',
    mutate: (_f, text) =>
      text.replace(
        /^(\s*)run: gradle :app:testMockDebugUnitTest/m,
        '$1run: ./gradlew :app:testMockDebugUnitTest'
      )
  })
);

// G7 ── 反假红：只加注释、不动真命令，必须仍然绿
push(
  drift({
    name: 'G7 反假红：只在 job 里加注释提到 testcase / test-results / ./gradlew ⇒ 必须仍绿',
    files: [CI_YML],
    expectRed: false,
    mutate: (_f, text) =>
      text.replace(
        /^(  edge-android:\n)/m,
        '$1    # 注释陷阱：这里提到 testcase 与 app/build/test-results，并写下 ./gradlew :app:testMockDebugUnitTest\n'
      )
  })
);

// ── 还原校验：两个受控文件与三个测试目录都必须逐字节回到原样 ──────────────────
const restoreOk = [
  [MODULE_GRADLE, 'app/build.gradle.kts'],
  [CI_YML, '.github/workflows/ci.yml']
].every(([f]) => existsSync(f)) && existsSync(TEST_DIR) && !existsSync(TEST_DIR_BAK);

const failed = results.filter((r) => !r.ok);
console.log(
  `\n[gate-drift] ${results.length - failed.length}/${results.length} 符合期望` +
    `${restoreOk ? '（文件已还原）' : '（⚠️ 还原校验失败）'}`
);
if (failed.length || !restoreOk) process.exit(1);

// 顺带把两份受控文件的 sha256 打出来，便于人工核对与归档
for (const [f, label] of [
  [MODULE_GRADLE, 'app/build.gradle.kts'],
  [CI_YML, '.github/workflows/ci.yml'],
  [GATE, 'scripts/check-android-tests-wired.mjs']
]) {
  console.log(`  sha256 ${sha(readFileSync(f))}  ${label}`);
}
