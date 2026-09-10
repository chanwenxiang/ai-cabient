#!/usr/bin/env node
/**
 * 后端依赖 CVE 快扫（OSV + pip-audit）。
 * 用法：node scripts/audit-backend-deps.mjs
 * 不启动容器；Maven 完整 NVD 扫描用：mvn -Powasp-depcheck -DskipTests verify
 */
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');

function readProp(pomText, name) {
  const m = pomText.match(new RegExp(`<${name}>([^<]+)</${name}>`));
  return m ? m[1].trim() : null;
}

function osvBatch(queries) {
  const res = spawnSync(
    'curl',
    [
      '-sS',
      '-X',
      'POST',
      'https://api.osv.dev/v1/querybatch',
      '-H',
      'Content-Type: application/json',
      '-d',
      JSON.stringify({ queries })
    ],
    { encoding: 'utf8' }
  );
  if (res.status !== 0) {
    // Windows 无 curl 时走 node fetch
    return null;
  }
  return JSON.parse(res.stdout);
}

async function osvBatchFetch(queries) {
  const r = await fetch('https://api.osv.dev/v1/querybatch', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ queries })
  });
  if (!r.ok) throw new Error(`OSV HTTP ${r.status}`);
  return r.json();
}

const pom = readFileSync(join(root, 'pom.xml'), 'utf8');
const boot = readProp(pom, 'spring-boot.version');
const tomcat = readProp(pom, 'tomcat.version');
const netty = readProp(pom, 'netty.version');
const jackson = readProp(pom, 'jackson-bom.version');
const pg = readProp(pom, 'postgresql.version');
const minio = readProp(pom, 'minio.version');

const packages = [
  ['org.springframework.boot:spring-boot', boot],
  ['org.apache.tomcat.embed:tomcat-embed-core', tomcat],
  ['io.netty:netty-handler', netty],
  ['com.fasterxml.jackson.core:jackson-databind', jackson],
  ['org.postgresql:postgresql', pg],
  ['io.minio:minio', minio]
];

console.log('=== Maven pins (from root pom) ===');
for (const [name, ver] of packages) {
  console.log(`  ${name}:${ver}`);
}

const queries = packages.map(([name, version]) => ({
  package: { ecosystem: 'Maven', name },
  version
}));

const data = (await osvBatchFetch(queries)) || osvBatch(queries);
let hit = 0;
for (let i = 0; i < packages.length; i++) {
  const vulns = data.results?.[i]?.vulns || [];
  if (vulns.length) {
    hit += vulns.length;
    console.log(`\n[OSV] ${packages[i][0]}@${packages[i][1]}`);
    for (const v of vulns) console.log(`  - ${v.id}`);
  }
}
if (!hit) console.log('\n[OSV] pinned packages: no known vulns');

console.log('\n=== pip-audit (vision-service, prefer Python 3.12) ===');
const req = join(root, 'vision-service', 'requirements-base.txt');
const pyCandidates = process.platform === 'win32' ? ['py', 'python'] : ['python3', 'python'];
let pipOk = false;
for (const py of pyCandidates) {
  const args =
    py === 'py'
      ? ['-3.12', '-m', 'pip_audit', '-r', req, '--desc', 'on']
      : ['-m', 'pip_audit', '-r', req, '--desc', 'on'];
  const r = spawnSync(py, args, { encoding: 'utf8', cwd: root });
  if (r.error && r.error.code === 'ENOENT') continue;
  process.stdout.write(r.stdout || '');
  process.stderr.write(r.stderr || '');
  if (r.status === 0 || (r.stdout || '').includes('No known vulnerabilities')) {
    pipOk = true;
    break;
  }
  // 非 3.12 环境可能因 rapidocr 解析失败；继续试
  if ((r.stderr || '').includes('rapidocr') || (r.stderr || '').includes('Requires-Python')) {
    console.log('(hint: use Python 3.12 matching vision Dockerfile)');
    continue;
  }
  if (r.status === 0) {
    pipOk = true;
    break;
  }
}
if (!pipOk) {
  console.log('pip-audit skipped/failed — install pip-audit on Python 3.12, or run:');
  console.log('  py -3.12 -m pip_audit -r vision-service/requirements-base.txt');
}

console.log('\nDone. Full NVD: mvn -Powasp-depcheck -DskipTests verify');
process.exit(hit > 0 ? 1 : 0);
