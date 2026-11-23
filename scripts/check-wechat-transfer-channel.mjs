#!/usr/bin/env node
/**
 * MA-407 微信商家转账通道判据门禁。
 *
 * 钉住生产级资金安全底线，防止后续改动把「不确定态」误当失败（释放冻结→双重支出）：
 * ① 确定失败码清单（NOT_ENOUGH/USER_ERROR/ORDER_CLOSED/ALREADY_EXISTS）必须存在；
 * ② 429/5xx/SYSTEM_ERROR 必须走 PayoutPendingException（不得 returned as rejected）；
 * ③ 幂等键 out_bill_no 原样传（不得换单号重试）；
 * ④ 场景 ID 1005 + 报备两条 info_type 固定值（岗位类型/报酬说明）；
 * ⑤ 授权单号确定性派生（SHA-256，纯字母数字）。
 *
 * 2026-11-23 复核补四条（都是「代码自洽、真打款必炸」的形态）：
 * ⑥ **单号形态**：微信 out_bill_no 只收数字/大小写字母 6–32 位，而幂等键旧形态 `MW:42`
 *    含冒号 ⇒ 真打款 100% PARAM_ERROR；且**不能发之前才清洗**（回调 out_bill_no 反查不回
 *    idem_key，幂等/对账链断）⇒ 生成侧必须直接产出渠道可用形态。
 * ⑦ **ALREADY_EXISTS 查单裁决**：该码语义是「渠道侧这笔单已存在」（多为上次请求其实受理、
 *    回执丢了）。当确定失败处理 = 释放冻结 + 渠道钱仍可能走 ⇒ 重复出款。必须查单定终态。
 * ⑧ **确认票据 key 一致**：写入用 transfer_bill_no、读取用 idem_key 会让商户永远拿不到
 *    「去确认收款」入口（钱锁渠道 24h）。
 * ⑨ **线长侧建单前预拒**：确认模式一建单就锁资金，线长端无确认入口 ⇒ 必须在 `channel.transfer(`
 *    之前判 `requiresUserConfirmation()`，事后再判等于「钱锁渠道 + 本地已解冻」。
 *
 * 负向对照：篡改任一判据必须变红（见本文件末尾 --selftest）。
 */
import fs from 'node:fs';
import path from 'node:path';

const ROOT = process.cwd();
const P = 'services/trade-service/src/main/java/com/aicabinet/trade';
const FILES = {
  client: path.join(ROOT, P, 'payout/WeChatTransferClient.java'),
  channel: path.join(ROOT, P, 'payout/WeChatPayoutChannel.java'),
  constants: path.join(ROOT, P, 'payout/PayoutConstants.java'),
  accountService: path.join(ROOT, P, 'service/PayoutAccountService.java'),
  withdrawService: path.join(ROOT, P, 'service/MerchantWithdrawService.java'),
  linePayout: path.join(ROOT, P, 'service/LineWithdrawPayoutService.java')
};

/** 等长空格遮掉注释——铁律 84：扫源码前先去注释，避免文案漂移假红。 */
function maskComments(src) {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/\/\/[^\n]*/g, (m) => m.replace(/[^\n]/g, ' '));
}

function check(condition, msg) {
  if (!condition) {
    console.error(`[check-wechat-transfer-channel] FAIL: ${msg}`);
    process.exitCode = 1;
  }
}

/** 取某个方法体（按签名行起、按括号配平止）；取不到返回空串（判据会因此红，不静默放行）。 */
export function methodBody(src, signatureFragment) {
  const idx = src.indexOf(signatureFragment);
  if (idx < 0) return '';
  const open = src.indexOf('{', idx);
  if (open < 0) return '';
  let depth = 0;
  for (let i = open; i < src.length; i += 1) {
    if (src[i] === '{') depth += 1;
    else if (src[i] === '}') {
      depth -= 1;
      if (depth === 0) return src.slice(open, i + 1);
    }
  }
  return '';
}

function evaluate(src) {
  const c = maskComments(src.client);
  const ch = maskComments(src.channel);
  const constants = maskComments(src.constants ?? '');
  const account = maskComments(src.accountService ?? '');
  const withdraw = maskComments(src.withdrawService ?? '');
  const line = maskComments(src.linePayout ?? '');

  // ① 确定失败码
  for (const code of ['NOT_ENOUGH', 'USER_ERROR', 'ORDER_CLOSED', 'ALREADY_EXISTS']) {
    check(c.includes(`"${code}"`), `确定失败码 ${code} 必须从 error 分类中 rejected`);
  }
  // ② 不确定态必须 Pending
  check(
    /class\s+DefiniteFailure/.test(c),
    '必须有 DefiniteFailure 表示确定失败（其余一律 Pending）'
  );
  check(
    c.includes('new PayoutChannel.PayoutPendingException'),
    '非确定失败必须抛 PayoutPendingException'
  );
  check(/case\s+"NOT_ENOUGH"/.test(c), 'NOT_ENOUGH 必须在 definiteFail switch 中');
  // ③ 幂等键原样作 out_bill_no
  check(ch.includes('command.idemKey()'), 'out_bill_no 必须原样使用 command.idemKey()');
  check(!/command\.idemKey\(\)\s*\+/.test(ch), '禁止对 idemKey 拼接（换单号=重复出款）');
  // ④ 场景与报备
  check(
    ch.includes('20_000L') && ch.includes('200_000L') && ch.includes('5_000_000L'),
    '渠道限额三维度常量不得改动'
  );
  check(
    c.includes('"岗位类型"') && c.includes('"报酬说明"'),
    '佣金报酬报备两条 info_type 固定值缺失'
  );
  // ⑤ 授权单号确定性
  check(ch.includes('MessageDigest.getInstance("SHA-256")'), '授权单号必须由 SHA-256 确定性派生');

  // ⑥ 单号形态：常量 + 生成侧 + 通道侧前置断言，三处缺一不可
  check(
    /CHANNEL_BILL_NO_PATTERN\s*=\s*"\^\[A-Za-z0-9\]\{6,32\}\$"/.test(constants),
    'PayoutConstants.CHANNEL_BILL_NO_PATTERN 必须是 ^[A-Za-z0-9]{6,32}$（微信 out_bill_no 规则）'
  );
  const idemBody = methodBody(account, 'newIdemKey(');
  check(!!idemBody, 'PayoutAccountService.newIdemKey 找不到（判据无法取证）');
  check(
    !idemBody.includes('":"'),
    'newIdemKey 不得拼冒号——微信 out_bill_no 不收 ":" ⇒ 真打款必 PARAM_ERROR'
  );
  check(
    /String\.format\(\s*"%0\d+d"/.test(idemBody),
    'newIdemKey 必须零填充到固定宽度（保证 out_bill_no ≥ 6 位）'
  );
  check(
    ch.includes('isChannelSafeBillNo(command.idemKey())'),
    '通道侧必须在发请求前对 out_bill_no 做形态前置校验（fail-closed）'
  );

  // ⑦ ALREADY_EXISTS 必须查单裁决，不得直接 rejected
  check(c.includes('ALREADY_EXISTS'), 'DefiniteFailure 分类需保留 ALREADY_EXISTS 码');
  check(
    /boolean\s+alreadyExists\s*\(/.test(c) && c.includes('"ALREADY_EXISTS".equals'),
    'DefiniteFailure 必须暴露 alreadyExists()（按码分流，不靠 message 文本）'
  );
  check(
    ch.includes('queryOutcome(') && /adjudicate\w*DefiniteFailure|alreadyExists\(\)/.test(ch),
    'ALREADY_EXISTS 必须走查单裁决（queryOutcome），不得直接判失败释放冻结'
  );
  const catchRejected =
    /catch\s*\(\s*WeChatTransferClient\.DefiniteFailure\s+\w+\s*\)\s*\{\s*return\s+PayoutResult\.rejected\(/.test(
      ch
    );
  check(!catchRejected, 'DefiniteFailure 捕获块不得直接 rejected：必须经裁决函数分流');

  // ⑧ 确认票据读写必须同一个键（幂等键），不得用 transfer_bill_no 当 key
  const saveCalls = withdraw.match(/confirmTicketStore\.save\([^;]*/g) ?? [];
  check(saveCalls.length > 0, '找不到 confirmTicketStore.save 调用（判据无法取证）');
  for (const call of saveCalls) {
    check(
      !/save\(\s*(result\.)?payoutRef\(\)/.test(call) && !/save\(\s*ref\b/.test(call),
      '票据必须按幂等键（渠道单号）存：用 payoutRef/transfer_bill_no 会让 confirmTicket 取不到'
    );
  }

  // ⑨ 线长侧建单前必须拒掉「需用户确认」的通道（顺序判据，注释已遮）
  const guardAt = line.indexOf('requiresUserConfirmation()');
  const dispatchAt = line.indexOf('channel.transfer(');
  check(guardAt >= 0, '线长打款必须先问 channel.requiresUserConfirmation()（无确认入口不得建单）');
  check(
    dispatchAt >= 0 && guardAt >= 0 && guardAt < dispatchAt,
    'requiresUserConfirmation() 必须在 channel.transfer( 之前判：建单后再判＝钱已锁渠道、本地已解冻'
  );
}

function readAll() {
  const out = {};
  for (const [k, p] of Object.entries(FILES)) {
    out[k] = fs.existsSync(p) ? fs.readFileSync(p, 'utf8') : '';
  }
  return out;
}

/** 负向夹具：正例绿 + 每条判据各注入一处改动后必须红。wiring 门禁据此认定有可执行负向夹具。 */
function selfTest() {
  const ok = readAll();
  for (const [k, p] of Object.entries(FILES)) {
    if (!ok[k]) {
      console.error(`[check-wechat-transfer-channel] FAIL: 源文件缺失 ${p}`);
      process.exit(1);
    }
  }
  const origError = console.error;
  const runFixture = (label, mutate, expectFragment) => {
    const captured = [];
    process.exitCode = 0;
    console.error = (...args) => captured.push(args.join(' '));
    const mutated = JSON.parse(JSON.stringify(ok));
    mutate(mutated);
    evaluate(mutated);
    console.error = origError;
    process.exitCode = 0;
    check(
      captured.some((m) => m.includes(expectFragment)),
      `selftest 负例「${label}」必须变红`
    );
  };

  // 正例
  process.exitCode = 0;
  const captured = [];
  console.error = (...args) => captured.push(args.join(' '));
  evaluate(ok);
  console.error = origError;
  check(captured.length === 0 && !process.exitCode, 'selftest 正例应通过');
  process.exitCode = 0;

  runFixture(
    'NOT_ENOUGH 移出确定失败',
    (m) => {
      m.client = m.client.replace('case "NOT_ENOUGH", ', 'case ');
    },
    'NOT_ENOUGH'
  );
  runFixture(
    '幂等键拼冒号',
    (m) => {
      m.accountService = m.accountService.replace(
        'return prefix + String.format("%010d", requestId);',
        'return prefix + ":" + requestId;'
      );
    },
    'newIdemKey 不得拼冒号'
  );
  runFixture(
    'ALREADY_EXISTS 直接 rejected',
    (m) => {
      m.channel = m.channel.replace(
        'return adjudicateDefiniteFailure(command, e);',
        'return PayoutResult.rejected(e.getMessage());'
      );
    },
    '必须经裁决函数分流'
  );
  runFixture(
    '票据用 transfer_bill_no 当 key',
    (m) => {
      m.withdrawService = m.withdrawService.replace(
        'confirmTicketStore.save(MerchantWithdrawPayoutService.billNoOf(request),',
        'confirmTicketStore.save(result.payoutRef(),'
      );
    },
    '票据必须按幂等键'
  );
  runFixture(
    '线长建单后才判确认模式',
    (m) => {
      // 把前置守卫挪到 transfer 之后：直接删掉前置块即可模拟「只剩事后判」
      const guardStart = m.linePayout.indexOf('if (channel.requiresUserConfirmation())');
      const guardEnd = m.linePayout.indexOf('PayoutAccountService.ResolvedPayee payee');
      if (guardStart >= 0 && guardEnd > guardStart) {
        m.linePayout = m.linePayout.slice(0, guardStart) + m.linePayout.slice(guardEnd);
      }
    },
    'requiresUserConfirmation() 必须在 channel.transfer( 之前判'
  );

  console.log('[check-wechat-transfer-channel] selftest: 正例绿、5 条负例全红，判据有效');
  if (process.exitCode) process.exit(process.exitCode);
}

const missing = Object.values(FILES).filter((p) => !fs.existsSync(p));
if (missing.length) {
  console.error('[check-wechat-transfer-channel] FAIL: 源文件缺失\n' + missing.join('\n'));
  process.exit(1);
}
if (process.argv.includes('--selftest')) {
  selfTest();
} else {
  evaluate(readAll());
  if (!process.exitCode) {
    console.log('[check-wechat-transfer-channel] OK：MA-407 资金安全判据全部在位（9 组）');
  }
}
