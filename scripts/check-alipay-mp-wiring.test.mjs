#!/usr/bin/env node
/**
 * `check-alipay-mp-wiring.mjs` 的负向夹具（铁律 4：新门禁必须证明「能红」）。
 *
 * 做法：先读**真实源码**构造一份本该通过的输入，再逐项破坏，断言每种破坏都被点名。
 * 不用手写假源码——假源码容易和真实结构漂移，验证会变成自欺。
 *
 * 覆盖：
 *  ① 支付宝静默登录缺失 / 用 res.code 取值 ⇒ 红；
 *  ② 小程序内支付缺 payScene / tradePay ⇒ 红；
 *  ③ 后端缺 PAY_SCENE_MINI_APP / JSAPI_PAY / buyer_id ⇒ 红；
 *  ④ 落地页先判 H5 后判小程序 ⇒ 红；
 *  ⑤ 目标文件缺失 ⇒ 防空转红；
 *  ⑥ 注释里写正确做法、代码没改 ⇒ 仍红（maskComments 生效）；
 *  ⑦ 字符串字面量不被抹掉（'MINI_APP' 能命中）。
 *
 *   node scripts/check-alipay-mp-wiring.test.mjs
 */
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { checkWiring, maskComments } from './check-alipay-mp-wiring.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-alipay-mp-wiring.test]';
let checks = 0;

function assert(cond, message) {
  checks++;
  if (!cond) throw new Error(`${TAG} 断言失败：${message}`);
}

function read(rel) {
  return readFileSync(join(ROOT, rel), 'utf8');
}

function baseSources() {
  const consumer = 'clients/consumer-mp';
  return {
    manifest: read(`${consumer}/src/manifest.json`),
    pkg: read(`${consumer}/package.json`),
    consumerApi: read(`${consumer}/src/utils/consumer-api.ts`),
    recharge: read(`${consumer}/src/utils/recharge.ts`),
    paymentService: read(
      'services/trade-service/src/main/java/com/aicabinet/trade/service/PaymentService.java'
    ),
    alipayClient: read(
      'services/trade-service/src/main/java/com/aicabinet/trade/payment/AlipayPayClient.java'
    ),
    landing: read(
      'services/trade-service/src/main/java/com/aicabinet/trade/api/CabinetOpenLandingController.java'
    )
  };
}

function expectFail(sources, fragment, label) {
  const failures = checkWiring(sources);
  assert(
    failures.some((f) => f.includes(fragment)),
    `${label} 应当失败并包含「${fragment}」，实际：${JSON.stringify(failures)}`
  );
}

// —— 0. 基线必须通过 ——
{
  const failures = checkWiring(baseSources());
  assert(failures.length === 0, `真实源码应通过，实际失败：${JSON.stringify(failures)}`);
}

// —— ① 静默登录 ——
{
  const s = baseSources();
  s.consumerApi = s.consumerApi.replace(/getAuthCode\s*\(/g, 'getAuthCodeX(');
  expectFail(s, 'my.getAuthCode', '删掉 getAuthCode 调用');
}
{
  const s = baseSources();
  // 模拟历史写法：支付宝分支按 res.code 取值
  s.consumerApi = 'function f(){ my.getAuthCode({ success(res){ return res.code; } }); }';
  expectFail(s, 'authCode 字段', '支付宝返回按 code 取值');
}
{
  const s = baseSources();
  s.consumerApi = s.consumerApi.replace(/consumerAlipayLogin\(/g, 'wxLoginOnly(');
  expectFail(s, 'consumerAlipayLogin', 'authCode 没有落地到支付宝登录');
}

// —— ② 小程序内支付 ——
{
  const s = baseSources();
  s.recharge = s.recharge.replace(/invokeAlipayTradePay/g, 'invokeWxRequestPayment');
  expectFail(s, 'invokeAlipayTradePay', '小程序收银台调用缺失');
}
{
  const s = baseSources();
  s.recharge = s.recharge.replace(/'MINI_APP'/g, "'H5'").replace(/"MINI_APP"/g, '"H5"');
  expectFail(s, 'MINI_APP', 'payScene 退化成 H5 口径');
}

// —— ③ 后端 ——
{
  const s = baseSources();
  s.paymentService = s.paymentService.replace(/PAY_SCENE_MINI_APP/g, 'PAY_SCENE_H5');
  expectFail(s, 'PAY_SCENE_MINI_APP', '后端丢掉小程序场景常量');
}
{
  const s = baseSources();
  s.alipayClient = s.alipayClient.replace(/JSAPI_PAY/g, 'QUICK_WAP_WAY');
  expectFail(s, 'JSAPI_PAY', '下单产品码退回 wap');
}
{
  const s = baseSources();
  s.alipayClient = s.alipayClient.replace(/buyer_id/g, 'buyer_x');
  expectFail(s, 'buyer_id', 'trade.create 缺 buyer_id');
}

// —— ④ 落地页顺序 ——
{
  const s = baseSources();
  s.landing = `
    if (ALIPAY.equals(channel)) {
      if (!qrProperties.hasConsumerH5()) { return alipayUnavailablePage(id); }
      if (alipayMiniProperties.isConfigured()) { return alipayMiniOpenPage(id, appId); }
    }`;
  expectFail(s, '先判 H5', '落地页先 H5 后小程序');
}

// —— ⑤ 防空转 ——
{
  const s = baseSources();
  s.landing = null;
  expectFail(s, '目标文件缺失', '落地页文件缺失');
}

// —— ⑥ 注释不算数 ——
{
  const s = baseSources();
  s.recharge = `// invokeAlipayTradePay(tradeNo); payScene 'MINI_APP'\nfunction noop() {}`;
  expectFail(s, 'invokeAlipayTradePay', '只在注释里写正确做法（必须仍红）');
}

// —— ⑦ 字符串字面量保留 ——
{
  const masked = maskComments(`const a = 'MINI_APP'; // 'MINI_APP'\nconst b = "x";`);
  assert(masked.includes("'MINI_APP'"), '字符串字面量被误抹：判据会永远红');
  assert(masked.split("'MINI_APP'").length - 1 === 1, '注释里的字面量未被抹掉');
  assert(masked.split('\n').length === 2, '抹注释必须保留行数');
}

console.log(`✅ ${TAG} 通过（${checks} 条断言）`);
