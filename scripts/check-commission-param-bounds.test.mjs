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

console.log(`\n[check-commission-param-bounds.test] ${pass} 通过 / ${fail} 失败`);
if (fail > 0) process.exit(1);
