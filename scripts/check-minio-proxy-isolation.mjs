#!/usr/bin/env node
import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-minio-proxy-isolation]';

function serviceBlock(text, name) {
  const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const match = text.match(
    new RegExp(
      `^  ${escaped}:\\n([\\s\\S]*?)(?=^  [A-Za-z0-9_-]+:\\n|^[A-Za-z_][\\w-]*:\\n|(?![\\s\\S]))`,
      'm'
    )
  );
  return match?.[1] || '';
}

export function evaluateMinioProxyIsolation({
  base,
  apps,
  full,
  prometheusCompose,
  prometheusFull,
  proxyConf
}) {
  base = base.replace(/\r\n/g, '\n');
  apps = apps.replace(/\r\n/g, '\n');
  full = full.replace(/\r\n/g, '\n');
  prometheusCompose = prometheusCompose.replace(/\r\n/g, '\n');
  prometheusFull = prometheusFull.replace(/\r\n/g, '\n');
  proxyConf = proxyConf.replace(/\r\n/g, '\n');
  const problems = [];
  const require = (condition, message) => {
    if (!condition) problems.push(message);
  };
  const networkListed = (block, name) => {
    const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    return (
      new RegExp(`^\\s+-\\s*${escaped}\\s*$`, 'm').test(block) ||
      new RegExp(`networks:\\s*\\[[^\\]]*["']?${escaped}["']?`, 'm').test(block)
    );
  };

  require(proxyConf.includes('$http_x_amz_content_sha256'), '代理配置未读取 X-Amz-Content-Sha256');
  require(proxyConf.includes('STREAMING-UNSIGNED-PAYLOAD-TRAILER'), '代理未拒绝 unsigned-trailer');
  require(proxyConf.includes('return 403'), 'unsigned-trailer 必须返回 403');
  require(proxyConf.includes('proxy_pass http://$minio_api'), 'S3 API 未转发到 MinIO');
  require(proxyConf.includes('proxy_pass http://$minio_console'), '控制台未转发到 MinIO');

  for (const [label, text] of [
    ['base', base],
    ['full', full]
  ]) {
    const minio = serviceBlock(text, 'minio');
    const proxy = serviceBlock(text, 'minio-proxy');
    const init =
      label === 'base' ? serviceBlock(apps, 'minio-init') : serviceBlock(text, 'minio-init');
    require(networkListed(minio, 'minio-internal') &&
      !networkListed(minio, 'default'), `${label}: MinIO 必须只接入 minio-internal`);
    require(!/^\s+ports:/m.test(minio), `${label}: MinIO 不得直接发布宿主端口`);
    require(networkListed(proxy, 'default') &&
      networkListed(proxy, 'minio-internal'), `${label}: 代理必须同时接入应用网与 MinIO 内网`);
    require(/^\s+ports:/m.test(proxy), `${label}: 代理必须承接原 MinIO 宿主端口`);
    require(networkListed(init, 'minio-internal'), `${label}: minio-init 必须接入 MinIO 内网`);
  }

  require(/minio-internal:\s*\n\s+internal:\s*true/.test(
    base
  ), 'base: minio-internal 必须为 internal 网络');
  require(/minio-internal:\s*\n\s+internal:\s*true/.test(
    full
  ), 'full: minio-internal 必须为 internal 网络');
  require(apps.includes(
    'MINIO_ENDPOINT: http://minio-proxy:9000'
  ), 'apps: trade 必须经代理访问 MinIO');
  require(full.includes(
    'MINIO_ENDPOINT: http://minio-proxy:9000'
  ), 'full: 应用必须经代理访问 MinIO');
  require(!full.includes('MINIO_ENDPOINT: http://minio:9000'), 'full: 不得保留 MinIO 直连端点');
  require(prometheusCompose.includes('minio-proxy:9000'), 'compose Prometheus 必须抓取代理');
  require(prometheusFull.includes('minio-proxy:9000'), 'full Prometheus 必须抓取代理');

  return problems;
}

function main() {
  const files = {
    base: 'infra/docker-compose.yml',
    apps: 'infra/docker-compose.apps.yml',
    full: 'infra/docker-compose.full.yml',
    prometheusCompose: 'infra/monitoring/prometheus.compose.yml',
    prometheusFull: 'infra/monitoring/prometheus-full.yml',
    proxyConf: 'infra/gateway/minio-proxy.conf'
  };
  for (const file of Object.values(files)) {
    if (!existsSync(resolve(root, file))) {
      console.error(`${TAG} FAIL: 缺少 ${file}`);
      process.exit(1);
    }
  }
  const input = Object.fromEntries(
    Object.entries(files).map(([key, file]) => [key, readFileSync(resolve(root, file), 'utf8')])
  );
  const problems = evaluateMinioProxyIsolation(input);
  if (problems.length) {
    console.error(`${TAG} FAIL:`);
    for (const problem of problems) console.error(`  - ${problem}`);
    process.exit(1);
  }
  console.log(`${TAG} OK：MinIO 仅内网可达，应用与监控统一经代理，unsigned-trailer 已拒绝`);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  main();
}
