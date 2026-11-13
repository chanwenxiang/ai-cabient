#!/usr/bin/env node
/**
 * `check-callback-endpoint-contract.mjs` 的负向用例（S8④ 自证）。
 *
 *   node scripts/check-callback-endpoint-contract.test.mjs
 */
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { evaluate, extractCallbackMappings } from './check-callback-endpoint-contract.mjs';

const TAG = '[check-callback-endpoint-contract.test]';
let checks = 0;
function assert(cond, message) {
  checks++;
  if (!cond) throw new Error(`${TAG} 断言失败：${message}`);
}
const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const REGISTRY = JSON.parse(
  readFileSync(resolve(ROOT, 'scripts/callback-endpoint-registry.json'), 'utf8')
);
const TRADE_WEB = readFileSync(
  resolve(ROOT, 'services/trade-service/src/main/java/com/aicabinet/trade/config/WebConfig.java'),
  'utf8'
);
const GATEWAY_WEB = readFileSync(
  resolve(
    ROOT,
    'services/jiangyi-gateway/src/main/java/com/aicabinet/jiangyi/config/GatewayWebConfig.java'
  ),
  'utf8'
);
const CTRL = (rel) => readFileSync(resolve(ROOT, rel), 'utf8');

// 1) 真实仓面 ⇒ 零问题
{
  const mappings = REGISTRY.entries.map((e) => ({
    path: e.path,
    file: e.controller,
    method: 'POST'
  }));
  const p = evaluate(mappings, REGISTRY, TRADE_WEB, GATEWAY_WEB, (rel) => true, CTRL);
  assert(p.length === 0, `真实仓面应零问题：${p.join('|')}`);
}

// 2) 新回调未登记 ⇒ 红（完备性本体）
{
  const mappings = [
    ...REGISTRY.entries.map((e) => ({ path: e.path, file: e.controller, method: 'POST' })),
    { path: '/api/v2/payment/newchannel/notify', file: 'x/NewCtrl.java', method: 'POST' }
  ];
  const p = evaluate(
    mappings,
    REGISTRY,
    TRADE_WEB,
    GATEWAY_WEB,
    () => true,
    () => ''
  );
  assert(
    p.some((x) => x.includes('newchannel/notify') && x.includes('未登记')),
    `新回调须红：${p.join('|')}`
  );
  assert(
    p.some((x) => x.includes('MA-047')),
    `红因要点名白名单家族：${p.join('|')}`
  );
}

// 3) MA-047 本体：SIGNATURE_BYPASS 回调从白名单里删掉 ⇒ 红
{
  const tradeWebWithoutWechat = TRADE_WEB.replace('"/api/v2/payment/wechat/notify",', '');
  const mappings = REGISTRY.entries.map((e) => ({
    path: e.path,
    file: e.controller,
    method: 'POST'
  }));
  const p = evaluate(mappings, REGISTRY, tradeWebWithoutWechat, GATEWAY_WEB, () => true, CTRL);
  assert(
    p.some((x) => x.includes('wechat/notify') && x.includes('白名单')),
    `漏白名单须红：${p.join('|')}`
  );
}

// 4) mock 裸奔防线：DEV_ONLY 端点被加进白名单 ⇒ 红
{
  const tradeWebWithMock = TRADE_WEB + '\n"/api/v2/dev/payment/wechat/notify/mock/{orderId}",';
  const mappings = REGISTRY.entries.map((e) => ({
    path: e.path,
    file: e.controller,
    method: 'POST'
  }));
  const p = evaluate(mappings, REGISTRY, tradeWebWithMock, GATEWAY_WEB, () => true, CTRL);
  assert(
    p.some((x) => x.includes('mock 裸奔') || x.includes('裸奔防线')),
    `mock 入白名单须红：${p.join('|')}`
  );
}

// 5) 白名单悄悄加 notify 路径但未登记 ⇒ 红（双向）
{
  const tradeWebWithGhost = TRADE_WEB + '\n"/api/v2/payment/ghost/notify",';
  const mappings = REGISTRY.entries.map((e) => ({
    path: e.path,
    file: e.controller,
    method: 'POST'
  }));
  const p = evaluate(mappings, REGISTRY, tradeWebWithGhost, GATEWAY_WEB, () => true, CTRL);
  assert(
    p.some((x) => x.includes('ghost/notify') && x.includes('白名单路径未登记')),
    `幽灵白名单须红：${p.join('|')}`
  );
}

// 6) 设备面豁免击穿 ⇒ 红
{
  const gatewayWithDevice = GATEWAY_WEB + '\n"/jiangyi/api/deviceInfo/downloadModelNotify",';
  const mappings = REGISTRY.entries.map((e) => ({
    path: e.path,
    file: e.controller,
    method: 'POST'
  }));
  const p = evaluate(mappings, REGISTRY, TRADE_WEB, gatewayWithDevice, () => true, CTRL);
  assert(
    p.some((x) => x.includes('downloadModelNotify') && x.includes('设备面')),
    `设备端点被豁免须红：${p.join('|')}`
  );
}

// 7) 提取器：prefs/subscribe/wx-bind 商户配置面不算回调
{
  const src = `
    @RequestMapping("/api/v2/merchant/notify")
    public class X {
      @GetMapping("/prefs") public void a() {}
      @PostMapping("/subscribe") public void b() {}
      @PostMapping("/notify") public void c() {}
    }`;
  const e = extractCallbackMappings(src, 'x.java');
  assert(
    e.length === 1 && e[0].path === '/api/v2/merchant/notify/notify',
    `商户配置面应排除，回调应命中 1：${JSON.stringify(e)}`
  );
}

console.log(`${TAG} OK：${checks} 项断言全过`);
