#!/usr/bin/env node
/**
 * `check-member-level-rules.mjs` 的负向夹具（2026-11-20 真机会员中心降级）。
 *
 *   node scripts/check-member-level-rules.test.mjs
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import {
  DEFAULT_CODES,
  MIGRATION_DIR,
  levelRuleProblems,
  loadMigrations,
  maskSqlComments
} from './check-member-level-rules.mjs';

const TAG = '[check-member-level-rules.test]';
let checks = 0;
function assert(cond, message) {
  checks++;
  if (!cond) throw new Error(`${TAG} 断言失败：${message}`);
}

const real = loadMigrations();
assert(real.length > 400, `真实迁移目录读取失败（只扫到 ${real.length} 个）`);
assert(levelRuleProblems(real).length === 0, '真实仓面必须绿');

const V344 = 'V344__production_demo_seed_guard.sql';
const V497 = 'V497__restore_member_level_rules.sql';
const deleteBody = maskSqlComments(readFileSync(join(MIGRATION_DIR, V344), 'utf8'));
const insertBody = maskSqlComments(readFileSync(join(MIGRATION_DIR, V497), 'utf8'));
assert(/DELETE\s+FROM\s+member_level_rule/i.test(deleteBody), 'V344 锚点漂移：找不到整表删除');
assert(/INSERT\s+INTO\s+member_level_rule/i.test(insertBody), 'V497 锚点漂移：找不到补回 INSERT');

// ① 删掉补回迁移 ⇒ 清空后无人补 ⇒ 红（本次生产事故形态）
const lost = real.filter((m) => m.file !== V497);
const lostProblems = levelRuleProblems(lost);
assert(lostProblems.length > 0, '缺补回迁移必须红');
assert(
  lostProblems.some((p) => p.includes('没有任何 INSERT') || p.includes('补回')),
  '红文案要点名「清空后没补回」'
);

// ② 补回迁移排到清空之前 ⇒ 等于没补 ⇒ 红
const reordered = [
  { file: V497, version: 300, body: insertBody },
  { file: V344, version: 344, body: deleteBody }
];
assert(
  levelRuleProblems(reordered).some((p) => p.includes('补回')),
  'INSERT 早于 DELETE 必须红'
);

// ③ 最后生效的 INSERT 缺一档 ⇒ 红
const missingOne = [{ file: V497, version: 497, body: insertBody.replace(/'PLATINUM'/g, "'VIP'") }];
const missingProblems = levelRuleProblems(missingOne);
assert(missingProblems.length > 0, '缺档位必须红');
assert(
  DEFAULT_CODES.some((c) => missingProblems.some((p) => p.includes(c))),
  '红文案要点名缺哪一档'
);

// ④ 等级名退化成英文编码 ⇒ 红（本次「显示 NORMAL」的形态）
const english = [
  {
    file: V497,
    version: 497,
    body: insertBody.replace(/普通会员|银卡会员|金卡会员|白金会员/g, 'MEMBER')
  }
];
assert(
  levelRuleProblems(english).some((p) => p.includes('中文等级名')),
  '等级名非中文必须红'
);

console.log(`${TAG} OK：${checks} 项断言全过（真树绿 / 四种注入红）`);
