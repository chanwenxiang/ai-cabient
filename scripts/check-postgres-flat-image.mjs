#!/usr/bin/env node
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const dockerfile = 'infra/docker/postgres.Dockerfile';

export function evaluatePostgresFlatImage(source) {
  const src = source.replace(/\r\n/g, '\n').replace(/^\s*#.*$/gm, '');
  const problems = [];
  const require = (pattern, message) => {
    if (!pattern.test(src)) problems.push(message);
  };

  require(/^FROM\s+scratch\s*$/m, '最终阶段必须是 FROM scratch');
  require(/^COPY\s+--from=patched\s+\/\s+\/\s*$/m, '必须从 patched 阶段复制合并后的完整根文件系统');
  require(/apk\s+add\s+--no-cache\s+gosu=1\.19-r6\s+libxml2=2\.13\.9-r3/, '必须固定 gosu=1.19-r6 与 libxml2=2.13.9-r3');
  require(/rm\s+-f\s+\/usr\/local\/bin\/gosu/, '必须移除基础层旧 gosu 路径');
  require(/ln\s+-s\s+\/usr\/bin\/gosu\s+\/usr\/local\/bin\/gosu/, '必须链接到 Alpine 修复版 gosu');
  require(/^ENTRYPOINT\s+\["docker-entrypoint\.sh"\]\s*$/m, '必须保留官方 PostgreSQL entrypoint');
  require(/^CMD\s+\["postgres"\]\s*$/m, '必须保留 postgres 默认命令');
  require(/^EXPOSE\s+5432\/tcp\s*$/m, '必须保留 5432 端口声明');
  require(/^VOLUME\s+\["\/var\/lib\/postgresql\/data"\]\s*$/m, '必须保留 PostgreSQL 数据卷');
  require(/^STOPSIGNAL\s+SIGINT\s*$/m, '必须保留 SIGINT 停止信号');
  return problems;
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  const problems = evaluatePostgresFlatImage(readFileSync(resolve(root, dockerfile), 'utf8'));
  if (problems.length) {
    console.error('[check-postgres-flat-image] FAIL');
    for (const problem of problems) console.error(`  - ${problem}`);
    process.exit(1);
  }
  console.log('[check-postgres-flat-image] OK：扁平化镜像结构、gosu/libxml2 修复与运行时配置齐全');
}
