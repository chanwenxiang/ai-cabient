#!/usr/bin/env node
/**
 * 后端依赖 CVE 扫描（OSV 全树 + 钉扎包 + pip-audit）。
 * 用法：node scripts/audit-backend-deps.mjs
 * 可选：SKIP_MVN=1 仅扫 pom 钉扎；FULL=0 跳过 dependency:list
 *
 * 完整 NVD（需 NVD_API_KEY，首次下载库较慢）：
 *   mvn -Powasp-depcheck -DskipTests verify
 */
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import https from 'node:https';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const FULL = process.env.FULL !== '0';

function readProp(pomText, name) {
  const m = pomText.match(new RegExp(`<${name}>([^<]+)</${name}>`));
  return m ? m[1].trim() : null;
}

async function sleep(ms) {
  await new Promise((resolve) => setTimeout(resolve, ms));
}

/** 用 node:https 避免 undici 默认 10s connectTimeout 误杀慢网/代理环境 */
function httpsPostJson(url, bodyObj, timeoutMs = 120_000) {
  const body = JSON.stringify(bodyObj);
  return new Promise((resolve, reject) => {
    const req = https.request(
      url,
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Content-Length': Buffer.byteLength(body)
        },
        timeout: timeoutMs
      },
      (res) => {
        const chunks = [];
        res.on('data', (c) => chunks.push(c));
        res.on('end', () => {
          const text = Buffer.concat(chunks).toString('utf8');
          if ((res.statusCode || 0) >= 400) {
            reject(new Error(`OSV HTTP ${res.statusCode}: ${text.slice(0, 200)}`));
            return;
          }
          try {
            resolve(JSON.parse(text));
          } catch (err) {
            reject(err);
          }
        });
      }
    );
    req.on('timeout', () => req.destroy(new Error(`OSV request timeout after ${timeoutMs}ms`)));
    req.on('error', reject);
    req.write(body);
    req.end();
  });
}

async function osvBatchFetch(queries) {
  const chunkSize = 80;
  const results = [];
  const maxAttempts = 3;
  for (let i = 0; i < queries.length; i += chunkSize) {
    const chunk = queries.slice(i, i + chunkSize);
    let lastErr;
    for (let attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        const data = await httpsPostJson('https://api.osv.dev/v1/querybatch', { queries: chunk });
        results.push(...(data.results || []));
        lastErr = undefined;
        break;
      } catch (err) {
        lastErr = err;
        if (attempt < maxAttempts) {
          const waitMs = attempt * 2000;
          console.warn(
            `  OSV chunk ${i / chunkSize + 1} attempt ${attempt} failed, retry in ${waitMs}ms:`,
            err instanceof Error ? err.message : err
          );
          await sleep(waitMs);
        }
      }
    }
    if (lastErr) throw lastErr;
  }
  return { results };
}

function parseDepList(text) {
  const out = new Map();
  const re = /([a-zA-Z0-9_.-]+):([a-zA-Z0-9_.-]+):jar:([0-9][a-zA-Z0-9._+-]*):(compile|runtime)/g;
  let m;
  while ((m = re.exec(text))) {
    const name = `${m[1]}:${m[2]}`;
    // skip local modules
    if (m[1] === 'com.aicabinet') continue;
    out.set(name, m[3]);
  }
  return out;
}

function runMvnDepList() {
  mkdirSync(join(root, 'target'), { recursive: true });
  // 相对路径 outputFile：每个模块写到自己的 target/，避免单文件被后执行模块覆盖
  const moduleLists = [
    join(root, 'services', 'trade-service', 'target', 'dep-list-runtime.txt'),
    join(root, 'services', 'device-service', 'target', 'dep-list-runtime.txt')
  ];
  for (const f of moduleLists) {
    if (existsSync(f)) writeFileSync(f, '', 'utf8');
  }
  const isWin = process.platform === 'win32';
  const r = spawnSync(
    isWin ? 'mvn.cmd' : 'mvn',
    [
      '-ntp',
      '-pl',
      ':trade-service,:device-service',
      '-am',
      'package',
      'dependency:list',
      '-DskipTests',
      '-Dskip.admin.build=true',
      '-DincludeScope=runtime',
      '-DoutputFile=target/dep-list-runtime.txt'
    ],
    { cwd: root, encoding: 'utf8', shell: isWin }
  );
  if (r.status !== 0) {
    console.error(r.stderr?.slice(-1200) || r.stdout?.slice(-1200));
    throw new Error('mvn package dependency:list failed for :trade-service,:device-service');
  }
  const chunks = [];
  for (const f of moduleLists) {
    if (!existsSync(f) || !readFileSync(f, 'utf8').trim()) {
      throw new Error(`missing dependency list output: ${f}`);
    }
    chunks.push(readFileSync(f, 'utf8'));
  }
  return chunks.join('\n');
}

const pom = readFileSync(join(root, 'pom.xml'), 'utf8');
const pins = [
  ['org.springframework.boot:spring-boot', readProp(pom, 'spring-boot.version')],
  ['org.apache.tomcat.embed:tomcat-embed-core', readProp(pom, 'tomcat.version')],
  ['io.netty:netty-handler', readProp(pom, 'netty.version')],
  ['com.fasterxml.jackson.core:jackson-databind', readProp(pom, 'jackson-bom.version')],
  ['org.postgresql:postgresql', readProp(pom, 'postgresql.version')],
  ['io.minio:minio', readProp(pom, 'minio.version')],
  ['at.yawk.lz4:lz4-java', readProp(pom, 'lz4-java.version')]
];

console.log('=== Maven pins (from root pom) ===');
for (const [name, ver] of pins) console.log(`  ${name}:${ver}`);

const depMap = new Map(pins.filter(([, v]) => v));
if (FULL && process.env.SKIP_MVN !== '1') {
  console.log('\n=== Resolving runtime dependency tree (Maven) ===');
  const raw = runMvnDepList();
  const parsed = parseDepList(raw);
  console.log(`  unique runtime jars: ${parsed.size}`);
  if (parsed.size < 50) {
    throw new Error(
      `dependency tree too small (${parsed.size}); refusing pin-only scan. Fix Maven reactor or set SKIP_MVN=1 intentionally.`
    );
  }
  for (const [k, v] of parsed) depMap.set(k, v);
  writeFileSync(
    join(root, 'target', 'dep-osv-input.txt'),
    [...depMap.entries()].map(([k, v]) => `${k}:${v}`).join('\n')
  );
}

const packages = [...depMap.entries()];
const queries = packages.map(([name, version]) => ({
  package: { ecosystem: 'Maven', name },
  version
}));

console.log(`\n=== OSV query (${packages.length} packages) ===`);
const data = await osvBatchFetch(queries);
let hit = 0;
const findings = [];
for (let i = 0; i < packages.length; i++) {
  const vulns = data.results?.[i]?.vulns || [];
  if (!vulns.length) continue;
  hit += vulns.length;
  const [name, ver] = packages[i];
  const ids = vulns.map((v) => v.id);
  findings.push({ name, ver, ids });
  console.log(`[OSV] ${name}@${ver}`);
  for (const id of ids) console.log(`  - ${id}`);
}
if (!hit) console.log('[OSV] no known vulns in scanned set');
else {
  writeFileSync(join(root, 'target', 'osv-findings.json'), JSON.stringify(findings, null, 2));
  console.log(`\nWrote target/osv-findings.json (${findings.length} packages)`);
}

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
  if ((r.stderr || '').includes('rapidocr') || (r.stderr || '').includes('Requires-Python')) {
    console.log('(hint: use Python 3.12 matching vision Dockerfile)');
    continue;
  }
}
if (!pipOk) {
  console.log(
    'pip-audit skipped/failed — py -3.12 -m pip_audit -r vision-service/requirements-base.txt'
  );
}

console.log('\nDone. Full NVD: set NVD_API_KEY then mvn -Powasp-depcheck -DskipTests verify');
process.exit(hit > 0 ? 1 : 0);
