#!/usr/bin/env node
/** 负向用例：node scripts/check-merchant-report-lifecycle.test.mjs */
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { problems, FILE } from './check-merchant-report-lifecycle.mjs';

const TAG = '[check-merchant-lifecycle.test]';
let checks = 0;
const assert = (cond, msg) => {
  checks++;
  if (!cond) throw new Error(`${TAG} 断言失败：${msg}`);
};
const md5 = (s) => createHash('md5').update(s).digest('hex');

const real = readFileSync(FILE, 'utf8');
assert(problems(real).length === 0, '真树应绿');
assert(problems('// merchantIdsWithOperatingDevices()\nclass X{}').length > 0, '注释不算');

// 1) 日报改用全表集合 → 红
{
  const m = /mergeDeviceMerchants\s*\([^)]*\)\s*\{([\s\S]*?)\n    \}/.exec(real);
  const broken = real.replace(
    m[0],
    m[0].replace('merchantIdsWithOperatingDevices()', 'merchantIdsWithDevices()')
  );
  assert(
    problems(broken).some((p) => p.includes('日报')),
    '日报用全表须红'
  );
}
// 2) 费用改用过滤集合 → 红
{
  const m = /chargeAndCheckAllMerchants\s*\([^)]*\)\s*\{([\s\S]*?)\n    \}/.exec(real);
  const broken = real.replace(
    m[0],
    m[0].replace('merchantIdsWithDevices()', 'merchantIdsWithOperatingDevices()')
  );
  assert(
    problems(broken).some((p) => p.includes('催缴')),
    '费用用过滤集合须红'
  );
}
// 3) 去掉 INBOUND 或 RETIRED 排除 → 红
{
  for (const [constant, label] of [
    ['LIFECYCLE_INBOUND', 'INBOUND'],
    ['LIFECYCLE_RETIRED', 'RETIRED']
  ]) {
    const broken = real.replace(`${constant}.equals(ls)`, 'false');
    assert(
      problems(broken).some((p) => p.includes(`未排除 ${label}`)),
      `去 ${constant} 排除须红`
    );
  }
}
// 4) 真树零污染
assert(md5(readFileSync(FILE, 'utf8')) === md5(real), '真树字节被污染');
console.log(`${TAG} OK：${checks} 项断言全过`);
