#!/usr/bin/env node
/**
 * 门禁：开门期支付分单的 order_id 可空闭环（2026-11-18 拍板，CB-064）。
 *
 * 三件事缺一不可：迁移放开 NOT NULL（否则开启支付分后开门必 412）、
 * 结算完结回填 orderId（否则本地单永远无关联）、长期未关联有告警（否则取消链断了没人知道）。
 *
 * R1 存在 V496 且 DROP NOT NULL + COMPETITOR_REF: CB-064
 * R2 completeWechatOrderForSession 仍会回填 orderId
 * R3 autoCancelExpired 调孤儿单巡检并报 PAYSCORE_ORDER_UNLINKED
 *
 * 用法：node scripts/check-payscore-order-link.mjs [--self-test]
 *      GATE_ROOT=<sandbox> node scripts/check-payscore-order-link.mjs
 */
import { existsSync, readFileSync, statSync, readdirSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = process.env.GATE_ROOT
  ? resolve(process.env.GATE_ROOT)
  : join(dirname(fileURLToPath(import.meta.url)), '..');
const MIG =
  'services/trade-service/src/main/resources/db/migration/V496__payscore_order_opening_nullable.sql';
const PAY = 'services/trade-service/src/main/java/com/aicabinet/trade/service/PayScoreService.java';
const UNPAID =
  'services/trade-service/src/main/java/com/aicabinet/trade/service/UnpaidOrderService.java';

function walk(dir, out = []) {
  if (!existsSync(dir)) return out;
  for (const n of readdirSync(dir)) {
    const p = join(dir, n);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (n.endsWith('.sql') || n.endsWith('.java')) out.push(p);
  }
  return out;
}

export function evaluate(root = ROOT) {
  const errors = [];
  const files = walk(join(root, 'services', 'trade-service', 'src', 'main'));
  const read = (rel) => {
    const hit = files.find((f) => relative(root, f).replace(/\\/g, '/') === rel);
    return hit ? readFileSync(hit, 'utf8') : '';
  };
  const mig = read(MIG);
  if (
    !/ALTER\s+TABLE\s+payscore_order\s+ALTER\s+COLUMN\s+order_id\s+DROP\s+NOT\s+NULL/i.test(mig)
  ) {
    errors.push(`R1 ${MIG} 缺 order_id DROP NOT NULL——开启支付分后开门必 412`);
  }
  if (!/COMPETITOR_REF\s*:\s*CB-064/.test(mig)) {
    errors.push('R1 迁移缺 COMPETITOR_REF: CB-064（业务决策必须先入竞品台账）');
  }
  const pay = read(PAY);
  const at = pay.indexOf('completeWechatOrderForSession');
  const body = at >= 0 ? pay.slice(at, at + 3000) : '';
  if (!/po\.setOrderId\s*\(/.test(body)) {
    errors.push('R2 支付分完结不再回填 orderId——支付分单永远无本地订单关联');
  }
  const unpaid = read(UNPAID);
  const sweepAt = unpaid.indexOf('public int alertStaleUnlinkedPayscoreOrders');
  if (sweepAt < 0) {
    errors.push('R3 找不到支付分孤儿单巡检');
  } else {
    const sweep = unpaid.slice(sweepAt, sweepAt + 1800);
    if (!/"PAYSCORE_ORDER_UNLINKED"/.test(sweep)) {
      errors.push('R3 巡检不报 PAYSCORE_ORDER_UNLINKED——异常台看不见');
    }
    const entryAt = unpaid.indexOf('int autoCancelExpired()');
    let entry = '';
    if (entryAt >= 0) {
      const open = unpaid.indexOf('{', entryAt);
      let depth = 0;
      let end = open;
      for (; end < unpaid.length; end++) {
        if (unpaid[end] === '{') depth++;
        else if (unpaid[end] === '}') {
          depth--;
          if (depth === 0) break;
        }
      }
      // 只取方法体，否则后面同名方法声明会让「没调用」也绿。
      entry = unpaid.slice(open, end + 1);
    }
    if (!/alertStaleUnlinkedPayscoreOrders\s*\(/.test(entry)) {
      errors.push('R3 巡检没接进 autoCancelExpired，等于永远不跑');
    }
  }
  return errors;
}

export function selfTest() {
  if (!/COMPETITOR_REF\s*:\s*CB-064/.test('-- COMPETITOR_REF: CB-064')) {
    throw new Error('[self-test] 正则失配');
  }
  console.log('[check-payscore-order-link self-test] OK：1 项断言全过');
}

const invokedDirectly =
  process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (invokedDirectly) {
  if (process.argv.includes('--self-test')) {
    selfTest();
  } else {
    const errors = evaluate();
    if (errors.length) {
      console.error(`[check-payscore-order-link] ${errors.length} 项失败：`);
      for (const e of errors) console.error(`  - ${e}`);
      process.exitCode = 1;
    } else {
      console.log('[check-payscore-order-link] OK：迁移/回填/孤儿单告警三环齐');
    }
  }
}
