#!/usr/bin/env node
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { evaluateMinioProxyIsolation } from './check-minio-proxy-isolation.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const read = (file) => readFileSync(resolve(root, file), 'utf8');
const baseline = Object.fromEntries(
  Object.entries({
    base: read('infra/docker-compose.yml'),
    apps: read('infra/docker-compose.apps.yml'),
    full: read('infra/docker-compose.full.yml'),
    prometheusCompose: read('infra/monitoring/prometheus.compose.yml'),
    prometheusFull: read('infra/monitoring/prometheus-full.yml'),
    proxyConf: read('infra/gateway/minio-proxy.conf')
  }).map(([key, value]) => [key, value.replace(/\r\n/g, '\n')])
);
let checks = 0;
const assert = (condition, message) => {
  checks++;
  if (!condition) throw new Error(`[check-minio-proxy-isolation.test] ${message}`);
};
const has = (input, text) =>
  evaluateMinioProxyIsolation(input).some((problem) => problem.includes(text));

assert(evaluateMinioProxyIsolation(baseline).length === 0, '真实仓面应通过');
assert(
  has(
    { ...baseline, proxyConf: baseline.proxyConf.replaceAll('return 403', 'return 200') },
    '必须返回 403'
  ),
  '去掉 403 必须变红'
);
assert(
  has(
    { ...baseline, base: baseline.base.replace('    networks:\n      - minio-internal\n', '') },
    'MinIO 必须只接入'
  ),
  'MinIO 脱离内网必须变红'
);
assert(
  has(
    {
      ...baseline,
      full: baseline.full.replace(
        'minio-internal:\n    internal: true',
        'minio-internal:\n    internal: false'
      )
    },
    '必须为 internal 网络'
  ),
  '内网标志被关闭必须变红'
);
assert(
  has(
    {
      ...baseline,
      apps: baseline.apps.replace(
        'MINIO_ENDPOINT: http://minio-proxy:9000',
        'MINIO_ENDPOINT: http://minio:9000'
      )
    },
    'trade 必须经代理'
  ),
  '应用直连必须变红'
);
assert(
  has(
    {
      ...baseline,
      prometheusFull: baseline.prometheusFull.replace('minio-proxy:9000', 'minio:9000')
    },
    'full Prometheus 必须抓取代理'
  ),
  '监控直连必须变红'
);
assert(
  has(
    {
      ...baseline,
      base: baseline.base.replace(
        '      - default\n      - minio-internal\n',
        '      - minio-internal\n'
      )
    },
    '代理必须同时接入'
  ),
  '代理脱离应用网必须变红'
);

console.log(`[check-minio-proxy-isolation.test] OK：${checks} 项断言全过`);
