#!/usr/bin/env node
/**
 * 门禁：禁止 set(null) + updateById/save 期望清列的 MyBatis-Plus 误用（审计 M01）。
 *
 * 原理：updateById/save 默认忽略 null 字段，「实体.setXxx(null) 后紧接 updateById/save」
 * 不会清掉 DB 列。清列必须走 LambdaUpdateWrapper 的显式 set(field, null)。
 *
 * 启发式：`setXxx(null)` 之后 4 行内出现 `.updateById(` 或 `.save(` 即命中，
 * 逐条人工判定后：真缺陷改 wrapper；确属无清列意图的（如重建新对象、字段本就
 * 不可空）加入下方 ALLOWLIST（键=文件路径，值=理由），不得无理由豁免。
 */
import { readFileSync, readdirSync } from 'node:fs';
import { join, dirname, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const MODULES = [
  'services/trade-service/src/main/java',
  'services/device-service/src/main/java',
  'services/common'
];

/** 豁免清单：键 = 模块相对路径（/ 分隔），值 = 为什么这不是清列意图 */
const ALLOWLIST = new Map([
  [
    'services/trade-service/src/main/java/com/aicabinet/trade/service/CouponService.java',
    'order.setCouponId(null) 为内存投影；DB 清列由 SettlementOrderFinalizeService.clearCouponSelection 调用链负责（跨实体窗口误联）'
  ],
  [
    'services/trade-service/src/main/java/com/aicabinet/trade/service/DisputeService.java',
    '重开争议 set(ClosedAt,null) 的 DB 清列在 reopenUpdateWrapper（.set(ClosedAt,null)，距 set 点约 30 行超出窗口）'
  ],
  [
    'services/trade-service/src/main/java/com/aicabinet/trade/service/PaymentService.java',
    'RECHARGE_REFUND 为新建流水，order_id 置 null 是 FK 语义（V98），非 updateById 清列意图'
  ],
  [
    'services/trade-service/src/main/java/com/aicabinet/trade/service/DevicePresenceService.java',
    'set(null) 后紧跟 mapper 的 clearOnlineSince/clearSalesUnlockedAt 显式清列 SQL，净效果正确'
  ],
  [
    'services/trade-service/src/main/java/com/aicabinet/trade/service/SettlementOrderSupport.java',
    'setId(null) 是 INSERT 前清主键让 MP 自增生成，save 为插入而非清列'
  ]
]);

const SET_NULL = /\.set[A-Z]\w*\(\s*null\s*\)/;
const PERSIST = /\.(updateById|save)\(/;

function* walkJava(dir) {
  let entries;
  try {
    entries = readdirSync(dir, { withFileTypes: true });
  } catch {
    return;
  }
  for (const e of entries) {
    const p = join(dir, e.name);
    if (e.isDirectory()) yield* walkJava(p);
    else if (e.name.endsWith('.java')) yield p;
  }
}

const violations = [];
for (const base of MODULES) {
  for (const file of walkJava(join(ROOT, base))) {
    const rel = file
      .slice(ROOT.length + 1)
      .split(sep)
      .join('/');
    const lines = readFileSync(file, 'utf8').split(/\r?\n/);
    for (let i = 0; i < lines.length; i++) {
      if (!SET_NULL.test(lines[i])) continue;
      // 审计批次4：窗口 4→12 行（原只查 4 行，跨 4 行的持久化漏检）。
      // 缓解窗口 = set(null) → 持久化 → 后 8 行的**联合窗口**：标准 M01 模式是
      // 「entity.set(null) → save（非空列）→ lambdaUpdate wrapper 显式 set(col,null) 清列」，
      // wrapper 在持久化**之后**——缓解判定必须覆盖持久化后窗口，否则标准模式本身被误报。
      for (let j = i + 1; j <= Math.min(i + 12, lines.length - 1); j++) {
        if (PERSIST.test(lines[j])) {
          if (ALLOWLIST.has(rel)) break;
          const window = lines.slice(i + 1, Math.min(j + 8, lines.length)).join('\n');
          const mitigation =
            (/lambdaUpdate\(\)/.test(window) && /\.set\([^)]*null\)/.test(window)) ||
            /clear[A-Z]\w*\(/.test(window);
          if (mitigation) break;
          violations.push(`${rel}:${i + 1}  set(null) → :${j + 1} 持久化`);
          break;
        }
      }
    }
  }
}

if (violations.length > 0) {
  console.error(
    '[check-mybatis-null-clear] 疑似 set(null)+updateById/save 清列误用（updateById 默认忽略 null，列清不掉）：'
  );
  for (const v of violations) console.error(`  - ${v}`);
  console.error(
    '  修复：改 LambdaUpdateWrapper 显式 set(field, null)；确非清列意图的在 ALLOWLIST 带理由登记。'
  );
  process.exit(1);
}
console.log('[check-mybatis-null-clear] OK：未发现 set(null)+updateById/save 清列误用');
