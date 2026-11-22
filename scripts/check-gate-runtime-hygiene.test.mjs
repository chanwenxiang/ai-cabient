#!/usr/bin/env node
/**
 * `check-gate-runtime-hygiene.mjs` 的夹具（S8③/④：判据类改动必须自带可执行负向用例）。
 *
 * 每条判据都「能红」+「不乱红」两侧都钉：
 *   - 判据 1 同步子进程：真出现 ⇒ 红；只出现在注释里 ⇒ 不红（stripComments 生效）；
 *   - 判据 2 相对 --require：相对路径 ⇒ 红；绝对路径/变量 ⇒ 不红；
 *   - 判据 3 写 .tmp 前没 mkdirSync（2026-11-22 CI run 45037911316 实证）：
 *     `devops/verify-*.mjs` ⇒ 红；补了 mkdirSync ⇒ 不红；非 devops/verify 面 ⇒ 不适用。
 *
 * 🔴 夹具里禁止出现被判据扫描的字面量（本文件自身也在 `check-*.mjs` 扫描面内），
 * 只能用字符串拼接构造，否则本用例会把自己判红。
 *
 *   node scripts/check-gate-runtime-hygiene.test.mjs
 */
import {
  problemsOfRelativeRequire,
  problemsOfSyncSpawn,
  problemsOfTmpWrite
} from './check-gate-runtime-hygiene.mjs';

const TAG = '[check-gate-runtime-hygiene.test]';
let checks = 0;
function assert(cond, message) {
  checks++;
  if (!cond) throw new Error(`${TAG} 断言失败：${message}`);
}

const SPAWN = 'spawn' + 'Sync';
const REQUIRE_FLAG = '--requ' + 'ire';

// 1) 判据 1：真调用同步子进程 ⇒ 红（报出 API 名）
{
  const p = problemsOfSyncSpawn('scripts/check-x.mjs', `const r = ${SPAWN}('node', []);\n`);
  assert(
    p.length === 1 && p[0].includes('同步子进程') && p[0].includes(SPAWN),
    `应红：${p.join('|')}`
  );
}

// 2) 判据 1：只在注释里出现 ⇒ 不红（注释等长脱敏，铁律 84①）
{
  const p = problemsOfSyncSpawn('scripts/check-x.mjs', `// 历史写法 ${SPAWN}(node) 已废弃\n`);
  assert(p.length === 0, `注释里的字面量不该判红：${p.join('|')}`);
}

// 3) 判据 1：异步 spawn 形态 ⇒ 不红（正解不被误伤）
{
  const p = problemsOfSyncSpawn(
    'scripts/check-x.mjs',
    `const r = await runAsync(nodeBin, ['x']);\n`
  );
  assert(p.length === 0, `runAsync 不该被判红：${p.join('|')}`);
}

// 4) 判据 2：相对路径 --require ⇒ 红
{
  const p = problemsOfRelativeRequire(
    'infra/x.sh',
    `NODE_OPTIONS="${REQUIRE_FLAG} .tmp/tools/nopipe.cjs" node a.mjs\n`
  );
  assert(p.length === 1 && p[0].includes('相对路径'), `应红：${p.join('|')}`);
}

// 5) 判据 2：绝对路径 / 变量占位 ⇒ 不红
{
  const abs = problemsOfRelativeRequire(
    'infra/x.sh',
    `NODE_OPTIONS="${REQUIRE_FLAG} /opt/x.cjs" node a.mjs\n`
  );
  const envVar = problemsOfRelativeRequire(
    'ci.yml',
    `run: node ${REQUIRE_FLAG} \$GITHUB_WORKSPACE/x.cjs a.mjs\n`
  );
  assert(
    abs.length === 0 && envVar.length === 0,
    `绝对/变量路径不该红：${[...abs, ...envVar].join('|')}`
  );
}

// 6) 判据 3：devops/verify-* 写 .tmp 却没 mkdirSync ⇒ 红（按别名解析目标）
{
  const src = [
    "const root = '/repo';",
    "const BACKUP = join(root, '.tmp/orders.vue.backup');",
    'copyFileSync(TARGET, BACKUP);'
  ].join('\n');
  const p = problemsOfTmpWrite('scripts/devops/verify-x.mjs', src);
  assert(p.length === 1 && p[0].includes('mkdirSync'), `应红：${p.join('|')}`);
}

// 7) 判据 3：先 mkdirSync 再写 ⇒ 不红（正解不被误伤）
{
  const src = [
    "const root = '/repo';",
    "const BACKUP = join(root, '.tmp/orders.vue.backup');",
    'mkdirSync(dirname(BACKUP), { recursive: true });',
    'copyFileSync(TARGET, BACKUP);'
  ].join('\n');
  const p = problemsOfTmpWrite('scripts/devops/verify-x.mjs', src);
  assert(p.length === 0, `已自建目录不该红：${p.join('|')}`);
}

// 8) 判据 3：不在 devops/verify 面 ⇒ 不适用（避免把普通门禁的合理写法判红）
{
  const src = [
    "const root = '/repo';",
    "const OUT = join(root, '.tmp/out.txt');",
    'writeFileSync(OUT, body);'
  ].join('\n');
  const p = problemsOfTmpWrite('scripts/check-x.mjs', src);
  assert(p.length === 0, `非 devops/verify 面不该判红：${p.join('|')}`);
}

console.log(`${TAG} OK：${checks} 项断言全过`);
