/**
 * A-P2-006 门禁：禁止把 admin JWT 写入 localStorage；须走 auth-storage（sessionStorage / Cookie）。
 * 用法：node scripts/check-admin-token-storage.mjs
 */
import fs from 'node:fs';
import path from 'node:path';

const srcRoot = path.resolve('clients/admin-vue/src');

function walk(dir, out = []) {
  if (!fs.existsSync(dir)) return out;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      walk(full, out);
      continue;
    }
    if (!/\.(vue|ts|tsx|js)$/.test(entry.name)) continue;
    out.push(full);
  }
  return out;
}

const offenders = [];
const setLocalToken =
  /localStorage\.setItem\(\s*(['"`])admin_token\1|localStorage\.setItem\(\s*TOKEN_KEY\s*,/;
const getLocalTokenOnlyInClient =
  /localStorage\.getItem\(\s*(['"`])admin_token\1/;

for (const file of walk(srcRoot)) {
  const rel = path.relative(process.cwd(), file).replace(/\\/g, '/');
  const body = fs.readFileSync(file, 'utf8');
  const lines = body.split(/\n/);

  lines.forEach((line, idx) => {
    if (setLocalToken.test(line)) {
      offenders.push(`${rel}:${idx + 1} must not write admin_token to localStorage`);
    }
    // 仅允许 auth-storage 在迁移时读/删旧 localStorage token
    if (getLocalTokenOnlyInClient.test(line) && !rel.endsWith('api/auth-storage.ts')) {
      offenders.push(`${rel}:${idx + 1} read admin_token from localStorage — use getBearerToken()`);
    }
  });
}

const storagePath = path.resolve('clients/admin-vue/src/api/auth-storage.ts');
if (!fs.existsSync(storagePath)) {
  offenders.push('missing clients/admin-vue/src/api/auth-storage.ts');
} else {
  const storage = fs.readFileSync(storagePath, 'utf8');
  if (!storage.includes('sessionStorage.setItem(TOKEN_KEY')) {
    offenders.push('auth-storage.ts must persist Bearer via sessionStorage');
  }
  if (!storage.includes('import.meta.env.PROD') || !storage.includes('return false')) {
    offenders.push('auth-storage.ts must fail-closed in PROD when cookieEnabled=false');
  }
}

if (offenders.length) {
  console.error('A-P2-006 admin token storage check failed:\n' + offenders.join('\n'));
  process.exit(1);
}
console.log('check-admin-token-storage: ok');
