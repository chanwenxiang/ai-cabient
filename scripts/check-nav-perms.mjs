/**
 * Assert admin menu.ts / merchant-nav.ts perms align with Flyway seeds + pack prefixes.
 * Usage: node scripts/check-nav-perms.mjs
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { merchantPackForPerm, permissionRealm } from '../packages/shared-rbac/dist/index.js';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const errors = [];

function read(rel) {
  return fs.readFileSync(path.join(root, rel), 'utf8');
}

function extractQuotedPerms(src) {
  const found = new Set();
  const re = /perm:\s*(?:'([^']+)'|"([^"]+)"|\[([^\]]+)\])/g;
  let m;
  while ((m = re.exec(src))) {
    if (m[1] || m[2]) {
      found.add(m[1] || m[2]);
      continue;
    }
    const inner = m[3] || '';
    for (const q of inner.matchAll(/'([^']+)'|"([^"]+)"/g)) {
      found.add(q[1] || q[2]);
    }
  }
  return [...found].sort();
}

/** Collect perm_code literals appearing in Flyway migrations (ops_permission seeds). */
function collectSeededPerms() {
  const migDir = path.join(root, 'services/trade-service/src/main/resources/db/migration');
  const seeded = new Set();
  for (const name of fs.readdirSync(migDir)) {
    if (!name.endsWith('.sql')) continue;
    const text = fs.readFileSync(path.join(migDir, name), 'utf8');
    for (const m of text.matchAll(/'((?:ops|merchant):[^']+)'/g)) {
      seeded.add(m[1]);
    }
  }
  return seeded;
}

const menuSrc = read('clients/admin-vue/src/config/menu.ts');
const merchantNavSrc = read('clients/merchant-mp/src/config/merchant-nav.ts');
const adminPerms = extractQuotedPerms(menuSrc);
const merchantPerms = extractQuotedPerms(merchantNavSrc);
const seeded = collectSeededPerms();

for (const code of adminPerms) {
  const realm = permissionRealm(code);
  if (realm !== 'ops') {
    errors.push(`menu.ts perm must be ops:* : ${code}`);
  }
  if (!seeded.has(code)) {
    errors.push(`menu.ts perm missing from Flyway ops_permission seeds: ${code}`);
  }
}

const packByKey = {
  replenishment: 'field',
  devices: 'field',
  alerts: 'field',
  pricing: 'biz',
  settlements: 'biz',
  wallet: 'biz',
  splits: 'biz',
  'line-wallet': 'biz',
  orders: 'biz',
  disputes: 'biz',
  business: 'biz',
  team: 'team'
};

// Also parse pack: on each nav block roughly by key
const navBlocks = [...merchantNavSrc.matchAll(/key:\s*'([^']+)'[\s\S]*?pack:\s*'([^']+)'/g)];
for (const [, key, pack] of navBlocks) {
  if (packByKey[key] && packByKey[key] !== pack) {
    errors.push(`merchant-nav pack mismatch for ${key}: expected ${packByKey[key]}, got ${pack}`);
  }
}

for (const code of merchantPerms) {
  const realm = permissionRealm(code);
  if (realm !== 'merchant') {
    errors.push(`merchant-nav.ts perm must be merchant:* : ${code}`);
  }
  if (!seeded.has(code)) {
    errors.push(`merchant-nav.ts perm missing from Flyway seeds: ${code}`);
  }
  const pack = merchantPackForPerm(code);
  if (!pack) {
    errors.push(`merchant-nav.ts perm has no pack mapping (unexpected agnostic?): ${code}`);
  }
}

// Cross-check declared pack on items vs merchantPackForPerm
for (const m of merchantNavSrc.matchAll(
  /perm:\s*(?:'([^']+)'|"([^"]+)"|\[([^\]]+)\])[\s\S]*?pack:\s*'([^']+)'/g
)) {
  const pack = m[4];
  const codes = [];
  if (m[1] || m[2]) codes.push(m[1] || m[2]);
  else {
    for (const q of (m[3] || '').matchAll(/'([^']+)'|"([^"]+)"/g)) {
      codes.push(q[1] || q[2]);
    }
  }
  for (const code of codes) {
    const mapped = merchantPackForPerm(code);
    if (mapped && mapped !== pack) {
      errors.push(`merchant-nav pack ${pack} != MerchantFeaturePacks ${mapped} for ${code}`);
    }
  }
}

if (errors.length) {
  console.error('check-nav-perms FAILED:');
  for (const e of errors) console.error(' -', e);
  process.exit(1);
}

console.log(
  `check-nav-perms ok: admin=${adminPerms.length} merchant=${merchantPerms.length} seeded≈${seeded.size}`
);
