#!/usr/bin/env node
/**
 * 门禁：小程序产物里同一文件对的 wxml / wxss scoped 哈希必须一致。
 *
 * 背景（2026-11-06 消费端「共享组件 CSS 落空」）：`dist/dev` 曾有两个生产者——
 * `uni -p mp-weixin` watcher 与一次性 `build:mp-weixin:dev` 的跨目录同步，而**两者对同一份
 * 源码算出的 scoped id 就不同**（实证：home.vue 全程未改，build 侧 wxml/wxss 同为
 * `05765592`，watcher 重写的 wxss 却是 `0cd09a48`，且它一次重建会重写全部 wxss、只重写改动那页的
 * wxml）⇒ 混过一次之后，选择器 `.foo.data-v-A` 命中不到节点上的 `data-v-B`，页面/共享组件样式
 * 整块失效，而构建本身不报错。现已改为 watcher 独占 `dist/dev`、一次性构建只写 `dist/build`。
 *
 * 判据：wxml 里出现的每个 data-v 标记，都必须在同名 wxss 里出现；
 * 缺一个就说明该文件的样式无法命中（wxss 多出的标记不算问题）。
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-mp-scoped-hash]';

const DEFAULT_ROOTS = [
  'clients/consumer-mp/dist/dev/mp-weixin',
  'clients/consumer-mp/dist/build/mp-weixin',
  'clients/merchant-mp/dist/dev/mp-weixin',
  'clients/merchant-mp/dist/build/mp-weixin'
];

const HASH_RE = /data-v-([0-9a-z]+)/g;

function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    const st = statSync(p);
    if (st.isDirectory()) walk(p, out);
    else out.push(p);
  }
  return out;
}

function tokens(file) {
  const found = new Set();
  const src = readFileSync(file, 'utf8');
  for (const m of src.matchAll(HASH_RE)) found.add(m[1]);
  return found;
}

/** 两侧 release 构建必须内嵌「带产物路径」的 scoped-hash 检查（CI 日志实证的接线）。 */
export const BUILD_HOOK_TARGETS = [
  'clients/consumer-mp/package.json',
  'clients/merchant-mp/package.json'
];
const HOOK_RE = /check-mp-scoped-hash\.mjs\s+dist\/build\/mp-weixin/;

/**
 * MA-280：CI 干净检出时没有任何产物，聚合链里那条无参数调用原本直接 `SKIP` exit 0
 * ⇒ 恒零覆盖（实证：ci.yml 的聚合链在 mp-weixin 构建之前跑）。
 *
 * 真实覆盖来自两个 release 构建命令末尾内嵌的
 * `node ../../scripts/check-mp-scoped-hash.mjs dist/build/mp-weixin`
 * （CI 日志：consumer / merchant 各输出一次 OK）。所以无产物时本门禁不再假装判过，
 * 而是**验这条接线还在**；哪天有人把内嵌调用删掉，这一步立刻红。
 */
export function missingBuildHooks(readJson) {
  const missing = [];
  for (const rel of BUILD_HOOK_TARGETS) {
    let script = '';
    try {
      const pkg = readJson(rel);
      script = (pkg && pkg.scripts && pkg.scripts['build:mp-weixin']) || '';
    } catch {
      script = '';
    }
    if (!HOOK_RE.test(script)) missing.push(rel);
  }
  return missing;
}

export function findScopedHashMismatches(root) {
  if (!existsSync(root)) return [];
  const problems = [];
  for (const file of walk(root)) {
    if (!file.endsWith('.wxml')) continue;
    const wxss = file.replace(/\.wxml$/, '.wxss');
    if (!existsSync(wxss)) {
      // 有 wxml 无 wxss：样式文件整体缺失，同样会导致样式落空
      if (tokens(file).size > 0) {
        problems.push({ file: relative(root, file), missing: ['<wxss missing>'], wxssTokens: [] });
      }
      continue;
    }
    const wxmlTokens = tokens(file);
    if (wxmlTokens.size === 0) continue;
    const wxssTokens = tokens(wxss);
    const missing = [...wxmlTokens].filter((t) => t !== 'xxx' && !wxssTokens.has(t));
    if (missing.length > 0) {
      problems.push({
        file: relative(root, file),
        missing,
        wxssTokens: [...wxssTokens]
      });
    }
  }
  return problems;
}

function main() {
  const args = process.argv.slice(2);
  // 显式入参按调用方 cwd 解析（package.json 在各子包目录里执行），默认根按仓库根解析
  // MP_SCOPED_HASH_ROOTS 用于本地复刻「CI 干净检出无产物」的场景（夹具用）：
  // 逗号分隔、按 cwd 解析；不设则回落仓库内 4 个默认产物目录。
  const envRoots = process.env.MP_SCOPED_HASH_ROOTS;
  const roots =
    args.length > 0
      ? args.map((p) => resolve(process.cwd(), p))
      : envRoots
        ? envRoots.split(',').map((p) => resolve(process.cwd(), p.trim()))
        : DEFAULT_ROOTS.map((p) => resolve(ROOT, p));
  const existing = roots.filter((r) => existsSync(r));
  if (existing.length === 0) {
    if (args.length > 0) {
      // 显式传了产物路径 = 调用方承诺「构建刚产出」；此时缺目录必须红，
      // 不能沿用无参数时的 SKIP（那正是「构建失败也判绿」的口子）。
      console.error(
        `${TAG} FAIL：调用方显式指定产物目录但一个都不存在（${roots.join(', ')}）⇒ ` +
          '构建没产出或路径写错，不得判绿'
      );
      process.exit(1);
    }
    const missing = missingBuildHooks((rel) =>
      JSON.parse(readFileSync(resolve(ROOT, rel), 'utf8'))
    );
    if (missing.length) {
      console.error(
        `${TAG} FAIL：无本地产物（CI 干净检出），且这些 release 构建没有内嵌产物级 ` +
          `scoped-hash 检查 ⇒ 聚合链那条会恒 SKIP：\n  - ${missing.join('\n  - ')}\n` +
          '修法：在 build:mp-weixin 命令末尾加 `node ../../scripts/check-mp-scoped-hash.mjs dist/build/mp-weixin`'
      );
      process.exit(1);
    }
    console.log(
      `${TAG} OK：无本地产物；已验 consumer/merchant 的 build:mp-weixin 都内嵌 ` +
        '`dist/build/mp-weixin` 产物级检查（CI 实证），本轮不做哈希比对'
    );
    return;
  }
  let total = 0;
  for (const root of existing) {
    const problems = findScopedHashMismatches(root);
    total += problems.length;
    if (problems.length === 0) {
      console.log(`${TAG} OK：${relative(ROOT, root)} 的 wxml/wxss scoped 哈希全部成对`);
      continue;
    }
    console.error(
      `${TAG} FAIL：${relative(ROOT, root)} 有 ${problems.length} 对 wxml/wxss 哈希不一致：`
    );
    for (const p of problems) {
      console.error(
        `  - ${p.file}  wxml-only=${p.missing.join(',')}  wxss=${p.wxssTokens.join(',') || '(none)'}`
      );
    }
  }
  if (total > 0) {
    const devDir = existing.some((r) => r.includes(`${join('dist', 'dev')}`));
    console.error(
      `${TAG} 处置：${devDir ? '坏的是 dist/dev——它现在只归 dev:mp-weixin watcher 写（一次性构建不再同步进来）。混合过就重启一次 watcher 让它全量重发，或把开发者工具切到 dist/build/mp-weixin；' : '坏的是 dist/build——一次性构建的产物本身不对，重跑 build:mp-weixin[:dev]；'}` +
        '两侧目录都不许再混着写：watcher 与一次性构建对同一份源码算出的 scoped id 不同，混一次就成片错位。'
    );
    process.exit(1);
  }
}

if (import.meta.url === pathToFileURL(process.argv[1] || '').href) {
  main();
}
