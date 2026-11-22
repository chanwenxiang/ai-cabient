#!/usr/bin/env node
/** 负向用例：node scripts/check-consumer-template-and-tx-scope.test.mjs */
import { existsSync, readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import {
  templateProblems,
  txProblems,
  verdictProblems,
  viewProblems,
  PROPS,
  UNPAID,
  AUTO,
  REVIEW,
  VIEW,
  LEFTOVER
} from './check-consumer-template-and-tx-scope.mjs';

const TAG = '[check-consumer-tx.test]';
let checks = 0;
const assert = (cond, msg) => {
  checks++;
  if (!cond) throw new Error(`${TAG} 断言失败：${msg}`);
};
const md5 = (s) => createHash('md5').update(s).digest('hex');

const props = readFileSync(PROPS, 'utf8');
const unpaid = readFileSync(UNPAID, 'utf8');
const auto = readFileSync(AUTO, 'utf8');
const review = readFileSync(REVIEW, 'utf8');
const view = readFileSync(VIEW, 'utf8');

// 真树全绿
assert(templateProblems(props, unpaid).length === 0, '真树 template 应绿');
assert(txProblems(auto).length === 0, '真树 tx 应绿');
assert(verdictProblems(review, readFileSync(LEFTOVER, 'utf8')).length === 0, '真树 verdict 应绿');
assert(viewProblems(view).length === 0, '真树 view 应绿');

// ① 回落商户模板 → 红
{
  const broken = props.replace(/:\s*null;(\s*)\}/, ': subscribeTemplateId;$1}');
  assert(
    templateProblems(broken, unpaid).some((p) => p.includes('回落商户模板')),
    '回落商户模板须红'
  );
}
// ① mock 假模板 → 红
{
  const broken = unpaid.replace('消费者订阅模板未配置', 'mock-unpaid-template');
  assert(
    templateProblems(props, broken).some((p) => p.includes('mock-unpaid-template')),
    '假模板 id 须红'
  );
}
// ② @Transactional 复活 → 红
{
  const broken = auto.replace(
    '    public Result autoBind(',
    '    @Transactional\n    public Result autoBind('
  );
  assert(
    txProblems(broken).some((p) => p.includes('@Transactional')),
    '事务注解复活须红'
  );
}
// ② 去 txTemplate.execute → 红
{
  const broken = auto.replace(/txTemplate\.execute\s*\(/g, 'runDirectly(');
  assert(
    txProblems(broken).some((p) => p.includes('TransactionTemplate')),
    '去短事务须红'
  );
}
// ③ 残留壳应保留（历史登记引用），且不得重新长出第二份判据
assert(existsSync(LEFTOVER), 'JiangyiLearningLibraryService 委托壳应保留（历史登记引用）');
{
  const shell = readFileSync(LEFTOVER, 'utf8').replace(
    'return JiangyiMappingReviewService.isMappingVerified(device);',
    'return device.getClassesVersion() != null && device.getClassesVersion().equals(device.getMappingVerifiedVersion());'
  );
  assert(
    verdictProblems(review, shell).some((p) => p.includes('第二份复核判据')),
    '残留壳长出第二份判据须红'
  );
}
// ③ 判据被删 → 红
{
  const broken = review.replace(
    /static boolean isMappingVerified\s*\([^)]*\)/,
    'static boolean otherName(JiangyiDevice'
  );
  assert(
    verdictProblems(broken, readFileSync(LEFTOVER, 'utf8')).some((p) =>
      p.includes('isMappingVerified')
    ),
    '判据被删须红'
  );
}
// ④ resetForm 参数退化 → 红
{
  const broken = view.replace('async function load(resetForm = false)', 'async function load()');
  assert(
    viewProblems(broken).some((p) => p.includes('resetForm')),
    'resetForm 参数须红'
  );
}
// ④ 条件清空退化 → 红
{
  const broken = view.replace(/if \(resetForm\) \{[\s\S]{0,120}?\n  \}/, '');
  assert(
    viewProblems(broken).some((p) => p.includes('resetForm 条件')),
    '条件清空须红'
  );
}

// 真树零污染
for (const [path, before] of [
  [PROPS, props],
  [UNPAID, unpaid],
  [AUTO, auto],
  [REVIEW, review],
  [VIEW, view]
]) {
  assert(md5(readFileSync(path, 'utf8')) === md5(before), `${path} 真树字节被污染`);
}
console.log(`${TAG} OK：${checks} 项断言全过`);
