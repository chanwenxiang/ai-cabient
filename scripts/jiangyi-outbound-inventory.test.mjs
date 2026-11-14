#!/usr/bin/env node
/**
 * `jiangyi-outbound-inventory.mjs` 的负向夹具（S8③：改判据必须同批带能红的用例）。
 *
 * 全部走内存注入（evaluate 接受 reg/sources），不碰真实 client 源码与登记册 ⇒ 与本仓当天怎么改无关。
 *
 *   node scripts/jiangyi-outbound-inventory.test.mjs
 */
import { canon, evaluate } from './jiangyi-outbound-inventory.mjs';

const TAG = '[jiangyi-outbound-inventory.test]';
let checks = 0;
function assert(cond, msg) {
  checks++;
  if (!cond) throw new Error(`${TAG} 断言失败：${msg}`);
}
function red(problems, needle, label) {
  assert(
    problems.some((p) => p.includes(needle)),
    `${label}：期望命中「${needle}」，实际 ${JSON.stringify(problems)}`
  );
}

const SRC = [
  { name: 'JiangyiFooClient.java', text: 'post("/merchant/a/run", b); post("/merchant/b/two", c)' }
];
const SRC_OUT = new Map();

function reg(entries, maxAbsent) {
  return { countersAsOf: 't', maxAbsent: maxAbsent == null ? 5 : maxAbsent, entries };
}

// 0) 正例：登记与代码一致 ⇒ 必须 0 项（证明判据不是一律红）
const good = reg(
  [
    {
      path: '/merchant/a/run',
      status: 'implemented',
      code: ['JiangyiFooClient.run'],
      section: '§1.1'
    },
    {
      path: '/merchant/x/none',
      status: 'absent',
      section: 'V1.13 §2.3',
      why: '厂商未开放该面，无业务动作'
    },
    {
      path: '/merchant/y/never',
      status: 'vendor-side',
      section: 'V16 §4.5.1',
      why: '归属与记账主体在我方侧，不在厂商云建租户（已定口径）'
    },
    {
      path: '/merchant/b/two',
      status: 'implemented',
      code: ['JiangyiFooClient.two'],
      section: '§1.2'
    }
  ],
  1
);
let r = evaluate(good, SRC, new Map());
assert(r.problems.length === 0, `一致登记不该红：${r.problems.join(' | ')}`);
assert(r.codeTotal === 2, `代码侧应现算出 2 条，实得 ${r.codeTotal}`);

// 1) 说「已接」但代码里搜不到 ⇒ 红（最危险的一类：读者据此判风险已闭合）
r = evaluate(
  reg([{ path: '/merchant/gone/away', status: 'implemented', code: ['X.y'], section: '§1' }], 0),
  SRC,
  new Map()
);
red(r.problems, 'client 代码里搜不到该路径', 'A2 死实现');

// 2) 说「未接」但代码已在调 ⇒ 红（缺口已补却没改状态，台账继续骗人）
r = evaluate(
  reg([{ path: '/merchant/a/run', status: 'absent', section: '§9.9', why: '旧记未接' }], 1),
  SRC,
  new Map()
);
red(r.problems, '代码已在调用', 'A3 状态滞后');

// 3) 代码新增出向、登记册没有 ⇒ 红（新增通道无人对账）
r = evaluate(reg([], 0), SRC, new Map());
red(r.problems, '代码里新出现、登记册没有', 'A1 未登记新端点');
red(r.problems, 'JiangyiFooClient.java', 'A1 必须带出处文件名');

// 4) 未接条目数超过棘轮上限 ⇒ 红（只许减不许增）
r = evaluate(
  reg(
    [
      { path: '/merchant/p/one', status: 'absent', section: '§1', why: '缺口一' },
      { path: '/merchant/p/two', status: 'absent', section: '§2', why: '缺口二' }
    ],
    1
  ),
  SRC,
  new Map()
);
red(r.problems, 'maxAbsent', '棘轮超限');

// 5) 缺口条目没有文档章节锚点 ⇒ 红（没锚点就无法回溯原文，条目必然腐烂）
r = evaluate(
  reg([{ path: '/merchant/n/osec', status: 'absent', why: '厂商没开放' }], 5),
  SRC,
  new Map()
);
red(r.problems, '缺 section 锚点', '缺口必须可回溯');

// 6) 「明确不接」但理由太短 ⇒ 红（一句话豁免＝免检区）
r = evaluate(
  reg([{ path: '/merchant/v/side', status: 'vendor-side', section: '§3', why: '不接' }], 0),
  SRC,
  new Map()
);
red(r.problems, '理由 ≥12 字', '不接必须写理由');

// 7) 非法 status ⇒ 红
r = evaluate(
  reg([{ path: '/merchant/bad/status', status: 'maybe', section: '§4', why: '随便写的' }], 0),
  SRC,
  new Map()
);
red(r.problems, '非法 status', '状态词表外');

// 8) 父段/残段不算端点：拼接用的 `/merchant/stdSku/` 与过短串不得进集合
const r2 = evaluate(
  reg([], 0),
  [{ name: 'J.java', text: 'post("/merchant/stdSku/", a); post("/merchant/", b)' }],
  new Map()
);
assert(r2.codeTotal === 0, `父段/空段不该被当端点统计，实得 ${r2.codeTotal}`);

// 9) OCR 形变配对（实测：厂商 PDF 把大写 I 抽成小写 l）
assert(
  canon('/merchant/devicelnfo/getldentifier') === canon('/merchant/deviceInfo/getIdentifier'),
  'OCR 形变的同一端点必须归一到同一个键'
);
assert(canon('/merchant/a/one') !== canon('/merchant/a/two'), '归一不得把真正不同的端点并成一个');

console.log(`${TAG} OK：${checks} 项断言全过`);
