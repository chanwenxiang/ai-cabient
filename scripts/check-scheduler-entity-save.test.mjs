#!/usr/bin/env node
/**
 * `check-scheduler-entity-save.mjs` 的自测：新建实体识别 + 棘轮四方向 + 锚点 + 作用域。
 *
 * 判据的核心是「save 的参数是不是本方法新建的实体」：识别错了，要么放过读改写（假绿），
 * 要么把新建日志行也装进棘轮（假红）。所以这里同时钉 fresh/非 fresh 两向，
 * 以及非 Scheduler 类不扫、*Job 也扫。
 *
 *   node scripts/check-scheduler-entity-save.test.mjs
 */
import { existsSync, mkdirSync, mkdtempSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  checkProject,
  isFreshEntity,
  lastAssignmentBefore,
  parseClass
} from './check-scheduler-entity-save.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-scheduler-entity-save.test]';
const MIN = 1;
let checks = 0;

function assert(cond, message) {
  checks++;
  if (!cond) throw new Error(`断言失败：${message}`);
}

function sourceOf({ className = 'DemoTaskScheduler', body }) {
  return [
    'package demo;',
    `public class ${className} {`,
    '    private Repo repo;',
    '    public void run(Foo parameter) {',
    body,
    '    }',
    '    public void touch(Foo other) {',
    '        repo.updateById(other);',
    '    }',
    '}'
  ].join('\n');
}

const DEFAULT_BODY = [
  '        Foo fresh = new Foo();',
  '        repo.save(fresh);',
  '        Foo fromRepo = repo.findById(1L);',
  '        repo.save(fromRepo);'
].join('\n');

function writeFixture(
  root,
  { className = 'DemoTaskScheduler', body = DEFAULT_BODY, allowlist, extraClass } = {}
) {
  rmSync(root, { recursive: true, force: true });
  const javaDir = join(root, 'services', 'demo', 'src', 'main', 'java', 'demo');
  mkdirSync(javaDir, { recursive: true });
  writeFileSync(join(javaDir, `${className}.java`), `${sourceOf({ className, body })}\n`);
  if (extraClass) {
    writeFileSync(
      join(javaDir, `${extraClass.name}.java`),
      `${sourceOf({ className: extraClass.name, body: extraClass.body })}\n`
    );
  }
  const allowDir = join(root, 'scripts');
  mkdirSync(allowDir, { recursive: true });
  if (allowlist !== undefined) {
    writeFileSync(
      join(allowDir, 'scheduler-entity-save-allowlist.json'),
      JSON.stringify(allowlist, null, 2)
    );
  }
}

const KEY = 'services/demo/src/main/java/demo/DemoTaskScheduler.java#run';
const TOUCH = 'services/demo/src/main/java/demo/DemoTaskScheduler.java#touch';
const reason = 'fixture：读改写待列级更新';

function run(root, options = {}) {
  return checkProject(root, { minClasses: MIN, ...options });
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
  const body = [
    'Foo fresh = new Foo();',
    'Foo built = Foo.builder().id(1).build();',
    'Foo fromRepo = repo.findById(1L);',
    'Foo x = new Foo();',
    'x = repo.findById(2L);'
  ].join('\n');
  const saveIdx = body.indexOf('repo.save(fresh)');
  assert(isFreshEntity(body, 'fresh', body.length), 'new 出来的变量应判 fresh');
  assert(isFreshEntity(body, 'built', body.length), 'builder() 应判 fresh');
  assert(!isFreshEntity(body, 'fromRepo', body.length), 'findById 赋值的变量不应判 fresh');
  assert(
    !isFreshEntity(body, 'x', body.indexOf('x = repo.findById(2L)') + 30),
    '被仓储结果重新赋值后不应判 fresh'
  );
  assert(isFreshEntity(body, 'new Foo()', saveIdx), '内联 new 应判 fresh');
  assert(!isFreshEntity(body, 'parameter', saveIdx), '方法参数不应判 fresh');
  assert(lastAssignmentBefore(body, 'x', body.length).includes('findById'), '应取最近一次赋值');

  const parsed = parseClass(sourceOf({ body: DEFAULT_BODY }));
  assert(parsed.className === 'DemoTaskScheduler', '类名解析');
  assert(
    parsed.methods.map((m) => m.name).join(',') === 'run,touch',
    `方法解析应为 run,touch，实际 ${parsed.methods.map((m) => m.name).join(',')}`
  );
}

function main() {
  parserChecks();

  const tmpBase = join(ROOT, '.tmp');
  mkdirSync(tmpBase, { recursive: true });
  const fixture = mkdtempSync(join(tmpBase, 'scheduler-entity-save-'));

  try {
    // 1) 绿：新建实体 save 不计，读改写两处已登记
    writeFixture(fixture, {
      allowlist: {
        [KEY]: { count: 1, reason },
        [TOUCH]: { count: 1, reason }
      }
    });
    const green = expectOk(fixture, '合规 fixture');
    assert(green.hits.length === 2, `应只算 2 处读改写（新建实体不计），实际 ${green.hits.length}`);
    assert(
      green.hits.every((h) => h.args !== 'fresh'),
      '新建实体的 save 不应入账'
    );

    // 2) 未登记的新增读改写
    writeFixture(fixture, { allowlist: { [TOUCH]: { count: 1, reason } } });
    expectFinding(fixture, '整实体回写', '未登记读改写');

    // 3) 同一方法多一处
    writeFixture(fixture, {
      body: [
        '        Foo fromRepo = repo.findById(1L);',
        '        repo.save(fromRepo);',
        '        repo.save(fromRepo);'
      ].join('\n'),
      allowlist: {
        [KEY]: { count: 1, reason },
        [TOUCH]: { count: 1, reason }
      }
    });
    expectFinding(fixture, '同一方法又新增了整实体回写', 'count 超登记');

    // 4) 已改好但没下调
    writeFixture(fixture, {
      allowlist: {
        [KEY]: { count: 2, reason },
        [TOUCH]: { count: 1, reason }
      }
    });
    expectFinding(fixture, '已改好请把 count 下调', 'count 未下调');

    // 5) 条目失效（方法里只剩新建实体 save）
    writeFixture(fixture, {
      body: '        Foo fresh = new Foo();\n        repo.save(fresh);',
      allowlist: {
        [KEY]: { count: 1, reason },
        [TOUCH]: { count: 1, reason }
      }
    });
    expectFinding(fixture, '已失效', '条目失效');

    // 6) 空理由 / 非法 count / 清单缺失
    writeFixture(fixture, {
      allowlist: {
        [KEY]: { count: 1, reason: '  ' },
        [TOUCH]: { count: 1, reason }
      }
    });
    expectFinding(fixture, '缺少理由', '空理由');
    writeFixture(fixture, {
      allowlist: {
        [KEY]: { count: 0, reason },
        [TOUCH]: { count: 1, reason }
      }
    });
    expectFinding(fixture, 'count 必须是 ≥1 的整数', '非法 count');
    writeFixture(fixture, {});
    expectFinding(fixture, '不存在 —— 棘轮清单必须随代码一起提交', '清单缺失');

    // 7) 锚点
    writeFixture(fixture, {
      allowlist: {
        [KEY]: { count: 1, reason },
        [TOUCH]: { count: 1, reason }
      }
    });
    expectFinding(fixture, '锚点失效', '类数锚点', { minClasses: 99 });

    // 8) 非 Scheduler/Job 类不扫
    writeFixture(fixture, {
      className: 'DemoService',
      allowlist: {}
    });
    // 这条 fixture 里没有任何 Scheduler/Job，类数锚点必然红；把下限压到 0 单独验作用域。
    const ignored = run(fixture, { minClasses: 0 });
    assert(ignored.ok, `非 Scheduler 类应忽略，实际 ${JSON.stringify(ignored.findings)}`);
    assert(ignored.schedulerClasses === 0, '非 Scheduler 类不应计入锚点');
    assert(ignored.hits.length === 0, `非 Scheduler 类不应产生命中，实际 ${ignored.hits.length}`);

    // 9) *Job 类是作用域内
    writeFixture(fixture, {
      className: 'DemoTaskJob',
      allowlist: {}
    });
    expectFinding(fixture, '整实体回写', 'Job 后缀应扫描');

    // 10) 真仓：10 处 ↔ 10 条，24 类
    const live = checkProject(ROOT);
    assert(live.ok, `真仓应绿，实际 ${JSON.stringify(live.findings)}`);
    assert(live.hits.length === 10, `真仓应有 10 处读改写，实际 ${live.hits.length}`);
    assert(live.allowlistCount === 10, `真仓应有 10 条清单，实际 ${live.allowlistCount}`);
    assert(
      live.schedulerClasses >= 20,
      `真仓 Scheduler/Job 类数 ${live.schedulerClasses} 低于锚点`
    );
  } finally {
    rmSync(fixture, { recursive: true, force: true });
  }

  const leftovers = existsSync(tmpBase)
    ? readdirSync(tmpBase).filter((n) => n.startsWith('scheduler-entity-save-'))
    : [];
  assert(leftovers.length === 0, `临时目录未清理：${leftovers.join(',')}`);

  console.log(
    `${TAG} OK（${checks} 项：fresh 识别 / 棘轮四方向 / 作用域 / 锚点 / 真仓，且不残留临时目录）`
  );
}

main();
