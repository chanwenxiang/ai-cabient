#!/usr/bin/env node
/**
 * 门禁：infra/docker-compose*.yml 的端口映射必须绑定回环（127.0.0.1 / ::1），
 * 防止服务绕过网关直接暴露宿主（审计 H04b/M16）。
 *
 * 豁免（ALLOWLIST）：面向外部的业务入口与有意公开的调试端点。
 * 新增暴露端口：要么绑定回环，要么带书面理由加入豁免。
 */
import { readdirSync, readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const INFRA = 'infra';

/** 豁免清单：键 = "文件名:宿主端口"，值 = 理由 */
const ALLOWLIST = new Map([
  ['docker-compose.full.yml:80', 'gateway 业务入口（全栈部署对外唯一 HTTP）'],
  ['docker-compose.yml:80', 'gateway 业务入口（base 栈）'],
  ['docker-compose.staging.yml:8099', 'sms-webhook-mock 测试工具（staging 短信回调模拟，仅联调用）']
]);

const files = readdirSync(join(ROOT, INFRA))
  .filter((f) => f.startsWith('docker-compose') && f.endsWith('.yml'))
  .sort();

const violations = [];
let checked = 0;

for (const file of files) {
  const src = readFileSync(join(ROOT, INFRA, file), 'utf8');
  const lines = src.split(/\r?\n/);
  let inPorts = false;
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (/^\s*ports:\s*$/.test(line)) {
      inPorts = true;
      continue;
    }
    if (inPorts && !/^\s*-/.test(line)) inPorts = false;
    if (!inPorts) continue;
    const entry = line.match(/^\s*-\s+"?([^"]+?)"?\s*(?:#.*)?$/);
    if (!entry) continue;
    const spec = entry[1];
    // host:container[:proto] —— 从右取两段，host 侧允许 "${VAR:-port}" 形式
    const parts = spec.split(':');
    if (parts.length < 2) continue; // 仅容器端口（未发布宿主），不检查
    const hostPart = parts[0];
    if (/^(127\.0\.0\.1|::1|\[::1\])/.test(hostPart)) {
      checked++;
      continue;
    }
    const hostPort = (hostPart.match(/(\d+)\s*$/) ?? [])[1] ?? hostPart;
    const key = `${file}:${hostPort}`;
    if (ALLOWLIST.has(key)) {
      checked++;
      continue;
    }
    violations.push(`${INFRA}/${file}:${i + 1}  "${spec}"  （宿主侧未绑回环；豁免键 "${key}"）`);
  }
}

if (violations.length > 0) {
  console.error('[check-compose-ports] 以下端口映射未绑定 127.0.0.1/::1 且不在豁免清单：');
  for (const v of violations) console.error(`  - ${v}`);
  console.error(
    '  修复：改为 "127.0.0.1:端口:端口"；确需对外暴露则在此脚本 ALLOWLIST 带理由登记。'
  );
  process.exit(1);
}
console.log(
  `[check-compose-ports] OK：${checked} 条端口映射全部绑回环或在豁免清单（${files.length} 个 compose 文件）`
);
