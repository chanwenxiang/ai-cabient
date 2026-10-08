#!/usr/bin/env node
/**
 * 竞品对照门禁 —— 守住「写业务代码前先查竞品」这句话不空转。
 *
 * 背景（2026-10-07，用户明确要求）
 * ----------------------------------
 * 项目是面向消费者的付费业务（扫码/刷脸开门 → 取货 → 自动结算）。准入、额度、催缴、
 * 拉黑、券范围、识别路线、提现实名这些问题**行业里已有成熟答案**。不查就实现 = 大概率
 * 重造别人踩过的坑，且事后无法回答「为什么这样设计」。
 *
 * 规则见 `.cursor/rules/competitor-benchmark-before-code.mdc`（alwaysApply）。
 *
 * 为什么必须有门禁而不是只写规则
 * ------------------------------
 * 「先查竞品」是**流程性要求**，无法靠代码审查保证；只写在文档里必然随会话衰减成口号
 * （本项目已有铁律：规则写在文档里 ≠ 会被执行，见 `check-audit-gates-wiring` 的接线纪律）。
 * 所以把它钉成**可失败的静态判据**：台账结构不全 / 新建表迁移没有竞品引用 ⇒ 红。
 *
 * 七条规则
 *   R0 防恒真：台账条目数、URL 总数设**下限**。解析器哪天改坏导致「扫到 0 条」必须判红，
 *      不能因为「一条违规都没发现」而报 OK（与 check-migration-safety 同源教训）。
 *   R1 台账结构：每个 `### CB-xxx` 块必须齐四项 —— 竞品做法 / 证据 / 取证日期 / 我们的结论。
 *   R2 证据可解析：证据行至少 1 个 http(s) URL；禁止 example.com / 「见上」这类占位。
 *   R3 取证日期格式：`YYYY-MM-DD`，且**不得晚于今天**（防止「随手编个未来日期」）。
 *   R4 结论可执行：`我们的结论` 必须出现 采纳 / 不采纳 / 有意不同 之一 —— 「待定」不算结论。
 *   R5 迁移头引用：相对基线**新增**的 Flyway 脚本，若含 `CREATE TABLE`（业务新表）或
 *      `MIGRATION_KIND: feature`，头注释必须带 `COMPETITOR_REF: CB-xxx[,CB-yyy]`，
 *      且引用的编号必须真实存在于台账（防编造 id）。用 `N/A(<理由>)` 豁免时理由 ≥ 12 字，
 *      每次豁免都会打印出来（不静默）。
 *   R6 豁免不白拿：`N/A` 只对**非业务语义**的表有效；文件名/内容出现业务词
 *      （order/payment/refund/risk/member/coupon/inventory…）时 N/A 一律判红。
 *   R7 🔴 对照主体数 ≥ 3（用户 2026-10-07 明确「别只找一家」）：按**主体独立性**判定，
 *      不按 URL 数 —— 3 篇转载同一家服务商答疑仍只算 1 家。媒体/汇总类（人民网/百科/
 *      搜狐/新浪/行业报告…）**不计入主体**；无法识别的标签**一律不计数**（fail-closed）。
 *
 * 只对「新增迁移」生效：已合入的历史迁移不回溯翻旧账（R5 用与 check-migration-safety
 * 相同的基线 diff + 未跟踪枚举）。
 *
 *   node scripts/check-competitor-benchmark.mjs
 */
import { gitAsync } from './lib/async-spawn.mjs';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-competitor-benchmark]';
const DOC = join(ROOT, 'docs', 'COMPETITOR_BENCHMARK.md');
const MIGRATION_DIR = join(
  ROOT,
  'services',
  'trade-service',
  'src',
  'main',
  'resources',
  'db',
  'migration'
);

// ── 防恒真锚点（改解析器时同步复核这几个数，见 R0）────────────────────────
const MIN_ENTRIES = 14; // 台账条目下限（CB-001..CB-014 已落地）
const MIN_URLS = 40; // 证据 URL 总数下限
const MIN_NA_REASON = 12; // N/A 豁免理由最少字数
const MIN_SUBJECTS = 3; // 每条「对照主体」总数下限（用户 2026-10-07：别只找一家）
const MIN_PEERS = 2; // 其中「开门柜直接同行」下限 —— 跨行业只能补充、不能顶替

/**
 * 主体名归一：把「微信支付分（官方）」「支付宝芝麻先享（官方）」这类带**可信度标注**的写法
 * 归到同一主体，避免**同一个官方被数成两家**（那正是「3 个 URL 假 3 家竞品」的根源）。
 * 归一维度：平台域名 + 主体关键字。不依赖人工维护完整名单 —— 名单会过期，忘了更新就假绿。
 *
 * 🔴 `kind` 区分主体类别（用户 2026-10-07 追问「不是开门柜的竞品也可以吗」——可以，但**有条件**）：
 *   - `peer`     **开门柜直接同行**（运营方/设备商/服务商）。这是「对比竞品」的本义。
 *   - `official` **支付通道官方**（微信支付分/芝麻先享等）。既是同行依赖方，也是规则来源。
 *   - `cross`    **跨行业范式**（哈啰/共享充电宝）。借其**合规边界与做法形态**。
 *   - `research` 行业研究机构（给行业统计口径，不是某一家自述）。
 *   - `null`     媒体/汇总类，**不计入主体**（避免「一篇综述」冒充一家竞品）。
 *
 * 为什么必须分级：不分级时，写「哈啰 + 共享充电宝 + 某财经号」也能凑够 3 家过门禁 ——
 * 那就把「对照竞品」偷换成了「对照任何行业」。故 R7 额外要求 **≥2 家 peer**。
 */
const SUBJECT_ALIASES = [
  // 开门柜直接同行（peer）
  { re: /友宝|ubox/i, id: '友宝UBOX', kind: 'peer' },
  { re: /丰e|丰宜/i, id: '丰e足食', kind: 'peer' },
  { re: /哈哈零兽|武汉哈哈/i, id: '哈哈零兽', kind: 'peer' },
  { re: /美智微/i, id: '美智微', kind: 'peer' },
  { re: /宇脉/i, id: '宇脉电子', kind: 'peer' },
  { re: /合豚/i, id: '合豚', kind: 'peer' },
  { re: /思迅/i, id: '思迅', kind: 'peer' },
  { re: /旺旺/i, id: '旺旺', kind: 'peer' },
  { re: /创弗/i, id: '创弗', kind: 'peer' },
  { re: /dozzon/i, id: 'DOZZON', kind: 'peer' },
  { re: /缤果|小麦铺|小麦便利|盒子便利店/i, id: '盒子便利店系', kind: 'peer' },
  { re: /丰e管家/i, id: '丰e足食', kind: 'peer' },
  // 旧系统三套柜机厂商（chzh8 / jinyu2 / yichu2）—— 不是本项目竞品，
  // 但「我们支持哪些柜型」必须对照它们，故作为独立主体计数（CB-015）。
  { re: /chzh8|春潮/i, id: '旧系统chzh8', kind: 'peer' },
  { re: /jinyu2|金宇/i, id: '旧系统jinyu2', kind: 'peer' },
  { re: /yichu2|亿厨/i, id: '旧系统yichu2', kind: 'peer' },
  { re: /旧系统/i, id: '旧系统easygo', kind: 'peer' },
  // 支付/信用通道官方（official）
  { re: /微信支付分|weixin.?pay.?score/i, id: '微信支付分', kind: 'official' },
  { re: /芝麻先享|芝麻信用|zhima/i, id: '支付宝芝麻先享', kind: 'official' },
  { re: /通联|allinpay/i, id: '通联支付', kind: 'official' },
  { re: /支付宝/i, id: '支付宝', kind: 'official' },
  { re: /微信支付/i, id: '微信支付', kind: 'official' },
  // 跨行业范式（cross）—— 借合规边界/做法形态，**不可顶替同行**
  { re: /哈啰|哈罗|hellobike|hello\b/i, id: '哈啰出行', kind: 'cross' },
  { re: /充电宝|街电|怪兽充电|美团充电/i, id: '共享充电宝', kind: 'cross' },
  // 跨行业 ERP/进销存工具方（cross）—— CB-016 供应商对账的行业口径来源（官方帮助文档级）
  { re: /勤策/i, id: '勤策ERP', kind: 'cross' },
  { re: /简道云|飞优/i, id: '简道云', kind: 'cross' },
  { re: /企畅通/i, id: '企畅通ERP', kind: 'cross' },
  { re: /宏达|inmis/i, id: '宏达进销存', kind: 'cross' },
  // 跨行业财务/进销存（cross）—— CB-017 盘点差异金额化的会计口径来源
  { re: /管家婆/i, id: '管家婆', kind: 'cross' },
  { re: /金蝶|kis/i, id: '金蝶', kind: 'cross' },
  // 行业研究机构（research）：给统计口径，不是自述主体
  { re: /中研普华|chinairn/i, id: '中研普华', kind: 'research' },
  { re: /沙利文|frost\s*sullivan|弗若斯特/i, id: '弗若斯特沙利文', kind: 'research' },
  { re: /同花顺|10jqka/i, id: '同花顺', kind: 'research' },
  { re: /网经社|100ec/i, id: '网经社', kind: 'research' },
  { re: /21经济|21jingji|乐居|cbndata|财经媒体/i, id: '财经媒体', kind: 'research' },
  // 媒体/汇总类：明确排除，不计入主体（避免「一篇行业综述」冒充一家竞品）
  { re: /人民网|people\.com/i, id: null },
  { re: /百度百科|baike/i, id: null },
  { re: /知乎|zhihu/i, id: null },
  { re: /网易|163\.com/i, id: null },
  { re: /搜狐|sohu/i, id: null },
  { re: /新浪|sina/i, id: null },
  { re: /腾讯新闻|qq\.com/i, id: null },
  { re: /抖音|douyin/i, id: null },
  { re: /今日头条|toutiao/i, id: null },
  { re: /广告|流量主/i, id: null },
  { re: /普法|监管|政策|法规|法律/i, id: null }
];

/**
 * 判定一个主体标签对应哪个主体 id。
 * 返回 `null` = 明确排除（媒体类）；`undefined` = 无法识别（**不计数**，fail-closed）。
 * 关键设计：**无法识别的标签一律不计数** —— 宁可让作者把主体名写规范，
 * 也不要因为宽松匹配把 3 篇软文算成 3 家。
 */
function subjectId(label) {
  for (const { re, id } of SUBJECT_ALIASES) {
    if (re.test(label)) return id;
  }
  return undefined; // undefined = 不认识（不计入）；null = 明确排除
}

/** 主体 id → kind（R7 用：区分同行 / 官方 / 跨行业 / 研究）。 */
const SUBJECT_KIND = new Map(SUBJECT_ALIASES.filter((a) => a.id).map((a) => [a.id, a.kind]));

/** 业务语义表名关键词：命中即不允许 N/A 豁免（R6）。 */
const BUSINESS_WORDS =
  /(order|payment|refund|risk|settle|balance|wallet|member|coupon|promotion|inventory|stock|lot|dispatch|damage|dispute|merchant|user|account|price|fee|recharge|withdraw|settle)/i;

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

/**
 * 跑一条 git 命令并拿回 stdout。
 *
 * 🔴 走共用的异步 spawn（`scripts/lib/async-spawn.mjs`）：WorkBuddy 环境下
 * `spawnSync` 一律 `status=null` + `EBUSY`（lessons #292），会把「git 跑不起来」
 * 误判成「没有新增迁移」而静默放行。本门禁必须读到 `git diff` / `git ls-files` 的**内容**，
 * 所以降级 stdio 的垫片方案也解决不了（拿不到 stdout）。
 */
function git(args) {
  return gitAsync(args, { cwd: ROOT });
}

const problems = [];
const warnings = [];

// ══ R0/R1/R2/R3/R4：台账结构 ══════════════════════════════════════════════
if (!existsSync(DOC)) {
  fail(
    `台账不存在：docs/COMPETITOR_BENCHMARK.md。\n` +
      `  新增业务能力前必须先检索竞品/官方做法并登记（规则 competitor-benchmark-before-code）。\n` +
      `  这是唯一权威台账；结论只留在对话里等于没查。`
  );
}
const doc = readFileSync(DOC, 'utf8');

// 剥掉围栏代码块与行内代码，避免注释里的示例条目被当成真条目计入。
const docNoFence = doc.replace(/```[\s\S]*?```/g, '');

const entryRe = /^###\s+(CB-\d{3})\s+(.+)$/gm;
const entries = [];
let m;
while ((m = entryRe.exec(docNoFence)) !== null) {
  entries.push({ id: m[1], title: m[2].trim(), start: m.index, bodyStart: entryRe.lastIndex });
}
for (let i = 0; i < entries.length; i++) {
  entries[i].body = docNoFence.slice(
    entries[i].bodyStart,
    entries[i + 1] ? entries[i + 1].start : undefined
  );
}

if (entries.length < MIN_ENTRIES) {
  fail(
    `台账只解析出 ${entries.length} 条（CB-xxx），低于锚点下限 ${MIN_ENTRIES}。\n` +
      `  要么是台账被清空/改名，要么是本脚本的解析正则失效了。\n` +
      `  「扫到 0 条所以没有违规」是典型假绿 —— 不许当成通过。`
  );
}

// 重复编号
const seen = new Map();
for (const e of entries) {
  if (seen.has(e.id))
    problems.push(`台账编号重复：${e.id}（标题「${e.title}」与「${seen.get(e.id)}」重复）`);
  else seen.set(e.id, e.title);
}

let totalUrls = 0;
let subjectCount = 0;
let excludedSubjects = 0;
const subjectDetail = [];
for (const e of entries) {
  const field = (label) => {
    // 形如 `- 竞品做法：xxx`，允许续行（缩进 / 顶格都算，直到下一个 `- **字段**` 或下一个条目）。
    //
    // 🔴 结尾断言必须用 `(?![\s\S])`（真·文末）而**不是 `$`**：`m` 标志下 `$` 匹配的是
    // 「任意一行的行尾」，非文末 ⇒ 惰性 `[\s\S]*?` 会在**第一行行尾**就停下，
    // 导致多行字段（证据 URL 列表）只被读到第 1 条。实测踩到：URL 总数 9 < 下限 20。
    //
    // 字段名允许带括号后缀，且后缀里**可能再次出现 `**`**（如
    // `- **竞品做法**（服务商方案/自媒体，**证据强度低**）：` —— 嵌套加粗会打断第一层 `**`）。
    // 因此不匹配「`字段名…**`」，而是匹配「字段名 + 直到冒号的同行文本」，
    // 并用否定先行断言禁止跨到下一个字段起始（`(?!\\n-\\s*\\*\\*)`）。
    // 四种真实写法已实测全部命中且正确截断（含朴素式 / 斜杠后缀 / 括号后缀 / 括号内嵌套加粗）。
    const re = new RegExp(
      `^-\\s*\\*\\*${label}(?:(?!\\n-\\s*\\*\\*).)*?[：:]\\s*([\\s\\S]*?)(?=\\n-\\s*\\*\\*|\\n###\\s|(?![\\s\\S]))`,
      'm'
    );
    const hit = re.exec(e.body);
    return hit ? hit[1].trim() : '';
  };

  const practice = field('竞品做法');
  const evidence = field('证据');
  const date = field('取证日期');
  const conclusion = field('我们的结论');
  const subjectsRaw = field('对照主体');

  if (!practice) problems.push(`${e.id}（${e.title}）缺「竞品做法」`);
  if (!evidence) problems.push(`${e.id}（${e.title}）缺「证据」`);

  // ── R7 对照主体数（用户 2026-10-07：别只找一家）──────────────────────────
  // 按**主体独立性**判定，不按 URL 数：3 篇转载同一服务商答疑仍只算 1 家。
  const labels = subjectsRaw
    .split(/[｜|、,，/]/)
    .map((s) => s.replace(/\*/g, '').trim())
    .filter(Boolean);
  if (!subjectsRaw) {
    problems.push(`${e.id}（${e.title}）缺「对照主体」—— 必须显式列出对比了哪几家（含官方一手源）`);
  } else {
    const ids = new Set();
    const unknown = [];
    const excluded = [];
    for (const label of labels) {
      const id = subjectId(label);
      if (id === null) excluded.push(label);
      else if (id === undefined) unknown.push(label);
      else ids.add(id);
    }
    subjectCount += ids.size;
    const kinds = [...ids].map((id) => ({ id, kind: SUBJECT_KIND.get(id) }));
    const peers = kinds.filter((k) => k.kind === 'peer').map((k) => k.id);
    const cross = kinds.filter((k) => k.kind === 'cross').map((k) => k.id);
    if (ids.size < MIN_SUBJECTS) {
      problems.push(
        `${e.id}（${e.title}）只有 ${ids.size} 家有效对照主体（下限 ${MIN_SUBJECTS}）：` +
          `${[...ids].join('、') || '无'}｜标签：${labels.join(' / ')}\n` +
          `    3 个 URL ≠ 3 家竞品；媒体/汇总类不计入主体；无法识别的标签一律不计数（fail-closed）。`
      );
    }
    if (peers.length < MIN_PEERS) {
      problems.push(
        `${e.id}（${e.title}）只有 ${peers.length} 家**开门柜直接同行**（下限 ${MIN_PEERS}）：` +
          `${peers.join('、') || '无'}${cross.length ? `｜跨行业 ${cross.join('、')} 不能顶替同行` : ''}\n` +
          `    跨行业范式只能补充：借它的合规边界/做法形态可以，但「对比竞品」必须先看过同行。`
      );
    }
    if (unknown.length) {
      warnings.push(
        `${e.id} 对照主体里有无法识别的标签（未计入家数，请写规范名）：${unknown.join(' / ')}`
      );
    }
    excludedSubjects += excluded.length;
    subjectDetail.push(`${e.id}: ${ids.size} 家 [${[...ids].join('、')}]`);
  }

  // R2 证据可解析
  const urls = evidence.match(/https?:\/\/[^\s）)、，,｜|]+/g) || [];
  totalUrls += urls.length;
  if (evidence && urls.length === 0) {
    problems.push(`${e.id}（${e.title}）「证据」里没有可解析的 http(s) URL`);
  }
  for (const u of urls) {
    if (/example\.(com|org|net)|localhost|127\.0\.0\.1/i.test(u)) {
      problems.push(`${e.id}（${e.title}）证据 URL 是占位符：${u}`);
    }
  }

  // R3 取证日期
  if (!date) {
    problems.push(`${e.id}（${e.title}）缺「取证日期」（竞品做法会变，必须记取证时点）`);
  } else if (!/^\d{4}-\d{2}-\d{2}$/.test(date.trim())) {
    problems.push(`${e.id}（${e.title}）取证日期格式应为 YYYY-MM-DD，实际「${date.trim()}」`);
  } else {
    const today = new Date();
    const todayStr = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(
      today.getDate()
    ).padStart(2, '0')}`;
    if (date.trim() > todayStr) {
      problems.push(
        `${e.id}（${e.title}）取证日期 ${date.trim()} 晚于今天（${todayStr}），禁止编造未来日期`
      );
    }
  }

  // R4 结论可执行
  if (!conclusion) {
    problems.push(`${e.id}（${e.title}）缺「我们的结论」`);
  } else if (!/采纳|不采纳|有意不同|修正\s*CB-/.test(conclusion)) {
    problems.push(
      `${e.id}（${e.title}）结论不可执行：须含「采纳 / 不采纳 / 有意不同」之一（当前开头「${conclusion.slice(0, 24)}…」）`
    );
  }
}

if (totalUrls < MIN_URLS) {
  fail(
    `台账证据 URL 总数 ${totalUrls} < 锚点下限 ${MIN_URLS}。\n` +
      `  与上一条同理：URL 提取失效（含 R2 判定失效）时不能报 OK。`
  );
}

// ══ R5/R6：新增迁移必须带竞品引用 ═════════════════════════════════════════
const baseRef = process.env.MIGRATION_BASE_REF || 'origin/dev';
let diff = await git([
  'diff',
  '--name-only',
  '--diff-filter=A',
  `${baseRef}...HEAD`,
  '--',
  MIGRATION_DIR
]);
if (!diff.ok) {
  diff = await git(['diff', '--name-only', '--diff-filter=A', 'HEAD', '--', MIGRATION_DIR]);
}
// 刻意不加 --exclude-standard：migration 目录里任何未跟踪 .sql 都是一次真实的新迁移
// （与 check-migration-safety 同理，加了会让它对门禁隐形）。
const untracked = await git(['ls-files', '--others', '--', MIGRATION_DIR]);
if (!diff.ok && !untracked.ok) {
  fail(
    '无法枚举新增迁移（git 不可用或被环境阻断：spawn status=null / 非 0）。\n' +
      '  不要当成「没有新迁移」—— 那是在没看的情况下报 OK。\n' +
      '  本机沙箱下可用 .tmp/tools/nopipe.cjs 垫片重跑。'
  );
}

const relFiles = [
  ...(diff.ok && diff.out ? diff.out.split(/\r?\n/).filter(Boolean) : []),
  ...(untracked.ok && untracked.out ? untracked.out.split(/\r?\n/).filter(Boolean) : [])
]
  .filter((p) => /\.sql$/i.test(p))
  .map((p) => p.replace(/\\/g, '/'));
const newFiles = [...new Set(relFiles)];

const naUsed = [];

for (const rel of newFiles) {
  const abs = join(ROOT, rel);
  let body;
  try {
    body = readFileSync(abs, 'utf8');
  } catch {
    problems.push(`${rel}: cannot read`);
    continue;
  }

  const isFeature = /MIGRATION_KIND\s*:\s*(feature|new_table|create)/i.test(body);
  const createsTable = /CREATE\s+TABLE/i.test(body);
  if (!isFeature && !createsTable) continue; // 纯 DML / 索引 / 回填不要求竞品引用

  const refMatch = /^--\s*COMPETITOR_REF\s*:\s*(.+)$/im.exec(body);
  if (!refMatch) {
    problems.push(
      `${rel}: 新建业务表但缺 \`-- COMPETITOR_REF: CB-xxx\`。\n` +
        `    先检索竞品/官方做法并登记到 docs/COMPETITOR_BENCHMARK.md，或写明 \`-- COMPETITOR_REF: N/A(<理由>)\`。\n` +
        `    规则：.cursor/rules/competitor-benchmark-before-code.mdc`
    );
    continue;
  }

  const value = refMatch[1].trim();

  if (/^N\/A\b/i.test(value)) {
    const reason = value
      .replace(/^N\/A\s*/i, '')
      .replace(/^\((.*)\)$/s, '$1')
      .trim();
    if (reason.length < MIN_NA_REASON) {
      problems.push(
        `${rel}: COMPETITOR_REF 的 N/A 理由只有 ${reason.length} 字（需 ≥ ${MIN_NA_REASON}）—— 禁止随手甩豁免`
      );
    } else if (BUSINESS_WORDS.test(body)) {
      problems.push(
        `${rel}: 表名/内容含业务语义词，但 COMPETITOR_REF 写的是 N/A —— 业务能力没有「不需对照」的豁免权`
      );
    } else {
      naUsed.push(`${rel} → N/A(${reason})`);
    }
    continue;
  }

  // 校验引用的编号真实存在（防编造 id）
  for (const id of value
    .split(/[,，]/)
    .map((s) => s.trim())
    .filter(Boolean)) {
    if (!/^CB-\d{3}$/.test(id)) {
      problems.push(`${rel}: COMPETITOR_REF 引用了非法编号「${id}」（须形如 CB-001）`);
    } else if (!seen.has(id)) {
      problems.push(
        `${rel}: COMPETITOR_REF 引用了台账里不存在的 ${id}。\n` +
          `    已登记编号：${[...seen.keys()].join(', ')}`
      );
    }
  }
}

if (problems.length) {
  fail(`\n  ${problems.join('\n  ')}\n`);
}

console.log(
  `${TAG} OK：台账 ${entries.length} 条 / 证据 ${totalUrls} 个 URL / 有效对照主体合计 ${subjectCount} 家` +
    `（下限 ${MIN_ENTRIES} 条 / ${MIN_URLS} URL / 每条 ≥${MIN_SUBJECTS} 家；媒体类标签已排除 ${excludedSubjects} 个）；` +
    `新增迁移 ${newFiles.length} 个${newFiles.length ? '（含需竞品引用的已逐条校验）' : ''}无缺失引用。`
);
console.log(`${TAG} 各条对照主体数（核对「别只找一家」）：`);
for (const s of subjectDetail) console.log(`${TAG}   ${s}`);
if (naUsed.length) {
  console.log(`${TAG} 提示：本次有 ${naUsed.length} 处 N/A 豁免（已记录，不静默）：`);
  for (const n of naUsed) console.log(`${TAG}   - ${n}`);
}
for (const w of warnings) console.log(`${TAG} WARN: ${w}`);
