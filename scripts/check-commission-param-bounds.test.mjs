#!/usr/bin/env node
/**
 * check-commission-param-bounds（S12 逐调用点判定，2026-11-14 升级）的夹具。
 * 负向用例：裸 setter（参数未过 require* 闸）必须红——文件级旧判据「有 import 即过」
 * 的盲区（已过闸文件里补一记裸 setter 门禁照绿）由本夹具钉死不再复发。
 */
import { extractCalls, evaluate } from './check-commission-param-bounds.mjs';

let pass = 0;
let fail = 0;
function check(name, cond, detail = '') {
  if (cond) {
    pass += 1;
    console.log(`  ✓ ${name}`);
  } else {
    fail += 1;
    console.error(`  ✗ ${name}${detail ? ' —— ' + detail : ''}`);
  }
}

const GUARDED = `manager.setCommissionRateBps(CommissionParamBounds.requireBps(
        "commissionRateBps",
        manager.getCommissionRateBps()));`;
const BARE = `manager.setCommissionRateBps(99999);`;

// 1) 过闸调用（requireBps 在参数里）→ 绿
{
  const calls = extractCalls(`class X { ${GUARDED} }`);
  check('extractCalls 抽到 1 调用', calls.length === 1, JSON.stringify(calls));
  check('过闸调用 guarded=true', calls[0].guarded === true);
  const r = evaluate([{ rel: 'a.java', calls }], {});
  check('过闸调用判定绿', r.length === 0, r.join('|'));
}

// 2) 裸 setter（绕闸）→ 红逐调用点名
{
  const calls = extractCalls(`class X { ${BARE} }`);
  check('裸调用 guarded=false', calls.length === 1 && calls[0].guarded === false);
  const r = evaluate([{ rel: 'a.java', calls }], {});
  check(
    '裸 setter=红（含行号）',
    r.length === 1 && r[0].includes('a.java:1') && r[0].includes('绕过'),
    r.join('|')
  );
}

// 3) 混合文件（1 过闸 + 1 裸）→ 只红裸的那笔
{
  const calls = extractCalls(`class X { ${GUARDED} ${BARE} }`);
  check('混合文件抽到 2 调用', calls.length === 2);
  const r = evaluate([{ rel: 'a.java', calls }], {});
  check('混合只红裸调用', r.length === 1 && r[0].includes('a.java:3'), r.join('|'));
}

// 4) 白名单文件未过闸数对平 → 绿
{
  const calls = extractCalls(`class X { ${BARE} }`);
  const r = evaluate([{ rel: 'a.java', calls }], { 'a.java': { count: 1, reason: '自带闸先例' } });
  check('白名单对平=绿', r.length === 0, r.join('|'));
}

// 5) 白名单数不符 → 棘轮红
{
  const calls = extractCalls(`class X { ${BARE} ${BARE} }`);
  const r = evaluate([{ rel: 'a.java', calls }], { 'a.java': { count: 1, reason: '自带闸先例' } });
  check(
    '白名单棘轮 1≠2=红',
    r.length === 1 && r[0].includes('白名单登记 1 处未过闸，实际 2 处'),
    r.join('|')
  );
}

// 6) 多行过闸调用（requireBps 跨行）→ 仍判过闸
{
  const calls = extractCalls(`class X {
    manager.setCommissionFixedCents(CommissionParamBounds.requireFixedCents(
            "commissionFixedCents",
            body.getCommissionFixedCents()));
  }`);
  check(
    '多行过闸调用 guarded=true',
    calls.length === 1 && calls[0].guarded === true,
    JSON.stringify(calls)
  );
}

// 8) requireCents 过闸形态（三闸同族补验——2026-11-14 逐调用点升级时补）
{
  const calls = extractCalls(
    `class X { cfg.setCommissionPerUnitCents(CommissionParamBounds.requireCents("perUnit", 100L)); }`
  );
  check(
    'requireCents 过闸 guarded=true',
    calls.length === 1 && calls[0].guarded === true,
    JSON.stringify(calls)
  );
}

// 7) 白名单残留已删文件 → 红
{
  const r = evaluate([], { 'gone.java': { count: 1 } });
  check('白名单残留=红', r.length === 1 && r[0].includes('残留'), r.join('|'));
}

// 8) requireCents 也算过闸（三闸同族）
{
  const calls = extractCalls(
    `class X { req.setCommissionPerUnitCents(CommissionParamBounds.requireCents("perUnit", 100L)); }`
  );
  check('requireCents 过闸形态=绿', calls[0].guarded === true, JSON.stringify(calls));
}

// 9) 🔴 注释/字符串形态不得当调用（等长掩码，2026-11-14 补）
//    旧版只有 `!line.trim().startsWith('//')`，多行块注释、行尾注释、日志字符串全挡不住；
//    升级逐调用点后必须统一掩码，否则「注释掉一行调试代码」就假红。
{
  const lineCmt = extractCalls(`class X {\n  // mgr.setShareBps(99999);\n}`);
  check('行注释里的 setter 不抽取', lineCmt.length === 0, JSON.stringify(lineCmt));
  const blockCmt = extractCalls(
    `class X {\n  /* mgr.setShareBps(99999);\n     mgr.setCommissionRateBps(88888); */\n}`
  );
  check('块注释里的 setter 不抽取', blockCmt.length === 0, JSON.stringify(blockCmt));
  const inString = extractCalls(`class X { log.info("call mgr.setShareBps(99999) failed"); }`);
  check('字符串字面量里的 setter 不抽取', inString.length === 0, JSON.stringify(inString));
  // 掩码必须等长：注释不改变行号
  const afterCmt = extractCalls(
    `class X {\n  // 注释一行\n  // 又一行\n  mgr.setShareBps(99999);\n}`
  );
  check(
    '掩码等长：注释不影响行号',
    afterCmt.length === 1 && afterCmt[0].line === 4,
    JSON.stringify(afterCmt)
  );
}

// 10) 🔴 白名单必须双向：文件已全部过闸 ⇒ 条目残留要红（否则清单单向腐烂）
{
  const calls = extractCalls(`class X { ${GUARDED} }`);
  const r = evaluate([{ rel: 'a.java', calls }], { 'a.java': { count: 1, reason: '旧先例' } });
  check(
    '已修好但白名单留条=红（请删条目）',
    r.length === 1 && r[0].includes('已全部过闸'),
    r.join('|')
  );
}

// 11) S12 扩面（2026-11-14 拍板）：折扣两族 setter 也纳入视野——裸 setDiscountBps/setDiscountPercent 红
{
  const bareBps = extractCalls(`class X { a.setDiscountBps(request.discountBps()); }`);
  check(
    'setDiscountBps 纳入 SETTER_RE，裸调用红',
    bareBps.length === 1 && bareBps[0].guarded === false,
    JSON.stringify(bareBps)
  );
  const barePct = extractCalls(`class X { def.setDiscountPercent(discountPercent); }`);
  check(
    'setDiscountPercent 纳入 SETTER_RE，裸调用红',
    barePct.length === 1 && barePct[0].guarded === false,
    JSON.stringify(barePct)
  );
  const r = evaluate(
    [
      { rel: 'PromotionService.java', calls: bareBps },
      { rel: 'CouponService.java', calls: barePct }
    ],
    {
      'PromotionService.java': { count: 1, reason: '入口校验形态' },
      'CouponService.java': { count: 1, reason: '入口校验形态' }
    }
  );
  check('折扣两族白名单对平=绿', r.length === 0, r.join('|'));
}

console.log(`\n[check-commission-param-bounds.test] ${pass} 通过 / ${fail} 失败`);
if (fail > 0) process.exit(1);
