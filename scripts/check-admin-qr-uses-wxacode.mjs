#!/usr/bin/env node
/**
 * 门禁：后台「柜机二维码」必须出**小程序码**，不得贴普通链接二维码。
 *
 * 成因（2026-11-19 实锤）：柜门码原先只走普通链接二维码
 * （`https://<host>/o/<deviceId>`），微信扫它要靠公众平台
 * 「扫普通链接二维码打开小程序」的前缀规则 + 校验文件才肯拉起小程序。
 * 校验文件是 `docker cp` 进 gateway 容器的，一重建就丢——实测
 * `/o/aHNmrbOhxm.txt` 返回 404，规则失效，后台下载的码扫了只落到 302 短链。
 * 运营按后台提示打印贴柜门，等于贴了一张扫不动的码。
 *
 * 本门禁钉住**跨端契约**：后端出码走 getwxacodeunlimit，前端预览/下载走 wxacode 端点。
 * 只钉一侧护不住——后端改好了前端还打 qr.png，用户拿到的依旧是废码。
 *
 * 判据（任一不满足即红）：
 *  1. WeChatMiniAppClient 含 getwxacodeunlimit 调用；
 *  2. DeviceQrService.wxacodeFor 调 getWxaCodeUnlimited；
 *  3. AdminDashboardController 暴露 /devices/{deviceId}/wxacode.jpg 且调 wxacodeFor；
 *  4. endpoints.ts 的 deviceWxaCode 指向 /wxacode.jpg；
 *  5. DeviceDetailView 的 loadQr 与 downloadQr 都引用 deviceWxaCode；
 *  6. DeviceDetailView 全文不得出现 deviceQrPng(（普通二维码不得作为贴码出口）。
 *
 * 注释先等长脱敏（maskComments）再扫：否则注释里写路径就能骗过判据。
 *
 * 夹具：scripts/check-admin-qr-uses-wxacode.test.mjs（S8④：新门禁必须带能红的用例）。
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { dirname, join } from 'node:path';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');

/** 注释替换为等长空白，保行号、保偏移（跨语言统一口径）。 */
export function maskComments(source, kind) {
  let out = source;
  // 块注释 /* ... */（.vue 模板里不会出现，安全）
  out = out.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '));
  // 行注释 // ...（按行处理，避免动到 URL 里的 //）
  out = out
    .split('\n')
    .map((line) => {
      const idx = line.indexOf('//');
      if (idx < 0) return line;
      // 排除 http:// https:// 以及字符串里的 //
      const before = line.slice(0, idx);
      if (/https?:$/.test(before) || /["'`][^"'`]*$/.test(before)) return line;
      return before + ' '.repeat(line.length - before.length);
    })
    .join('\n');
  if (kind === 'vue') {
    out = out.replace(/<!--[\s\S]*?-->/g, (m) => m.replace(/[^\n]/g, ' '));
  }
  return out;
}

/**
 * 纯判定：给定五份（已脱敏的）源码文本，返回问题清单（空数组=绿）。
 *
 * @param {{wechat:string, qrService:string, controller:string, endpoints:string, detail:string}} src
 * @returns {string[]}
 */
export function evaluate(src) {
  const failures = [];
  const need = (cond, message) => {
    if (!cond) failures.push(message);
  };

  need(
    /getwxacodeunlimit/.test(src.wechat),
    'WeChatMiniAppClient 缺少 getwxacodeunlimit 调用（小程序码能力来源）'
  );
  need(
    /getWxaCodeUnlimited\s*\(/.test(src.wechat),
    'WeChatMiniAppClient 缺少 getWxaCodeUnlimited 方法'
  );

  need(/wxacodeFor\s*\(/.test(src.qrService), 'DeviceQrService 缺少 wxacodeFor');
  need(
    /getWxaCodeUnlimited\s*\(/.test(src.qrService),
    'DeviceQrService.wxacodeFor 未调用 getWxaCodeUnlimited'
  );

  need(
    /\/devices\/\{deviceId\}\/wxacode\.jpg/.test(src.controller),
    'AdminDashboardController 未暴露 /devices/{deviceId}/wxacode.jpg'
  );
  need(
    /wxacodeFor\s*\(/.test(src.controller),
    'AdminDashboardController 的二维码端点未调用 wxacodeFor'
  );

  need(
    /deviceWxaCode:\s*\([^)]*\)\s*=>\s*`[^`]*\/wxacode\.jpg`/.test(src.endpoints),
    'endpoints.ts 的 deviceWxaCode 未指向 /wxacode.jpg'
  );

  need(
    /async function loadQr[\s\S]*?deviceWxaCode\s*\(/.test(src.detail),
    'DeviceDetailView.loadQr 未使用 deviceWxaCode（预览会退回普通二维码）'
  );
  need(
    /async function downloadQr[\s\S]*?deviceWxaCode\s*\(/.test(src.detail),
    'DeviceDetailView.downloadQr 未使用 deviceWxaCode（下载的仍是普通二维码）'
  );
  need(
    !/deviceQrPng\s*\(/.test(src.detail),
    'DeviceDetailView 仍引用 deviceQrPng(：普通二维码不得作为柜门贴码出口'
  );

  return failures;
}

function readMasked(rel, kind) {
  return maskComments(readFileSync(join(root, rel), 'utf8'), kind);
}

function main() {
  const failures = evaluate({
    wechat: readMasked(
      'services/trade-service/src/main/java/com/aicabinet/trade/wechat/WeChatMiniAppClient.java',
      'java'
    ),
    qrService: readMasked(
      'services/trade-service/src/main/java/com/aicabinet/trade/service/DeviceQrService.java',
      'java'
    ),
    controller: readMasked(
      'services/trade-service/src/main/java/com/aicabinet/trade/api/AdminDashboardController.java',
      'java'
    ),
    endpoints: readMasked('clients/admin-vue/src/api/endpoints.ts', 'ts'),
    detail: readMasked('clients/admin-vue/src/views/devices/DeviceDetailView.vue', 'vue')
  });

  if (failures.length) {
    console.error('[check-admin-qr-uses-wxacode] FAIL');
    for (const f of failures) console.error('  - ' + f);
    process.exit(1);
  }
  console.log('[check-admin-qr-uses-wxacode] OK：后台柜门码走小程序码（后端出码 + 前端预览/下载）');
}

// 仅作为主程序执行时跑判定；被 .test.mjs import 时只取 evaluate/maskComments
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main();
}
