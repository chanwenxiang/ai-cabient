#!/usr/bin/env node
/**
 * R20-9 / CB-085 门禁（2026-11-12）：分账回退按「腿」立账，禁止回到单值覆盖。
 *
 * 🔴 判据设计（铁律 84）：
 *   ① 扫 Java 源码前 maskComments() 抹注释为**等长空格**——本轮每个落点都写了成因注释，
 *      注释里复述旧写法（含 findTop20ByWechatPendingReturnNo 等字面量），不抹必假红；
 *   ② 判据贴真实责任边界：写入点取 applyWeChatReturnOutcome 的**方法体**（不是全文 includes），
 *      金额侧直接禁止 WeChatProfitSharingService 出现 split 单值 getter；
 *   ③ SQL 真值由 Java 侧反射读 @Select/@Update 断言（OrderSplitReturnLegSqlPredicateTest），
 *      本脚本只负责跨文件的「链路形态」与迁移 DDL，不 grep Java 注解猜 SQL。
 *
 * 用法：
 *   node scripts/check-split-return-leg-conservation.mjs             # 正向门禁
 *   node scripts/check-split-return-leg-conservation.mjs --self-test # 沙箱化负向变异 + md5 零残留
 */
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const ROOT = process.cwd();
const P = {
  migration:
    'services/trade-service/src/main/resources/db/migration/V463__order_split_return_leg.sql',
  revenueSplit:
    'services/trade-service/src/main/java/com/aicabinet/trade/service/RevenueSplitService.java',
  wechat:
    'services/trade-service/src/main/java/com/aicabinet/trade/payment/WeChatProfitSharingService.java',
  compensation:
    'services/trade-service/src/main/java/com/aicabinet/trade/service/ProfitSharingReturnCompensationService.java',
  scheduler:
    'services/trade-service/src/main/java/com/aicabinet/trade/service/CompensationTaskScheduler.java',
  retryScheduler:
    'services/trade-service/src/main/java/com/aicabinet/trade/service/ProfitSharingRetryScheduler.java',
  splitMapper:
    'services/trade-service/src/main/java/com/aicabinet/trade/mapper/OrderRevenueSplitMapper.java',
  legMapper:
    'services/trade-service/src/main/java/com/aicabinet/trade/mapper/OrderSplitReturnLegMapper.java',
  conservationTest:
    'services/trade-service/src/test/java/com/aicabinet/trade/service/ProfitSharingReturnLegConservationTest.java',
  sqlPredicateTest:
    'services/trade-service/src/test/java/com/aicabinet/trade/mapper/OrderSplitReturnLegSqlPredicateTest.java'
};

const OPEN_PREDICATE = "status IN ('PENDING','PROCESSING','FAILED')";

/** 注释替换为等长空格：保行号/列偏移，注释里的旧写法不得被当成实现。 */
function maskComments(src) {
  return src.replace(/\/\*[\s\S]*?\*\/|\/\/[^\n]*/g, (m) => m.replace(/[^\n]/g, ' '));
}

/**
 * SQL 文件的行注释整行删掉：迁移头 NOTES 会复述 ON CONFLICT / 谓词等实现关键词，
 * 直接在全文上判「有没有回填语句」会被自己的注释骗成假绿（铁律 84① 同族）。
 */
function stripSqlLineComments(src) {
  return src.replace(/^\s*--[^\n]*/gm, '');
}

/** 从 openIdx（开括号后一个字符）起截取配平内容；字符串里的括号不计数。 */
function balanced(src, openIdx) {
  let depth = 1;
  let inStr = null;
  for (let i = openIdx; i < src.length; i++) {
    const c = src[i];
    if (inStr) {
      if (c === '\\') {
        i++;
        continue;
      }
      if (c === inStr) inStr = null;
      continue;
    }
    if (c === '"' || c === "'") {
      inStr = c;
      continue;
    }
    if (c === '{') depth++;
    else if (c === '}' && --depth === 0) return src.slice(openIdx, i);
  }
  return '';
}

/** 取方法签名后的完整方法体；找不到返回 null（调用方必须判红，不能当空串放过）。 */
function methodBody(src, signature) {
  const i = src.indexOf(signature);
  if (i < 0) return null;
  const open = src.indexOf('{', i + signature.length);
  if (open < 0) return null;
  return balanced(src, open + 1);
}

/**
 * 纯函数判据：read(relPath) → 源码文本。正向从磁盘读，自测从内存副本读。
 * 返回 problems 数组；空数组 = 绿。
 */
function evaluate(read) {
  const problems = [];
  const bad = (msg) => problems.push(msg);
  const java = (p) => maskComments(read(p));

  // ── R1 迁移：表 / 幂等键 / 回填 / partial index 完整谓词 ──
  // 只看可执行 SQL，头注释里的实现复述不算数。
  const mig = stripSqlLineComments(read(P.migration));
  if (!/CREATE\s+TABLE\s+IF\s+NOT\s+EXISTS\s+order_split_return_leg/i.test(mig))
    bad('V463 缺 order_split_return_leg 建表');
  if (!/out_return_no\s+VARCHAR\(\d+\)\s+NOT\s+NULL\s+UNIQUE/i.test(mig))
    bad('V463 缺 out_return_no UNIQUE（渠道幂等键，多轮不得合并单号）');
  if (!/ON\s+CONFLICT\s+\(out_return_no\)\s+DO\s+NOTHING/i.test(mig))
    bad('V463 缺存量回填的 ON CONFLICT DO NOTHING');
  if (!mig.includes(OPEN_PREDICATE))
    bad(`V463 partial index 谓词不是完整的 ${OPEN_PREDICATE}（铁律 88①）`);

  // ── R2 唯一写入点：按腿 upsert，禁止再 set 单值列 ──
  const outcomeBody = methodBody(java(P.revenueSplit), 'private void applyWeChatReturnOutcome');
  if (outcomeBody === null) {
    bad('找不到 RevenueSplitService.applyWeChatReturnOutcome 方法体');
  } else {
    if (!/returnLegService\.recordOutcome\(/.test(outcomeBody))
      bad('applyWeChatReturnOutcome 没有按腿写入 returnLegService.recordOutcome');
    if (/setWechatPendingReturn(No|Cents)\(/.test(outcomeBody))
      bad('applyWeChatReturnOutcome 仍在 setWechatPendingReturnXxx 覆盖单值列（R20-9 病灶）');
  }

  // ── R3 金额与单号只从腿读 ──
  const wechat = java(P.wechat);
  if (/getWechatPendingReturnCents\(\)/.test(wechat))
    bad('WeChatProfitSharingService 仍在读 split 单值金额，渠道回退金额必须从 leg.getCents() 取');
  if (/getWechatPendingReturnNo\(\)/.test(wechat))
    bad('WeChatProfitSharingService 仍在读 split 单值单号，查单/重提必须用 leg.getOutReturnNo()');
  if (!/leg\.getCents\(\)/.test(wechat)) bad('WeChatProfitSharingService 未从腿取金额');

  // ── R4 补偿任务按腿去重 ──
  const comp = java(P.compensation);
  if (!/legTxId\(/.test(comp)) bad('补偿服务没有按腿生成 txId');
  if (/setTxId\(split\.getSplitId\(\)\)/.test(comp))
    bad('补偿服务仍把裸 splitId 当 txId（多轮部分退第二轮调度槽会被吞）');

  // ── R5 补偿调度器按腿处理 ──
  const sched = java(P.scheduler);
  if (!/resolveLeg\(/.test(sched)) bad('补偿调度器缺 resolveLeg（新旧 txId 兼容）');
  if (/retryFailedReturns\(/.test(sched)) bad('补偿调度器仍走按 split 的 retryFailedReturns');
  if (!/findOpenBySplitId\(/.test(sched)) bad('补偿调度器告警未按腿聚合');

  // ── R6 定时兜底从腿表扫 ──
  const retry = java(P.retryScheduler);
  if (!/findOpenLegs\(/.test(retry)) bad('定时兜底没有从腿表扫开放腿');
  if (/findTop20ByWechatPendingReturnNo/.test(retry))
    bad('定时兜底仍在扫 split 单值列（新腿会整批隐形）');
  if (!/pollOpenReturnLegs\(|retryFailedReturnLegs\(/.test(retry))
    bad('定时兜底没有改走按腿的轮询/重提入口');

  // ── R7 旧扫描入口已删（注释里的字面量已被 maskComments 抹掉） ──
  if (
    /findTop20ByWechatPendingReturnNo|findTop20ByFailureReasonContaining/.test(java(P.splitMapper))
  )
    bad('OrderRevenueSplitMapper 仍保留旧的单值扫描入口（铁律 62：删功能要删入口）');

  // ── R8 腿表 Mapper 与两类判据文件都在 ──
  const legMapper = java(P.legMapper);
  if (!/OPEN_STATUSES/.test(legMapper)) bad('腿表 Mapper 缺开放状态白名单');
  if (!/ORDER BY leg_id ASC/.test(legMapper)) bad('全局扫开放腿缺 ORDER BY（铁律 88②）');
  const conservation = java(P.conservationTest);
  if (!/sumSuccessCents\(/.test(conservation) || !/守恒式/.test(conservation))
    bad('守恒用例缺失或未断言守恒式');
  const sqlTest = java(P.sqlPredicateTest);
  if (
    !/OrderSplitReturnLegMapper\.class\.getMethod\(/.test(sqlTest) ||
    !/\.getAnnotation\(/.test(sqlTest)
  )
    bad('SQL 谓词用例必须反射读注解真值，不能 grep 源码');

  return problems;
}

const diskRead = (rel) => readFileSync(join(ROOT, rel), 'utf8');
const md5 = (s) => createHash('md5').update(s).digest('hex');

function selfTest() {
  const paths = Object.values(P);
  const baseline = new Map(paths.map((p) => [p, diskRead(p)]));
  const before = new Map(paths.map((p) => [p, md5(baseline.get(p))]));
  const green = evaluate((p) => baseline.get(p));
  if (green.length) {
    console.error('[self-test] 基线不绿，先修正向门禁：', green);
    process.exit(1);
  }

  const mutations = [
    ['迁移删 UNIQUE', P.migration, (s) => s.replace('NOT NULL UNIQUE', 'NOT NULL')],
    ['索引谓词截断', P.migration, (s) => s.replaceAll(OPEN_PREDICATE, "status IN ('PENDING')")],
    // 注意：迁移头注释里也复述了这个串，replace 只改第一处会只改到注释 ⇒ 变异本身失效。
    [
      '删回填 ON CONFLICT',
      P.migration,
      (s) => s.replaceAll('ON CONFLICT (out_return_no) DO NOTHING', 'ON CONFLICT DO NOTHING')
    ],
    [
      '回填语句整段删掉（注释仍复述）',
      P.migration,
      (s) =>
        s.replace(
          /INSERT INTO order_split_return_leg[\s\S]*?ON CONFLICT \(out_return_no\) DO NOTHING;/,
          ''
        )
    ],
    [
      '写入改回单值覆盖',
      P.revenueSplit,
      (s) =>
        s.replace(
          'returnLegService.recordOutcome(split, outReturnNo, returnCents, outcome, failureMessage);',
          'split.setWechatPendingReturnNo(outReturnNo); split.setWechatPendingReturnCents(returnCents);'
        )
    ],
    [
      '渠道金额改读单值',
      P.wechat,
      (s) => s.replace('leg.getCents(),', 'split.getWechatPendingReturnCents(),')
    ],
    ['补偿键改回 splitId', P.compensation, (s) => s.replace(/legTxId\(/g, 'split.getSplitId(')],
    [
      '调度器回到按 split 重试',
      P.scheduler,
      (s) => s.replace('resolveLeg(task)', 'retryFailedReturns(List.of(split), Map.of())')
    ],
    [
      '定时兜底回到单值扫描',
      P.retryScheduler,
      (s) =>
        s.replace(
          'findOpenLegs(batch)',
          'findTop20ByWechatPendingReturnNoIsNotNullOrderByCreatedAtAsc()'
        )
    ],
    [
      '旧扫描入口复活',
      P.splitMapper,
      (s) =>
        s +
        '\n    default List<OrderRevenueSplit> findTop20ByWechatPendingReturnNoIsNotNullOrderByCreatedAtAsc() { return List.of(); }\n'
    ],
    [
      '腿表批量取丢排序',
      P.legMapper,
      (s) => s.replace('ORDER BY leg_id ASC', 'ORDER BY split_id ASC')
    ]
  ];

  let passed = 0;
  for (const [name, path, mutate] of mutations) {
    const copy = new Map(baseline);
    copy.set(path, mutate(baseline.get(path)));
    const problems = evaluate((p) => copy.get(p));
    if (!problems.length) {
      console.error(`[self-test] FAIL 变异未被咬住：${name}`);
      process.exit(1);
    }
    passed++;
  }

  // 反向：注释里写旧写法不得假红（铁律 84①）
  const withComment = new Map(baseline);
  withComment.set(
    P.splitMapper,
    baseline.get(P.splitMapper) + '\n// 旧写法 findTop20ByWechatPendingReturnNo 已下线，禁止回归\n'
  );
  const commentProblems = evaluate((p) => withComment.get(p));
  if (commentProblems.length) {
    console.error('[self-test] FAIL 注释里的旧写法造成假红：', commentProblems);
    process.exit(1);
  }
  passed++;

  // md5 零残留：自测全程只在内存副本上变异
  for (const p of paths) {
    if (md5(diskRead(p)) !== before.get(p)) {
      console.error(`[self-test] FAIL 磁盘被改动：${p}`);
      process.exit(1);
    }
  }
  console.log(
    `[check-split-return-leg-conservation] self-test OK：${passed} 个变异/防假红断言全部符合，md5 零残留`
  );
  process.exit(0);
}

// 负向夹具识别（check-audit-gates-wiring 规则 3）要求剥注释后同时出现 selfTest( 与 --self-test，
// 所以自测主体必须是具名函数 + 显式调用，不能写成一段匿名 if 分支。
if (process.argv.includes('--self-test')) {
  selfTest();
} else {
  const problems = evaluate(diskRead);
  if (problems.length) {
    console.error('[check-split-return-leg-conservation] FAIL');
    for (const p of problems) console.error(`  - ${p}`);
    process.exit(1);
  }
  console.log('[check-split-return-leg-conservation] OK：分账回退按腿立账，8 组链路判据全绿');
}
