/**
 * 商户「每日经营日报」口径门禁（MA-377）。
 *
 * 背景：商户每天要知道「昨天卖了多少」，但站内信只有金额、微信通道压根没接
 * （`notifyMerchant` 传 userId=null，外部渠道在 `NotificationService#send` 早退）。
 * 接通这条链时最容易犯的四类错，代码本身看不出来，只有跑起来才发现：
 *   ① 复用待办模板（thing1/thing2/time3）发金额 → 微信回 400，静默失败；
 *   ② 用消费者 token 发商户 openid → 40003，同样静默；
 *   ③ 去掉按「人 × 日期」的幂等 → 重跑一次商户收两条；
 *   ④ 改了站内信文案的占位符（V499 加了 {orders}/{avg}）却没传参 → 商户看到字面量
 *      「{orders} 笔订单」。
 *
 * 判据（全部基于源码字面 + 迁移文件，不依赖运行时）：
 *   A 模板 id 只从配置取：发出端出现 resolveTemplateId()，且不得回退到待办模板
 *     subscribeTemplateId()
 *   B 关键词名只从配置取：发出端不得出现 amount1/number2/amount3/thing1 等字面量键
 *   C 目标端必须是 MERCHANT（消费者 token 发商户 openid 必失败）
 *   D 幂等查询必须同时带 channel 与 userId（只按商户查 = 同商户多人只发一次；
 *     不带 channel = 站内信那条会把微信挡掉）
 *   E 未配模板必须显式跳过（configured() + return 0），不许「发一条空消息」
 *   F 站内信与微信同源：调度器必须用 aggregateSales 的产物构造日报对象
 *   G 模板占位符闭环：V499 文案里的每个 {x} 都必须在调度器里有同名参数
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

export function merchantDailyReportProblems({ notifier, scheduler, placeholders }) {
  const problems = [];
  const n = maskComments(notifier);
  const s = maskComments(scheduler);

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

  // B 关键词名只从配置取
  for (const key of KEYWORD_LITERALS) {
    if (n.includes(`"${key}"`)) {
      problems.push(
        `日报发出端出现关键词字面量 "${key}"：换模板就得改代码，必须走配置 resolveXxxKey()`
      );
    }
  }

  // C 目标端必须 MERCHANT
  const call = n.match(/sendSubscribeMessage\s*\(([\s\S]*?)\)\s*;/);
  if (!call) {
    problems.push('日报发出端找不到 sendSubscribeMessage 调用（判据失效，先核对实现）');
  } else {
    if (!/MiniAppTarget\.MERCHANT/.test(call[1])) {
      problems.push(
        '日报发送没有指定 MiniAppTarget.MERCHANT（消费者 token 发商户 openid 必 40003）'
      );
    }
    if (/MiniAppTarget\.CONSUMER/.test(call[1])) {
      problems.push('日报发送被改成消费者端（商户收不到，且会污染消费者模板）');
    }
  }

  // D 幂等查询：channel + userId
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
  console.log(`${TAG} PASS：模板/键名全部来自配置，端=MERCHANT，按人×日期幂等，文案占位符闭环`);
}
