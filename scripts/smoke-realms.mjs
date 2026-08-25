/**
 * 三端分域冒烟（API）：字典共用、权限不串域。
 * Usage: node scripts/smoke-realms.mjs
 * Requires: gateway at http://localhost (+ redis for captcha peek)
 */
import { execSync } from 'node:child_process';

const BASE = process.env.SMOKE_BASE_URL || 'http://localhost';

async function json(path, opts = {}) {
  const res = await fetch(`${BASE}${path}`, {
    ...opts,
    headers: {
      'Content-Type': 'application/json',
      ...(opts.headers || {})
    }
  });
  const body = await res.json().catch(() => ({}));
  if (!res.ok || (body.code != null && body.code !== 0)) {
    throw new Error(
      `${opts.method || 'GET'} ${path} => ${res.status} ${body.message || JSON.stringify(body)}`
    );
  }
  return body.data;
}

function redisGet(key) {
  return execSync(`docker exec ai-cabinet-redis-1 redis-cli GET ${JSON.stringify(key)}`, {
    encoding: 'utf8'
  })
    .trim()
    .replace(/^"|"$/g, '');
}

function assert(cond, msg) {
  if (!cond) throw new Error(msg);
}

const fail = [];

// --- ops ---
const captcha = await json('/api/v2/auth/captcha');
const captchaCode = redisGet(`aicabinet:captcha:${captcha.captchaId}`);
assert(captchaCode, 'captcha code missing in redis');
const opsLogin = await json('/api/v2/auth/admin-password-login', {
  method: 'POST',
  body: JSON.stringify({
    phoneNumber: '13900000001',
    password: '123456',
    captchaId: captcha.captchaId,
    captchaCode
  })
});
const opsH = { Authorization: `Bearer ${opsLogin.token}` };
const opsPerms = await json('/api/v2/ops/admin/rbac/me/permissions', { headers: opsH });
const opsDict = await json('/api/v2/dicts/runtime', { headers: opsH });
assert(Array.isArray(opsPerms) && opsPerms.includes('ops:admin'), 'ops missing ops:admin');
assert(opsDict?.itemsByType?.order_status, 'ops dict missing order_status');

// --- merchant ---
const mLogin = await json('/api/v2/auth/merchant-password-login', {
  method: 'POST',
  body: JSON.stringify({ phoneNumber: '13800138001', password: '123456' })
});
const mH = { Authorization: `Bearer ${mLogin.token}` };
const me = await json('/api/v2/merchant/me', { headers: mH });
const mDict = await json('/api/v2/dicts/runtime', { headers: mH });
const mPerms = me.permissions || [];
const mOpsLeak = mPerms.filter((p) => p.startsWith('ops:') && p !== 'ops:admin');
const mMer = mPerms.filter((p) => p.startsWith('merchant:'));
assert(mMer.length > 0, 'merchant missing merchant:*');
assert(mOpsLeak.length === 0, `merchant leaked ops:*: ${mOpsLeak.join(',')}`);
assert(mDict?.itemsByType?.order_status, 'merchant dict missing order_status');

// --- consumer (password login; aligns with e2e-three-end / dev demo account) ---
const cLogin = await json('/api/v2/auth/password-login', {
  method: 'POST',
  body: JSON.stringify({ phoneNumber: '13800138000', password: '123456' })
});
const cDict = await json('/api/v2/dicts/runtime', {
  headers: { Authorization: `Bearer ${cLogin.token}` }
});
assert(cDict?.itemsByType?.order_status, 'consumer dict missing order_status');

const ot = Object.keys(opsDict.itemsByType || {}).length;
const mt = Object.keys(mDict.itemsByType || {}).length;
const ct = Object.keys(cDict.itemsByType || {}).length;
assert(ot === mt && ot === ct, `dict type count mismatch ops=${ot} merchant=${mt} consumer=${ct}`);

console.log(
  `smoke-realms ok: opsPerms=${opsPerms.length} merchantPerms=${mMer.length} packs=${(me.enabledPacks || []).join(',')} dictTypes=${ot}`
);
if (fail.length) {
  console.error(fail.join('\n'));
  process.exit(1);
}
