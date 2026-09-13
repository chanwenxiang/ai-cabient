/**
 * 重建完整轮所需演示账号：viewer 13900000005 + MCH-OTHER / 13800138003
 * 用法：node scripts/rebuild-demo-rbac-accounts.mjs
 */
import { execSync } from 'node:child_process';
import fs from 'node:fs';

const BASE = process.env.API_BASE || 'http://127.0.0.1';

async function adminLogin() {
  const cap = await fetch(`${BASE}/api/v2/auth/captcha`).then((r) => r.json());
  const id = cap.data.captchaId;
  const code = execSync(
    `docker exec ai-cabinet-redis-1 redis-cli --raw GET aicabinet:captcha:${id}`,
    {
      encoding: 'utf8'
    }
  ).trim();
  const res = await fetch(`${BASE}/api/v2/auth/admin-password-login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      phoneNumber: '13900000001',
      password: '123456',
      captchaId: id,
      captchaCode: code
    })
  });
  const data = await res.json();
  if (data.code !== 0) throw new Error(`login failed: ${JSON.stringify(data)}`);
  return data.data.token;
}

function headers(token) {
  return {
    Authorization: `Bearer ${token}`,
    'Content-Type': 'application/json',
    'X-Requested-With': 'XMLHttpRequest'
  };
}

async function api(token, method, path, body) {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: headers(token),
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  const text = await res.text();
  let data;
  try {
    data = JSON.parse(text);
  } catch {
    data = { raw: text.slice(0, 200) };
  }
  return { status: res.status, data };
}

async function findOperator(token, phone) {
  const r = await api(
    token,
    'GET',
    `/api/v2/ops/admin/rbac/operators?page=0&size=50&phone=${phone}`
  );
  const list = r.data?.data?.list || r.data?.data?.records || r.data?.data || [];
  const arr = Array.isArray(list) ? list : [];
  return arr.find((u) => String(u.phoneNumber || u.phone) === phone) || null;
}

async function main() {
  const token = await adminLogin();
  const out = { tokenHead: token.slice(0, 16), steps: [] };

  const roles = await api(token, 'GET', '/api/v2/ops/admin/rbac/roles');
  const roleList = roles.data?.data || [];
  const viewer = roleList.find((r) => r.roleKey === 'viewer');
  const merchant = roleList.find((r) => r.roleKey === 'merchant');
  out.roles = { viewer: viewer?.roleId, merchant: merchant?.roleId };
  if (!viewer?.roleId || !merchant?.roleId) throw new Error('missing viewer/merchant role');

  // MCH-OTHER
  let mch = await api(token, 'GET', '/api/v2/ops/admin/merchants?q=MCH-OTHER&page=0&size=20');
  const mchList = mch.data?.data?.list || mch.data?.data?.records || mch.data?.data || [];
  const hasMch = (Array.isArray(mchList) ? mchList : []).some((m) => m.merchantId === 'MCH-OTHER');
  if (!hasMch) {
    const created = await api(token, 'POST', '/api/v2/ops/admin/merchants', {
      merchantId: 'MCH-OTHER',
      merchantName: '演示商户B',
      platformRateBps: 1500,
      remark: '完整轮数据隔离'
    });
    out.steps.push({ createMerchant: created.status, body: created.data });
  } else {
    out.steps.push({ createMerchant: 'exists' });
  }

  // viewer
  let viewerUser = await findOperator(token, '13900000005');
  if (!viewerUser) {
    const created = await api(token, 'POST', '/api/v2/ops/admin/rbac/operators', {
      phoneNumber: '13900000005',
      name: '只读演示',
      password: '123456',
      status: 'ACTIVE',
      roleIds: [viewer.roleId]
    });
    out.steps.push({ createViewer: created.status, body: created.data });
    viewerUser = created.data?.data || (await findOperator(token, '13900000005'));
  } else {
    await api(token, 'PUT', `/api/v2/ops/admin/rbac/users/${viewerUser.userId}/roles`, [
      viewer.roleId
    ]);
    out.steps.push({ createViewer: 'exists', userId: viewerUser.userId });
  }

  // merchant B admin
  let mchAdmin = await findOperator(token, '13800138003');
  if (!mchAdmin) {
    const created = await api(token, 'POST', '/api/v2/ops/admin/rbac/operators', {
      phoneNumber: '13800138003',
      name: '商户B管理员',
      password: '123456',
      status: 'ACTIVE',
      roleIds: [merchant.roleId]
    });
    out.steps.push({ createMchAdmin: created.status, body: created.data });
    mchAdmin = created.data?.data || (await findOperator(token, '13800138003'));
  } else {
    out.steps.push({ createMchAdmin: 'exists', userId: mchAdmin.userId });
  }
  if (mchAdmin?.userId) {
    const rolesPut = await api(
      token,
      'PUT',
      `/api/v2/ops/admin/rbac/users/${mchAdmin.userId}/roles`,
      [merchant.roleId]
    );
    const mchPut = await api(
      token,
      'PUT',
      `/api/v2/ops/admin/rbac/users/${mchAdmin.userId}/merchants`,
      ['MCH-OTHER']
    );
    out.steps.push({
      bindRoles: rolesPut.status,
      bindMerchants: mchPut.status,
      userId: mchAdmin.userId
    });
  }

  // verify logins
  async function tryLogin(phone) {
    const cap = await fetch(`${BASE}/api/v2/auth/captcha`).then((r) => r.json());
    const id = cap.data.captchaId;
    const code = execSync(
      `docker exec ai-cabinet-redis-1 redis-cli --raw GET aicabinet:captcha:${id}`,
      {
        encoding: 'utf8'
      }
    ).trim();
    const res = await fetch(`${BASE}/api/v2/auth/admin-password-login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        phoneNumber: phone,
        password: '123456',
        captchaId: id,
        captchaCode: code
      })
    });
    const data = await res.json();
    return { phone, code: data.code, userId: data.data?.userId, message: data.message };
  }
  out.logins = {
    viewer: await tryLogin('13900000005'),
    mchB: await tryLogin('13800138003')
  };

  fs.writeFileSync(
    'docs/uat-screenshots/2026-09-12/rebuild-accounts.json',
    JSON.stringify(out, null, 2)
  );
  console.log(JSON.stringify(out, null, 2));
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
