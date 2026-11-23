#!/usr/bin/env node
/**
 * 门禁（S13）：并发写策略必须**显式声明**（diff 级，不做全仓扫描）——
 * MA-017/044/045、R18-2、MA-160、R22-2 家族的结构化控制。
 *
 * 诚实边界（§九 S13）：「select 后无 CAS 即 save」的全仓形态扫描**误报面极大**
 * （很多读改写本来就安全），做成硬门禁会把人训练成加白名单。故不做全量扫描，
 * 做**新增面强制声明**：凡改到资金/库存/额度写路径的 diff，必须命中三选一：
 *   ① 条件 UPDATE（`WHERE ... = ...` 谓词 / 原子方法 deductQuantityAtomic 等）
 *   ② 乐观锁 version（version 字段 CAS / version = version + 1）
 *   ③ 显式锁 key（tryLock / distributedLock / lockKey / runWithLock）
 *   或附 `// CONCURRENCY: <策略>` 锚点（声明进工件，评审可见）。
 * 门禁**只校验被本次 diff 触碰的方法**（diff 级，不是全仓级）。
 *
 * 写路径判据：方法体内含资金/库存/额度写操作（save/update/insert/delete +
 * fund/inventory/lot/ledger/wallet/quota/balance/amount/cents/stock/deduct 等领域词）。
 *
 *   node scripts/check-concurrency-declaration.mjs
 */
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gitAsync } from './lib/async-spawn.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-concurrency-declaration]';

// 资金/库存/额度写路径领域词
const DOMAIN_RE =
  /fund|inventory|lot|ledger|wallet|quota|balance|amount|cents|stock|deduct|restock|pullOff|settlement|refund|payment/i;
// 写操作调用
const WRITE_RE =
  /\.(save|updateById|update|insert|delete|deleteById|deductQuantityAtomic|increaseQuantityAtomic)\s*\(/;
// 三策略证据
const ANCHOR_RE = /\/\/\s*CONCURRENCY\s*:/;
const COND_UPDATE_RE =
  /deductQuantityAtomic|increaseQuantityAtomic|updateStatusAndSlotById|UPDATE\s+\w+\s+SET.*WHERE|WHERE\s+\w+\s*=\s*#\{|conditional|原子/i;
const VERSION_RE = /version\s*=\s*version\s*\+\s*1|version\s*=|CAS|乐观锁/i;
const LOCK_RE = /tryLock|distributedLock|lockKey|runWithLock|runWithDeviceLotLock|显式锁|加锁/i;

/**
 * 方法签名行检测。
 *
 * 🔴 旧实现是「含括号、不以分号结尾、首词不是控制语句」的近似——于是
 * `DeviceSkuInventory inv = existing.orElseGet(() -> {`、`.map(this::toDto)` 这类
 * **调用/链式续行**也被当成方法起点。后果不是少判而是**判错方法**：
 * 一个跨 30 行的 blob 被命名成「orElseGet」，diff 落在 blob 内任一位置都会被算成
 * 「改到了 orElseGet 这个写方法」（2026-11-15 实测：只改了只读取数
 * `listRoutesPage`，报的是「写方法 orElseGet 无并发策略」）。
 *
 * 现在按真实声明形态判：`[注解/修饰符] 返回类型 名字(` + 括号后只能跟 `{` 或行尾，
 * 且括号前不得出现 `=`（赋值/初始化）、不得是 `.foo(` / `} else` / lambda 续行。
 * 修饰符不是必需（包级可见方法也是方法），但必须像声明：首词是类型词而不是表达式。
 */
export function isMethodSignature(line) {
  const stripped = line.replace(/\/\/.*$/, '').trim();
  if (!stripped || stripped.startsWith('*') || stripped.startsWith('/*')) return false;
  if (stripped.startsWith('@')) return false; // 注解行：真签名在下一行
  const paren = stripped.indexOf('(');
  if (paren < 0) return false;
  // 赋值 / 初始化（含 lambda 赋值）不是方法声明
  const eq = stripped.indexOf('=');
  if (eq >= 0 && eq < paren) return false;
  if (stripped.includes('->')) return false;
  if (stripped.startsWith('.') || stripped.startsWith('}') || stripped.startsWith(')'))
    return false;
  if (!/\)\s*\{?\s*$/.test(stripped)) return false; // `)` 之后只能跟 `{` 或行尾
  const firstWord = stripped.split(/\s+/)[0];
  if (
    ['if', 'for', 'while', 'switch', 'catch', 'return', 'new', 'super', 'this', 'else'].includes(
      firstWord
    )
  )
    return false;
  // 声明形态：修饰符/类型 + 名字 + `(`。链式调用（`repo.find(`）会被上面的 `.`/`=` 拦掉，
  // 走到这里的 `foo(` 只剩「名字(…」——那种是构造器或裸调用，按声明处理会误判，
  // 故要求括号前至少有一个类型词（含泛型/数组/限定名）。
  return /^[\w$@.<>\[\],?&\s]+\s+[A-Za-z_]\w*\s*\(/.test(stripped);
}

/** 一行里的括号净深度（只判「签名是否跨行」；注释/字符串里的括号只影响本用途）。 */
function parenDelta(line) {
  let d = 0;
  for (const c of line) {
    if (c === '(') d++;
    else if (c === ')') d--;
  }
  return d;
}

/** 找到所有方法签名行号 */
function findMethodStarts(lines) {
  const starts = [];
  for (let i = 0; i < lines.length; i++) {
    let probe = lines[i];
    // 🔴 2026-11-23：参数换行的签名（`public X foo(long a,` 换行 `  PayoutResult b) {`）在**逐行**
    // 判据里永远不成立（`)` 不在行尾），于是该方法根本不算签名 ⇒ hunk 落在它体内时被归属到
    // **上一个方法**头上。实测形态：改 `finalizePayout`（多行签名）报成「写方法 markPaying
    // 无并发策略」——既不指名真凶，也让有证据的方法背锅。这里把括号补平后再判，
    // 签名仍登记在**首行**，行号语义（归属/切片）不变。
    if (parenDelta(probe) > 0 && /[A-Za-z_]\w*\s*\(/.test(probe)) {
      let j = i + 1;
      while (j < lines.length && parenDelta(probe) > 0) {
        probe += ' ' + lines[j].trim();
        j += 1;
      }
    }
    if (isMethodSignature(probe)) starts.push(i);
  }
  return starts;
}

/**
 * 把某个变更行归属到「它所在的方法」。
 *
 * 🔴 只按「最后一个签名行 ≤ 变更行」归属会错在**方法前导**上：新写的方法通常带上
 * javadoc / 注解（`@Transactional`），而 `-U0` 的 hunk 起始行落在那段前导里——
 * 于是 hunk 被算到**上一个方法**头上。2026-11-15 实测：给只读的 `listRoutes` 加 javadoc，
 * 报成了「写方法 upsertInventory 改到库存写路径」。
 *
 * 规则：若变更行落在「下一个方法声明之前的前导块」（空白/注释/注解）里，归属那个方法；
 * 否则归属最后一个签名行 ≤ 变更行的方法。
 */
function attributeMethod(lines, methodStarts, changedLine) {
  const below = [...methodStarts].reverse().find((s) => s <= changedLine) ?? -1;
  const fwd = methodStarts.find((s) => s > changedLine);
  if (fwd !== undefined) {
    let lead = fwd;
    // 1-based 行号 → 0-based 索引；向上吃掉空白/注释/注解
    while (lead - 2 >= 0) {
      const prev = lines[lead - 2].trim();
      const isTrivia =
        prev === '' ||
        prev.startsWith('*') ||
        prev.startsWith('/*') ||
        prev.startsWith('//') ||
        prev.startsWith('@') ||
        prev.endsWith('*/');
      if (!isTrivia) break;
      lead -= 1;
    }
    if (changedLine >= lead) return fwd;
  }
  return below;
}

/**
 * 纯判定：对每个 diff 触碰的方法，检查写路径 + 并发策略。
 * @param {{file:string, content:string, changedRanges:{start:number,end:number}[]}[]} files
 * @returns {{problems:string[], checked:number}}
 */
export function judge(files) {
  const problems = [];
  let checked = 0;
  for (const f of files) {
    const lines = f.content.split(/\r?\n/);
    const methodStarts = findMethodStarts(lines);
    for (const range of f.changedRanges) {
      const start = attributeMethod(lines, methodStarts, range.start);
      if (start < 0) continue; // 变更在类级/字段级，非方法
      const nextIdx = methodStarts.indexOf(start) + 1;
      const end = nextIdx < methodStarts.length ? methodStarts[nextIdx] : lines.length;
      const body = lines.slice(start, end).join('\n');
      // 写路径判定：领域词 + 写操作
      if (!DOMAIN_RE.test(body) || !WRITE_RE.test(body)) continue;
      checked += 1;
      // 三策略或锚点
      if (
        ANCHOR_RE.test(body) ||
        COND_UPDATE_RE.test(body) ||
        VERSION_RE.test(body) ||
        LOCK_RE.test(body)
      )
        continue;
      const methodName = lines[start].match(/(\w+)\s*\(/)?.[1] || '(方法)';
      problems.push(
        `${f.file}：diff 触碰的写方法「${methodName}」改到资金/库存/额度写路径，` +
          `但无并发策略证据——须命中三选一（条件 UPDATE / 乐观锁 version / 显式锁 key）` +
          `或附 ` // CONCURRENCY: <策略>` 锚点（R18-2/MA-017 家族）`
      );
    }
  }
  return { problems, checked };
}

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

async function main() {
  // ① 变更的 Java 文件
  const nameStatus = await gitAsync(['diff', '--name-only', '--diff-filter=AM', '--', 'services'], {
    cwd: ROOT
  });
  if (!nameStatus.ok)
    fail(`git diff 失败（${nameStatus.spawnError || 'non-zero exit'}）：${nameStatus.out}`);
  const changedJava = (nameStatus.out || '')
    .split(/\r?\n/)
    .filter((p) => p.endsWith('.java') && p.includes('src/main/'));

  if (changedJava.length === 0) {
    console.log(`${TAG} OK：本次 diff 无 Java 主源码改动（diff 级并发声明无需检查）`);
    return;
  }

  // ② 每个文件的变更行范围
  const files = [];
  for (const rel of changedJava) {
    const abs = join(ROOT, rel);
    if (!existsSync(abs)) continue; // 已删除的文件不读
    const diff = await gitAsync(['diff', '--', rel], { cwd: ROOT });
    if (!diff.ok) continue;
    const ranges = parseDiffHunks(diff.out || '');
    if (ranges.length === 0) continue;
    files.push({
      file: rel.replace(/\\/g, '/'),
      content: readFileSync(abs, 'utf8'),
      changedRanges: ranges
    });
  }

  if (files.length === 0) {
    console.log(`${TAG} OK：diff 涉及的 Java 文件无方法级变更（无需检查）`);
    return;
  }

  const { problems, checked } = judge(files);
  if (problems.length > 0) {
    for (const p of problems) console.error(`${TAG} FAIL ${p}`);
    console.error(`${TAG} 共 ${problems.length} 项（S13 diff 级并发声明）`);
    process.exit(1);
  }
  console.log(
    `${TAG} OK：${files.length} 个变更文件，${checked} 个 diff 触碰的写方法全部有并发策略证据或锚点（S13 diff 级）`
  );
}

/** 解析 git diff 的 hunk，返回新文件行号范围 [{start, end}] */
export function parseDiffHunks(diffText) {
  const ranges = [];
  for (const line of diffText.split(/\r?\n/)) {
    const m = line.match(/^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@/);
    if (m) {
      const newStart = parseInt(m[3], 10);
      const newCount = m[4] ? parseInt(m[4], 10) : 1;
      ranges.push({ start: newStart, end: newStart + newCount - 1 });
    }
  }
  return ranges;
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  await main();
}
