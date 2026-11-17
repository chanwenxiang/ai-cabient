#!/usr/bin/env node
/**
 * 门禁：渠道扣款痕迹只能「本地事务提交之后」收口（F3 / 2026-11-17）。
 *
 * 修的缺陷形态（MA-333 系列）：渠道返回成功后**就地**调 `finalizeChargePending`，
 * 而它是 `REQUIRES_NEW`（独立提交，不管外层成不成）。主结算链是一个大事务，后续任一步
 * （分账 / 订单状态 / 余额腿）抛错回滚后：
 *   钱在渠道被扣 ← 已发生
 *   本地账回滚   ← 已发生
 *   证据行状态 = FINALIZED（已收口）← 于是 PENDING 巡检看不见、告警不响
 *   用户重试 → 新 orderId → **再扣一次**
 *
 * 这条链路上任何一处被「顺手简化」回去，都会重新变成看不见的资损，所以用门禁钉住：
 *
 *  R1 `submitChannelCharge` 成功段必须走 `settleChargeTraceAfterCommit(`，
 *     且不得出现 `self.finalizeChargePending(`（REQUIRES_NEW 就地提交）。
 *  R2 收口助手必须真的是 afterCommit 语义：查 `isSynchronizationActive()`、
 *     注册 `registerSynchronization(`、回调里实现 `afterCommit()`；
 *     不得用 `afterCompletion(` 代替（回滚时也会触发，等于又在错误时刻收口）。
 *  R3 判据与出口必须成对存在：`hasUnsettledCharge` 在，且巡检
 *     `alertChargeSettledButUnbookedOps` 被调度链调用——否则「加了判据没人用」，
 *     存量单还是只在数据库里躺着。
 *
 * 用法：`node scripts/check-charge-trace-after-commit.mjs`（无参=扫真实仓）
 *       `node scripts/check-charge-trace-after-commit.mjs --self-test`
 * 负向夹具：`scripts/check-charge-trace-after-commit.test.mjs`
 */
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const TAG = '[check-charge-trace-after-commit]';

const REL_PAYMENT = join(
  'services',
  'trade-service',
  'src',
  'main',
  'java',
  'com',
  'aicabinet',
  'trade',
  'service',
  'OrderPaymentService.java'
);
const REL_UNPAID = join(
  'services',
  'trade-service',
  'src',
  'main',
  'java',
  'com',
  'aicabinet',
  'trade',
  'service',
  'UnpaidOrderService.java'
);

/** 抹注释但保留行号与字符串字面量（铁律 84①：不能把字符串里的 `/*` 当注释）。 */
export function maskComments(s) {
  const out = s.split('');
  let i = 0;
  let state = 'code';
  while (i < s.length) {
    const c = s[i];
    const n = s[i + 1];
    if (state === 'code') {
      if (c === '"') state = 'str';
      else if (c === "'") state = 'chr';
      else if (c === '/' && n === '/') state = 'line';
      else if (c === '/' && n === '*') {
        state = 'block';
        out[i] = ' ';
        out[i + 1] = ' ';
        i += 2;
        continue;
      }
    } else if (state === 'line') {
      if (c === '\n') state = 'code';
      else out[i] = ' ';
    } else if (state === 'block') {
      if (c === '*' && n === '/') {
        out[i] = ' ';
        out[i + 1] = ' ';
        i += 2;
        state = 'code';
        continue;
      }
      if (c !== '\n') out[i] = ' ';
    } else if (state === 'str') {
      if (c === '\\') {
        i += 2;
        continue;
      }
      if (c === '"') state = 'code';
    } else if (state === 'chr') {
      if (c === '\\') {
        i += 2;
        continue;
      }
      if (c === "'") state = 'code';
    }
    i++;
  }
  return out.join('');
}

function bodyOf(src, openIdx) {
  let depth = 0;
  for (let i = openIdx; i < src.length; i++) {
    if (src[i] === '{') depth++;
    else if (src[i] === '}') {
      depth--;
      if (depth === 0) return src.slice(openIdx, i + 1);
    }
  }
  return src.slice(openIdx);
}

/**
 * 取方法体（同名重载全部拼接）。
 * 🔴 锚点必须落在修饰符上：只按 `name(` 会命中**调用点**，拿到的是别人的方法体
 * （check-ops-device-scope 首版踩过，11 条假红）。
 */
export function methodBody(src, name) {
  const re = new RegExp(`(?:public|private|protected|static|\\s)+[^;{}()]*?\\b${name}\\s*\\(`, 'g');
  const bodies = [];
  let m;
  while ((m = re.exec(src))) {
    const brace = src.indexOf('{', m.index + m[0].length - 1);
    if (brace < 0) continue;
    bodies.push(bodyOf(src, brace));
  }
  return bodies.length ? bodies.join('\n') : null;
}

/** 纯函数判据（便于单测与注入）。sources = {payment, unpaid} 原文。 */
export function evaluate(sources) {
  const errors = [];
  const payment = maskComments(sources.payment || '');
  const unpaid = maskComments(sources.unpaid || '');

  // ── R1 成功段必须 after-commit 收口 ──
  const submit = methodBody(payment, 'submitChannelCharge');
  if (!submit) {
    errors.push('R1 找不到 OrderPaymentService#submitChannelCharge（方法被改名/删除，门禁失效）');
  } else {
    if (!submit.includes('settleChargeTraceAfterCommit(')) {
      errors.push(
        'R1 submitChannelCharge 成功段未调用 settleChargeTraceAfterCommit( —— 痕迹会在本地事务提交前被收口'
      );
    }
    // 允许「无单号」就地收口（渠道判回落余额、一分钱没扣，必须立刻终结痕迹否则告警误报）；
    // 只要带了渠道单号（= 钱真的扣了）就必须等提交，禁止就地提交。
    // `[^;]*` 而不是 `[^)]*`：实参里 `order.getOrderId()` 自带 `)`，用 `[^)]*` 会提前截断，
    // 把「无单号的回落分支」误判成违规（首版实测假红）。
    if (/self\.finalizeChargePending\s*\((?![^;]*\bnull\b)/.test(submit)) {
      errors.push(
        'R1 submitChannelCharge 用渠道单号就地 self.finalizeChargePending( —— 它是 REQUIRES_NEW 独立提交，' +
          '外层回滚时「渠道已扣、本地无账」会被洗成已收口（无单号的回落分支允许就地收口）'
      );
    }
  }

  // ── R2 助手必须是 afterCommit 语义 ──
  const helper = methodBody(payment, 'settleChargeTraceAfterCommit');
  if (!helper) {
    errors.push('R2 找不到 settleChargeTraceAfterCommit（收口时机的唯一实现被删）');
  } else {
    if (!helper.includes('isSynchronizationActive()')) {
      errors.push(
        'R2 settleChargeTraceAfterCommit 未判断 isSynchronizationActive()—— 无事务时必须交给调用方收口'
      );
    }
    if (!helper.includes('registerSynchronization(')) {
      errors.push('R2 settleChargeTraceAfterCommit 未注册事务同步 —— 等于没有 after-commit 时机');
    }
    if (!helper.includes('afterCommit()')) {
      errors.push('R2 settleChargeTraceAfterCommit 未实现 afterCommit() 回调');
    }
    if (helper.includes('afterCompletion(')) {
      errors.push(
        'R2 settleChargeTraceAfterCommit 用了 afterCompletion( —— 回滚时也会触发，等于在错误时刻收口'
      );
    }
    if (!helper.includes('finalizeChargePending(')) {
      errors.push(
        'R2 settleChargeTraceAfterCommit 提交后没有真正收口（未调 finalizeChargePending）'
      );
    }
  }

  // ── R3 判据与出口成对 ──
  if (!methodBody(payment, 'hasUnsettledCharge')) {
    errors.push('R3 缺少 hasUnsettledCharge —— 「痕迹 FINALIZED 但本地无账」这类单无处可判');
  }
  if (!methodBody(payment, 'findSettledButUnbooked')) {
    errors.push('R3 缺少 findSettledButUnbooked —— 存量单扫不出来');
  }
  const alert = methodBody(unpaid, 'alertChargeSettledButUnbookedOps');
  if (!alert) {
    errors.push('R3 UnpaidOrderService 缺 alertChargeSettledButUnbookedOps 告警巡检');
  } else if (!alert.includes('CHARGE_SETTLED_NOT_BOOKED')) {
    errors.push(
      'R3 alertChargeSettledButUnbookedOps 未上报 CHARGE_SETTLED_NOT_BOOKED（告警类型被改=看板口径断）'
    );
  }
  // 巡检必须真的被调度链调到：只定义不调用＝静默的死代码
  const sweep = methodBody(unpaid, 'autoCancelExpired');
  if (!sweep || !sweep.includes('alertChargeSettledButUnbookedOps(')) {
    errors.push(
      'R3 alertChargeSettledButUnbookedOps 未接入 autoCancelExpired 巡检链（定义了没人调）'
    );
  }

  return { errors };
}

export function runCli() {
  const missing = [REL_PAYMENT, REL_UNPAID].filter((rel) => !existsSync(join(ROOT, rel)));
  if (missing.length) {
    console.error(`${TAG} FAIL 源文件缺失：${missing.join('、')}`);
    process.exit(1);
  }
  const { errors } = evaluate({
    payment: readFileSync(join(ROOT, REL_PAYMENT), 'utf8'),
    unpaid: readFileSync(join(ROOT, REL_UNPAID), 'utf8')
  });
  if (errors.length) {
    for (const e of errors) console.error(`${TAG} FAIL ${e}`);
    process.exit(1);
  }
  console.log(
    `${TAG} OK：痕迹收口时机（after-commit）/ 判据（hasUnsettledCharge）/ 出口（巡检）三类判据全部通过`
  );
}

/** 合成反例自检：判据真的会红，不是摆设。 */
export function selfTest() {
  const okPayment = `
    private boolean submitChannelCharge(CabinetOrder order) {
        settleChargeTraceAfterCommit(order.getOrderId(), "TN");
        return true;
    }
    private void settleChargeTraceAfterCommit(String orderId, String tradeNo) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) { return; }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { self.finalizeChargePending(orderId, tradeNo); }
        });
    }
    public boolean hasUnsettledCharge(String orderId) { return false; }
    public java.util.List<PaymentOperation> findSettledButUnbooked(int limit) { return null; }
  `;
  const okUnpaid = `
    public int autoCancelExpired() { alertChargeSettledButUnbookedOps(); return 0; }
    public int alertChargeSettledButUnbookedOps() { report("CHARGE_SETTLED_NOT_BOOKED"); return 0; }
  `;
  const cases = [];
  const push = (name, sources, expectRed) => cases.push({ name, sources, expectRed });

  push('基线绿', { payment: okPayment, unpaid: okUnpaid }, false);
  push(
    '旧形态：成功段就地 finalize',
    {
      payment: okPayment.replace(
        'settleChargeTraceAfterCommit(order.getOrderId(), "TN");',
        'self.finalizeChargePending(order.getOrderId(), "TN");'
      ),
      unpaid: okUnpaid
    },
    true
  );
  push(
    '成功段既不 after-commit 也不收口',
    {
      payment: okPayment.replace('settleChargeTraceAfterCommit(order.getOrderId(), "TN");', ''),
      unpaid: okUnpaid
    },
    true
  );
  push(
    '助手缺 isSynchronizationActive',
    {
      payment: okPayment.replace(
        'if (!TransactionSynchronizationManager.isSynchronizationActive()) { return; }',
        ''
      ),
      unpaid: okUnpaid
    },
    true
  );
  push(
    '助手不注册同步（立即执行）',
    {
      payment: okPayment.replace(
        'TransactionSynchronizationManager.registerSynchronization',
        'noop'
      ),
      unpaid: okUnpaid
    },
    true
  );
  push(
    '助手用 afterCompletion',
    { payment: okPayment.replace('afterCommit()', 'afterCompletion(a, b)'), unpaid: okUnpaid },
    true
  );
  push(
    '判据被删',
    {
      payment: okPayment.replace(
        'public boolean hasUnsettledCharge(String orderId) { return false; }',
        ''
      ),
      unpaid: okUnpaid
    },
    true
  );
  push(
    '巡检未接入调度链',
    { payment: okPayment, unpaid: okUnpaid.replace('alertChargeSettledButUnbookedOps();', '') },
    true
  );
  push(
    '告警类型被改',
    { payment: okPayment, unpaid: okUnpaid.replace('CHARGE_SETTLED_NOT_BOOKED', 'OTHER_TYPE') },
    true
  );

  let pass = 0;
  const fails = [];
  for (const c of cases) {
    const { errors } = evaluate(c.sources);
    const red = errors.length > 0;
    if (red === c.expectRed) pass++;
    else fails.push(`${c.name}（期望${c.expectRed ? '红' : '绿'}，实得${red ? '红' : '绿'}）`);
  }
  if (fails.length) {
    for (const f of fails) console.error(`${TAG} FAIL self-test ${f}`);
    process.exit(1);
  }
  console.log(`${TAG} OK：${pass} 项合成断言全过（${cases.length} 个反例/正例）`);
}

const invokedDirectly =
  process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (invokedDirectly) {
  if (process.argv.includes('--self-test')) selfTest();
  else runCli();
}
