#!/usr/bin/env node
/**
 * `check-inmemory-paging.mjs` 的自测：方法解析 + 棘轮四个方向（新增/加处/减少/失效）+ 锚点。
 *
 * 棘轮门禁最危险的是「清单和实际一起失真」：解析器少算一处、清单也多一处，看起来仍然绿。
 * 所以这里既验证解析出的方法/行号，也逐条验证「未登记 / 多一处 / 少一处 / 条目失效 /
 * 空理由 / 非法 count / 清单缺失」都必须红，最后确认真仓 10 处 ↔ 10 条一一对应。
 *
 *   node scripts/check-inmemory-paging.test.mjs
 */
import { existsSync, mkdirSync, mkdtempSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  checkProject,
  isPageReturnType,
  parseMethods,
  stripJavaComments
} from './check-inmemory-paging.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-inmemory-paging.test]';
const MIN = 1;
let checks = 0;

function assert(cond, message) {
  checks++;
  if (!cond) throw new Error(`断言失败：${message}`);
}

function sourceOf(body) {
  return [
    'package demo;',
    'public class DemoService {',
    '    public PageResult<Row> pagedList(int page, int size) {',
    '        int from = page * size;',
    '        int to = Math.min(from + size, all.size());',
    body,
    '    }',
    '',
    '    public List<Row> topN(int n) {',
    '        return all.size() <= n ? all : all.subList(0, n);',
    '    }',
    '',
    '    public PageDto pagedDto(int page, int size) {',
    '        return new PageDto(all.subList(page * size, Math.min(page * size + size, all.size())), page, size);',
    '    }',
    '}'
  ].join('\n');
}

function writeFixture(
  root,
  {
    body = '        return new PageResult<>(all.subList(from, to), page, size, all.size());',
    allowlist
  }
) {
  rmSync(root, { recursive: true, force: true });
  const javaDir = join(root, 'services', 'demo', 'src', 'main', 'java', 'demo');
  mkdirSync(javaDir, { recursive: true });
  writeFileSync(join(javaDir, 'DemoService.java'), `${sourceOf(body)}\n`);
  const allowDir = join(root, 'scripts');
  mkdirSync(allowDir, { recursive: true });
  if (allowlist !== undefined) {
    writeFileSync(
      join(allowDir, 'inmemory-paging-allowlist.json'),
      JSON.stringify(allowlist, null, 2)
    );
  }
}

const KEY = 'services/demo/src/main/java/demo/DemoService.java#pagedList';
const DTO_KEY = 'services/demo/src/main/java/demo/DemoService.java#pagedDto';
const reason = 'fixture：源查询无界，待下推';

function run(root, options = {}) {
  return checkProject(root, { minPageMethods: MIN, ...options });
}

function expectOk(root, message) {
  const r = run(root);
  assert(r.ok, `${message}：期望绿，实际 ${JSON.stringify(r.findings)}`);
  return r;
}

function expectFinding(root, needle, message, options) {
  const r = run(root, options);
  assert(!r.ok, `${message}：期望红，实际绿`);
  assert(
    r.findings.some((f) => f.includes(needle)),
    `${message}：期望命中「${needle}」，实际 ${JSON.stringify(r.findings)}`
  );
  return r;
}

function parserChecks() {
  assert(isPageReturnType('PageResult<FundDailyBillDto>'), 'PageResult<T> 应识别为分页');
  assert(isPageReturnType('SalesReportPageDto'), '*PageDto 应识别为分页');
  assert(!isPageReturnType('List<PageDto>'), 'List<PageDto> 不是分页返回');
  assert(!isPageReturnType('PageResult'), '裸 PageResult 不是分页（无泛型）');

  const masked = stripJavaComments('String s = "http://x/{y}"; // c\nint a;');
  assert(masked.includes('http://x/{y}'), '字符串里的 // 与花括号不能被破坏');
  assert(!masked.includes('// c'), '行注释应清除');

  const { methods } = parseMethods(sourceOf('        return null;'));
  const paged = methods.find((m) => m.name === 'pagedList');
  const dto = methods.find((m) => m.name === 'pagedDto');
  const top = methods.find((m) => m.name === 'topN');
  assert(paged && paged.returnType === 'PageResult<Row>', '应解析出 PageResult 方法与返回类型');
  assert(dto && dto.returnType === 'PageDto', '应解析出 PageDto 方法');
  assert(top && top.returnType === 'List<Row>', '应解析出普通 List 方法');
  assert(paged.line < dto.line && paged.start < dto.start, '方法区间应按出现顺序');
}

function main() {
  parserChecks();

  const tmpBase = join(ROOT, '.tmp');
  mkdirSync(tmpBase, { recursive: true });
  const fixture = mkdtempSync(join(tmpBase, 'inmemory-paging-'));

  try {
    // 1) 绿：两处分页都登记，Top-N 不计
    writeFixture(fixture, {
      allowlist: {
        [KEY]: { count: 1, reason },
        [DTO_KEY]: { count: 1, reason }
      }
    });
    const green = expectOk(fixture, '合规 fixture');
    assert(green.hits.length === 2, `应只算 2 处内存分页（Top-N 不计），实际 ${green.hits.length}`);
    assert(
      green.hits.every((h) => h.method !== 'topN'),
      'Top-N 截断不应入账'
    );

    // 2) 未登记的新增点
    writeFixture(fixture, { allowlist: { [DTO_KEY]: { count: 1, reason } } });
    expectFinding(fixture, '新增内存分页', '未登记新增点');

    // 3) 同一方法里加了一处
    writeFixture(fixture, {
      body: [
        '        Object a = all.subList(from, to);',
        '        return new PageResult<>(all.subList(from, to), page, size, all.size());'
      ].join('\n'),
      allowlist: {
        [KEY]: { count: 1, reason },
        [DTO_KEY]: { count: 1, reason }
      }
    });
    expectFinding(fixture, '同一方法又新增了内存分页', 'count 超登记');

    // 4) 已改好但没下调 count
    writeFixture(fixture, {
      allowlist: {
        [KEY]: { count: 2, reason },
        [DTO_KEY]: { count: 1, reason }
      }
    });
    expectFinding(fixture, '棘轮只减不增', 'count 未下调');

    // 5) 条目失效（方法已经没有 subList）
    writeFixture(fixture, {
      body: '        return new PageResult<>(List.of(), page, size, 0);',
      allowlist: {
        [KEY]: { count: 1, reason },
        [DTO_KEY]: { count: 1, reason }
      }
    });
    expectFinding(fixture, '已失效', '条目失效');

    // 6) 空理由 / 非法 count
    writeFixture(fixture, {
      allowlist: {
        [KEY]: { count: 1, reason: '   ' },
        [DTO_KEY]: { count: 1, reason }
      }
    });
    expectFinding(fixture, '缺少理由', '空理由');
    writeFixture(fixture, {
      allowlist: {
        [KEY]: { count: 0, reason },
        [DTO_KEY]: { count: 1, reason }
      }
    });
    expectFinding(fixture, 'count 必须是 ≥1 的整数', '非法 count');

    // 7) 清单整体缺失
    writeFixture(fixture, {});
    expectFinding(fixture, '不存在 —— 棘轮清单必须随代码一起提交', '清单缺失');

    // 8) 锚点：分页方法数低于下限
    writeFixture(fixture, {
      allowlist: {
        [KEY]: { count: 1, reason },
        [DTO_KEY]: { count: 1, reason }
      }
    });
    expectFinding(fixture, '锚点失效', '分页方法锚点', { minPageMethods: 99 });

    // 9) 真仓：10 处 ↔ 10 条，方法数锚点满足
    const live = checkProject(ROOT);
    assert(live.ok, `真仓应绿，实际 ${JSON.stringify(live.findings)}`);
    assert(live.hits.length === 10, `真仓应有 10 处内存分页，实际 ${live.hits.length}`);
    assert(live.allowlistCount === 10, `真仓应有 10 条清单，实际 ${live.allowlistCount}`);
    assert(live.pageMethods >= 60, `真仓分页方法数 ${live.pageMethods} 低于锚点`);
  } finally {
    rmSync(fixture, { recursive: true, force: true });
  }

  const leftovers = existsSync(tmpBase)
    ? readdirSync(tmpBase).filter((n) => n.startsWith('inmemory-paging-'))
    : [];
  assert(leftovers.length === 0, `临时目录未清理：${leftovers.join(',')}`);

  console.log(
    `${TAG} OK（${checks} 项：方法解析 / 棘轮四方向 / 空理由 / 锚点 / 真仓，且不残留临时目录）`
  );
}

main();
