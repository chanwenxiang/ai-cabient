#!/usr/bin/env node
/**
 * check-dispute-vendor-tail 的负向夹具：真树绿 + 三种注入红 + 真树字节零残留。
 */
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import {
  BACKEND_FILE,
  FRONTEND_FILE,
  backendProblems,
  frontendProblems
} from './check-dispute-vendor-tail.mjs';

const TAG = '[check-dispute-vendor-tail.test]';
let checks = 0;
function assert(cond, message) {
  checks++;
  if (!cond) throw new Error(`${TAG} 断言失败：${message}`);
}
const md5 = (p) => createHash('md5').update(readFileSync(p)).digest('hex');

const backendBefore = md5(BACKEND_FILE);
const frontendBefore = md5(FRONTEND_FILE);
const backend = readFileSync(BACKEND_FILE, 'utf8');
const frontend = readFileSync(FRONTEND_FILE, 'utf8');
assert(backend.length > 1000, '后端源文件读取失败（锚点漂移）');
assert(frontend.length > 1000, '前端源文件读取失败（锚点漂移）');
assert(backendProblems(backend).length === 0, '真实后端必须绿');
assert(frontendProblems(frontend).length === 0, '真实前端必须绿');

const rawInjected = backend.replace(
  'String raw = stripVendorDetail(reason == null ? "" : reason.trim());',
  'String raw = reason == null ? "" : reason.trim();'
);
assert(rawInjected !== backend, '注入锚点 String raw 找不到');
assert(
  backendProblems(rawInjected).some((p) => p.includes('未经 stripVendorDetail')),
  '后端恢复透传 reason 必须红'
);

const anchorInjected = backend.replace(
  'value.indexOf("｜设备原文：")',
  'value.indexOf("__gone__")'
);
assert(anchorInjected !== backend, '注入锚点设备原文字符串找不到');
assert(
  backendProblems(anchorInjected).some((p) => p.includes('锚点')),
  '后端剥离锚点被改掉必须红'
);

const derivedInjected = frontend.replace(
  'stripVendorTail(ticket.consumerReviewDetail)',
  'ticket.consumerReviewDetail'
);
assert(derivedInjected !== frontend, '注入锚点 consumerReviewDetail 找不到');
assert(
  frontendProblems(derivedInjected).some((p) => p.includes('consumerReviewDetail')),
  '前端派生字段绕过剥离必须红'
);

assert(md5(BACKEND_FILE) === backendBefore, '后端真树被写入污染');
assert(md5(FRONTEND_FILE) === frontendBefore, '前端真树被写入污染');
console.log(`${TAG} OK：${checks} 项断言全过（真树绿 / 三种注入红 / md5 零污染）`);
