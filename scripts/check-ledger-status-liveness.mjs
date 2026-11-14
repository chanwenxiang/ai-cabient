#!/usr/bin/env node
/**
 * 台账「状态行 ↔ 现行代码」对账门禁（lessons 437 的结构化收口，S8 家族）。
 *
 * 背景（2026-11-14 一天内三次同形误读，每次都把已完成的事列成待办）：
 *   ① 将邑清单 §5.5.3 行写「❌ 未接」，同文件 §G-9 却写「✅ 2026-10-22 已接入」，
 *      而 `JiangyiGatherClient.stdSkuByTextNames` 确实在被调；
 *   ② 同表 G′ 汇总行写「实现 0 条」，与明细行冲突；
 *   ③ 结构台账顶部小标题写「S10 显式缓议（需拍板）」「S15 缺 18 条夹具」，
 *      紧跟其后的回执段写明 2026-11-13 两者都已落地并接进聚合链。
 * 共同形状：**同一份台账里，状态行与落地回执是两处独立书写、无人对账的副本**，
 * 而状态行最短、最像结论，复述时天然被取用。文字约定「引用前先读完全节」靠人遵守＝没约束。
 *
 * 判据（只吃同行可机械解析的证据，不跨行推语义）：
 *   R1 同文件状态冲突：同一个「能力键」（反引号里的 HTTP 路径，或 `Class.member`/`X.java` 代码锚点）
 *      在一行被判「已做」、另一行被判「未做」⇒ 红。
 *   R2 死锚点：某行以「已做」语气引用 `X.java[:NN]` 或 `Class.member` 作为证据，
 *      但文件不存在 / 行号越界 / 成员名查不到 ⇒ 红。
 *      （「说做了、证据却不在」比「说没做」更危险：读者会据此判风险已闭合。）
 *
 * 误报控制（每条都是实测出来的形状，不是预防性堆砌）：
 *   · 状态词只在**表格行的最后一格**（本仓台账的状态列）或整行（非表格行）判定；
 *   · 「」引用的旧状态、`~~划删除线~~` 的作废内容一律先剥掉——「旧记『❌ 未接』已过期」不是当前状态；
 *   · 成员锚点先排除带扩展名的文件引用（`Foo.xml`/`a.ts`/`b.mjs`…）；
 *   · 固件侧类（同行出现 固件|smali|apk|设备侧）不参与本仓存在性判定——那些锚点在厂商固件里，
 *     判「不存在」是拿我们的目录树去要求别人的产物。
 *
 * 存量死锚点走 `scripts/ledger-stale-anchors.json` **带理由显式登记**（`maxEntries` 只减不增）：
 * 它们是文档债而不是规则漏洞，修一处删一条；不在册的新死锚点直接红。
 *
 * 用法：
 *   node scripts/check-ledger-status-liveness.mjs            # 判据
 *   node scripts/check-ledger-status-liveness.mjs --report   # 只量统计与命中，不改退出码
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-ledger-status-liveness]';

const DOC_FILES = [
  'docs/JIANGYI_INTERFACE_CHECKLIST.md',
  'docs/AUDIT_STRUCTURAL_BACKLOG_2026-11-12.md',
  'docs/AUDIT_FOUR_END_R20_2026-11-12.md',
  'docs/AUDIT_FOUR_END_R22_2026-11-12.md',
  'docs/BUSINESS_FULL_TEST_MATRIX.md'
];
const STALE_REGISTRY_REL = 'scripts/ledger-stale-anchors.json';

const PRESENT = /(✅|已接入|已接|已实现|已落地|已补接|已登记)/;
const ABSENT = /(❌|未接|未实现|未做)/;
const FIRMWARE_HINT = /(固件|smali|apk|设备侧|活体)/i;
const BACKTICK = /`([^`\n]+)`/g;
const JAVA_FILE = /([A-Za-z0-9_.-]+)\.java(?:[:#](\d+))?/;
const HTTP_PATH = /^\/[A-Za-z0-9][\w./{}-]*\/[\w./{}-]*$/;
const EXT_LIKE = /\.[a-z]{2,4}$/i;

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

let javaIndex = null;
function buildJavaIndex() {
  if (javaIndex) return javaIndex;
  javaIndex = new Map();
  const walk = (dir) => {
    for (const e of readdirSync(dir, { withFileTypes: true })) {
      if (['node_modules', 'target', 'dist', '.git', '.tmp', 'build'].includes(e.name)) continue;
      const full = join(dir, e.name);
      if (e.isDirectory()) walk(full);
      else if (e.name.endsWith('.java')) {
        const rel = relative(ROOT, full).replace(/\\/g, '/');
        for (const k of [e.name, e.name.replace(/\.java$/, '')]) {
          if (!javaIndex.has(k)) javaIndex.set(k, []);
          javaIndex.get(k).push(rel);
        }
      }
    }
  };
  for (const top of ['services', 'clients', 'edge', 'packages', 'infra', 'scripts']) {
    if (existsSync(join(ROOT, top))) walk(join(ROOT, top));
  }
  return javaIndex;
}

const fileCache = new Map();
function text(relPath) {
  if (!fileCache.has(relPath)) {
    fileCache.set(
      relPath,
      readFileSync(join(ROOT, relPath), 'utf8').split(String.fromCharCode(10))
    );
  }
  return fileCache.get(relPath);
}

/** 一行的「能力键」：HTTP 路径键 + 代码锚点键（含成员名与可选行号）。 */
function keysOf(line) {
  const keys = [];
  for (const m of line.matchAll(BACKTICK)) {
    const t = m[1].trim();
    if (HTTP_PATH.test(t)) keys.push({ kind: 'path', id: t });
    const j = JAVA_FILE.exec(t);
    if (j)
      keys.push({
        kind: 'java',
        id: j[1],
        file: `${j[1]}.java`,
        lineNo: j[2] ? Number(j[2]) : null,
        raw: t
      });
    else if (
      /\.[a-z][A-Za-z0-9_]*$/.test(t) &&
      !EXT_LIKE.test(t) &&
      /^[A-Z][A-Za-z0-9]+\.[a-z][A-Za-z0-9_]*$/.test(t)
    ) {
      const dot = t.lastIndexOf('.');
      keys.push({
        kind: 'member',
        id: t.slice(0, dot),
        cls: t.slice(0, dot),
        member: t.slice(dot + 1),
        raw: t
      });
    }
  }
  return keys;
}

function anchorDeath(k) {
  const idx = buildJavaIndex();
  const paths = idx.get(k.kind === 'java' ? k.file : k.cls) || [];
  if (paths.length === 0) {
    return `本仓找不到 ${k.kind === 'java' ? k.file : `类 ${k.cls}`}（锚点已死或从未存在）`;
  }
  if (k.kind === 'java' && k.lineNo != null) {
    const within = paths.some((p) => text(p).length >= k.lineNo);
    if (!within) return `${k.raw}：同名文件最大行数仍小于 ${k.lineNo}（锚点行号已漂移）`;
  }
  if (k.kind === 'member') {
    const hit = paths.some((p) => text(p).some((l) => l.includes(k.member)));
    if (!hit) return `${k.raw}：类文件里查不到成员名 ${k.member}`;
  }
  return null;
}

/** 表格行取最后一格（状态列）；非表格行取整行。 */
function statusCell(rawLine) {
  if (!rawLine.trimStart().startsWith('|')) return rawLine;
  const cells = rawLine.split('|').filter((c) => c.trim() !== '');
  return cells.length > 0 ? cells[cells.length - 1] : rawLine;
}

/**
 * 状态列里「**对端**没做」的分句不参与我方状态判定。
 * 实测形状（§4.2.13 行）：同一格既写「已实现（我方下发端）」又写「设备侧未实现该可选接口」——
 * 这是两侧陈述而不是自相矛盾；把它当矛盾会把台账逼成「只能说一边」。
 * 判据：分句（按 ；。 切）里出现 设备侧|固件|厂商|对端|云端|活体 等对方主语时，整句摘掉。
 */
const COUNTERPARTY = /(设备侧|固件|厂商|对端|云侧|云端|活体|apk)/i;
function ourSideStatus(cell) {
  return cell
    .split(/[；。]/)
    .filter((clause) => !COUNTERPARTY.test(clause))
    .join('；');
}

function clean(line) {
  return line
    .replace(/~~[^~]*~~/g, ' ')
    .replace(/「[^」]*」/g, ' ')
    .replace(/『[^』]*』/g, ' ');
}

function loadStaleRegistry() {
  const p = join(ROOT, STALE_REGISTRY_REL);
  if (!existsSync(p)) {
    return { maxEntries: 0, anchors: [] };
  }
  const raw = readFileSync(p, 'utf8').replace(/^﻿/, '');
  const d = JSON.parse(raw);
  return { maxEntries: d.maxEntries, anchors: d.anchors || [] };
}

export function evaluate(docs, readDoc, registry) {
  const problems = [];
  const stats = { lines: 0, present: 0, absent: 0, firmwareSkipped: 0, staleAllowed: 0 };
  const allow = new Set(registry.anchors.map((a) => `${a.doc}|${a.anchor}`));
  for (const rel of docs) {
    const raw = readDoc(rel);
    if (raw == null) {
      problems.push(`${rel}: 纳入对账的台账文件读不到（清单本身过期也要红）`);
      continue;
    }
    const byKey = new Map();
    raw.split(String.fromCharCode(10)).forEach((rawLine, i) => {
      const n = i + 1;
      const line = clean(rawLine);
      if (!/[\u4e00-\u9fa5]/.test(line)) return;
      const keys = keysOf(line);
      if (keys.length === 0) return;
      stats.lines++;
      const cell = ourSideStatus(clean(statusCell(line)));
      const present = PRESENT.test(cell);
      const absent = ABSENT.test(cell);
      if (present && absent) {
        problems.push(
          `${rel}:${n}: 状态格里同时出现「已做」与「未做」判据词——读者无法判风险是否闭合`
        );
        return;
      }
      if (present) stats.present++;
      if (absent) stats.absent++;
      if (present && absent) return;
      for (const k of keys) {
        if (present && !absent && (k.kind === 'java' || k.kind === 'member')) {
          if (FIRMWARE_HINT.test(line) && k.kind === 'member') {
            stats.firmwareSkipped++;
            continue;
          }
          const why = anchorDeath(k);
          if (why) {
            if (allow.has(`${rel}|${k.raw || k.id}`)) {
              stats.staleAllowed++;
            } else {
              problems.push(`${rel}:${n}: 以「已做」语气引用锚点，但 ${why}`);
            }
          }
        }
        // 能力键只认「端点路径」与「Class.member」——`X.java` 是证据而非身份：
        // 一个 Controller 服务十几个端点，按类名聚合会把不同端点的状态误判成互相矛盾。
        if (present !== absent && (k.kind === 'path' || k.kind === 'member')) {
          const id = k.id;
          const b = byKey.get(id) || { present: [], absent: [] };
          (present ? b.present : b.absent).push(n);
          byKey.set(id, b);
        }
      }
    });
    for (const [id, b] of byKey) {
      if (b.present.length > 0 && b.absent.length > 0) {
        problems.push(
          `${rel}: 能力键「${id}」既被标已做（行 ${b.present.slice(0, 3).join(',')}）` +
            `又被标未做（行 ${b.absent.slice(0, 3).join(',')}）——同文件两处状态必须收敛成一处`
        );
      }
    }
  }
  if (registry.anchors.length > registry.maxEntries) {
    problems.push(
      `${STALE_REGISTRY_REL}: 登记 ${registry.anchors.length} 条 > maxEntries ${registry.maxEntries}（豁免只许减不许增）`
    );
  }
  for (const a of registry.anchors) {
    const raw = readDoc(a.doc);
    if (raw == null || !raw.includes(a.anchor)) {
      problems.push(
        `${STALE_REGISTRY_REL}: 陈旧锚点「${a.anchor}」已不在 ${a.doc} 里 ⇒ 条目必须销掉（留着的豁免就是免检区）`
      );
    }
  }
  return { problems, stats };
}

function main() {
  const reportOnly = process.argv.includes('--report');
  const missing = DOC_FILES.filter((f) => !existsSync(join(ROOT, f)));
  if (missing.length > 0 && !reportOnly) fail(`台账清单里的文件缺失：${missing.join(', ')}`);
  const registry = loadStaleRegistry();
  const { problems, stats } = evaluate(
    DOC_FILES,
    (rel) => (existsSync(join(ROOT, rel)) ? readFileSync(join(ROOT, rel), 'utf8') : null),
    registry
  );
  if (reportOnly) {
    console.log(
      `${TAG} REPORT（不看退出码）：带锚点状态行 ${stats.lines} / 已做 ${stats.present} / 未做 ${stats.absent}`
    );
    console.log(
      `  固件侧跳过 ${stats.firmwareSkipped}、在册陈旧锚点 ${stats.staleAllowed}、违规 ${problems.length}`
    );
    for (const p of problems.slice(0, 40)) console.log(`  · ${p}`);
    if (problems.length > 40) console.log(`  …共 ${problems.length} 条（截前 40）`);
    return;
  }
  if (problems.length > 0) {
    console.error(
      `${TAG} 共 ${problems.length} 项：\n  ${problems.slice(0, 30).join('\n  ')}` +
        `${problems.length > 30 ? `\n  …共 ${problems.length} 项` : ''}\n` +
        '修法：把状态改成与现行代码一致（并带上现码核对到的类/方法），或补落地回执；' +
        `确属文档债暂修不动的，进 ${STALE_REGISTRY_REL} 带理由登记（maxEntries 只减不增）。`
    );
    process.exit(1);
  }
  console.log(
    `${TAG} OK：${DOC_FILES.length} 份台账 ${stats.lines} 行状态锚点对账通过` +
      `（已做 ${stats.present} / 未做 ${stats.absent}；固件侧跳过 ${stats.firmwareSkipped}、在册陈旧 ${stats.staleAllowed}）`
  );
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  main();
}
