#!/usr/bin/env node
/**
 * R20 P3-4 门禁（2026-11-12）：支付回调「静默 200」必须有可追溯痕迹。
 *
 * 🔴 判据设计（铁律 84 / 75 / 3）：
 *   ① 扫 Java 源码前 maskComments() 抹注释为**等长空格**——实现里写了大段成因注释，
 *      注释复述了 `.ifPresent` / `log.warn` 等旧写法，不抹会假红/假绿；
 *   ② 判据贴真实责任边界：只取四个回调/同步方法的**方法体**，不全文 includes；
 *   ③ 跨实现↔测试的告警类型集合**自动对齐**（集合相等），不写人工白名单——
 *      「加分支不写断言」与「删分支不删断言」都会红；
 *   ④ 钉住收口方法三要素：本地 log.error + 外部告警 + catch fail-open，且响应语义
 *      仍是「告警后 return 200」，不能改成 throw / 5xx（渠道会无限重投）；
 *   ⑤ 负向变异保持语法完整的真实回归形态，并要求命中**指定判据**。
 *
 * 用法：
 *   node scripts/check-payment-callback-silent-trace.mjs             # 正向门禁
 *   node scripts/check-payment-callback-silent-trace.mjs --self-test # 沙箱化负向变异 + md5 零残留
 */
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const ROOT = process.cwd();
const P = {
  impl: 'services/trade-service/src/main/java/com/aicabinet/trade/service/PaymentService.java',
  test: 'services/trade-service/src/test/java/com/aicabinet/trade/service/PaymentServiceSilentCallbackAlertTest.java'
};

const METHODS = [
  'public void handleWeChatNotify(',
  'public void handleAlipayNotify(',
  'public void handleWeChatRefundNotify(',
  'private void syncChannelSideRefund('
];

const FAMILIES = ['WECHAT_RECHARGE_', 'ALIPAY_RECHARGE_', 'WECHAT_REFUND_'];
const SEMANTICS = [
  ['无主订单/缺单号', /(ORDER_NOT_FOUND|MISSING_OUT_TRADE_NO)$/],
  ['渠道不符', /CHANNEL_MISMATCH$/],
  ['金额异常', /(AMOUNT_INVALID|MISSING_REFUND_AMOUNT)$/]
];

const diskRead = (p) => readFileSync(join(ROOT, p), 'utf8');

/** 注释替换为等长空格：保行号/列偏移，注释里的旧写法不得被当成实现。 */
function maskComments(src) {
  return src.replace(/\/\*[\s\S]*?\*\/|\/\/[^\n]*/g, (m) => m.replace(/[^\n]/g, ' '));
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

const uniq = (a) => [...new Set(a)];

/** 纯函数判据：read(relPath) → 源码文本。正向从磁盘读，自测从内存副本读。 */
function evaluate(read) {
  const problems = [];
  const bad = (msg) => problems.push(msg);
  const impl = maskComments(read(P.impl));
  const test = maskComments(read(P.test));

  // ① 收口方法三要素 + fail-open（定义体，不是调用点）
  const alertBody = methodBody(impl, 'private void alertSilentCallback(');
  if (alertBody === null) {
    bad('alertSilentCallback 定义体找不到');
  } else {
    if (!/log\.error\s*\(/.test(alertBody))
      bad(
        'alertSilentCallback 缺本地 log.error：OpsAlertDispatcher.fanout 未配置渠道时静默返回，只发告警仍是零痕迹'
      );
    if (!/opsAlertDispatcher\.send\s*\(/.test(alertBody))
      bad('alertSilentCallback 未调用 OpsAlertDispatcher.send（外部告警链路）');
    if (!/catch\s*\(\s*Exception\s+\w+\s*\)/.test(alertBody))
      bad('alertSilentCallback 缺 catch(Exception) fail-open：告警链路异常会把回调变成 5xx');
    if (/\bthrow\b/.test(alertBody)) bad('alertSilentCallback 定义体内出现 throw，违反 fail-open');
  }

  // ② 四个方法的真实责任边界：不得退回「无主静默」或「只 log.warn」
  const bodies = new Map();
  for (const sig of METHODS) {
    const body = methodBody(impl, sig);
    if (body === null) {
      bad(`找不到方法体 ${sig}`);
      continue;
    }
    bodies.set(sig, body);
    if (/\.ifPresent\s*\(/.test(body))
      bad(`${sig} 出现 .ifPresent( —— Optional.empty() 会退回「渠道已收款但完全无痕」`);
    if (/log\.warn\s*\(/.test(body))
      bad(`${sig} 出现 log.warn —— 静默吞回调不得只留 warn 后 return`);
    if (!/alertSilentCallback\s*\(/.test(body))
      bad(`${sig} 完全没有留痕收口（这一族分支不能只修别处）`);
  }

  // ③ 实现↔测试告警类型集合自动对齐
  const implTypes = [...impl.matchAll(/alertSilentCallback\(\s*"([A-Z_]+)"/g)].map((m) => m[1]);
  const testTypes = [...test.matchAll(/verifySilentAlert\(\s*"([A-Z_]+)"/g)].map((m) => m[1]);
  if (!implTypes.length) bad('实现侧没有任何 alertSilentCallback 告警类型');
  if (implTypes.length !== uniq(implTypes).length)
    bad('实现侧告警类型重复：每类静默分支必须有独立类型');
  const missingInTest = uniq(implTypes).filter((t) => !testTypes.includes(t));
  const extraInTest = uniq(testTypes).filter((t) => !implTypes.includes(t));
  if (missingInTest.length || extraInTest.length)
    bad(`告警类型与断言未自动对齐：实现侧缺断言=[${missingInTest}] 测试侧多余=[${extraInTest}]`);
  for (const family of FAMILIES) {
    if (!implTypes.some((t) => t.startsWith(family)))
      bad(`告警族 ${family} 缺覆盖（微信/支付宝充值 + 微信退款三族必须齐全）`);
  }
  for (const [label, re] of SEMANTICS) {
    if (!implTypes.some((t) => re.test(t))) bad(`语义族「${label}」缺覆盖`);
  }

  // ④ 响应语义：告警后必须紧跟 return（仍 200 让渠道停止重投），不得改成 throw
  // 必须限定「调用点＝类型字符串字面量」，否则 private void alertSilentCallback(String type...
  // 的方法定义也会被 [\s\S]*? 一路吞到后面某个 return，凭空多算一个分支。
  const returned = [...impl.matchAll(/alertSilentCallback\(\s*"[A-Z_]+"[\s\S]*?\);\s*return;/g)];
  if (returned.length !== implTypes.length)
    bad(
      `告警后紧跟 return 的分支数 ${returned.length} ≠ 告警分支总数 ${implTypes.length}（不得改成 throw/5xx，渠道会无限重投）`
    );
  // 逐个 @Test 方法体钉「响应语义不变 / fail-open」：只 grep 全文会被「别处还有一处」骗成假绿。
  let testedMethods = 0;
  for (const m of test.matchAll(/@Test[\s\S]{0,240}?\bvoid\s+(\w+)\s*\(\s*\)\s*\{/g)) {
    testedMethods++;
    const body = balanced(test, m.index + m[0].length);
    if (!/assertDoesNotThrow\s*\(/.test(body))
      bad(`测试方法 ${m[1]} 缺 assertDoesNotThrow 判据（响应语义不变 / fail-open 无人钉）`);
  }
  if (testedMethods === 0) bad('测试侧没有可识别的 @Test 方法体');

  return problems;
}

const md5 = (s) => createHash('md5').update(s).digest('hex');

/** 负向夹具：只在内存副本上变异，逐条期望转红；再验注释不假红与磁盘 md5 零残留。 */
function selfTest() {
  const paths = Object.values(P);
  const baseline = new Map(paths.map((p) => [p, diskRead(p)]));
  const before = new Map(paths.map((p) => [p, md5(baseline.get(p))]));

  const mutations = [
    [
      '删本地 log.error 痕迹',
      P.impl,
      (s) => s.replace(/\s*log\.error\("payment callback silently ignored[^\n]*\n/, '\n'),
      'log.error'
    ],
    [
      '去掉 catch fail-open 包装',
      P.impl,
      (s) =>
        s.replace(
          /try \{[ \t]*\r?\n[ \t]*opsAlertDispatcher\.send\(type, title, detail, Map\.of\("detail", detail\)\);[ \t]*\r?\n[ \t]*\} catch \(Exception e\) \{[ \t]*\r?\n[^\r\n]*[ \t]*\r?\n[ \t]*\}/,
          'opsAlertDispatcher.send(type, title, detail, Map.of("detail", detail));'
        ),
      'catch(Exception)'
    ],
    [
      'catch 里改成 throw（告警失败拖垮回调）',
      P.impl,
      (s) =>
        s.replace(
          'log.warn("ops alert failed type={} detail={}", type, detail, e);',
          'throw new IllegalStateException("alert down", e);'
        ),
      'throw'
    ],
    [
      '微信无主分支退回 log.warn 静默',
      P.impl,
      (s) =>
        s.replace(
          /alertSilentCallback\("WECHAT_RECHARGE_NOTIFY_ORDER_NOT_FOUND"[\s\S]*?需人工核对"\);/,
          'log.warn("wechat notify order not found orderId={}", outTradeNo);'
        ),
      'log.warn'
    ],
    [
      '微信充值回调整段退回 .ifPresent 旧实现',
      P.impl,
      (s) =>
        s.replace(
          /RechargeOrder order = rechargeOrderRepository\.findById\(outTradeNo\)\.orElse\(null\);[\s\S]*?transaction\.path\(TRANSACTION_ID\)\.asText\(null\), null\);/,
          'rechargeOrderRepository.findById(outTradeNo).ifPresent(order -> {\n' +
            '            if (!PayChannels.WECHAT.equalsIgnoreCase(order.getChannel())) {\n' +
            '                log.warn("wechat notify channel mismatch");\n' +
            '                return;\n' +
            '            }\n' +
            '            int notifyCents = transaction.path(AMOUNT).path("total").asInt(0);\n' +
            '            creditRechargeNotify(outTradeNo, notifyCents, null, null);\n' +
            '        });'
        ),
      '.ifPresent('
    ],
    [
      '告警后改成 throw（响应语义变 5xx）',
      P.impl,
      (s) =>
        s.replace(
          /需人工核对"\);([ \t]*\r?\n[ \t]*)return;/,
          '需人工核对");$1throw new IllegalStateException("no such order");'
        ),
      '紧跟 return'
    ],
    [
      '删一条测试断言（实现未同步）',
      P.test,
      (s) =>
        s.replace(/\s*verifySilentAlert\("WECHAT_RECHARGE_NOTIFY_AMOUNT_INVALID"[^\n]*\n/, '\n'),
      '自动对齐'
    ],
    [
      '实现里多出一个未写断言的类型',
      P.impl,
      (s) =>
        s.replace(
          'private void alertSilentCallback(String type, String title, String detail) {',
          'private void alertSilentCallback(String type, String title, String detail) {\n' +
            '        if (type.isEmpty()) { alertSilentCallback("UNTESTED_GHOST_TYPE", "x", "y"); return; }'
        ),
      '自动对齐'
    ],
    [
      '支付宝回调整族告警被删',
      P.impl,
      (s) =>
        s.replace(
          /alertSilentCallback\("ALIPAY_RECHARGE_NOTIFY_ORDER_NOT_FOUND"[\s\S]*?total_amount=" \+ verified\.get\("total_amount"\)[ \t]*\r?\n[ \t]*\+ " 未入账，需人工核对"\);/,
          'log.warn("alipay notify ignored unmatched");'
        ),
      '告警族 ALIPAY_RECHARGE_'
    ],
    [
      '测试侧丢掉 assertDoesNotThrow 判据',
      P.test,
      (s) =>
        s.replace(
          'assertDoesNotThrow(() -> paymentService.handleWeChatNotify("{}", "ts", "n", "sig", "serial"));',
          'paymentService.handleWeChatNotify("{}", "ts", "n", "sig", "serial");'
        ),
      'assertDoesNotThrow'
    ]
  ];

  let passed = 0;
  for (const [name, path, mutate, expect] of mutations) {
    const copy = new Map(baseline);
    const mutated = mutate(baseline.get(path));
    if (mutated === baseline.get(path)) {
      console.error(`[self-test] FAIL 变异未生效（源串没匹配上）：${name}`);
      process.exit(1);
    }
    copy.set(path, mutated);
    const problems = evaluate((p) => copy.get(p));
    if (!problems.length) {
      console.error(`[self-test] FAIL 变异未被咬住：${name}`);
      process.exit(1);
    }
    if (!problems.some((p) => p.includes(expect))) {
      console.error(`[self-test] FAIL 变异命中的不是预期判据「${expect}」：${name}`, problems);
      process.exit(1);
    }
    passed++;
  }

  // 反向：注释里复述旧写法不得造成假红（铁律 84①）
  const withComment = new Map(baseline);
  withComment.set(
    P.impl,
    baseline.get(P.impl) +
      '\n// 旧实现：rechargeOrderRepository.findById(x).ifPresent(...); log.warn("channel mismatch");\n' +
      '/* alertSilentCallback("FAKE_COMMENT_TYPE", ...) 曾 throw new IllegalStateException */\n'
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
    `[check-payment-callback-silent-trace] self-test OK：${passed} 个变异/防假红断言全部符合，md5 零残留`
  );
  process.exit(0);
}

// 负向夹具识别要求剥注释后同时出现 selfTest( 与 --self-test，故自测主体必须是具名函数 + 显式调用。
if (process.argv.includes('--self-test')) {
  selfTest();
} else {
  const problems = evaluate(diskRead);
  if (problems.length) {
    console.error('[check-payment-callback-silent-trace] FAIL');
    for (const p of problems) console.error(`  - ${p}`);
    process.exit(1);
  }
  console.log(
    '[check-payment-callback-silent-trace] OK：支付回调静默分支全族留痕，6 组链路判据全绿'
  );
}
