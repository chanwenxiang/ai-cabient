#!/usr/bin/env node
/**
 * 审计报告 → 台账登记门禁（补「审计产出没有落库机制」的洞）。
 *
 * 为什么需要：R23 四端轮 22 条缺陷全在报告正文里、`ISSUES.md` 零登记
 * （`grep -c 'R23Q-'` = 0），按台账派工会直接漏掉 5 个 P1。人工补登只能补
 * 一次数据，补不上「下次还会漏」这个机制，所以把「报告里出现的编号必须能在
 * 台账解析到条目」变成 CI 判据。
 *
 * 判据：
 *   R1 报告里的缺陷编号必须可解析到台账条目。解析途径三选一：
 *      a) `MA-xxx` 本身在 `ISSUES.md` 的 ID 列存在；
 *      b) 该编号（`C01` / `R23Q-01` / `R23X-01`）作为字面串出现在台账行内
 *         （台账「关联」列习惯写 `C01（R23Q-01）`）；
 *      c) 组合版映射表里显式映射到已存在的 MA
 *         （`| MA-262 | C01 |` 或 `C18=MA-176` / `C26=**MA-178**`）。
 *   R2（WARN，不阻断）报告声明的「建议新 MA 号段」若与台账已占用号段重叠，
 *      打印告警。不判红的原因：静态文本无法区分「本轮预留窗口」（README 的
 *      MA-176..199，号段留白是正常的）与「抢占已占用号段」（EXT1 的 280..299），
 *      硬判红会误伤全部合法预留（本轮实测 11 条误报）。阻断只由 R1 承担。
 *
 * 单向性（有意）：报告有、台账无 ⇒ 红；台账有、报告无 ⇒ 放行（历史条目）。
 *
 * 豁免形态（只认显式声明，不认文件名白名单——文件名白名单会腐化成永久放行）：
 *   报告内出现 `不写 ISSUES.md` 或 `仅本报告标注` ⇒ 该文件内的 MA 号免 R1，
 *   其号段声明也免 R2（本报告自带不领号声明）。豁免会以 WARN 形式逐条打印，
 *   且 R1/R2 对其余编号仍然生效。
 *
 * 防假绿：
 *   - 扫描报告数 < MIN_REPORTS ⇒ 直接红（docs 目录改名/移动后不会静默扫空）；
 *   - 台账 MA 条目数 < MIN_LEDGER ⇒ 直接红（台账被截断/换文件时不会静默通过）；
 *   - 扫描前 maskComments()：抹 HTML 注释与 ``` 代码块（报告里贴的示例编号
 *     否则会假红），替换为等长空白以保留偏移；
 *   - 编号提取不依赖行首格式（表格/标题/正文都扫）。
 *
 * 用法：node scripts/check-audit-report-registered.mjs [--verbose]
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-audit-report-registered]';
const LEDGER = 'docs/module-audit/ISSUES.md';
const MIN_REPORTS = 3;
const MIN_LEDGER = 100;
const EXEMPT_MARKERS = ['不写 ISSUES.md', '仅本报告标注'];

const VERBOSE = process.argv.includes('--verbose');

/** 抹注释与代码块，等长空白（保留偏移，便于后续扩展成带行号的报错）。 */
export function maskComments(text) {
  return text
    .replace(/<!--[\s\S]*?-->/g, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/```[\s\S]*?```/g, (m) => m.replace(/[^\n]/g, ' '));
}

/** 收集 R2x 轮次的审计报告（docs 顶层 AUDIT_*R2*.md + module-audit/R2x/ 下全部 md）。 */
export function listReportFiles(root = ROOT) {
  const out = [];
  const docs = join(root, 'docs');
  if (existsSync(docs)) {
    for (const entry of readdirSync(docs)) {
      if (/^AUDIT_.*R2\d.*\.md$/i.test(entry)) out.push(join(docs, entry));
    }
  }
  const moduleAudit = join(docs, 'module-audit');
  if (existsSync(moduleAudit)) {
    for (const entry of readdirSync(moduleAudit)) {
      if (!/^R2\d$/.test(entry)) continue;
      const dir = join(moduleAudit, entry);
      if (!statSync(dir).isDirectory()) continue;
      const stack = [dir];
      while (stack.length) {
        const cur = stack.pop();
        for (const f of readdirSync(cur)) {
          const full = join(cur, f);
          if (statSync(full).isDirectory()) stack.push(full);
          else if (f.endsWith('.md')) out.push(full);
        }
      }
    }
  }
  return out.sort();
}

/** 台账索引：ID 集合 + 全文（抹注释后）+ 已占用号区间。 */
export function buildLedger(text) {
  const masked = maskComments(text);
  const ids = new Set([...masked.matchAll(/^\|\s*(MA-\d{3,})\s*\|/gm)].map((m) => m[1]));
  const numbers = [...ids].map((id) => Number(id.slice(3))).sort((a, b) => a - b);
  // 合并写法展开：台账关联列常写 `R23Q-08/09`，展开成两个独立串供反查
  const expanded = masked.replace(
    /\b(R2\d[A-Z])-(\d{1,2})\s*\/\s*(\d{1,2})\b/g,
    (_, fam, a, b) => `${fam}-${a} ${fam}-${b}`
  );
  return {
    raw: expanded,
    ids,
    min: numbers.length ? numbers[0] : 0,
    max: numbers.length ? numbers[numbers.length - 1] : 0,
    numbers
  };
}

/** 从组合版/台账正文里的映射形态建立 `C# → MA` 别名。 */
export function buildAliases(text, ledgerIds) {
  const masked = maskComments(text);
  const alias = new Map();
  const put = (key, ma) => {
    if (ledgerIds.has(ma)) alias.set(key, ma);
  };
  // 表格行：| MA-262 | C01 | P1 | ...
  for (const m of masked.matchAll(/^\|\s*(MA-\d{3,})\s*\|\s*(C\d{2}[a-c]?)\s*\|/gm))
    put(m[2], m[1]);
  // 赋值形：C18=MA-176 / C26=**MA-178 / C38a（MA-290）
  for (const m of masked.matchAll(/\b(C\d{2}[a-c]?)\s*(?:=|＝|≡|（)\s*\**\s*(MA-\d{3,})/g))
    put(m[1], m[2]);
  return alias;
}

/** 提取报告中的编号（家族编号 + 缺陷编号）。 */
export function extractIds(text) {
  const masked = maskComments(text);
  const out = new Set();
  for (const m of masked.matchAll(/\bMA-\d{3,}\b/g)) out.add(m[0]);
  for (const m of masked.matchAll(/\bC\d{2}[a-c]?\b/g)) out.add(m[0]);
  // R2x 家族编号：R23Q-01 / R23X-12 / R23I-01
  for (const m of masked.matchAll(/\bR2\d[A-Z]-\d{1,2}\b/g)) out.add(m[0]);
  // 合并写法 R23Q-08/09 → 展开两个号（台账习惯这么写关联列）
  for (const m of masked.matchAll(/\b(R2\d[A-Z])-(\d{1,2})\s*\/\s*(\d{1,2})\b/g)) {
    out.add(`${m[1]}-${m[2].padStart(2, '0')}`);
    out.add(`${m[1]}-${m[3].padStart(2, '0')}`);
  }
  return out;
}

/**
 * 号段声明：只认「本报告要占用」的语义（保留段 / 建议新 MA 号 / 预留号）。
 * 不认纯区间描述（如 README 的「本轮编号 MA-176…199」、组合版的「台账已占用 MA-262…294」），
 * 否则每份总结报告的区间说明都会被判成撞号。
 */
const RANGE_DECL_RE = /保留段|建议新\s*MA|预留号|号段预留/;

export function extractRanges(text) {
  const masked = maskComments(text);
  const out = [];
  for (const m of masked.matchAll(/MA-(\d{3,})\s*(?:\.\.|…|—|~)\s*(\d{3,})/g)) {
    const context = masked.slice(Math.max(0, m.index - 80), m.index);
    if (!RANGE_DECL_RE.test(context)) continue;
    out.push([Number(m[1]), Number(m[2])]);
  }
  return out;
}

/** 家族别名归一：R23I → R23Q（EXT1 用 R23I 指四端轮，独立轮旧号也是 R23I，故需显式声明）。 */
const FAMILY_ALIAS = new Map([['R23I', 'R23Q']]);

export function normalizeFamily(id) {
  const m = /^(R2\d[A-Z])-(\d{1,2})$/.exec(id);
  if (!m) return id;
  const target = FAMILY_ALIAS.get(m[1]);
  return target ? `${target}-${m[2]}` : id;
}

export function run(root = ROOT) {
  const errors = [];
  const warnings = [];
  const ledgerPath = join(root, LEDGER);
  if (!existsSync(ledgerPath)) {
    return { errors: [`台账不存在：${LEDGER}`], warnings, scanned: 0, unregistered: [] };
  }
  const ledger = buildLedger(readFileSync(ledgerPath, 'utf8'));
  if (ledger.ids.size < MIN_LEDGER) {
    errors.push(`台账条目数 ${ledger.ids.size} < ${MIN_LEDGER}：疑似台账被截断或换成非台账文件`);
  }

  const reports = listReportFiles(root);
  if (reports.length < MIN_REPORTS) {
    errors.push(`扫描到报告 ${reports.length} 份 < ${MIN_REPORTS}：疑似报告目录改名导致扫空`);
  }

  let alias = buildAliases(ledger.raw, ledger.ids);
  // 组合版等顶层审计文档也参与别名（| MA-262 | C01 | 常在组合版里）
  for (const f of reports) {
    const text = maskComments(readFileSync(f, 'utf8'));
    for (const [k, v] of buildAliases(text, ledger.ids)) alias.set(k, v);
  }

  const unregistered = [];
  for (const file of reports) {
    const rel = relative(root, file).replace(/\\/g, '/');
    const text = readFileSync(file, 'utf8');
    const masked = maskComments(text);
    const exempt = EXEMPT_MARKERS.some((marker) => masked.includes(marker));
    const ids = [...extractIds(text)];

    for (const rawId of ids) {
      const id = normalizeFamily(rawId);
      if (id.startsWith('MA-')) {
        if (ledger.ids.has(id)) continue;
        if (exempt) {
          warnings.push(`${rel}：${id} 未登记，但文件声明「不领号」⇒ 放行`);
          continue;
        }
        unregistered.push({ file: rel, id: rawId, reason: '台账 ID 列无此条目' });
        continue;
      }
      // C## / R2x-XX：字面串命中或别名映射命中
      const candidates = [id, rawId];
      if (candidates.some((c) => ledger.raw.includes(c))) continue;
      const aliasKey = candidates.find((c) => alias.has(c));
      if (aliasKey) continue;
      unregistered.push({ file: rel, id: rawId, reason: '台账行内与映射表均无此编号' });
    }

    if (exempt) {
      warnings.push(
        `${rel}：声明「不领号」（命中 ${EXEMPT_MARKERS.filter((m) => masked.includes(m)).join('/')}）⇒ 其 MA 号免登记`
      );
      continue;
    }
    for (const [from, to] of extractRanges(text)) {
      const hit = ledger.numbers.filter((n) => n >= from && n <= to);
      if (hit.length) {
        // 只 WARN 不红：静态无法区分「本轮的预留窗口」（README 的 MA-176..199）
        // 与「抢占已被占用的号段」（EXT1 的 MA-280..299）。二者文本形态相同，
        // 硬判红会把合法的预留窗口全部误伤（本轮实测 11 条误报）。撞号靠本 WARN
        // + 台账/组合版的人工登记兜底；R1 才是阻断判据。
        warnings.push(
          `${rel}：号段 MA-${from}..${to} 与台账已占用 ${hit.length} 个号重叠（${hit
            .slice(0, 4)
            .map((n) => `MA-${n}`)
            .join('、')}${hit.length > 4 ? ' …' : ''}）⇒ 若属抢占号段须让号`
        );
      }
    }
  }

  for (const item of unregistered) {
    errors.push(`${item.file}：${item.id} 未进台账（${item.reason}）`);
  }

  if (VERBOSE) {
    for (const w of warnings) console.log(`${TAG} WARN ${w}`);
  }
  return { errors, warnings, scanned: reports.length, unregistered, ledgerCount: ledger.ids.size };
}

const isMain =
  process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url));
if (isMain) {
  const { errors, warnings, scanned, ledgerCount } = run();
  if (!VERBOSE) {
    for (const w of warnings) console.log(`${TAG} WARN ${w}`);
  }
  if (errors.length) {
    console.error(`${TAG} FAIL（${errors.length} 条）`);
    for (const e of errors) console.error(`  - ${e}`);
    console.error(`${TAG} 修法：把报告条目登记进 ${LEDGER}（或补 C#→MA 映射），撞号则让号。`);
    process.exit(1);
  }
  console.log(`${TAG} OK 报告 ${scanned} 份 / 台账 ${ledgerCount} 条，全部编号可解析`);
}
