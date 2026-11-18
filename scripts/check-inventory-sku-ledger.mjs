#!/usr/bin/env node
/**
 * 门禁：库存扣减的批次判据必须贴在「柜 + SKU」上（F4）。
 *
 * 根因：old `InventoryService#doDeductForOrder` 用 `deviceUsesLotLedger(deviceId)`
 * （该柜有任意批次）决定所有 SKU 走 FEFO。于是同柜里「只有汇总裸库存、没批次行」的 SKU
 * 被 FEFO 判 409（账面有货却卖不掉）；反过来若只在 FEFO 里补建期初批次而扣减仍按设备级
 * 判，缺陷照旧。两条判据必须同时在位。
 *
 * R1 扣减方法不得再调 `deviceUsesLotLedger(`
 * R2 扣减方法必须按 SKU 判批次并读汇总裸数量
 * R3 FEFO 取批次前必须先补建未跟踪库存（LEGACY-OPENING）
 *
 * 用法：node scripts/check-inventory-sku-ledger.mjs [--self-test]
 *      GATE_ROOT=<sandbox> node scripts/check-inventory-sku-ledger.mjs
 */
import { existsSync, readFileSync, statSync, readdirSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = process.env.GATE_ROOT
  ? resolve(process.env.GATE_ROOT)
  : join(dirname(fileURLToPath(import.meta.url)), '..');
const REL_INV =
  'services/trade-service/src/main/java/com/aicabinet/trade/service/InventoryService.java';
const REL_LOT =
  'services/trade-service/src/main/java/com/aicabinet/trade/service/InventoryLotService.java';

/** 等长空格遮蔽注释与字符串，避免注释里的旧写法造成假红。 */
export function maskComments(src) {
  const out = src.split('');
  let i = 0;
  let st = 'code';
  while (i < src.length) {
    const c = src[i];
    const n = src[i + 1];
    if (st === 'code') {
      if (c === '"') st = 'str';
      else if (c === "'") st = 'chr';
      else if (c === '/' && n === '/') {
        st = 'line';
        out[i] = ' ';
      } else if (c === '/' && n === '*') {
        st = 'block';
        out[i] = ' ';
        out[i + 1] = ' ';
        i += 2;
        continue;
      }
    } else if (st === 'line') {
      if (c === '\n') st = 'code';
      else out[i] = ' ';
    } else if (st === 'block') {
      if (c === '*' && n === '/') {
        out[i] = ' ';
        out[i + 1] = ' ';
        i += 2;
        st = 'code';
        continue;
      }
      if (c !== '\n') out[i] = ' ';
    } else if (st === 'str') {
      if (c === '\\') {
        out[i] = ' ';
        if (i + 1 < out.length) out[i + 1] = ' ';
        i += 2;
        continue;
      }
      if (c === '"') st = 'code';
      else if (c !== '\n') out[i] = ' ';
    } else if (st === 'chr') {
      if (c === "'") st = 'code';
      else if (c !== '\n') out[i] = ' ';
    }
    i++;
  }
  return out.join('');
}

/** 按方法签名后的第一个 `{` 做大括号配对，返回方法体。 */
export function methodBody(src, signature) {
  const at = src.indexOf(signature);
  if (at < 0) return '';
  const open = src.indexOf('{', at + signature.length);
  if (open < 0) return '';
  let depth = 0;
  for (let i = open; i < src.length; i++) {
    if (src[i] === '{') depth++;
    else if (src[i] === '}') {
      depth--;
      if (depth === 0) return src.slice(open + 1, i);
    }
  }
  return src.slice(open + 1);
}

export function evaluate(root = ROOT) {
  const errors = [];
  const invPath = join(root, REL_INV);
  const lotPath = join(root, REL_LOT);
  for (const p of [invPath, lotPath]) {
    if (!existsSync(p)) return [`缺文件：${relative(root, p)}——扫描面塌了`];
  }
  const inv = maskComments(readFileSync(invPath, 'utf8'));
  const lot = maskComments(readFileSync(lotPath, 'utf8'));
  const deduct = methodBody(inv, 'doDeductForOrder(String deviceId, List<RecognizedItem> items,');
  const fefo = methodBody(lot, 'doDeductFefo(String deviceId, String skuId, int quantity,');

  if (!deduct) errors.push('R0 找不到 InventoryService#doDeductForOrder——扫描面塌了');
  if (!fefo) errors.push('R0 找不到 InventoryLotService#doDeductFefo——扫描面塌了');
  if (deduct && /deviceUsesLotLedger\s*\(/.test(deduct)) {
    errors.push('R1 扣减又用回设备级 deviceUsesLotLedger( —— 同柜其他 SKU 的裸库存会再次被判 409');
  }
  if (deduct && !/hasLotForSku\s*\(/.test(deduct)) {
    errors.push('R2 扣减没有按 SKU 判批次 hasLotForSku( —— 判据与责任边界脱钩');
  }
  if (deduct && !/rawAggregateQuantity\s*\(/.test(deduct)) {
    errors.push(
      'R2 扣减没有读汇总裸数量 rawAggregateQuantity( —— 无批次但有货的 SKU 无法补建期初批次'
    );
  }
  if (fefo) {
    const materialize = fefo.indexOf('materializeUntrackedInventory(');
    const query = fefo.indexOf('findByDeviceIdAndSkuIdOrderByExpiryDateAsc(');
    if (materialize < 0) {
      errors.push('R3 FEFO 不再补建未跟踪库存——账面有货但批次为空的 SKU 仍会 409');
    } else if (query >= 0 && materialize > query) {
      errors.push('R3 补建发生在读取批次之后——并发下会把刚补的批次漏掉');
    }
  }
  return errors;
}

export function selfTest() {
  const src = '// deviceUsesLotLedger(x)\nString s="hasLotForSku()";\nvoid a(){ hasLotForSku(); }';
  const masked = maskComments(src);
  if (!masked.includes('hasLotForSku();') || masked.split('\n')[1].includes('hasLotForSku')) {
    throw new Error('[self-test] 注释/字符串遮蔽失败');
  }
  if (!methodBody('void a(){ if(x){} }', 'void a()').includes('if(x)')) {
    throw new Error('[self-test] 大括号配对失败');
  }
  console.log('[check-inventory-sku-ledger self-test] OK：2 项断言全过');
}

const invokedDirectly =
  process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (invokedDirectly) {
  if (process.argv.includes('--self-test')) {
    selfTest();
  } else {
    const errors = evaluate();
    if (errors.length) {
      console.error(`[check-inventory-sku-ledger] ${errors.length} 项失败：`);
      for (const e of errors) console.error(`  - ${e}`);
      process.exitCode = 1;
    } else {
      console.log('[check-inventory-sku-ledger] OK：扣减批次判据为 SKU 级，FEFO 前补建期初批次');
    }
  }
}
