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
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const FULL = process.env.FULL !== '0';

function readProp(pomText, name) {
  const m = pomText.match(new RegExp(`<${name}>([^<]+)</${name}>`));
  return m ? m[1].trim() : null;
}

async function osvBatchFetch(queries) {
  const chunkSize = 80;
  const results = [];
  for (let i = 0; i < queries.length; i += chunkSize) {
    const chunk = queries.slice(i, i + chunkSize);
    const r = await fetch('https://api.osv.dev/v1/querybatch', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ queries: chunk }),
      signal: AbortSignal.timeout(60_000)
    });
    if (!r.ok) throw new Error(`OSV HTTP ${r.status}`);
    const data = await r.json();
    results.push(...(data.results || []));
  }
  return { results };
}

function parseDepList(text) {
  const out = new Map();
  const re =
    /([a-zA-Z0-9_.-]+):([a-zA-Z0-9_.-]+):jar:([0-9][a-zA-Z0-9._+-]*):(compile|runtime)/g;
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
  const outFile = join(root, 'target', 'dep-list-runtime.txt');
  const r = spawnSync(
    'mvn',
    [
      '-pl',
      'services/trade-service,services/device-service',
      '-am',
      'dependency:list',
      '-DincludeScope=runtime',
      '-Dskip.admin.build=true',
      `-DoutputFile=${outFile}`
    ],
    { cwd: root, encoding: 'utf8', shell: true }
  );
  if (r.status !== 0) {
    console.error(r.stderr?.slice(-800) || r.stdout?.slice(-800));
    throw new Error('mvn dependency:list failed');
  }
  // Maven may write per-module files; also check module targets
  const chunks = [];
  if (existsSync(outFile)) chunks.push(readFileSync(outFile, 'utf8'));
  for (const mod of [
    'services/trade-service/target/dep-list-runtime.txt',
    'services/device-service/target/dep-list-runtime.txt',
    'services/common/common-core/target/dep-list-runtime.txt',
    'services/trade-service/target/dep-list.txt'
  ]) {
    const p = join(root, mod);
    if (existsSync(p)) chunks.push(readFileSync(p, 'utf8'));
  }
  // Prefer absolute path we asked for; Maven often writes relative to each module
  const trade = join(root, 'services/trade-service/target');
  if (existsSync(trade)) {
    for (const f of ['dep-list-runtime.txt', 'dep-list.txt']) {
      const p = join(trade, f);
      if (existsSync(p)) chunks.push(readFileSync(p, 'utf8'));
    }
  }
  const device = join(root, 'services/device-service/target');
  if (existsSync(device)) {
    for (const f of ['dep-list-runtime.txt', 'dep-list.txt']) {
      const p = join(device, f);
      if (existsSync(p)) chunks.push(readFileSync(p, 'utf8'));
    }
  }
  if (!chunks.length && existsSync(join(root, 'target/dep-list-trade.txt'))) {
    chunks.push(readFileSync(join(root, 'target/dep-list-trade.txt'), 'utf8'));
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
  ['io.minio:minio', readProp(pom, 'minio.version')]
];

console.log('=== Maven pins (from root pom) ===');
for (const [name, ver] of pins) console.log(`  ${name}:${ver}`);

const depMap = new Map(pins.filter(([, v]) => v));
if (FULL && process.env.SKIP_MVN !== '1') {
  console.log('\n=== Resolving runtime dependency tree (Maven) ===');
  try {
    const raw = runMvnDepList();
    const parsed = parseDepList(raw);
    console.log(`  unique runtime jars: ${parsed.size}`);
    for (const [k, v] of parsed) depMap.set(k, v);
    writeFileSync(join(root, 'target', 'dep-osv-input.txt'), [...depMap.entries()].map(([k, v]) => `${k}:${v}`).join('\n'));
  } catch (e) {
    console.warn('  Maven tree skipped:', e instanceof Error ? e.message : e);
  }
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
  console.log('pip-audit skipped/failed — py -3.12 -m pip_audit -r vision-service/requirements-base.txt');
}

console.log('\nDone. Full NVD: set NVD_API_KEY then mvn -Powasp-depcheck -DskipTests verify');
process.exit(hit > 0 ? 1 : 0);
