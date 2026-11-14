#!/usr/bin/env node
/**
 * check-wechat-bill-skip-visibility 夹具：证明判据「能红也能绿」。
 *
 *   node scripts/check-wechat-bill-skip-visibility.test.mjs
 */
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { evaluate, extractParseBody, maskComments } from './check-wechat-bill-skip-visibility.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-wechat-bill-skip-visibility.test]';
const TARGET =
  'services/trade-service/src/main/java/com/aicabinet/trade/reconciliation/WeChatBillCsvParser.java';

let checks = 0;
let failed = 0;
function assert(cond, msg) {
  checks++;
  if (!cond) {
    failed++;
    console.error(`${TAG} ✗ ${msg}`);
  }
}
function hasProblem(problems, fragment) {
  return problems.some((p) => p.includes(fragment));
}

/** 合规 parse 体（与真实实现同形，压缩版）。 */
const GREEN = `
{
    List<PlatformBillLine> result = new ArrayList<>();
    int skipped = 0;
    long amountCents;
    try {
        amountCents = YuanFen.toCents(new BigDecimal(cols[12].trim()));
    } catch (RuntimeException e) {
        skipped++;
        log.warn("skip wechat bill line (amount parse failed): line={}", line);
        continue;
    }
    try {
        result.add(new PlatformBillLine(platformTradeNo, merchantOrderNo, amountCents));
    } catch (RuntimeException e) {
        skipped++;
        log.warn("skip wechat bill line (unexpected): line={}", line);
    }
    if (skipped > 0) {
        log.warn("wechat bill parse finished with skipped rows: kept={}, skipped={}", result.size(), skipped);
    }
    return result;
}`;

// 0) maskComments 必须等长（保行号），且注释被抹、字符串字面量保留
{
  const src = 'int a = 1; // log.debug("skip wechat bill line")\n/* block */ String s = "keep me";';
  const masked = maskComments(src);
  assert(masked.length === src.length, `掩码必须等长：${masked.length} vs ${src.length}`);
  assert(!masked.includes('log.debug'), '注释里的 log.debug 应被抹掉');
  assert(masked.includes('keep me'), '字符串字面量必须保留（判据依赖日志文案）');
  const cfg = maskComments(src, true);
  assert(cfg.length === src.length && !cfg.includes('keep me'), 'maskStrings=true 应抹字符串内容');
}

// 1) 合规体 = 绿
{
  const p = evaluate(GREEN);
  assert(p.length === 0, `合规体应绿：${p.join(' | ')}`);
}

// 2) 真实仓面 = 绿（对账，防夹具自娱自乐）
{
  const real = readFileSync(join(ROOT, TARGET), 'utf8');
  const body = extractParseBody(real);
  assert(body !== null, '真实文件应能抽出 parse 方法体');
  const p = evaluate(body);
  assert(p.length === 0, `真实 WeChatBillCsvParser.parse 应绿：${p.join(' | ')}`);
}

// 3) 静默 debug 丢行 = 红
{
  const bad = GREEN.replace(
    'log.warn("skip wechat bill line (amount parse failed)',
    'log.debug("skip wechat bill line (amount parse failed)'
  );
  assert(hasProblem(evaluate(bad), 'log.debug 静默丢行'), '改回 debug 必须红');
}

// 4) 删汇总 WARN = 红
{
  const bad = GREEN.replace(/log\.warn\("wechat bill parse finished[\s\S]*?\);/, '');
  assert(hasProblem(evaluate(bad), '汇总 WARN'), '缺汇总必须红');
}

// 5) 删 skipped 声明 / 自增 = 红
{
  const noDecl = GREEN.replace('int skipped = 0;', '');
  assert(hasProblem(evaluate(noDecl), '计数变量'), '缺声明必须红');
  const noInc = GREEN.replaceAll('skipped++;', '');
  assert(hasProblem(evaluate(noInc), '自增'), '缺自增必须红');
}

// 6) 旧式大 catch 把金额与 result.add 一起包住 = 红（这正是本次修复的形态）
{
  const oldStyle = `
{
    List<PlatformBillLine> result = new ArrayList<>();
    try {
        long amountCents = YuanFen.toCents(new BigDecimal(cols[12].trim()));
        result.add(new PlatformBillLine(platformTradeNo, merchantOrderNo, amountCents));
    } catch (Exception e) {
        log.debug("skip wechat bill line: {}", line);
    }
    return result;
}`;
  const p = evaluate(oldStyle);
  assert(hasProblem(p, '未独立 try/catch'), '金额解析未独立接住必须红');
  assert(hasProblem(p, 'log.debug 静默丢行'), '旧式静默 debug 必须红');
}

// 7) 金额降级取整 = 红（产品拍板「不猜业务、不静默错账」）
{
  // 把金额换算换成 setScale(2, HALF_UP)；另放一处 YuanFen 以满足单源判据，
  // 确保这条红只由「降级取整」触发，不是被别的判据顺带咬住。
  const degraded = GREEN.replace(
    'YuanFen.toCents(new BigDecimal(cols[12].trim()))',
    'new BigDecimal(cols[12].trim()).setScale(2, RoundingMode.HALF_UP).longValue()'
  ).replace('int skipped = 0;', 'int skipped = 0; long ignored = YuanFen.toCents(BigDecimal.ONE);');
  const p = evaluate(degraded);
  assert(hasProblem(p, '降级取整'), `出现 setScale/HALF_UP 必须红：${p.join(' | ')}`);
  assert(!hasProblem(p, '未走 YuanFen 单源'), '本用例不应由单源判据顺带触发');
}

// 8) 注释里出现违规写法 = 不误报（铁律 84 免疫）
{
  const withComment = GREEN.replace(
    'int skipped = 0;',
    'int skipped = 0; // log.debug("skip wechat bill line: {}") 历史写法，勿恢复'
  );
  const p = evaluate(withComment);
  assert(!hasProblem(p, '静默丢行'), `注释里的示例不得假红：${p.join(' | ')}`);
  assert(!hasProblem(p, '降级取整'), '注释里的 HALF_UP 不得假红');
}

console.log(`${TAG} ${failed === 0 ? 'OK' : 'FAIL'}：${checks} 项断言，失败 ${failed}`);
if (failed > 0) process.exit(1);
