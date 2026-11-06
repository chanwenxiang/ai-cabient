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
  const roots =
    args.length > 0
      ? args.map((p) => resolve(process.cwd(), p))
      : DEFAULT_ROOTS.map((p) => resolve(ROOT, p));
  const existing = roots.filter((r) => existsSync(r));
  if (existing.length === 0) {
    console.log(`${TAG} SKIP：未找到产物目录（先构建小程序）`);
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
