#!/usr/bin/env node
/**
 * `check-admin-qr-uses-wxacode.mjs` 的负向夹具（S8④：新门禁必须带能红的用例）。
 *
 * 重点钉三件事：
 *  ① 主码退回小程序码时必须红——柜门码只有微信能扫，支付宝用户直接扫不动；
 *  ② 落地页少一支分流（微信/支付宝）时必须红——一码两用靠的就是这两支；
 *  ③ 注释必须先脱敏：注释里出现 `deviceWxaCode(` 不该判红，否则写说明就假红。
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
  qrService: masked(
    'services/trade-service/src/main/java/com/aicabinet/trade/service/DeviceQrService.java',
    'java'
  ),
  controller: masked(
    'services/trade-service/src/main/java/com/aicabinet/trade/api/AdminDashboardController.java',
    'java'
  ),
  endpoints: masked('clients/admin-vue/src/api/endpoints.ts', 'ts'),
  detail: masked('clients/admin-vue/src/views/devices/DeviceDetailView.vue', 'vue'),
  landing: masked(
    'services/trade-service/src/main/java/com/aicabinet/trade/api/CabinetOpenLandingController.java',
    'java'
  )
};

// 1) 真实现网源码 ⇒ 零问题
{
  const failures = evaluate(REAL);
  assert(failures.length === 0, `真实源码应判绿，实际问题：${failures.join(' | ')}`);
}

// 2) 主码退回小程序码 ⇒ 必须红（核心回归点：支付宝扫不动）
{
  const detail = REAL.detail.replace(
    /async function downloadQr\(\)[\s\S]*?\n\}/,
    'async function downloadQr() {\n  await downloadAuthFile(AdminEndpoints.deviceWxaCode(deviceId), "x.jpg");\n}'
  );
  assert(detail !== REAL.detail, '夹具前提：应能替换 downloadQr 函数体');
  const failures = evaluate({ ...REAL, detail });
  assert(failures.length > 0, '主码退回小程序码时门禁未判红');
  assert(
    failures.some((f) => f.includes('downloadQr')),
    `应点名 downloadQr，实际：${failures.join(' | ')}`
  );
}

// 3) 预览退回小程序码 ⇒ 必须红
{
  const detail = REAL.detail.replace(
    /async function loadQr\(\)[\s\S]*?\n\}/,
    'async function loadQr() {\n  const r = await authFetch(AdminEndpoints.deviceWxaCode(deviceId));\n}'
  );
  assert(detail !== REAL.detail, '夹具前提：应能替换 loadQr 函数体');
  const failures = evaluate({ ...REAL, detail });
  assert(
    failures.some((f) => f.includes('loadQr')),
    `预览退回小程序码时门禁未判红：${failures.join(' | ')}`
  );
}

// 4) 注释里提到 deviceWxaCode( 不许判红（脱敏生效）
{
  const detail = REAL.detail + '\n<!-- 说明：旧实现调用 deviceWxaCode( 已降级为次要入口 -->\n';
  const failures = evaluate({ ...REAL, detail: maskComments(detail, 'vue') });
  assert(
    !failures.some((f) => f.includes('deviceWxaCode')),
    `注释里的 deviceWxaCode( 被误判：${failures.join(' | ')}`
  );
}

// 5) 后端去掉 /qr.png 端点 ⇒ 红
{
  const controller = REAL.controller.replace(
    /\/devices\/\{deviceId\}\/qr\.png/g,
    '/devices/{deviceId}/q.png'
  );
  const failures = evaluate({ ...REAL, controller });
  assert(
    failures.some((f) => f.includes('qr.png')),
    `缺少 /qr.png 端点时门禁未判红：${failures.join(' | ')}`
  );
}

// 6) endpoints 的 deviceQrPng 指错 ⇒ 红
{
  const endpoints = REAL.endpoints.replace(
    /(deviceQrPng:\s*\([^)]*\)\s*=>\s*`[^`]*\/)qr\.png`/,
    '$1wxacode.jpg`'
  );
  const failures = evaluate({ ...REAL, endpoints });
  assert(
    failures.some((f) => f.includes('deviceQrPng')),
    `deviceQrPng 指向错端点时门禁未判红：${failures.join(' | ')}`
  );
}

// 7) 后端去掉普通码生成能力 ⇒ 红
{
  const qrService = REAL.qrService.replace(/pngFor\s*\(/g, 'legacyPng(');
  const failures = evaluate({ ...REAL, qrService });
  assert(
    failures.some((f) => f.includes('pngFor')),
    `去掉 pngFor 时门禁未判红：${failures.join(' | ')}`
  );
}

// 8) 落地页丢掉支付宝支 ⇒ 红（一码两用失效）
{
  const landing = REAL.landing.replace(/Alipay/gi, 'OtherPay');
  const failures = evaluate({ ...REAL, landing });
  assert(
    failures.some((f) => f.includes('支付宝')),
    `落地页丢失支付宝识别时门禁未判红：${failures.join(' | ')}`
  );
}

// 9) 落地页丢掉微信支 ⇒ 红
{
  const landing = REAL.landing.replace(/MicroMessenger/g, 'OtherUA');
  const failures = evaluate({ ...REAL, landing });
  assert(
    failures.some((f) => f.includes('微信')),
    `落地页丢失微信识别时门禁未判红：${failures.join(' | ')}`
  );
}

console.log(`${TAG} OK：${checks} 项断言全过`);
