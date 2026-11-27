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
  notifier: readFileSync(NOTIFIER_FILE, 'utf8').replace(/\r\n/g, '\n'),
  scheduler: readFileSync(SCHEDULER_FILE, 'utf8').replace(/\r\n/g, '\n'),
  placeholders: settlementCopyPlaceholders().placeholders
};

assert(real.notifier.length > 2000, '真实 MerchantDailyReportNotifier.java 读取失败');
assert(real.scheduler.length > 5000, '真实 MerchantDailyIncomeNotifyScheduler.java 读取失败');
assert(real.placeholders.includes('orders'), 'V499 文案占位符解析失败（应含 {orders}）');
assert(merchantDailyReportProblems(real).length === 0, '真实仓面必须绿');
const crlf = {
  ...real,
  notifier: real.notifier.replace(/\n/g, '\r\n'),
  scheduler: real.scheduler.replace(/\n/g, '\r\n')
};
assert(merchantDailyReportProblems(crlf).length === 0, 'CRLF 工作树不得误报 sentToday 缺失');

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
  'user.getWxOpenId(), templateId, page, fields, MiniAppTarget.MERCHANT)',
  'user.getWxOpenId(), templateId, page, fields, MiniAppTarget.CONSUMER)'
);
assert(wrongTarget !== real.notifier, '注入②锚点 MiniAppTarget.MERCHANT 找不到');
inject({ notifier: wrongTarget }, '注入②错端发送', 'MiniAppTarget.MERCHANT');

const wrongOfficialTarget = real.notifier.replace(
  'MiniAppTarget.MERCHANT, reportProperties.resolvePage())',
  'MiniAppTarget.CONSUMER, reportProperties.resolvePage())'
);
assert(wrongOfficialTarget !== real.notifier, '注入②b锚点服务号 MiniAppTarget.MERCHANT 找不到');
inject({ notifier: wrongOfficialTarget }, '注入②b服务号错端', '服务号日报');

// ③ 幂等丢掉 channel —— 站内信那条日志会把微信日报挡掉
const noChannel = real.notifier.replace(
  '.in(NotificationLog::getChannel, List.of(CHANNEL, OFFICIAL_CHANNEL))\n',
  ''
);
assert(noChannel !== real.notifier, '注入③锚点 channel 条件找不到');
inject({ notifier: noChannel }, '注入③幂等缺 channel', 'channel');

// ③b 幂等丢掉 merchantId —— 同一操作员管理多个商户时会互相抑制
const noMerchantId = real.notifier.replace('.eq(NotificationLog::getMerchantId, merchantId)', '');
assert(noMerchantId !== real.notifier, '注入③b锚点 merchantId 条件找不到');
inject({ notifier: noMerchantId }, '注入③b幂等缺 merchantId', 'merchantId');

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

// ⑥ 微信日报被塞回 00:20 的日结里 —— 会在凌晨弹窗，商户直接关提醒
const midnightPush = real.scheduler.replace(
  'chargeAndCheckAllMerchants(yesterday, salesByMerchant.keySet());',
  'dailyReportNotifier.send(null);\n        chargeAndCheckAllMerchants(yesterday, salesByMerchant.keySet());'
);
assert(midnightPush !== real.scheduler, '注入⑥锚点日结方法体找不到');
inject({ scheduler: midnightPush }, '注入⑥凌晨弹微信', '凌晨弹通知');

// ⑦ 注释里的历史写法不得误判（否则删注释会假红）
const commented = real.notifier.replace(
  'Map<String, String> data = new LinkedHashMap<>();',
  '// 历史写法（错的，勿恢复）：data.put("amount1", ...); weChatMiniAppProperties.subscribeTemplateId()\n' +
    '        Map<String, String> data = new LinkedHashMap<>();'
);
assert(commented !== real.notifier, '注入⑦锚点 buildFields 找不到');
assert(
  merchantDailyReportProblems({ ...real, notifier: commented }).length === 0,
  '注释里的旧代码被误判为红'
);

// ⑧ 站内信侧把「零成交有柜商户」的扩集合摘掉 —— 商户没成交就什么都收不到
const inAppNoMerge = real.scheduler.replace(
  'Map<String, DailySales> salesByMerchant = mergeDeviceMerchants(aggregateSales(splits));\n        if (salesByMerchant.isEmpty()) {\n            // 审计 T4',
  'Map<String, DailySales> salesByMerchant = aggregateSales(splits);\n        if (salesByMerchant.isEmpty()) {\n            // 审计 T4'
);
assert(inAppNoMerge !== real.scheduler, '注入⑧锚点（00:20 扩集合）找不到');
inject({ scheduler: inAppNoMerge }, '注入⑧站内信只发有成交的', '00:20 站内信');

// ⑨ 微信侧摘掉扩集合 —— 站内信有、微信没有，两条通道又对不上
const wechatNoMerge = real.scheduler.replace(
  'Map<String, DailySales> salesByMerchant = mergeDeviceMerchants(aggregateSales(splits));\n        if (salesByMerchant.isEmpty()) {\n            return 0;',
  'Map<String, DailySales> salesByMerchant = aggregateSales(splits);\n        if (salesByMerchant.isEmpty()) {\n            return 0;'
);
assert(wechatNoMerge !== real.scheduler, '注入⑨锚点（07:30 扩集合）找不到');
inject({ scheduler: wechatNoMerge }, '注入⑨微信只发有成交的', '07:30 微信');

// ⑩ 「有柜机的商户」不再按设备表枚举（改成凭空造集合）—— 覆盖范围就没有依据了
const noDeviceEnum = real.scheduler.replace(
  'for (DeviceInfo d : deviceRepository.selectList(Wrappers.<DeviceInfo>lambdaQuery())) {',
  'for (DeviceInfo d : java.util.List.<DeviceInfo>of()) {'
);
assert(noDeviceEnum !== real.scheduler, '注入⑩锚点 deviceRepository.selectList 找不到');
inject({ scheduler: noDeviceEnum }, '注入⑩覆盖范围无依据', '设备表枚举');

// ⑪ 扩集合空转（还在调 mergeDeviceMerchants，但不往里补零成交商户）—— 等于白扩
const emptyMerge = real.scheduler.replace(
  'merged.putIfAbsent(merchantId, new DailySales(0L, 0));',
  'merged.remove(merchantId);'
);
assert(emptyMerge !== real.scheduler, '注入⑪锚点 putIfAbsent 找不到');
inject({ scheduler: emptyMerge }, '注入⑪扩集合空转', '空转');

console.log(`${TAG} OK：${checks} 项断言全过（真树绿 / CRLF 绿 / 双渠道注入红 / 注释不误判）`);
