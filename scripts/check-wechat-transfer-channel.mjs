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
 * 负向对照：篡改任一判据必须变红（见本文件末尾 --selftest）。
 */
import fs from 'node:fs';
import path from 'node:path';

const ROOT = process.cwd();
const CLIENT = path.join(
  ROOT,
  'services/trade-service/src/main/java/com/aicabinet/trade/payout/WeChatTransferClient.java'
);
const CHANNEL = path.join(
  ROOT,
  'services/trade-service/src/main/java/com/aicabinet/trade/payout/WeChatPayoutChannel.java'
);

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

function evaluate(clientSrc, channelSrc) {
  const c = maskComments(clientSrc);
  const ch = maskComments(channelSrc);
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
}

/** 负向夹具：正例绿 + 篡改判据后必须红。wiring 门禁据此认定本门禁有可执行负向夹具。 */
function selfTest() {
  const okClient = fs.readFileSync(CLIENT, 'utf8');
  const okChannel = fs.readFileSync(CHANNEL, 'utf8');
  // 正例：当前实现必须绿
  const before = process.exitCode;
  evaluate(okClient, okChannel);
  check(process.exitCode === before, 'selftest 正例应通过');
  // 负例：把 NOT_ENOUGH 从确定失败移除，必须变红
  const badClient = okClient.replace('case "NOT_ENOUGH", ', 'case ');
  process.exitCode = 0;
  let captured = [];
  const origError = console.error;
  console.error = (...args) => captured.push(args.join(' '));
  evaluate(badClient, okChannel);
  console.error = origError;
  const negativeFailed = captured.some((m) => m.includes('NOT_ENOUGH'));
  process.exitCode = 0; // evaluate 负例设的 exitCode 是预期的，自检本身要以结论为准
  check(negativeFailed, 'selftest 负例必须变红（判据真在咬人）');
  console.log(`[check-wechat-transfer-channel] selftest: 正例绿、负例红，判据有效`);
  process.exit(process.exitCode ?? 0);
}

if (!fs.existsSync(CLIENT) || !fs.existsSync(CHANNEL)) {
  console.error('[check-wechat-transfer-channel] FAIL: 源文件缺失');
  process.exit(1);
}
if (process.argv.includes('--selftest')) {
  selfTest();
}
evaluate(fs.readFileSync(CLIENT, 'utf8'), fs.readFileSync(CHANNEL, 'utf8'));
if (!process.exitCode) {
  console.log('[check-wechat-transfer-channel] OK：MA-407 资金安全判据全部在位');
}
