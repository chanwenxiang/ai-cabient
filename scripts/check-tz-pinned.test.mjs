#!/usr/bin/env node
/**
 * `check-tz-pinned.mjs` 的负向用例（S8④ 自证）。
 *
 *   node scripts/check-tz-pinned.test.mjs
 */
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { evaluateTzPin, findJvmDockerfiles } from './check-tz-pinned.mjs';

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
  assert(p.some((x) => x.includes('取值不等')), `不等须红：${p.join('|')}`);
}

// 5) 值棘轮：双钉但不是 Asia/Shanghai ⇒ 红
{
  const p = evaluateTzPin(
    { ...OK, javaLine: 'ENTRYPOINT ["java", "-Duser.timezone=UTC", "-jar", "x"]', src: 'ENV TZ=UTC\n' },
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
{
  const { readdirSync, statSync, existsSync } = await import('node:fs');
  const { join, relative } = await import('node:path');
  const walk = (dir, out = []) => {
    for (const name of readdirSync(dir)) {
      const full = join(dir, name);
      const st = statSync(full);
      if (st.isDirectory()) {
        if (['node_modules', 'target', '.git', 'dist'].includes(name)) continue;
        walk(full, out);
      } else if (/^Dockerfile/i.test(name) || /\.Dockerfile$/i.test(name)) {
        out.push(relative(ROOT, full).replace(/\\/g, '/'));
      }
    }
    return out;
  };
  const files = [...new Set([walk(ROOT), walk(join(ROOT, 'infra')), walk(join(ROOT, 'edge'))].flat())]
    .filter((rel) => existsSync(join(ROOT, rel)));
  const jvm = findJvmDockerfiles(files);
  assert(jvm.length >= 4, `JVM 容器数应 ≥4，实际 ${jvm.length}`);
  const bad = jvm.flatMap((f) => evaluateTzPin(f, []));
  assert(bad.length === 0, `真实仓面应全绿：${bad.join('|')}`);
}

console.log(`${TAG} OK：${checks} 项断言全过`);
