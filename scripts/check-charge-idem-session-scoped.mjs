#!/usr/bin/env node
/**
 * 门禁：CHARGE 流水幂等键必须以 sessionId 为轴（F6/J10，F3b 拆段前置）。
 *
 * 旧键 `CHARGE:<orderId>:<amount>` 的致命形态：渠道扣款成功、本地后续失败后用户重试
 * 会换一个新 orderId ⇒ 绕过幂等键再扣一次。同一 session 只对应一个订单，
 * 所以 sessionId 才是「这次购物」的稳定身份。
 *
 * R1 `chargeIdempotencyKey` 必须以 `CHARGE:S:<sessionId>:` 构造新键
 * R2 生产代码不得再用旧键**写入** setIdempotencyKey（历史键只允许双读）
 * R3 必须有双读：新键查不到时再查旧键（否则历史 COMPLETED 单会被当新单）
 * R4 支付分回调与主链必须用同一个 helper，且回调侧也要双读
 *
 * 用法：node scripts/check-charge-idem-session-scoped.mjs [--self-test]
 *      GATE_ROOT=<sandbox> node scripts/check-charge-idem-session-scoped.mjs
 */
import { existsSync, readFileSync, statSync, readdirSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = process.env.GATE_ROOT
  ? resolve(process.env.GATE_ROOT)
  : join(dirname(fileURLToPath(import.meta.url)), '..');
const SRC_REL = 'services/trade-service/src/main/java/com/aicabinet/trade';
const FILES = ['service/OrderPaymentService.java', 'service/PayScoreOrderNotifyService.java'];

export function maskComments(src) {
  const out = src.split('');
  let i = 0;
  let st = 'code';
  while (i < src.length) {
    const c = src[i];
    const n = src[i + 1];
    if (st === 'code') {
      // 字符串字面量**保留**：本门禁的判据就是键字面量（"CHARGE:S:" / "CHARGE:"），
      // 涂掉会真的把判据抹没（首版 self-test 实测）。注释才需要遮蔽。
      if (c === '/' && n === '/') {
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
    }
    i++;
  }
  return out.join('');
}

function walk(dir, out = []) {
  if (!existsSync(dir)) return out;
  for (const n of readdirSync(dir)) {
    const p = join(dir, n);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (n.endsWith('.java')) out.push(p);
  }
  return out;
}

export function evaluate(root = ROOT) {
  const errors = [];
  const texts = new Map();
  for (const rel of FILES) {
    const p = join(root, SRC_REL, rel);
    if (!existsSync(p)) {
      errors.push(`R0 缺文件 ${rel.replace('service/', '')}——扫描面塌了`);
      continue;
    }
    texts.set(rel, maskComments(readFileSync(p, 'utf8')));
  }
  if (errors.length) return errors;

  const pay = texts.get(FILES[0]);
  const notify = texts.get(FILES[1]);

  // R1 新键形态
  if (!/static\s+String\s+chargeIdempotencyKey\s*\(/.test(pay)) {
    errors.push('R1 找不到 chargeIdempotencyKey helper');
  } else if (!/"CHARGE:S:"/.test(pay)) {
    errors.push('R1 helper 不再以 CHARGE:S:<sessionId>: 造键——重试会换 orderId 绕过幂等');
  }
  // R2 旧键只允许双读，不允许写入
  for (const [rel, src] of texts) {
    for (const line of src.split('\n')) {
      if (/setIdempotencyKey\s*\([^)]*"CHARGE:"\s*\+/.test(line)) {
        errors.push(
          `R2 ${rel.split('/').pop()} 仍用旧 CHARGE:<orderId>: 键写入：${line.trim().slice(0, 100)}`
        );
      }
    }
  }
  // R3 双读
  if (!/findChargeOperation\s*\(/.test(pay)) {
    errors.push('R3 找不到 findChargeOperation 双读入口');
  } else {
    const m = /findChargeOperation\s*\(\s*CabinetOrder order, int amountCents\s*\)\s*\{/.exec(pay);
    const body = m ? pay.slice(m.index, m.index + 1200) : '';
    if (!/legacyChargeIdempotencyKey\s*\(\s*order\.getOrderId\(\)/.test(body)) {
      errors.push('R3 双读不再查旧键——历史 COMPLETED 单会被误当新单再扣');
    }
    const legacyAt = pay.indexOf('private static String legacyChargeIdempotencyKey');
    const legacyBody = legacyAt >= 0 ? pay.slice(legacyAt, legacyAt + 300) : '';
    if (!/"CHARGE:"/.test(legacyBody)) {
      errors.push('R3 legacy  helper 不再返回 CHARGE:<orderId>:<amount> 旧键形态');
    }
  }
  // R4 回调与主链同源
  if (!/OrderPaymentService\.chargeIdempotencyKey\s*\(/.test(notify)) {
    errors.push('R4 支付分 DONE 回调没用主链 helper——同一笔会落两条 CHARGE 流水');
  }
  if (!/"CHARGE:"\s*\+\s*order\.getOrderId\(\)/.test(notify)) {
    errors.push('R4 支付分回调没双读旧键——历史支付分单会重复补记');
  }
  return errors;
}

export function selfTest() {
  const s = maskComments('// setIdempotencyKey("CHARGE:" + x)\nString a = "CHARGE:S:";');
  if (/setIdempotencyKey/.test(s) || !s.includes('CHARGE:S:')) {
    throw new Error('[self-test] 注释遮蔽或字符串保留失败');
  }
  console.log('[check-charge-idem-session-scoped self-test] OK：1 项断言全过');
}

const invokedDirectly =
  process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (invokedDirectly) {
  if (process.argv.includes('--self-test')) {
    selfTest();
  } else {
    const errors = evaluate();
    if (errors.length) {
      console.error(`[check-charge-idem-session-scoped] ${errors.length} 项失败：`);
      for (const e of errors) console.error(`  - ${e}`);
      process.exitCode = 1;
    } else {
      console.log('[check-charge-idem-session-scoped] OK：CHARGE 幂等键按 sessionId，历史键双读');
    }
  }
}
