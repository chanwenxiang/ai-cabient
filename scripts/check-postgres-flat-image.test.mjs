#!/usr/bin/env node
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { evaluatePostgresFlatImage } from './check-postgres-flat-image.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const baseline = readFileSync(resolve(root, 'infra/docker/postgres.Dockerfile'), 'utf8');
let checks = 0;
const assert = (condition, message) => {
  checks++;
  if (!condition) throw new Error(`[check-postgres-flat-image.test] ${message}`);
};
const has = (source, text) =>
  evaluatePostgresFlatImage(source).some((problem) => problem.includes(text));

assert(evaluatePostgresFlatImage(baseline).length === 0, '真实 Dockerfile 应通过');
assert(
  has(baseline.replace('FROM scratch', 'FROM alpine:3.24'), 'FROM scratch'),
  '退回分层镜像必须红'
);
assert(
  has(
    baseline.replace('COPY --from=patched / /', 'COPY --from=patched /usr /usr'),
    '完整根文件系统'
  ),
  '只复制部分目录必须红'
);
assert(has(baseline.replace('gosu=1.19-r6', 'gosu=1.19-r5'), 'gosu=1.19-r6'), 'gosu 降版本必须红');
assert(
  has(baseline.replace('libxml2=2.13.9-r3', 'libxml2=2.13.9-r2'), 'libxml2=2.13.9-r3'),
  'libxml2 降版本必须红'
);
assert(
  has(baseline.replace('rm -f /usr/local/bin/gosu', 'true'), '移除基础层旧 gosu'),
  '不删旧 gosu 必须红'
);
assert(
  has(
    baseline.replace('ENTRYPOINT ["docker-entrypoint.sh"]', 'ENTRYPOINT ["postgres"]'),
    'entrypoint'
  ),
  '改坏 entrypoint 必须红'
);
assert(
  has(baseline.replace('VOLUME ["/var/lib/postgresql/data"]', 'VOLUME ["/data"]'), '数据卷'),
  '改坏数据卷必须红'
);
assert(
  has('# FROM scratch\n# COPY --from=patched / /', 'FROM scratch'),
  '注释里的旧声明不得冒充真实结构'
);

console.log(`[check-postgres-flat-image.test] OK：${checks} 项断言全过`);
