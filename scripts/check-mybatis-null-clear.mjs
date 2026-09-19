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
const MODULES = ['services/trade-service/src/main/java', 'services/device-service/src/main/java', 'services/common'];

/** 豁免清单：键 = 模块相对路径（/ 分隔），值 = 为什么这不是清列意图 */
const ALLOWLIST = new Map([
  [
    'services/trade-service/src/main/java/com/aicabinet/trade/service/DevicePresenceService.java',
    'set(null) 后紧跟 mapper 的 clearOnlineSince/clearSalesUnlockedAt 显式清列 SQL，净效果正确',
  ],
  [
    'services/trade-service/src/main/java/com/aicabinet/trade/service/SettlementOrderSupport.java',
    'setId(null) 是 INSERT 前清主键让 MP 自增生成，save 为插入而非清列',
  ],
]);

const SET_NULL = /\.set[A-Z]\w*\(\s*null\s*\)/;
const PERSIST = /\.(updateById|save)\(/;
/** 缓解信号：紧随其后出现 LambdaUpdateWrapper（update(null, ...)）即视为已显式清列 */
const MITIGATION = /lambdaUpdate\(\)[\s\S]{0,400}?\.set\([\s\S]{0,80}?null\)/;

function* walkJava(dir) {
  let entries;
  try { entries = readdirSync(dir, { withFileTypes: true }); } catch { return; }
  for (const e of entries) {
    const p = join(dir, e.name);
    if (e.isDirectory()) yield* walkJava(p);
    else if (e.name.endsWith('.java')) yield p;
  }
}

const violations = [];
for (const base of MODULES) {
  for (const file of walkJava(join(ROOT, base))) {
    const rel = file.slice(ROOT.length + 1).split(sep).join('/');
    const lines = readFileSync(file, 'utf8').split(/\r?\n/);
    for (let i = 0; i < lines.length; i++) {
      if (!SET_NULL.test(lines[i])) continue;
      for (let j = i + 1; j <= Math.min(i + 4, lines.length - 1); j++) {
        if (PERSIST.test(lines[j])) {
          if (ALLOWLIST.has(rel)) break;
          // 缓解检测：set(null) 起点后 8 行内出现 lambdaUpdate wrapper 显式 set(null) → 已兜底
          const mitigation = lines.slice(i + 1, i + 9).join('\n');
          if (MITIGATION.test(mitigation)) break;
          violations.push(`${rel}:${i + 1}  set(null) → :${j + 1} 持久化`);
          break;
        }
      }
    }
  }
}

if (violations.length > 0) {
  console.error('[check-mybatis-null-clear] 疑似 set(null)+updateById/save 清列误用（updateById 默认忽略 null，列清不掉）：');
  for (const v of violations) console.error(`  - ${v}`);
  console.error('  修复：改 LambdaUpdateWrapper 显式 set(field, null)；确非清列意图的在 ALLOWLIST 带理由登记。');
  process.exit(1);
}
console.log('[check-mybatis-null-clear] OK：未发现 set(null)+updateById/save 清列误用');
