#!/usr/bin/env node
/**
 * `check-target-orphans.mjs` 的自测：干净检出 / 全量对齐 / 迁移孤儿 / 嵌套 Mapper 孤儿 /
 * 非资源文件不误报 / 锚点。
 *
 *   node scripts/check-target-orphans.test.mjs
 */
import { existsSync, mkdirSync, mkdtempSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { checkProject } from './check-target-orphans.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-target-orphans.test]';
let checks = 0;

function assert(cond, message) {
  checks++;
  if (!cond) throw new Error(`断言失败：${message}`);
}

function writeFile(root, rel, content = '-- fixture\n') {
  const full = join(root, rel);
  mkdirSync(dirname(full), { recursive: true });
  writeFileSync(full, content);
}

const MIGRATION = 'services/demo/src/main/resources/db/migration/V1__init.sql';
const MAPPER = 'services/demo/src/main/resources/mapper/OrderMapper.xml';
const NESTED = 'services/demo/src/main/resources/mapper/sub/NestedMapper.xml';

function writeFixture(root) {
  rmSync(root, { recursive: true, force: true });
  writeFile(root, MIGRATION);
  writeFile(root, MAPPER);
  writeFile(root, NESTED);
  writeFile(root, 'services/demo/target/classes/db/migration/V1__init.sql');
  writeFile(root, 'services/demo/target/classes/mapper/OrderMapper.xml');
  writeFile(root, 'services/demo/target/classes/mapper/sub/NestedMapper.xml');
}

function run(root, options = {}) {
  return checkProject(root, { minComparedFiles: 1, ...options });
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

function main() {
  const tmpBase = join(ROOT, '.tmp');
  mkdirSync(tmpBase, { recursive: true });
  const fixture = mkdtempSync(join(tmpBase, 'target-orphans-'));

  try {
    // 1) 全量对齐 → 绿，且两个资源目录都被比对
    writeFixture(fixture);
    const aligned = expectOk(fixture, 'target 与 src 全量对齐');
    assert(aligned.areasCompared === 2, `应比对 db+mapper 两个目录，实际 ${aligned.areasCompared}`);
    assert(aligned.comparedFiles === 3, `应比对 3 个文件，实际 ${aligned.comparedFiles}`);
    assert(aligned.modules === 1, `应识别 1 个 target 模块，实际 ${aligned.modules}`);

    // 2) target 里有源码已删的迁移 → 红并给 mvn -pl 清理提示
    writeFile(fixture, 'services/demo/target/classes/db/migration/V999__deleted.sql');
    expectFinding(fixture, 'V999__deleted.sql 在源码里已不存在', '迁移孤儿');
    expectFinding(fixture, 'mvn -pl services/demo clean', '清理提示带模块路径');

    // 3) 嵌套目录里的 Mapper 孤儿也要抓
    writeFixture(fixture);
    writeFile(fixture, 'services/demo/target/classes/mapper/sub/OldMapper.xml');
    expectFinding(fixture, 'mapper/sub/OldMapper.xml 在源码里已不存在', '嵌套 Mapper 孤儿');

    // 4) 非 db/mapper 的 target 文件不参与比对（新构建产物/配置不算孤儿）
    writeFixture(fixture);
    writeFile(fixture, 'services/demo/target/classes/application.yml');
    writeFile(fixture, 'services/demo/target/classes/demo/Demo.class');
    expectOk(fixture, '非资源文件忽略');

    // 5) 没有 target（CI 干净检出）→ 绿且 areasCompared=0
    rmSync(join(fixture, 'services/demo/target'), { recursive: true, force: true });
    const clean = expectOk(fixture, '无 target 干净检出');
    assert(clean.areasCompared === 0, `无 target 时应为 0 个资源目录，实际 ${clean.areasCompared}`);
    assert(clean.modules === 0, `无 target 时应为 0 个模块，实际 ${clean.modules}`);

    // 6) 有 target 资源但比对量低于锚点 → 红（防遍历退化假绿）
    writeFixture(fixture);
    expectFinding(fixture, '锚点失效', '比对量锚点', { minComparedFiles: 99 });

    // 7) 真仓：干净检出下无可比对，有 target 时应全绿
    const live = checkProject(ROOT);
    assert(live.ok, `真仓应绿，实际 ${JSON.stringify(live.findings)}`);
    assert(
      live.areasCompared === 0 || live.comparedFiles >= 300,
      `真仓比对量异常：${live.comparedFiles}`
    );
  } finally {
    rmSync(fixture, { recursive: true, force: true });
  }

  const leftovers = existsSync(tmpBase)
    ? readdirSync(tmpBase).filter((n) => n.startsWith('target-orphans-'))
    : [];
  assert(leftovers.length === 0, `临时目录未清理：${leftovers.join(',')}`);

  console.log(
    `${TAG} OK（${checks} 项：对齐绿 / 迁移与嵌套 Mapper 孤儿红 / 非资源忽略 / 无 target 绿 / 锚点 / 真仓，且不残留临时目录）`
  );
}

main();
