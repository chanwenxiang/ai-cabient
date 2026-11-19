#!/usr/bin/env node
/**
 * 门禁：消费者**支付宝小程序**接线完整性（mp-alipay）。
 *
 * 背景（2026-11-19）：柜门码回退普通二维码后，支付宝里扫码必须能进小程序并完成
 * 「登录 → 充值 → 开门」。这条链路横跨四个文件、六个环节，任意一处退回微信口径
 * 都**不会报错**，只会在支付宝真机上表现为「停在登录页」或「收银台没反应」：
 *
 *   ① manifest 缺 mp-alipay 节点 / 构建脚本缺 appid 注入 ⇒ 产物 appid= touristappid
 *      （能编译、能模拟器预览，真机不能用）；
 *   ② 静默登录仍只写 MP-WEIXIN 分支 ⇒ 扫码进来永远未登录（ensureConsumerAuth 落回
 *      `#ifndef MP-WEIXIN` 的 return false）；
 *   ③ 支付宝 authCode 按 `res.code` 取 ⇒ 恒 undefined（支付宝原生字段是 authCode）；
 *   ④ 充值仍走 wap 表单 ⇒ 小程序里被客户端拦截，用户只看到白屏；
 *   ⑤ 后端没有 trade.create / 缺 buyer_id ⇒ 小程序支付无法调起；
 *   ⑥ 落地页 ALIPAY 分支先跳 H5 ⇒ 支付宝用户被挡在小程序门外。
 *
 * 判据（全部对**去注释后的源码**执行——注释里写满正确做法但代码没改，是最常见的
 * 假绿，铁律 84 ①）：
 *   1. manifest.json 有 mp-alipay 节点；package.json 的 build:mp-alipay 含 appid 注入脚本；
 *   2. consumer-api.ts 含支付宝静默登录：getAuthCode + authCode 字段 + consumerAlipayLogin；
 *   3. recharge.ts 含 invokeAlipayTradePay + my.tradePay + payScene 'MINI_APP'；
 *   4. 后端 PaymentService 含 PAY_SCENE_MINI_APP 且调用 createMiniPay；
 *   5. 后端 AlipayPayClient 的 mini 支付带 product_code=JSAPI_PAY 与 buyer_id；
 *   6. 落地页 ALIPAY 分支里 `alipayMiniProperties.isConfigured()` 必须出现在
 *      `hasConsumerH5()` 之前（先小程序、后 H5 兜底）；
 *   7. 防空转：任一目标文件缺失 ⇒ 红（改名/移目录时不许静默绿）。
 *
 * 用法：node scripts/check-alipay-mp-wiring.mjs
 * 负向验证：scripts/check-alipay-mp-wiring.test.mjs（内存夹具，不起仓库文件）
 */
import { existsSync, readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..');

/**
 * 抹掉注释（等长空格，保留行号）。字符串内容保留——判据里的 'MINI_APP'、JSAPI_PAY
 * 等字面量就在字符串里，抹掉会让门禁永远红。
 */
export function maskComments(src) {
  let out = '';
  let i = 0;
  let state = 'code';
  while (i < src.length) {
    const c = src[i];
    const d = src[i + 1];
    if (state === 'code') {
      if (c === '/' && d === '/') {
        state = 'line';
        out += '  ';
        i += 2;
        continue;
      }
      if (c === '/' && d === '*') {
        state = 'block';
        out += '  ';
        i += 2;
        continue;
      }
      if (c === '"') state = 'str2';
      else if (c === "'") state = 'str1';
      else if (c === '`') state = 'tpl';
      out += c;
      i += 1;
      continue;
    }
    if (state === 'line') {
      if (c === '\n') {
        state = 'code';
        out += '\n';
      } else {
        out += ' ';
      }
      i += 1;
      continue;
    }
    if (state === 'block') {
      if (c === '*' && d === '/') {
        state = 'code';
        out += '  ';
        i += 2;
        continue;
      }
      out += c === '\n' ? '\n' : ' ';
      i += 1;
      continue;
    }
    if (c === '\\') {
      out += c + (d || '');
      i += 2;
      continue;
    }
    if (
      (state === 'str1' && c === "'") ||
      (state === 'str2' && c === '"') ||
      (state === 'tpl' && c === '`')
    ) {
      state = 'code';
    }
    out += c;
    i += 1;
  }
  return out;
}

/**
 * 纯判据：入参是**已读取**的源码字符串（manifest/pkg 为原文 JSON 文本）。
 * 返回失败原因数组（空 = 通过）。抽出来是为了负向验证能喂夹具，不必改仓库文件。
 */
export function checkWiring(sources) {
  const failures = [];
  const fail = (msg) => failures.push(msg);

  const missing = [];
  const manifestSrc = sources.manifest;
  const pkgSrc = sources.pkg;
  if (typeof manifestSrc !== 'string') missing.push('src/manifest.json');
  if (typeof pkgSrc !== 'string') missing.push('package.json');
  for (const rel of ['consumerApi', 'recharge', 'paymentService', 'alipayClient', 'landing']) {
    if (typeof sources[rel] !== 'string') missing.push(rel);
  }
  if (missing.length > 0) {
    return [`目标文件缺失：${missing.join(', ')}（门禁防空转：移走/改名必须同步改本门禁）`];
  }

  // 1) manifest / 构建脚本
  let manifest = null;
  try {
    manifest = JSON.parse(manifestSrc);
  } catch {
    fail('manifest.json 不是合法 JSON');
  }
  if (manifest && !manifest['mp-alipay']) {
    fail('manifest.json 缺 "mp-alipay" 节点：支付宝编译会退回默认值，产物不可用');
  }
  let pkg = null;
  try {
    pkg = JSON.parse(pkgSrc);
  } catch {
    fail('package.json 不是合法 JSON');
  }
  const alipayBuild = String(pkg?.scripts?.['build:mp-alipay'] || '');
  if (!alipayBuild) {
    fail('package.json 缺 scripts.build:mp-alipay');
  } else if (!alipayBuild.includes('inject-alipay-mp-env.mjs')) {
    fail('build:mp-alipay 未接 appid 注入（产物会带 touristappid，真机不可用）');
  }

  const api = maskComments(sources.consumerApi);
  if (!/\bgetAuthCode\s*\(/.test(api)) {
    fail('consumer-api.ts 未调用 my.getAuthCode：支付宝端无法静默登录');
  }
  if (!/getAuthCode[\s\S]{0,400}?authCode/.test(api)) {
    fail('getAuthCode 的返回未按 authCode 字段取值（支付宝原生字段是 authCode，不是 code）');
  }
  if (!api.includes('consumerAlipayLogin(')) {
    fail('consumer-api.ts 未调用 consumerAlipayLogin：authCode 换了也没落地');
  }

  const recharge = maskComments(sources.recharge);
  if (!recharge.includes('invokeAlipayTradePay')) {
    fail('recharge.ts 缺 invokeAlipayTradePay：小程序内无法拉起支付宝收银台');
  }
  if (!recharge.includes('tradePay')) {
    fail('recharge.ts 未调用 my.tradePay');
  }
  if (!recharge.includes("'MINI_APP'") && !recharge.includes('"MINI_APP"')) {
    fail('recharge.ts 未声明 payScene MINI_APP：服务端会返回 wap 表单，小程序里必然白屏');
  }

  const paymentService = maskComments(sources.paymentService);
  if (!paymentService.includes('PAY_SCENE_MINI_APP')) {
    fail('PaymentService 缺 PAY_SCENE_MINI_APP：payScene 传了也没人认');
  }
  if (!paymentService.includes('createMiniPay(')) {
    fail('PaymentService 未调用 createMiniPay：小程序场景落不到 trade.create');
  }

  const alipayClient = maskComments(sources.alipayClient);
  if (!alipayClient.includes('JSAPI_PAY')) {
    fail(
      'AlipayPayClient 的 createMiniPay 未带 product_code=JSAPI_PAY（会按 wap 处理并报交易类型不符）'
    );
  }
  if (!alipayClient.includes('buyer_id')) {
    fail('AlipayPayClient 的 createMiniPay 未传 buyer_id：trade.create 必失败');
  }

  const landing = maskComments(sources.landing);
  const miniIdx = landing.indexOf('alipayMiniProperties.isConfigured()');
  const h5Idx = landing.indexOf('hasConsumerH5()');
  if (miniIdx < 0) {
    fail('落地页未判断支付宝小程序配置：支付宝扫码仍会落到「请用微信扫码」页');
  } else if (h5Idx >= 0 && h5Idx < miniIdx) {
    fail('落地页先判 H5 再判小程序：支付宝用户会被 H5 截走（顺序须为先小程序）');
  }

  return failures;
}

function readSource(relPath) {
  const abs = join(repoRoot, relPath);
  return existsSync(abs) ? readFileSync(abs, 'utf8') : null;
}

function main() {
  const consumer = 'clients/consumer-mp';
  const failures = checkWiring({
    manifest: readSource(`${consumer}/src/manifest.json`),
    pkg: readSource(`${consumer}/package.json`),
    consumerApi: readSource(`${consumer}/src/utils/consumer-api.ts`),
    recharge: readSource(`${consumer}/src/utils/recharge.ts`),
    paymentService: readSource(
      'services/trade-service/src/main/java/com/aicabinet/trade/service/PaymentService.java'
    ),
    alipayClient: readSource(
      'services/trade-service/src/main/java/com/aicabinet/trade/payment/AlipayPayClient.java'
    ),
    landing: readSource(
      'services/trade-service/src/main/java/com/aicabinet/trade/api/CabinetOpenLandingController.java'
    )
  });

  if (failures.length > 0) {
    console.error('❌ 支付宝小程序接线门禁未通过：');
    for (const f of failures) console.error(`  - ${f}`);
    process.exit(1);
  }
  console.log('✅ 支付宝小程序接线门禁通过（登录 / 支付 / 落地页 / 构建注入）');
}

// 被测试 import 时不执行主流程
if (process.argv[1] && process.argv[1].endsWith('check-alipay-mp-wiring.mjs')) {
  main();
}
