/**
 * 商户「每日经营日报」口径门禁（MA-377）。
 *
 * 背景：商户每天要知道「昨天卖了多少」，但站内信只有金额、微信通道压根没接
 * （`notifyMerchant` 传 userId=null，外部渠道在 `NotificationService#send` 早退）。
 * 接通这条链时最容易犯的四类错，代码本身看不出来，只有跑起来才发现：
 *   ① 复用待办模板（thing1/thing2/time3）发金额 → 微信回 400，静默失败；
 *   ② 用消费者 token 发商户 openid → 40003，同样静默；
 *   ③ 去掉按「人 × 商户 × 日期」的幂等 → 重跑一次商户收两条；或多商户操作员只收到任意一个商户的日报；
 *   ④ 改了站内信文案的占位符（V499 加了 {orders}/{avg}）却没传参 → 商户看到字面量
 *      「{orders} 笔订单」。
 *
 * 判据（全部基于源码字面 + 迁移文件，不依赖运行时）：
 *   A 模板 id 只从配置取：发出端出现 resolveTemplateId()，且不得回退到待办模板
 *     subscribeTemplateId()
 *   B 关键词名只从配置取：发出端不得出现 amount1/number2/amount3/thing1 等字面量键
 *   C 目标端必须是 MERCHANT（消费者 token 发商户 openid 必失败）
 *   D 幂等查询必须同时带 channel、userId 与 merchantId（只按商户查 = 同商户多人只发一次；
 *     不带 channel = 站内信那条会把微信挡掉；不带 merchantId = 多商户操作员互相抑制）
 *   E 未配模板必须显式跳过（configured() + return 0），不许「发一条空消息」
 *   F 站内信与微信同源：调度器必须用 aggregateSales 的产物构造日报对象
 *   G 模板占位符闭环：V499 文案里的每个 {x} 都必须在调度器里有同名参数
 *   H 凌晨不许弹微信：00:20 的日结方法里不得调日报发送（站内信静默、微信是弹窗）
 *   I 零销售日也要发：两个任务的商户集合都必须过 mergeDeviceMerchants（有柜机但昨日零成交
 *     补一条 0 元），且该集合只能来自设备表枚举——「有成交才发」等于商户没成交的日子
 *     什么都收不到，分不清「没生意」和「通知坏了」（2026-11-20 改判）
 */
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const SERVICE = join(ROOT, 'services/trade-service/src/main');
export const NOTIFIER_FILE = join(
  SERVICE,
  'java/com/aicabinet/trade/service/MerchantDailyReportNotifier.java'
);
export const SCHEDULER_FILE = join(
  SERVICE,
  'java/com/aicabinet/trade/service/MerchantDailyIncomeNotifyScheduler.java'
);
export const MIGRATION_DIR = join(SERVICE, 'resources/db/migration');
const TAG = '[check-merchant-daily-report]';

/** 关键词名字面量：只允许出现在配置类/yml 里，不许出现在发出端（换模板要改代码就是坑）。 */
const KEYWORD_LITERALS = ['amount1', 'number2', 'amount3', 'thing1', 'thing2', 'time3'];

/**
 * 注释替换成等长空格：注释里的「历史写法」不能判红，也不能因为删注释而假红。
 * 保持字符长度 = 行号与列号不变（报错定位才对得回原文件）。
 */
export function maskComments(raw) {
  return raw
    .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/^(\s*)\/\/[^\n]*$/gm, (m, lead) => lead + ' '.repeat(m.length - lead.length));
}

/** 取最近一份更新 merchant_settlement 文案的迁移（按版本号最大值）。 */
export function settlementCopyPlaceholders() {
  const files = readdirSync(MIGRATION_DIR)
    .filter((f) => /^V\d+__.*\.sql$/.test(f))
    .filter((f) => /merchant_settlement/.test(f))
    .sort((a, b) => Number(a.slice(1).split('__')[0]) - Number(b.slice(1).split('__')[0]));
  if (!files.length) return { file: '', placeholders: [] };
  const file = files[files.length - 1];
  const sql = maskSqlComments(readFileSync(join(MIGRATION_DIR, file), 'utf8'));
  const block = sql.match(/UPDATE\s+notification_template[\s\S]*?;/i);
  if (!block) return { file, placeholders: [] };
  const placeholders = [...block[0].matchAll(/\{([a-zA-Z][a-zA-Z0-9_]*)\}/g)].map((m) => m[1]);
  return { file, placeholders: [...new Set(placeholders)] };
}

/** SQL 行注释（--）脱敏；字符串里的 -- 不在本文件出现，按行首判断即可。 */
function maskSqlComments(sql) {
  return sql.replace(/^\s*--[^\n]*$/gm, (m) => ' '.repeat(m.length));
}

/**
 * 取一个方法的方法体（签名行到下一个 4 空格缩进的声明之间）。
 * javadoc 已被 {@link maskComments} 抹成空格，不能当边界锚点，故按「4 空格缩进的下一行声明」判。
 * 返回 null 表示源码里找不到该签名（调用方据此跳过，避免用失效锚点判红）。
 */
export function methodBody(source, signature) {
  const start = source.indexOf(signature);
  if (start < 0) return null;
  const rel = source.slice(start + 1).search(/\n {4}(?=[^\s}])/);
  return source.slice(start, rel < 0 ? source.length : start + 1 + rel);
}

export function merchantDailyReportProblems({ notifier, scheduler, placeholders }) {
  const problems = [];
  const n = maskComments(notifier.replace(/\r\n/g, '\n'));
  const s = maskComments(scheduler.replace(/\r\n/g, '\n'));

  // A 模板 id 只从配置取
  if (!/resolveTemplateId\s*\(/.test(n)) {
    problems.push(
      '日报发出端没有从 MerchantDailyReportProperties 取模板 id（resolveTemplateId()）'
    );
  }
  if (/subscribeTemplateId\s*\(/.test(n)) {
    problems.push(
      '日报发出端点用了待办提醒模板 subscribeTemplateId()（thing 键与金额键不通用，微信必回 400）'
    );
  }
  if (!/isMerchantDailyReportConfigured\s*\(/.test(n)) {
    problems.push('日报发出端没有服务号独立日报模板配置判据（不能复用消费者模板）');
  }

  // B 关键词名只从配置取
  for (const key of KEYWORD_LITERALS) {
    if (n.includes(`"${key}"`)) {
      problems.push(
        `日报发出端出现关键词字面量 "${key}"：换模板就得改代码，必须走配置 resolveXxxKey()`
      );
    }
  }

  // C 目标端必须 MERCHANT：服务号与小程序订阅两条发送链都不得串到消费者
  const officialCall = n.match(/sendTemplateMessage\s*\(([\s\S]*?)\)\s*;/);
  if (!officialCall) {
    problems.push('日报发出端找不到服务号 sendTemplateMessage 调用（服务号优先链缺失）');
  } else {
    if (!/MiniAppTarget\.MERCHANT/.test(officialCall[1])) {
      problems.push('服务号日报没有指定 MiniAppTarget.MERCHANT（会跳到消费者小程序）');
    }
    if (/MiniAppTarget\.CONSUMER/.test(officialCall[1])) {
      problems.push('服务号日报被改成消费者端（商户收不到，且会污染消费者模板）');
    }
  }
  const call = n.match(/sendSubscribeMessage\s*\(([\s\S]*?)\)\s*;/);
  if (!call) {
    problems.push('日报发出端找不到 sendSubscribeMessage 回落调用（未绑定服务号的商户会失去通知）');
  } else {
    if (!/MiniAppTarget\.MERCHANT/.test(call[1])) {
      problems.push(
        '日报订阅回落没有指定 MiniAppTarget.MERCHANT（消费者 token 发商户 openid 必 40003）'
      );
    }
    if (/MiniAppTarget\.CONSUMER/.test(call[1])) {
      problems.push('日报订阅回落被改成消费者端（商户收不到，且会污染消费者模板）');
    }
  }

  // D 幂等查询：channel + userId + merchantId
  const dedupe = n.match(/private boolean sentToday\s*\(([\s\S]*?)\n\s*\}\n/);
  if (!dedupe) {
    problems.push('日报发出端找不到 sentToday 幂等判断（重跑会给同一人重复推送）');
  } else {
    if (!/getChannel/.test(dedupe[1])) {
      problems.push('日报幂等没有按 channel 过滤：站内信那条日志会把微信日报挡掉（或反之）');
    }
    if (!/getUserId/.test(dedupe[1])) {
      problems.push('日报幂等没有按 userId 过滤：同商户多人只发到一个人，重跑还会重复打扰');
    }
    if (!/getMerchantId/.test(dedupe[1])) {
      problems.push(
        '日报幂等没有按 merchantId 过滤：同一操作员管理多个商户时会互相抑制，只能收到任意一个商户的日报'
      );
    }
    if (!/getStatus[\s\S]*"SENT"/.test(dedupe[1])) {
      problems.push('日报幂等没有限定 SENT：失败日志会挡住后续重试');
    }
  }

  const sendBody = methodBody(n, 'public DeliveryResult send(');
  if (sendBody === null) {
    problems.push('日报发出端找不到 send 方法（开关/失败可见性判据锚点失效）');
  } else {
    if (
      !/reportProperties\.enabled\s*\(\s*\)[\s\S]*isMerchantDailyReportConfigured/.test(sendBody)
    ) {
      problems.push('服务号日报绕过业务开关：MERCHANT_DAILY_REPORT_ENABLED=false 时仍会发送');
    }
    if (!/saveLog\([^;]*"FAILED"\)/.test(sendBody)) {
      problems.push('微信日报发送失败没有落 FAILED 日志，运营无法区分未发与失败');
    }
    if (!/new\s+DeliveryResult\s*\(/.test(sendBody)) {
      problems.push('日报发送结果没有返回失败人数，调度无法识别部分失败');
    }
  }

  // E 未配模板显式跳过
  if (!/configured\s*\(/.test(n)) {
    problems.push('日报发出端没有 configured() 判据：未配模板会去调微信并静默失败');
  }

  // F 站内信与微信同源
  if (!/aggregateSales\s*\(/.test(s)) {
    problems.push('日结调度器没有用 aggregateSales 聚合（订单数/客单价会缺失或与金额不同源）');
  }
  if (!/MerchantDailyReportNotifier\.MerchantDailyReport\s*\(/.test(s)) {
    problems.push('日结调度器没有构造 MerchantDailyReport（微信日报根本没接上）');
  }

  // G 模板占位符闭环
  for (const p of placeholders) {
    if (!new RegExp(`"${p}"`).test(s)) {
      problems.push(
        `V 迁移文案占位符 {${p}} 在调度器渲染参数里没有同名键（商户会看到字面量 {${p}}）`
      );
    }
  }

  // H 凌晨不许弹微信：00:20 的日结只做扣费+站内信，微信日报在 07:30 的独立任务里发。
  //   站内信是静默入库、微信是真弹窗——混在一起等于半夜叫醒商户，结局是对方关掉提醒。
  //   方法边界按「4 空格缩进的下一行声明」判断（javadoc 已被脱敏成空格，不能当锚点）。
  const inAppBody = methodBody(s, 'NotifyOutcome sendDailyIncomeNotify');
  if (inAppBody === null) {
    problems.push('调度器里找不到日结方法 sendDailyIncomeNotify（判据锚点失效，先核对实现）');
  } else if (/dailyReportNotifier\s*\.\s*send\s*\(/.test(inAppBody)) {
    problems.push(
      '00:20 的日结方法里调用了微信日报发送：会在凌晨弹通知，应只在 07:30 的 notifyYesterdayReport 里发'
    );
  }
  if (
    !/DeliveryResult\s+sendWechatReportsOnly\s*\(/.test(s) ||
    !/notifyYesterdayReport\s*\(/.test(s)
  ) {
    problems.push('调度器里没有早间日报任务（sendWechatReportsOnly / notifyYesterdayReport 缺失）');
  }
  const reportTask = methodBody(s, 'public void notifyYesterdayReport()');
  if (reportTask === null) {
    problems.push('调度器里找不到 notifyYesterdayReport 方法（失败可见性判据锚点失效）');
  } else {
    if (!/outcome\.failed\s*\(\s*\)\s*>\s*0/.test(reportTask)) {
      problems.push('早间日报任务没有把渠道失败冒泡：微信发送失败仍会记 SUCCESS');
    }
    if (!/failed\s*\?\s*"FAILED"\s*:\s*"SUCCESS"/.test(reportTask)) {
      problems.push('早间日报任务没有按失败人数落 FAILED/SUCCESS 状态');
    }
  }

  // I 零销售日也要发（2026-11-20 改判）。生产长期 0 单时，「有成交才发」= 商户永远收不到
  //   任何东西，分不清「没生意」和「通知坏了」。覆盖范围只按设备表枚举（没柜的商户发 0 元=噪音）。
  const deviceScope = methodBody(s, 'merchantIdsWithDevices() {');
  if (deviceScope === null) {
    problems.push('调度器没有 merchantIdsWithDevices()：零销售商户的覆盖范围无从判定');
  } else if (!/deviceRepository\s*\.\s*selectList\s*\(/.test(deviceScope)) {
    problems.push('merchantIdsWithDevices 没有按设备表枚举（「有柜机的商户」来路不明）');
  }
  const mergeBody = methodBody(s, 'mergeDeviceMerchants(Map<String, DailySales>');
  if (mergeBody === null) {
    problems.push('调度器没有 mergeDeviceMerchants()：零成交的有柜商户不会被补进日报');
  } else {
    if (!/putIfAbsent\s*\(/.test(mergeBody)) {
      problems.push('mergeDeviceMerchants 没有补齐零成交商户（扩集合空转，等于只发有成交的）');
    }
    if (!/new\s+DailySales\s*\(\s*0L\s*,\s*0\s*\)/.test(mergeBody)) {
      problems.push('零成交商户补的不是「0 销售额 / 0 笔」：日报数字会凭空冒出来');
    }
  }
  for (const [signature, label] of [
    ['NotifyOutcome sendDailyIncomeNotify', '00:20 站内信'],
    ['DeliveryResult sendWechatReportsOnly', '07:30 微信']
  ]) {
    const body = methodBody(s, signature);
    // 缺失由 H 段判红；这里只判「扩集合有没有被摘掉」
    if (body === null) continue;
    if (!/mergeDeviceMerchants\s*\(/.test(body)) {
      problems.push(
        `${label}的商户集合没过 mergeDeviceMerchants：昨日零成交的有柜商户收不到日报（只发有成交的）`
      );
    }
  }

  return problems;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const problems = merchantDailyReportProblems({
    notifier: readFileSync(NOTIFIER_FILE, 'utf8'),
    scheduler: readFileSync(SCHEDULER_FILE, 'utf8'),
    placeholders: settlementCopyPlaceholders().placeholders
  });
  if (problems.length) {
    console.error(`${TAG} FAIL：${problems.length} 项`);
    for (const p of problems) console.error(`  - ${p}`);
    process.exit(1);
  }
  console.log(
    `${TAG} PASS：模板/键名全部来自配置，端=MERCHANT，按人×商户×日期幂等，文案占位符闭环，零成交有柜商户照发`
  );
}
