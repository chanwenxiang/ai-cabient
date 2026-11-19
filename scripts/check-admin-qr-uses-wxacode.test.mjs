#!/usr/bin/env node
/**
 * `check-admin-qr-uses-wxacode.mjs` 的负向夹具（S8④：新门禁必须带能红的用例）。
 *
 * 重点钉两件事：
 *  ① 判据真的会在「前端退回普通二维码」时红——这正是本门禁存在的理由
 *    （只钉后端护不住跨端契约：后端出小程序码、前端仍打 qr.png，用户拿到的还是废码）；
 *  ② 注释必须先脱敏：注释里出现 `deviceQrPng(` 不该判红，否则每写一句说明就假红。
 *
 *   node scripts/check-admin-qr-uses-wxacode.test.mjs
 */
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { evaluate, maskComments } from './check-admin-qr-uses-wxacode.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-admin-qr-uses-wxacode.test]';
let checks = 0;
function assert(cond, message) {
  checks++;
  if (!cond) throw new Error(`${TAG} 断言失败：${message}`);
}

function masked(rel, kind) {
  return maskComments(readFileSync(join(ROOT, rel), 'utf8'), kind);
}

const REAL = {
  wechat: masked(
    'services/trade-service/src/main/java/com/aicabinet/trade/wechat/WeChatMiniAppClient.java',
    'java'
  ),
  qrService: masked(
    'services/trade-service/src/main/java/com/aicabinet/trade/service/DeviceQrService.java',
    'java'
  ),
  controller: masked(
    'services/trade-service/src/main/java/com/aicabinet/trade/api/AdminDashboardController.java',
    'java'
  ),
  endpoints: masked('clients/admin-vue/src/api/endpoints.ts', 'ts'),
  detail: masked('clients/admin-vue/src/views/devices/DeviceDetailView.vue', 'vue')
};

// 1) 真实现网源码 ⇒ 零问题
{
  const failures = evaluate(REAL);
  assert(failures.length === 0, `真实源码应判绿，实际问题：${failures.join(' | ')}`);
}

// 2) 前端下载退回普通二维码 ⇒ 必须红（核心回归点）
{
  const detail = REAL.detail.replace(/deviceWxaCode\s*\(/g, 'deviceQrPng(');
  assert(detail !== REAL.detail, '夹具前提：DeviceDetailView 里应至少有一处 deviceWxaCode 调用');
  const failures = evaluate({ ...REAL, detail });
  assert(failures.length > 0, '前端退回普通二维码时门禁未判红');
  assert(
    failures.some((f) => f.includes('downloadQr') || f.includes('loadQr')),
    `应点名 loadQr/downloadQr，实际：${failures.join(' | ')}`
  );
}

// 3) 注释里提到 deviceQrPng( 不许判红（脱敏生效）
{
  const detail = REAL.detail + '\n<!-- 说明：旧实现调用 deviceQrPng( 已废弃 -->\n';
  const failures = evaluate({ ...REAL, detail: maskComments(detail, 'vue') });
  assert(
    !failures.some((f) => f.includes('deviceQrPng')),
    `注释里的 deviceQrPng( 被误判：${failures.join(' | ')}`
  );
}

// 4) 后端去掉小程序码端点 ⇒ 红
{
  const controller = REAL.controller.replace(
    /\/devices\/\{deviceId\}\/wxacode\.jpg/g,
    '/devices/{deviceId}/qrcode.png'
  );
  const failures = evaluate({ ...REAL, controller });
  assert(
    failures.some((f) => f.includes('wxacode.jpg')),
    `缺少 /wxacode.jpg 端点时门禁未判红：${failures.join(' | ')}`
  );
}

// 5) endpoints 指向旧端点 ⇒ 红
{
  const endpoints = REAL.endpoints.replace(/\/wxacode\.jpg/g, '/qr.png');
  const failures = evaluate({ ...REAL, endpoints });
  assert(
    failures.some((f) => f.includes('deviceWxaCode')),
    `endpoints 指向 qr.png 时门禁未判红：${failures.join(' | ')}`
  );
}

// 6) DeviceQrService 不再调微信侧 ⇒ 红
{
  const qrService = REAL.qrService.replace(/getWxaCodeUnlimited\s*\(/g, 'legacyLinkQr(');
  const failures = evaluate({ ...REAL, qrService });
  assert(
    failures.some((f) => f.includes('getWxaCodeUnlimited')),
    `后端不再调 getWxaCodeUnlimited 时门禁未判红：${failures.join(' | ')}`
  );
}

console.log(`${TAG} OK：${checks} 项断言全过`);
