#!/usr/bin/env node
/**
 * 门禁：商户日报的「有柜机」边界排除 INBOUND/RETIRED，且费用催缴仍走全表口径。
 *
 * 成因（2026-11-21 用户复核 P2③）：merchantIdsWithDevices() 全表扫 device_info，
 * 只剩入库未投放/已退役柜的商户也每天收 0 元日报；而该方法同时被费用催缴复用，
 * 直接加过滤会连带停掉退役柜欠费催缴 ⇒ 必须拆成两个方法。
 *
 * 判据（任一处退化即红）：
 *   ① scheduler 同时定义 merchantIdsWithDevices（费用全集）与 merchantIdsWithOperatingDevices（日报在运营）
 *   ② 日报 mergeDeviceMerchants 调 merchantIdsWithOperatingDevices
 *   ③ 费用 chargeAndCheckAllMerchants 仍调 merchantIdsWithDevices
 *   ④ operating 方法内排除 INBOUND 与 RETIRED（两个常量都在）
 *
 * 用法：node scripts/check-merchant-report-lifecycle.mjs
 */
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
export const FILE = join(
  ROOT,
  'services/trade-service/src/main/java/com/aicabinet/trade/service/MerchantDailyIncomeNotifyScheduler.java'
);

export function maskComments(src) {
  let out = '';
  let i = 0;
  while (i < src.length) {
    if (src.startsWith('//', i)) {
      while (i < src.length && src[i] !== '\n') {
        out += ' ';
        i++;
      }
    } else if (src.startsWith('/*', i)) {
      out += '  ';
      i += 2;
      while (i < src.length && !src.startsWith('*/', i)) {
        out += src[i] === '\n' ? '\n' : ' ';
        i++;
      }
      if (i < src.length) {
        out += '  ';
        i += 2;
      }
    } else {
      out += src[i];
      i++;
    }
  }
  return out;
}

export function problems(source) {
  const src = maskComments(source);
  const out = [];
  if (!/Set<\s*String\s*>\s*merchantIdsWithDevices\s*\(/.test(src)) {
    out.push('缺 merchantIdsWithDevices（费用全集口径）');
  }
  if (!/Set<\s*String\s*>\s*merchantIdsWithOperatingDevices\s*\(/.test(src)) {
    out.push('缺 merchantIdsWithOperatingDevices（日报在运营口径）');
  }
  const merge = /mergeDeviceMerchants\s*\([^)]*\)\s*\{([\s\S]*?)\n    \}/.exec(src);
  if (!merge) {
    out.push('mergeDeviceMerchants 方法体定位失败');
  } else if (!/merchantIdsWithOperatingDevices\s*\(/.test(merge[1])) {
    out.push(
      '日报 mergeDeviceMerchants 未用 merchantIdsWithOperatingDevices（退役柜会收到 0 元日报）'
    );
  }
  const charge = /chargeAndCheckAllMerchants\s*\([^)]*\)\s*\{([\s\S]*?)\n    \}/.exec(src);
  if (!charge) {
    out.push('chargeAndCheckAllMerchants 方法体定位失败');
  } else if (!/merchantIdsWithDevices\s*\(/.test(charge[1])) {
    out.push('费用 chargeAndCheckAllMerchants 改用了过滤集合（退役柜欠费催缴会被停掉）');
  }
  const operating = /merchantIdsWithOperatingDevices\s*\([^)]*\)\s*\{([\s\S]*?)\n    \}/.exec(src);
  if (!operating) {
    out.push('merchantIdsWithOperatingDevices 方法体定位失败');
  } else {
    if (!/LIFECYCLE_INBOUND\.equals\s*\(\s*ls\s*\)/.test(operating[1])) {
      out.push('operating 未排除 INBOUND');
    }
    if (!/LIFECYCLE_RETIRED\.equals\s*\(\s*ls\s*\)/.test(operating[1])) {
      out.push('operating 未排除 RETIRED');
    }
  }
  return out;
}

export function run() {
  const p = problems(readFileSync(FILE, 'utf8'));
  if (p.length) {
    console.error('[check-merchant-report-lifecycle] FAIL:\n' + p.join('\n'));
    process.exit(1);
  }
  console.log('[check-merchant-report-lifecycle] OK：日报排除 INBOUND/RETIRED，费用仍走全表');
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  run();
}
