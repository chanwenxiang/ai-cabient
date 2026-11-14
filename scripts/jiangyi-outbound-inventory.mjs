#!/usr/bin/env node
/**
 * 将邑「我方→厂商云」出向接口清单现算门禁（T-JY-DOC-9）。
 *
 * 为什么要现算：台账里那行 `V1.13 34 条唯一路径 / 我方 16 条 / 20 条未接` 是 2026-10-22 一次性手算的，
 * 之后没人复算 ⇒ 同一份文档 §5.5/§5.6 的计数与明细行互相矛盾，我当天三次把它当现状读，
 * 其中一次直接推出「租机要先在厂商云建租户」这种假架构分叉（前提早被用户否掉）。
 * 数字只要不现算就一定会腐烂，所以把它做成门禁：文档侧、代码侧、登记表三方对账。
 *
 * 🔴 文档侧为什么**不**做成判据（本轮实测，别误以为覆盖了两边）：厂商原文抽取文本里的 URL 被
 *   PDF 换行/空格切断（`https:/` `…/merchant/stdSku/` 只剩父段）且带 OCR 形变（`deviceInfo`→`devicelnfo`），
 *   现算出来的「文档侧唯一路径」只有 13 条、且大半是残段 ⇒ 拿它当门禁只会造出**假绿或长红**。
 *   判据因此只跑「代码 ↔ 登记册」双向；文档侧计数写在 `countersAsOf`（人工现算 + 日期 + 口径），
 *   缺口本体由 `status: absent` 显式登记并带理由。抽取质量要到能门禁化，先做 T-JY-DOC-8（原文入库）。
 *
 * 四条判据（都能红）：
 *   A1 代码里有、登记册没有 ⇒ 红（新增出向却没人对账）。
 *   A2 登记册标 `implemented` 但代码里已经搜不到该路径 ⇒ 红（“说接了、代码却不在”，最危险的一类）。
 *   A3 登记册标 `absent` 但代码里能搜到 ⇒ 红（接完了没改状态，台账继续把人往旧缺口上带）。
 *   另有计数棘轮：`absentMaxOnlyDecreases` —— 未接条目数只许减不许增，且不许超过 baseline.maxAbsent。
 *
 * OCR 坑（实测踩到过）：厂商 PDF 里 `deviceInfo` 被抽成 `devicelnfo`、`getIdentifier` 抽成 `getldentifier`
 * （大写 I → 小写 l）。两侧同规则归一：小写后把 `l` 当 `i` 再比 ⇒ `devicelnfo`/`deviceInfo` 归一到同一个键。
 * 归一只用于配对，**打印时一律用原始字面量**，避免把「相似但不同」的两条并成一条却看不出来。
 *
 * 用法：
 *   node scripts/jiangyi-outbound-inventory.mjs            # 对账（CI/聚合链）
 *   node scripts/jiangyi-outbound-inventory.mjs --report   # 只看现算清单，不判退出码（建/改登记册时用）
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[jiangyi-outbound-inventory]';
const REGISTRY_REL = 'scripts/jiangyi-outbound-registry.json';
const CLIENT_DIR = join(ROOT, 'services/trade-service/src/main/java/com/aicabinet/trade/client');

/** 出向只算这两族：`/merchant/*`（商户侧）与 `/platform/*`（平台侧）。 */
const OUTBOUND = /\/(merchant|platform)\/[A-Za-z0-9_./-]+/g;

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

export function canon(p) {
  return p.toLowerCase().replace(/l/g, 'i').replace(/\/+/g, '/');
}

/** 代码侧：client 包内字符串字面量里的出向路径（键=canon，值=原始字面量；来源名单独存 src Map）。 */
export function pathsFromCode(sources, srcOut) {
  const out = new Map();
  for (const { name, text } of sources) {
    for (const m of String(text).matchAll(OUTBOUND)) {
      const raw = m[0].replace(/[.,;)"'`]+$/, '');
      if (raw.length <= '/merchant/'.length + 1 || raw.endsWith('/')) continue; // 拼接口径的父段，不是完整端点
      const key = canon(raw);
      if (!out.has(key)) out.set(key, raw);
      if (srcOut) srcOut.set(key, name);
    }
  }
  return out;
}

function readClientSources() {
  if (!existsSync(CLIENT_DIR))
    fail(`找不到出向客户端目录 ${relative(ROOT, CLIENT_DIR)}（fail-closed）`);
  const files = [];
  const walk = (d) => {
    for (const e of readdirSync(d, { withFileTypes: true })) {
      const full = join(d, e.name);
      if (e.isDirectory()) walk(full);
      else if (e.name.endsWith('.java'))
        files.push({ name: e.name, text: readFileSync(full, 'utf8') });
    }
  };
  walk(CLIENT_DIR);
  return files;
}

export function evaluate(reg, sources, srcOut) {
  const problems = [];
  srcOut = srcOut || new Map();
  const code = pathsFromCode(sources, srcOut);
  const regKeys = new Map((reg.entries || []).map((e) => [canon(e.path), e]));

  for (const e of reg.entries || []) {
    const k = canon(e.path);
    const inCode = code.has(k);
    if (e.status === 'implemented' && !inCode) {
      problems.push(`登记为 implemented 但 client 代码里搜不到该路径：${e.path}`);
    }
    if (e.status === 'absent' && inCode) {
      problems.push(
        `登记为 absent 但代码已在调用：${e.path}（出处 ${code.get(k)}）⇒ 缺口已补，状态必须改`
      );
    }
    if (e.status !== 'implemented' && e.status !== 'absent' && e.status !== 'vendor-side') {
      problems.push(`非法 status「${e.status}」：${e.path}（只许 implemented|absent|vendor-side）`);
    }
    if (e.status === 'vendor-side' && (!e.why || e.why.trim().length < 12)) {
      problems.push(`vendor-side（明确不接）必须写理由 ≥12 字：${e.path}`);
    }
    if (e.status === 'absent' && (!e.why || e.why.trim().length < 6)) {
      problems.push(`absent 必须写清缺什么（≥6 字），否则就是无声欠账：${e.path}`);
    }
    if (e.status !== 'implemented' && !e.section) {
      problems.push(
        `缺口条目「${e.path}」缺 section 锚点 ⇒ 无法回溯厂商原文。` +
          '不确定是哪一节就先去 T-JY-DOC-8（现行采集文档原文入库）核，不要凭印象编号。'
      );
    }
    if (e.status === 'implemented' && !(e.code || []).length) {
      problems.push(`已接条目「${e.path}」缺 code 锚点（哪个 client 类的哪个方法）`);
    }
  }

  const unregistered = [...code.keys()].filter((k) => !regKeys.has(k));
  for (const k of unregistered) {
    problems.push(
      `代码里新出现、登记册没有的出向路径：${code.get(k)}（出处 ${srcOut.get(k) || '?'}）`
    );
  }

  const absent = (reg.entries || []).filter((e) => e.status === 'absent').length;
  const max = Number(reg.maxAbsent);
  if (!Number.isInteger(max) || max < 0) problems.push('maxAbsent 必须是非负整数（未接条目上限）');
  else if (absent > max) {
    problems.push(
      `未接条目 ${absent} > maxAbsent ${max}：棘轮只许减不许增。` +
        '若确是厂商文档新增条目，先补文档侧证据再一次性上调并写理由。'
    );
  }
  const implemented = (reg.entries || []).filter((e) => e.status === 'implemented').length;
  return { problems, absent, implemented, codeTotal: code.size, unregistered: unregistered.length };
}

function main() {
  const reportOnly = process.argv.includes('--report');
  const regPath = join(ROOT, REGISTRY_REL);
  if (!existsSync(regPath)) fail(`缺少登记册 ${REGISTRY_REL}（先跑 --report 生成清单再登记）`);
  const reg = JSON.parse(readFileSync(regPath, 'utf8').replace(/^﻿/, ''));
  const srcOut = new Map();
  const { problems, absent, implemented, codeTotal } = evaluate(reg, readClientSources(), srcOut);
  const line =
    `代码侧出向 ${codeTotal}（${reg.countersAsOf || '缺 countersAsOf'}）；登记：已接 ${implemented} / 未接 ${absent} / ` +
    `明确不接 ${(reg.entries || []).filter((e) => e.status === 'vendor-side').length}（上限 ${reg.maxAbsent}）`;
  if (reportOnly) {
    console.log(`${TAG} REPORT（不看退出码）：${line}`);
    for (const e of reg.entries || [])
      console.log(`  · [${e.status}] ${e.path}${e.why ? ' — ' + e.why.slice(0, 60) : ''}`);
    for (const p of problems) console.log(`  ⚠ ${p}`);
    return;
  }
  if (problems.length > 0) {
    fail(`${problems.length} 项：\n  ${problems.slice(0, 25).join('\n  ')}`);
  }
  console.log(`${TAG} OK：${line}`);
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  main();
}
