#!/usr/bin/env node
/**
 * `target/` 孤儿资源门禁：本机 `target/classes` 里存在**源码已删除**的 Flyway 迁移
 * 或 MyBatis Mapper 即红（lessons #302 / #323）。
 *
 * 为什么危险：Flyway / MyBatis 扫的是 **classpath**（`target/classes`），不是 `src`。
 * 删掉 `src/main/resources/db/migration/Vxxx__*.sql` 或 `mapper/*.xml` 之后不 `mvn clean`，
 * 旧副本仍留在 target 里 ⇒ 本机照样按旧迁移/旧 XML 跑（Maven 增量编译对「删除」不产生
 * 新时间戳，`Nothing to compile` 还会把未验证伪装成通过）。CI 是干净检出，永远看不到这类
 * 残留，所以它只在**有 target 的机器**上生效——干净检出时零命中是正常状态，不是假绿。
 *
 * 比对范围（只比会被 classpath 扫描的两类资源，避免误伤编译产物）：
 *   target/classes/db/**     ↔ src/main/resources/db/**
 *   target/classes/mapper/** ↔ src/main/resources/mapper/**
 * 只报「target 有、src 没有」；src 有而 target 没有属于「还没构建」，不在本门禁范围。
 *
 *   node scripts/check-target-orphans.mjs
 */
import { existsSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-target-orphans]';
const RESOURCE_AREAS = ['db', 'mapper'];
const MIN_COMPARED_FILES = 300;

export function walkFiles(dir, out = []) {
  for (const entry of readdirSync(dir)) {
    if (entry === 'node_modules') continue;
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) walkFiles(full, out);
    else out.push(full);
  }
  return out;
}

/** 收集带 `target/classes` 的模块（遇到 target 不再下潜）。 */
export function findModuleTargets(dir, out = []) {
  for (const entry of readdirSync(dir)) {
    if (entry === 'node_modules' || entry === '.git') continue;
    const full = join(dir, entry);
    if (!statSync(full).isDirectory()) continue;
    if (entry === 'target') {
      const classes = join(full, 'classes');
      if (existsSync(classes)) out.push({ module: dir, targetClasses: classes });
      continue;
    }
    findModuleTargets(full, out);
  }
  return out;
}

export function checkProject(root, options = {}) {
  const minComparedFiles = options.minComparedFiles ?? MIN_COMPARED_FILES;
  const servicesDir = join(root, 'services');
  const findings = [];
  const modules = existsSync(servicesDir) ? findModuleTargets(servicesDir) : [];
  let comparedFiles = 0;
  let areasCompared = 0;

  for (const { module, targetClasses } of modules) {
    const moduleRel = relative(root, module).split('\\').join('/');
    const srcResources = join(module, 'src', 'main', 'resources');
    for (const area of RESOURCE_AREAS) {
      const targetArea = join(targetClasses, area);
      if (!existsSync(targetArea)) continue;
      areasCompared++;
      const sourceArea = join(srcResources, area);
      for (const file of walkFiles(targetArea)) {
        comparedFiles++;
        const rel = relative(targetArea, file);
        if (existsSync(join(sourceArea, rel))) continue;
        const targetRel = relative(root, file).split('\\').join('/');
        const srcRel = `${moduleRel}/src/main/resources/${area}/${rel.split('\\').join('/')}`;
        findings.push(
          `${targetRel} 在源码里已不存在（${srcRel} 缺失）` +
            `—— Flyway/MyBatis 扫的是 classpath，旧副本仍会生效；` +
            `先 \`mvn -pl ${moduleRel} clean\` 或删掉该文件`
        );
      }
    }
  }

  if (areasCompared === 0) {
    return {
      ok: findings.length === 0,
      modules: modules.length,
      areasCompared,
      comparedFiles,
      findings
    };
  }
  if (comparedFiles < minComparedFiles) {
    findings.push(
      `锚点失效：只比对到 ${comparedFiles} 个 target 资源文件（下限 ${minComparedFiles}）` +
        `—— 目录遍历退化时零命中是假绿`
    );
  }

  return {
    ok: findings.length === 0,
    modules: modules.length,
    areasCompared,
    comparedFiles,
    findings
  };
}

function main() {
  const result = checkProject(ROOT);
  if (!result.ok) {
    console.error(`${TAG} FAIL: ${result.findings.length} 处 target 孤儿资源：`);
    for (const f of result.findings) console.error(`  - ${f}`);
    process.exit(1);
  }
  if (result.areasCompared === 0) {
    console.log(
      `${TAG} OK：${result.modules} 个 target，但没有 db/mapper 资源目录（干净检出或尚未构建）—— 无可比对`
    );
    return;
  }
  console.log(
    `${TAG} OK：${result.modules} 个 target、比对 ${result.areasCompared} 个资源目录共 ${result.comparedFiles} 个文件，` +
      `无源码已删除的迁移/Mapper`
  );
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main();
}
