#!/usr/bin/env node
/**
 * 「前端单测必须真的被 CI 跑」—— 接线门禁（静态，不需要构建产物）。
 *
 * 背景（2026-09-19 取证）：
 *   `clients/admin-vue` 的单测曾**从不被 CI 调用**（ci.yml 里紧挨着的注释就是这句话）。
 *   两端小程序直到本批之前，`src/**` 下 **0 个 `*.test.ts`**、devDeps 里**没有 vitest**，
 *   纯逻辑（`todo-list.ts` 的合并/去重、`dispute-form.ts` 的库存回补策略、`account.ts` 的
 *   支付门槛）**只被 Playwright e2e 覆盖** —— 而 e2e 需要 dev 栈，本机/CI 的环境门一关就
 *   等于**没人守**：改坏不会红。
 *
 *   光「加了 vitest」不够：失效形态有三条——
 *     ① `test` 脚本被改名/删掉，CI 那条 `pnpm --filter … test` 就静默跑不到东西；
 *     ② `vitest.config.ts` 的 `include` 被改窄（比如只留 `tests/**`），测试文件在，
 *        但一条都不被匹配（vitest 3 在**无匹配文件**时 exit 1，可一旦留下一个空目录就未必）；
 *     ③ CI 步骤被删掉/挪出 job —— 测试还在跑，只是没人调（形态①）。
 *
 * 判据全部落在**真值**上（不搜关键词）：
 *   · 用 JSON 解析 package.json 取 `scripts.test`
 *   · 用 glob→RegExp 把 `include` 模式**真的拿去匹配**仓库里实际存在的测试文件
 *   · 用 CI 文本的 job 块 + 剥 shell 注释后的**命令串**做锚
 *   · 覆盖面**自动跟随**：扫描 `clients/*` 里**凡是装了 vitest**的包，而不是写死名单 ——
 *     以后新增端 / admin-vue 调整，本门禁自动纳入，不需要有人记得回来改脚本
 *
 *   node scripts/check-mp-unit-tests-wired.mjs
 */
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-mp-unit-tests-wired]';

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

function escapeRe(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/** 最小 glob→RegExp：支持 `**`、`*`、`?`、`{a,b}`。够用来判 include 是否真匹配到文件。 */
function globToRegExp(glob) {
  let re = '';
  for (let i = 0; i < glob.length; i++) {
    const c = glob[i];
    if (c === '*') {
      if (glob[i + 1] === '*') {
        re += '.*';
        i++;
      } else {
        re += '[^/]*';
      }
    } else if (c === '{') {
      const end = glob.indexOf('}', i);
      if (end === -1) {
        re += '\\{';
        continue;
      }
      re += `(?:${glob
        .slice(i + 1, end)
        .split(',')
        .map((a) => escapeRe(a.trim()))
        .join('|')})`;
      i = end;
    } else if (c === '?') {
      re += '.';
    } else {
      re += escapeRe(c);
    }
  }
  return new RegExp(`^${re}$`);
}

/** 递归收集包内 `*.test.ts` / `*.spec.ts`（跳过 node_modules/dist/output）。 */
function collectTestFiles(dir, base = dir, out = []) {
  if (!existsSync(dir)) return out;
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (entry.isDirectory()) {
      if (['node_modules', 'dist', 'output', '.git'].includes(entry.name)) continue;
      collectTestFiles(join(dir, entry.name), base, out);
    } else if (/\.(test|spec)\.(ts|tsx)$/.test(entry.name)) {
      out.push(relative(base, join(dir, entry.name)).replace(/\\/g, '/'));
    }
  }
  return out;
}

/** 取某 job 的 YAML 文本块（顶层 job 名到下一个顶层 job 名之间）。 */
function jobBlock(yamlText, jobName) {
  const start = new RegExp(`^  ${jobName}:\\s*$`, 'm').exec(yamlText);
  if (!start) return null;
  const rest = yamlText.slice(start.index + start[0].length);
  const next = /^  [A-Za-z0-9_-]+:\s*$/m.exec(rest);
  return next ? rest.slice(0, next.index) : rest;
}

/** 剥掉整行 shell 注释 —— 说明文字里就有被校验的命令串。 */
function stripShellLineComments(block) {
  return block
    .split('\n')
    .filter((line) => !/^\s*#/.test(line))
    .join('\n');
}

// ── 自动发现覆盖面：clients/* 里凡是装了 vitest 的包 ────────────────────────────
const clientsDir = join(root, 'clients');
if (!existsSync(clientsDir)) fail('缺少 clients/ 目录 —— 扫描锚点已失效');

const targets = [];
for (const name of readdirSync(clientsDir)) {
  const pkgPath = join(clientsDir, name, 'package.json');
  if (!existsSync(pkgPath)) continue;
  let pkg;
  try {
    pkg = JSON.parse(readFileSync(pkgPath, 'utf8'));
  } catch (e) {
    fail(`clients/${name}/package.json 不是合法 JSON：${e.message}`);
  }
  if (pkg.devDependencies?.vitest || pkg.dependencies?.vitest) {
    targets.push({ name, dir: join(clientsDir, name), pkg });
  }
}

// 自身护栏：装了 vitest 的包必须非空，否则「零目标 ⇒ 全绿」是恒真的。
if (targets.length === 0) {
  fail(
    'clients/* 下找不到任何声明了 vitest 的包 —— 要么扫描锚点被改坏，要么单测依赖被整体移除；' +
      '本门禁已失去判别力'
  );
}

const ciPath = join(root, '.github', 'workflows', 'ci.yml');
if (!existsSync(ciPath)) fail('缺少 .github/workflows/ci.yml —— 门禁锚点已失效');
const ci = readFileSync(ciPath, 'utf8');
const job = jobBlock(ci, 'mini-programs');
if (job === null) fail("ci.yml 里找不到 job 'mini-programs' —— 前端测试的宿主 job 已改名/删除");
const jobCode = stripShellLineComments(job);

const problems = [];

for (const { name, dir, pkg } of targets) {
  const label = `clients/${name}`;

  // 规则 1：test 脚本必须是 vitest run（而不是 type-check 之类的替代品）
  const testScript = pkg.scripts?.test;
  if (!testScript) {
    problems.push(`${label}：package.json 没有 \`test\` 脚本（CI 的 \`pnpm --filter … test\` 会 ERR`);
  } else if (!/\bvitest\b/.test(testScript)) {
    problems.push(`${label}：\`test\` 脚本不是 vitest（实际：\`${testScript}\`）`);
  }

  // 规则 2：必须真有测试文件
  const files = collectTestFiles(dir);
  if (files.length === 0) {
    problems.push(`${label}：声明了 vitest 但 src/** 下一个 *.test.ts 都没有（等于没测）`);
  }

  // 规则 3：vitest.config.ts 必须存在，且 include **真的**匹配到这些文件
  const cfgPath = join(dir, 'vitest.config.ts');
  if (!existsSync(cfgPath)) {
    problems.push(`${label}：缺少 vitest.config.ts（无 alias/include 时 @ 前缀 import 解析不了）`);
  } else if (files.length > 0) {
    const cfg = readFileSync(cfgPath, 'utf8');
    const includeMatch = /include\s*:\s*\[([^\]]*)\]/.exec(cfg);
    if (!includeMatch) {
      problems.push(`${label}：vitest.config.ts 里没有显式 include —— 无法判定测试范围`);
    } else {
      const globs = [...includeMatch[1].matchAll(/['"`]([^'"`]+)['"`]/g)].map((m) => m[1]);
      if (globs.length === 0) {
        problems.push(`${label}：vitest.config.ts 的 include 解析为空`);
      } else {
        const regs = globs.map(globToRegExp);
        const unmatched = files.filter((f) => !regs.some((r) => r.test(f)));
        if (unmatched.length) {
          problems.push(
            `${label}：include ${JSON.stringify(globs)} 匹配不到这些测试文件（改了 include 等于静默停跑）：\n      - ` +
              unmatched.join('\n      - ')
          );
        }
      }
    }
  }

  // 规则 4：CI 必须真的调用（剥注释后的命令串锚点）
  const cmd = `pnpm --filter ${pkg.name} test`;
  if (!jobCode.includes(cmd)) {
    problems.push(
      `${label}：mini-programs job 里找不到可执行命令 \`${cmd}\` —— 测试写了但没人调（失效形态①）`
    );
  }
}

// 规则 5（反向）：CI 里调用的每个 `--filter … test` 都必须真有 `test` 脚本，
// 防止「脚本被改名/删掉，CI 那条命令静默跑空或直接 ERR」。
// ⚠️ 这里**不能**要求对方必须声明 vitest：`packages/*` 里的包（如 shared-rbac）可能用别的
//    测试框架，它们只需有 `test` 脚本即可。判据落在「package.json 里到底有没有这个脚本」。
function readPkg(relPath) {
  const p = join(root, relPath);
  if (!existsSync(p)) return null;
  try {
    return JSON.parse(readFileSync(p, 'utf8'));
  } catch {
    return null;
  }
}

function pkgJsonFor(pkgName) {
  for (const group of ['clients', 'packages']) {
    const dir = join(root, group);
    if (!existsSync(dir)) continue;
    for (const name of readdirSync(dir)) {
      const pkg = readPkg(join(group, name, 'package.json'));
      if (pkg?.name === pkgName) return pkg;
    }
  }
  return null;
}

const ciCalls = [...jobCode.matchAll(/pnpm --filter (@aicabinet\/[A-Za-z0-9_-]+)\s+test\b/g)].map(
  (m) => m[1]
);
for (const pkgName of ciCalls) {
  if (targets.some((t) => t.pkg.name === pkgName)) continue;
  const pkg = pkgJsonFor(pkgName);
  if (!pkg) {
    problems.push(
      `ci.yml 调用了 \`pnpm --filter ${pkgName} test\`，但仓库里找不到该包 —— 命令会 ERR`
    );
  } else if (!pkg.scripts?.test) {
    problems.push(
      `ci.yml 调用了 \`pnpm --filter ${pkgName} test\`，但 ${pkgName} 的 package.json 没有 \`test\` 脚本 —— 命令会 ERR`
    );
  }
}

if (problems.length) {
  fail(`前端单测接线不完整：\n  - ${problems.join('\n  - ')}`);
}

console.log(
  `${TAG} OK（装了 vitest 的包 ${targets.length} 个，逐个校验 test 脚本 / 测试文件 / include 命中 / CI 真调用）`
);
for (const { name, dir, pkg } of targets) {
  console.log(`  · ${pkg.name}（clients/${name}）：${collectTestFiles(dir).length} 个测试文件`);
}
