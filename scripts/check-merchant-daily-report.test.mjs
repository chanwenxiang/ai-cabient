/**
 * check-merchant-daily-report 的负向自测：真树必须绿，六种典型退化必须红。
 * 门禁本身不做负向验证就是「一直绿的空转」（本仓已踩过 R7/R13 两次）。
 */
import { readFileSync } from 'node:fs';
import {
  NOTIFIER_FILE,
  SCHEDULER_FILE,
  merchantDailyReportProblems,
  settlementCopyPlaceholders
} from './check-merchant-daily-report.mjs';

const TAG = '[check-merchant-daily-report.test]';
let checks = 0;
function assert(cond, message) {
  checks++;
  if (!cond) throw new Error(`${TAG} 断言失败：${message}`);
}

const real = {
  notifier: readFileSync(NOTIFIER_FILE, 'utf8'),
  scheduler: readFileSync(SCHEDULER_FILE, 'utf8'),
  placeholders: settlementCopyPlaceholders().placeholders
};

assert(real.notifier.length > 2000, '真实 MerchantDailyReportNotifier.java 读取失败');
assert(real.scheduler.length > 5000, '真实 MerchantDailyIncomeNotifyScheduler.java 读取失败');
assert(real.placeholders.includes('orders'), 'V499 文案占位符解析失败（应含 {orders}）');
assert(merchantDailyReportProblems(real).length === 0, '真实仓面必须绿');

function inject(patch, label, expectedFragment) {
  const mutated = { ...real, ...patch };
  const problems = merchantDailyReportProblems(mutated);
  assert(problems.length > 0, `${label}：应判红但判绿了`);
  if (expectedFragment) {
    assert(
      problems.some((p) => p.includes(expectedFragment)),
      `${label}：红的原因不对，实际=${JSON.stringify(problems)}`
    );
  }
}

// ① 复用待办模板（thing 键）发金额 —— 微信 400，静默失败
const tpl = real.notifier.replace(
  'reportProperties.resolveTemplateId()',
  'weChatMiniAppProperties.subscribeTemplateId()'
);
assert(tpl !== real.notifier, '注入①锚点 resolveTemplateId 找不到');
inject({ notifier: tpl }, '注入①复用待办模板', 'subscribeTemplateId');

// ② 目标端改成消费者 —— 商户 openid 用消费者 token 必 40003
const wrongTarget = real.notifier.replace(
  'openId, templateId, page, fields, MiniAppTarget.MERCHANT)',
  'openId, templateId, page, fields, MiniAppTarget.CONSUMER)'
);
assert(wrongTarget !== real.notifier, '注入②锚点 MiniAppTarget.MERCHANT 找不到');
inject({ notifier: wrongTarget }, '注入②错端发送', 'MiniAppTarget.MERCHANT');

// ③ 幂等丢掉 channel —— 站内信那条日志会把微信日报挡掉
const noChannel = real.notifier.replace('.eq(NotificationLog::getChannel, CHANNEL)\n', '');
assert(noChannel !== real.notifier, '注入③锚点 channel 条件找不到');
inject({ notifier: noChannel }, '注入③幂等缺 channel', 'channel');

// ④ 关键词名写死 —— 换模板要改代码（且与后台不一致就 400）
const hardKey = real.notifier.replace(
  'data.put(reportProperties.resolveSalesKey(), yuan(report.salesCents()));',
  'data.put("amount1", yuan(report.salesCents()));'
);
assert(hardKey !== real.notifier, '注入④锚点 resolveSalesKey 找不到');
inject({ notifier: hardKey }, '注入④关键词写死', 'amount1');

// ⑤ 文案占位符没传参 —— 商户看到字面量 {orders}
const noOrders = real.scheduler.replace('"orders", String.valueOf(report.orderCount()),', '');
assert(noOrders !== real.scheduler, '注入⑤锚点 orders 参数找不到');
inject({ scheduler: noOrders }, '注入⑤占位符闭环', 'orders');

// ⑥ 注释里的历史写法不得误判（否则删注释会假红）
const commented = real.notifier.replace(
  'Map<String, String> data = new LinkedHashMap<>();',
  '// 历史写法（错的，勿恢复）：data.put("amount1", ...); weChatMiniAppProperties.subscribeTemplateId()\n' +
    '        Map<String, String> data = new LinkedHashMap<>();'
);
assert(commented !== real.notifier, '注入⑥锚点 buildFields 找不到');
assert(
  merchantDailyReportProblems({ ...real, notifier: commented }).length === 0,
  '注释里的旧代码被误判为红'
);

console.log(`${TAG} OK：${checks} 项断言全过（真树绿 / 五种注入红 / 注释不误判）`);
