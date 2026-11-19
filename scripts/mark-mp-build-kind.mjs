#!/usr/bin/env node
/**
 * 给小程序构建产物打「这是哪种包」的标记，供 check-mp-release-bundle.mjs 判真伪。
 *
 * 为什么需要：`build:mp-weixin`（release）与 `build:mp-weixin:dev`（--mode development）
 * **输出到同一个目录** `dist/build/mp-weixin`。跑过 dev 构建之后，同一目录里躺着的
 * 就是 API 基址为 `http://127.0.0.1:8080` 的 dev 包；此时从该目录上传，线上版就会
 * 把所有请求打到回环地址（2026-11-19 线上事故的成因）。
 *
 * 落点刻意放在 `dist/build/.build-kind`（mp-weixin 的**上一级**）：那里不会被微信
 * 打包上传，不会产生「无依赖文件未打包」之类的噪音。
 *
 * 用法：node scripts/mark-mp-build-kind.mjs <dev|release> <distDir>
 *   distDir 形如 `dist/build/mp-weixin`（相对当前包目录）。
 */
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';

const TAG = '[mark-mp-build-kind]';
const [kind, distDir] = process.argv.slice(2);

if (kind !== 'dev' && kind !== 'release') {
  console.error(`${TAG} 用法：node scripts/mark-mp-build-kind.mjs <dev|release> <distDir>`);
  process.exit(2);
}
if (!distDir) {
  console.error(`${TAG} 缺少 distDir（如 dist/build/mp-weixin）`);
  process.exit(2);
}

const target = resolve(process.cwd(), dirname(distDir));
mkdirSync(target, { recursive: true });
const file = join(target, '.build-kind');
writeFileSync(
  file,
  JSON.stringify({ kind, distDir, markedAt: new Date().toISOString() }, null, 2) + '\n',
  'utf8'
);
console.log(`${TAG} ${kind} -> ${file}`);
