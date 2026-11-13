#!/usr/bin/env node
/**
 * `flyway-drift-check.mjs` 的负向夹具（S8③/S8④）。
 *
 * 判据类工具必须能红：这里逐条钉「去 BOM / 丢行尾 / DELETE 覆盖 / 空输入 fail-closed」，
 * 并钉一条**来自真库的向量**（V458 在 dev `flyway_schema_history` 里的 checksum），
 * 这样将来谁改了算法或改了 V458，本夹具会红着要求一起复核——不是无声漂移。
 *
 *   node scripts/flyway-drift-check.test.mjs
 */
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  effectiveApplied,
  evaluate,
  flywayChecksum,
  parseApplied,
  scanMigrations
} from './flyway-drift-check.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[flyway-drift-check.test]';
let checks = 0;
function assert(cond, message) {
  checks++;
  if (!cond) throw new Error(`${TAG} 断言失败：${message}`);
}
function assertThrows(fn, message) {
  checks++;
  try {
    fn();
  } catch {
    return;
  }
  throw new Error(`${TAG} 断言失败（期望抛错却没抛）：${message}`);
}

const dirs = [];
function tmpWith(files) {
  const d = mkdtempSync(join(tmpdir(), 'drift-'));
  const rel = 'db/migration';
  mkdirSync(join(d, rel), { recursive: true });
  for (const [name, text] of Object.entries(files)) writeFileSync(join(d, rel, name), text, 'utf8');
  dirs.push(d);
  return { root: d, locs: [rel] };
}

try {
  // 1) 算法本体：Flyway 是「去 BOM + 按行 readLine（丢行尾）」后逐行 CRC32
  const plain = 'ALTER TABLE t ADD COLUMN c int;\n';
  assert(
    flywayChecksum(plain) === flywayChecksum(plain.replace(/\n$/, '')),
    '结尾换行不得影响 checksum'
  );
  assert(
    flywayChecksum('﻿' + plain) === flywayChecksum(plain),
    'BOM 必须剥掉（Flyway 用 BOMInputStream；不剥就是 15 个早期迁移全体误报）'
  );
  assert(
    flywayChecksum('a\r\nb\r\n') === flywayChecksum('a\nb\n'),
    'CRLF 与 LF 必须同值（readLine 丢行尾）'
  );
  // readLine() 语义：末行有没有换行产出同样两行 ⇒ 必须同值；真的多一个空行才是多一行输入
  assert(flywayChecksum('a\nb') === flywayChecksum('a\nb\n'), '末行换行的有无不得影响 checksum');
  assert(
    flywayChecksum('a\nb\n') === flywayChecksum('a\nb\n\n'),
    '纯空行喂给 CRC32 是 0 字节 ⇒ 不改变 checksum（Flyway 同构）。' +
      '推论：本工具与 Flyway 一样看不见"只加空行"的改动，所以别拿"checksum 没变"当"内容没变"的证据'
  );
  assert(
    flywayChecksum('a\nb\n') !== flywayChecksum('a\nB\n'),
    '但任何非空字符差异必须可辨（否则整个判据失效）'
  );

  // 2) 真库向量：V458 在 dev flyway_schema_history 里的实际值（2026-11-13 采集）
  const v458 = resolve(
    ROOT,
    'services/trade-service/src/main/resources/db/migration/V458__dispute_created_template.sql'
  );
  if (existsSync(v458)) {
    const real = flywayChecksum(readFileSync(v458, 'utf8'));
    assert(
      real === 1815510941,
      `V458 算法向量变了（${real} ≠ 1815510941）：要么 Flyway 算法理解错、要么该迁移被改过，` +
        '两者都必须回头复核本工具，不能让部署带着错判据过'
    );
  }

  // 3) 判据：一致=绿，不一致=点名红
  const { root, locs } = tmpWith({ 'V900__a.sql': 'SELECT 1;\n' });
  const scanned = scanMigrations(root, locs);
  const good = `1|900|SQL|${scanned.byVersion.get('900').checksum}`;
  assert(evaluate(parseApplied(good), scanned).problems.length === 0, 'checksum 相等应判无漂移');
  const bad = evaluate(parseApplied('1|900|SQL|12345'), scanned);
  assert(
    bad.problems.length === 1 && bad.problems[0].includes('V900 checksum 漂移'),
    `库里值不等必须点名：${bad.problems.join('|')}`
  );

  // 4) 本地缺文件：不能因为"看不见"就当无漂移
  const missing = evaluate(parseApplied('1|901|SQL|7'), scanned);
  assert(
    missing.problems.length === 1 && missing.problems[0].includes('本地迁移文件缺失'),
    `已应用但本地缺失必须红：${missing.problems.join('|')}`
  );

  // 5) repair 的 DELETE 追写行必须覆盖前一条（折叠语义），否则退役记录会被误判成漂移
  const rows = parseApplied(['1|900|SQL|111', '2|900|DELETE|'].join('\n'));
  assert(effectiveApplied(rows).length === 0, '同版本后写 DELETE ⇒ 该版本不再算已应用');
  const stillThere = parseApplied(['2|900|DELETE|', '3|900|SQL|111'].join('\n'));
  assert(
    effectiveApplied(stillThere).length === 1,
    'DELETE 之后再重新应用 ⇒ 必须重新计入（按 rank 顺序折叠）'
  );

  // 6) 空输入 / 脏输入 / 目录缺失 一律 throw（main 里映射成 exit 2 = fail-closed）
  assertThrows(() => parseApplied(''), '空导出不得当成"无漂移"');
  assertThrows(() => parseApplied('not-a-row'), '格式错误的行必须抛');
  assertThrows(() => parseApplied('x|1|SQL|1'), 'installed_rank 非数字必须抛');
  assertThrows(() => scanMigrations(root, ['db/nope']), '迁移目录缺失必须抛（采集面不完整）');
  // 版本列为空 = R__ 可重复迁移，不参与"启动被拒"判定
  assert(
    effectiveApplied(parseApplied('9|\\N|SQL|1')).length === 0,
    'R__ 行（version 为空）不得进入判定集'
  );

  console.log(`${TAG} OK：${checks} 项断言全过`);
} finally {
  for (const d of dirs) rmSync(d, { recursive: true, force: true });
}
