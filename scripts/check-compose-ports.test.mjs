#!/usr/bin/env node
/**
 * `check-compose-ports.mjs` 的负向用例（S8③ couplingDebt 销账）：沙箱注入非回环绑定 ⇒ 红。
 *
 *   node scripts/check-compose-ports.test.mjs
 */
import { createSandbox, expectGreen, expectRed } from './lib/gate-sandbox.mjs';

const TAG = '[check-compose-ports.test]';
let checks = 0;
const ok = () => {
  checks++;
};

try {
  const sb = createSandbox();
  sb.copyTree('infra', ['.yml', '.yaml']);
  await expectGreen('check-compose-ports.mjs', sb.root);
  ok();

  // 注入：把任一回环绑定改成 0.0.0.0（docker-compose.yml 的 postgres 端口）
  const compose = sb.root + '/infra/docker-compose.yml';
  const fs = await import('node:fs');
  let src = fs.readFileSync(compose, 'utf8');
  if (!src.includes('127.0.0.1:'))
    throw new Error('真实 compose 里找不到 127.0.0.1 锚点（快照过期）');
  src = src.replace('127.0.0.1:', '0.0.0.0:');
  fs.writeFileSync(compose, src);
  await expectRed('check-compose-ports.mjs', sb.root, '0.0.0.0');
  ok();

  // 还原主 compose（用例 2 的注入不能带进用例 3）
  fs.writeFileSync(compose, src.replace('0.0.0.0:', '127.0.0.1:'));

  // 白名单豁免形态：lan.yml 显式叠加层放行局域网（非回环不红）
  const lan = sb.root + '/infra/docker-compose.lan.yml';
  let lanSrc = fs.readFileSync(lan, 'utf8');
  lanSrc = lanSrc.replace(/127\.0\.0\.1:/g, '0.0.0.0:');
  fs.writeFileSync(lan, lanSrc);
  await expectGreen('check-compose-ports.mjs', sb.root);
  ok();
} finally {
  // 沙箱留在 .tmp 由系统清理；不阻塞断言结果
}

console.log(`${TAG} OK：${checks} 项断言全过`);
