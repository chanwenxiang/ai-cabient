#!/usr/bin/env node
/**
 * 门禁：后台「柜门贴码」必须是**普通链接二维码**，且落地页必须一码两用（微信 + 支付宝）。
 *
 * ⚠️ 文件名是历史遗留（原名 check-admin-qr-uses-wxacode，曾把「必须出小程序码」写死）。
 * 2026-11-19 纠正：那个判据是错的，已就地反转——保留文件名是为了不动历史登记台账
 * （fix-batch-registry 里的登记是既成事实，改名会让覆盖门禁对不上）。
 *
 * 为什么必须是普通码（业务事实，非偏好）：
 * 柜门是公用入口，微信和支付宝用户都会扫同一个码。微信小程序码（getwxacodeunlimit）
 * 只有微信扫得出，支付宝扫到的是「无法识别二维码」——把它当主码等于把支付宝渠道关掉。
 * 竞品与既有设计（docs/archive/superpowers/specs/2026-08-05-cabinet-qr-dual-entry-design.md）
 * 都是「固定 HTTPS 普通码 + 服务端按 User-Agent 分流」。
 *
 * 为什么当初会误判成小程序码：曾据「校验文件 /o/<token>.txt 404」推断普通码已废。
 * 实测（2026-11-19）不成立——普通码扫描后服务端 302 到 URL Link
 * （https://wxmpurl.cn/xxx）即可拉起小程序，302 是设计而非失败。
 * 真正的缺口在支付宝那一支的落点（H5）从未部署，见 check-cabinet-qr-alipay-landing.mjs。
 *
 * 判据（任一不满足即红）：
 *  1. DeviceQrService 保留 pngFor（普通二维码生成能力）；
 *  2. AdminDashboardController 暴露 /devices/{deviceId}/qr.png 且调用 pngFor；
 *  3. endpoints.ts 的 deviceQrPng 指向 /qr.png；
 *  4. DeviceDetailView.loadQr 使用 deviceQrPng（预览即贴码）；
 *  5. DeviceDetailView.downloadQr 使用 deviceQrPng，且不得使用 deviceWxaCode；
 *  6. CabinetOpenLandingController 同时识别 AlipayClient 与 MicroMessenger（分流在两支）。
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

/** 取具名函数体（到下一处行首 } 为止）；取不到返回空串。 */
export function fnBody(source, name) {
  const m = new RegExp(`(?:async\\s+)?function\\s+${name}\\s*\\([\\s\\S]*?\\n\\}`).exec(source);
  return m ? m[0] : '';
}

/**
 * 纯判定：给定七份（已脱敏的）源码文本，返回问题清单（空数组=绿）。
 *
 * @param {{qrService:string, controller:string, endpoints:string, detail:string, landing:string}} src
 * @returns {string[]}
 */
export function evaluate(src) {
  const failures = [];
  const need = (cond, message) => {
    if (!cond) failures.push(message);
  };

  need(/pngFor\s*\(/.test(src.qrService), 'DeviceQrService 缺少 pngFor（普通二维码生成能力）');
  need(
    /BarcodeFormat\.QR_CODE/.test(src.qrService),
    'DeviceQrService 未用 QR_CODE 编码（普通二维码能力被移除？）'
  );

  need(
    /\/devices\/\{deviceId\}\/qr\.png/.test(src.controller),
    'AdminDashboardController 未暴露 /devices/{deviceId}/qr.png'
  );
  need(/pngFor\s*\(/.test(src.controller), 'AdminDashboardController 的 qr.png 端点未调用 pngFor');

  need(
    /deviceQrPng:\s*\([^)]*\)\s*=>\s*`[^`]*\/qr\.png`/.test(src.endpoints),
    'endpoints.ts 的 deviceQrPng 未指向 /qr.png'
  );

  const loadQr = fnBody(src.detail, 'loadQr');
  need(loadQr.length > 0, 'DeviceDetailView 找不到 loadQr 函数');
  need(
    /deviceQrPng\s*\(/.test(loadQr),
    'DeviceDetailView.loadQr 未使用 deviceQrPng（预览不是柜门贴码）'
  );

  const downloadQr = fnBody(src.detail, 'downloadQr');
  need(downloadQr.length > 0, 'DeviceDetailView 找不到 downloadQr 函数');
  need(
    /deviceQrPng\s*\(/.test(downloadQr),
    'DeviceDetailView.downloadQr 未使用 deviceQrPng（下载的不是柜门贴码）'
  );
  need(
    !/deviceWxaCode\s*\(/.test(downloadQr),
    'DeviceDetailView.downloadQr 使用了 deviceWxaCode：小程序码只有微信能扫，不得作为柜门主码'
  );

  need(
    /AlipayClient|Alipay/.test(src.landing),
    'CabinetOpenLandingController 未识别支付宝 UA（一码两用的支付宝支缺失）'
  );
  need(
    /MicroMessenger/.test(src.landing),
    'CabinetOpenLandingController 未识别微信 UA（一码两用的微信支缺失）'
  );

  return failures;
}

function readMasked(rel, kind) {
  return maskComments(readFileSync(join(root, rel), 'utf8'), kind);
}

function main() {
  const failures = evaluate({
    qrService: readMasked(
      'services/trade-service/src/main/java/com/aicabinet/trade/service/DeviceQrService.java',
      'java'
    ),
    controller: readMasked(
      'services/trade-service/src/main/java/com/aicabinet/trade/api/AdminDashboardController.java',
      'java'
    ),
    endpoints: readMasked('clients/admin-vue/src/api/endpoints.ts', 'ts'),
    detail: readMasked('clients/admin-vue/src/views/devices/DeviceDetailView.vue', 'vue'),
    landing: readMasked(
      'services/trade-service/src/main/java/com/aicabinet/trade/api/CabinetOpenLandingController.java',
      'java'
    )
  });

  if (failures.length) {
    console.error('[check-admin-qr-uses-wxacode] FAIL');
    for (const f of failures) console.error('  - ' + f);
    process.exit(1);
  }
  console.log(
    '[check-admin-qr-uses-wxacode] OK：柜门贴码为普通二维码，落地页微信/支付宝双支分流齐备'
  );
}

// 仅作为主程序执行时跑判定；被 .test.mjs import 时只取 evaluate/maskComments
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main();
}
