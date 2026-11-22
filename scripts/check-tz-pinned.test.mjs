#!/usr/bin/env node
/**
 * `check-tz-pinned.mjs` 的负向用例（S8④ 自证）。
 *
 *   node scripts/check-tz-pinned.test.mjs
 */
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { evaluateTzPin, findJvmDockerfiles, walkDockerfiles } from './check-tz-pinned.mjs';

const TAG = '[check-tz-pinned.test]';
let checks = 0;
function assert(cond, message) {
  checks++;
  if (!cond) throw new Error(`${TAG} 断言失败：${message}`);
}
const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');

const OK = {
  rel: 'Dockerfile.good',
  javaLine: 'ENTRYPOINT ["java", "-Duser.timezone=Asia/Shanghai", "-jar", "/app/app.jar"]',
  src: 'ENV TZ=Asia/Shanghai\n'
};

// 1) 双钉相等 ⇒ 绿
assert(evaluateTzPin(OK, []).length === 0, `合规文件应绿：${evaluateTzPin(OK, []).join('|')}`);

// 2) R19-17 缺口形态：只有 ENV TZ、java 行无第二根钉 ⇒ 红
{
  const p = evaluateTzPin({ ...OK, javaLine: 'ENTRYPOINT ["java", "-jar", "/app/app.jar"]' }, []);
  assert(p.length === 1 && p[0].includes('-Duser.timezone'), `缺 JVM 钉须红：${p.join('|')}`);
}

// 3) 只有 -Duser.timezone、缺 ENV TZ ⇒ 红
{
  const p = evaluateTzPin({ ...OK, src: 'WORKDIR /app\n' }, []);
  assert(p.length === 1 && p[0].includes('ENV TZ'), `缺容器层钉须红：${p.join('|')}`);
}

// 4) 双钉取值不等 ⇒ 红
{
  const p = evaluateTzPin({ ...OK, src: 'ENV TZ=UTC\n' }, []);
  assert(
    p.some((x) => x.includes('取值不等')),
    `不等须红：${p.join('|')}`
  );
}

// 5) 值棘轮：双钉但不是 Asia/Shanghai ⇒ 红
{
  const p = evaluateTzPin(
    {
      ...OK,
      javaLine: 'ENTRYPOINT ["java", "-Duser.timezone=UTC", "-jar", "x"]',
      src: 'ENV TZ=UTC\n'
    },
    []
  );
  assert(p.length === 1 && p[0].includes('Asia/Shanghai'), `改区须红：${p.join('|')}`);
}

// 6) 非 java 主进程的 Dockerfile 不在判定范围（nginx/CI runner）
{
  const files = ['a.Dockerfile', 'b.Dockerfile'];
  const srcs = {
    'a.Dockerfile': 'ENTRYPOINT ["nginx", "-g", "daemon off;"]\n',
    'b.Dockerfile': 'CMD ["./config.sh", "run"]\n'
  };
  const jvm = findJvmDockerfiles(files, (rel) => srcs[rel] || '');
  assert(jvm.length === 0, `非 JVM 容器应被忽略：${jvm.map((f) => f.rel).join(',')}`);
}

// 7) 真实仓面：当前 4 个 JVM 容器必须全部合规（R19-17 漏网的 trade/device/simulator 已补齐）
//    🔴 直接调生产 walkDockerfiles，不在此另写一份 walk（影子实现会让「.tmp 不进扫描面」
//    这类修复在本用例里永远测不到，且它自己也只跳 4 个目录、同样会踩 .tmp 回收竞态）。
{
  const files = [
    ...new Set(
      [
        walkDockerfiles(ROOT),
        walkDockerfiles(join(ROOT, 'infra')),
        walkDockerfiles(join(ROOT, 'edge'))
      ].flat()
    )
  ].filter((rel) => existsSync(join(ROOT, rel)));
  const jvm = findJvmDockerfiles(files);
  assert(jvm.length >= 4, `JVM 容器数应 ≥4，实际 ${jvm.length}`);
  const bad = jvm.flatMap((f) => evaluateTzPin(f, []));
  assert(bad.length === 0, `真实仓面应全绿：${bad.join('|')}`);
}

// 8) `.tmp` 不进扫描面（2026-11-22 实证：聚合链里 tz-pinned 走进负向用例正在回收的沙箱
//    ⇒ statSync ENOENT 崩溃，退出码 1 但报的是 fs 错误而非判据违规 = 假红）
{
  const probeDir = join(ROOT, '.tmp', `tzpin-walk-probe-${process.pid}`);
  mkdirSync(probeDir, { recursive: true });
  writeFileSync(join(probeDir, 'Dockerfile'), 'FROM eclipse-temurin:21-jre\n');
  try {
    const leaked = walkDockerfiles(ROOT).filter((rel) => rel.startsWith('.tmp/'));
    assert(leaked.length === 0, `.tmp 下的 Dockerfile 不应进扫描面：${leaked.join(',')}`);
  } finally {
    try {
      rmSync(probeDir, { recursive: true, force: true });
    } catch {
      /* safe-delete 垫片可能拦（turn 级计数）⇒ 探针目录留在 .tmp 无害，且已被排除出扫描面 */
    }
  }
}

console.log(`${TAG} OK：${checks} 项断言全过`);
