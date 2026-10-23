#!/usr/bin/env node
/**
 * 门禁：已提交的 `packages/shared-xxx/dist` 必须等于**用当前 src 重新编译**的产物。
 *
 * 背景（lessons #231）：`check:shared` 只做 `build:packages` 重建，不比对 ⇒ 门禁恒绿。
 * 实测 shared-rbac/dist、shared-dict/dist 分别落后 src 27 天/6 天，rbac 的 dist 甚至
 * 缺了 B-13 修复（`ops:admin` 无条件放行）——Vite 端走 src 别名看不出来，而 node
 * 直消费 dist 的脚本/包拿到的是旧逻辑。
 *
 * 做法：把每个包用**包内 tsc** 编到系统临时目录下的独立 outDir（不动工作树），
 * 再与 git 中已跟踪的 dist 文件逐字节比对（CRLF 归一）：
 *   - 内容不同 → 落后；
 *   - 已跟踪文件在新构建里不存在（源文件已删）→ 死产物；
 *   - 新构建多出未跟踪文件（新增源文件没提交产物）→ 落后。
 *
 * 负向验证：改 `packages/shared-dict/src/index.ts` 里的任一导出文本但不重建 => 红；
 * 重建并提交 dist => 绿。
 *
 *   node scripts/check-shared-dist-fresh.mjs
 */
import { existsSync, mkdirSync, readFileSync, readdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gitAsync, runAsync } from './lib/async-spawn.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-shared-dist-fresh]';

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

function walk(dir, out = []) {
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else out.push(p);
  }
  return out;
}

const normalize = (s) => s.replace(/\r\n/g, '\n');

const packagesDir = join(ROOT, 'packages');
const packages = readdirSync(packagesDir, { withFileTypes: true })
  .filter((d) => d.isDirectory())
  .map((d) => d.name)
  .filter(
    (p) =>
      existsSync(join(packagesDir, p, 'dist')) && existsSync(join(packagesDir, p, 'tsconfig.json'))
  );

const outRoot = join(tmpdir(), `aicabinet-shared-dist-${process.pid}-${Date.now()}`);
mkdirSync(outRoot, { recursive: true });

const problems = [];
const checked = [];

try {
  for (const pkg of packages) {
    const pkgDir = join(packagesDir, pkg);
    const listed = await gitAsync(['ls-files', '--', `packages/${pkg}/dist/`], { cwd: ROOT });
    if (!listed.ok) fail(`git ls-files 失败：${listed.out}`);
    const tracked = listed.out.split(/\r?\n/).filter(Boolean);
    if (tracked.length === 0) continue;

    const localTsc = join(pkgDir, 'node_modules', 'typescript', 'bin', 'tsc');
    if (!existsSync(localTsc)) {
      fail(`${pkg}: 找不到包内 tsc（${localTsc}）——先 pnpm install；无法比对就不许报绿`);
    }

    const outDir = join(outRoot, pkg);
    const build = await runAsync(
      process.execPath,
      [localTsc, '-p', join(pkgDir, 'tsconfig.json'), '--outDir', outDir],
      {
        cwd: ROOT
      }
    );
    if (build.status !== 0) {
      fail(`${pkg}: 重新编译失败（exit ${build.status}）\n${build.stdout}\n${build.stderr}`);
    }

    const generated = existsSync(outDir)
      ? walk(outDir).map((f) => relative(outDir, f).replace(/\\/g, '/'))
      : [];
    const generatedSet = new Set(generated);
    const trackedSet = new Set(
      tracked.map((f) => {
        const prefix = `packages/${pkg}/dist/`;
        return f.startsWith(prefix) ? f.slice(prefix.length) : f;
      })
    );

    for (const rel of trackedSet) {
      const diskFile = join(pkgDir, 'dist', rel);
      if (!generatedSet.has(rel)) {
        problems.push(
          `${pkg}/dist/${rel}: 源文件已不存在，但产物仍被提交（死产物，删掉并重新提交）`
        );
        continue;
      }
      const committed = existsSync(diskFile) ? readFileSync(diskFile, 'utf8') : null;
      if (committed === null) {
        problems.push(`${pkg}/dist/${rel}: 已被 git 跟踪但工作树里缺失`);
        continue;
      }
      if (normalize(committed) !== normalize(readFileSync(join(outDir, rel), 'utf8'))) {
        problems.push(`${pkg}/dist/${rel}: 与当前 src 编译结果不一致（dist 落后）`);
      }
    }
    for (const rel of generatedSet) {
      if (!trackedSet.has(rel)) {
        problems.push(`${pkg}/dist/${rel}: 新编译产物未被 git 跟踪（新增源文件后没提交 dist）`);
      }
    }
    checked.push(pkg);
  }
} finally {
  rmSync(outRoot, { recursive: true, force: true });
}

if (checked.length === 0) fail('没有任何带已跟踪 dist 的 shared 包，锚点可能已失效');

if (problems.length) {
  fail(
    `shared dist 与 src 不同步 ${problems.length} 处：\n  - ${problems.join('\n  - ')}\n运行 pnpm build:packages 后连同 dist 一起提交。`
  );
}

console.log(`${TAG} OK：${checked.join(', ')} 的已提交 dist 与当前 src 编译结果一致`);
