#!/usr/bin/env node
/** S8③ 配套夹具：check-inventory-sku-ledger 的 R1/R2/R3 负向验证。 */
import assert from 'node:assert/strict';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = resolve(join(dirname(fileURLToPath(import.meta.url)), '..'));
const BOX = join(ROOT, '.tmp', 'inv-sku-gate-fixture');
const DIR = join(
  BOX,
  'services',
  'trade-service',
  'src',
  'main',
  'java',
  'com',
  'aicabinet',
  'trade',
  'service'
);
mkdirSync(DIR, { recursive: true });

const INV = 'InventoryService.java';
const LOT = 'InventoryLotService.java';
const REL = (name) => `services/trade-service/src/main/java/com/aicabinet/trade/service/${name}`;

const goodInv = `class InventoryService {
  private Map<String,String> doDeductForOrder(String deviceId, List<RecognizedItem> items, String refId) {
    boolean useLot = inventoryLotService.hasLotForSku(deviceId, item.skuId())
      || inventoryLotService.rawAggregateQuantity(deviceId, item.skuId()) > 0;
    return Map.of();
  }
}`;
const goodLot = `class InventoryLotService {
  private FefoDeductResult doDeductFefo(String deviceId, String skuId, int quantity, String refType, String refId) {
    materializeUntrackedInventory(deviceId, skuId);
    var lots = lotRepository.findByDeviceIdAndSkuIdOrderByExpiryDateAsc(deviceId, skuId);
    return new FefoDeductResult("B");
  }
}`;

const gate = await import(
  pathToFileURL(join(ROOT, 'scripts', 'check-inventory-sku-ledger.mjs')).href
);

writeFileSync(join(DIR, INV), goodInv);
writeFileSync(join(DIR, LOT), goodLot);
assert.deepEqual(gate.evaluate(BOX), [], '基线应为绿');

// R1：退回设备级判据
writeFileSync(
  join(DIR, INV),
  goodInv.replace('hasLotForSku(deviceId, item.skuId())', 'deviceUsesLotLedger(deviceId)')
);
assert.ok(
  gate.evaluate(BOX).some((e) => e.includes('R1')),
  'R1 未判红'
);

// R2：拿掉汇总裸数量读取
writeFileSync(
  join(DIR, INV),
  goodInv.replace('rawAggregateQuantity(deviceId, item.skuId()) > 0', 'false')
);
assert.ok(
  gate.evaluate(BOX).some((e) => e.includes('R2')),
  'R2 未判红'
);

// R3：补建放到读批次之后
writeFileSync(join(DIR, INV), goodInv);
writeFileSync(
  join(DIR, LOT),
  goodLot
    .replace('materializeUntrackedInventory(deviceId, skuId);\n    var lots', 'var lots')
    .replace(
      'return new FefoDeductResult("B");',
      'materializeUntrackedInventory(deviceId, skuId);\n    return new FefoDeductResult("B");'
    )
);
assert.ok(
  gate.evaluate(BOX).some((e) => e.includes('R3')),
  'R3 未判红'
);

// 注释里的旧写法不得造假红
writeFileSync(join(DIR, INV), `// deviceUsesLotLedger(x)\n${goodInv}`);
writeFileSync(join(DIR, LOT), goodLot);
assert.deepEqual(gate.evaluate(BOX), [], '注释遮蔽失败');

console.log('[check-inventory-sku-ledger.test] OK：6 项断言全过（R1/R2/R3 负向）');
