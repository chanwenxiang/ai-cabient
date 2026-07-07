/** 运营后台按钮级权限（借鉴 RuoYi v-permission） */
let userPermissions = new Set();

const PAGE_PERM = {
  dashboard: 'ops:dashboard:view',
  devices: 'ops:device:list',
  sessions: 'ops:session:list',
  orders: 'ops:order:list',
  recharges: 'ops:order:list',
  skus: 'ops:sku:list',
  users: 'ops:user:list',
  reports: 'ops:device:list',
  audit: 'ops:audit:list',
  recent: 'ops:audit:recent',
  disputes: 'ops:dispute',
  sla: 'ops:sla',
  ota: 'ops:ota:list',
  risk: 'ops:risk:list',
  reconciliation: 'ops:reconciliation:list',
  replenishment: 'ops:replenishment:list',
  rbac: 'ops:rbac:role'
};

const ACTION_PERM = {
  'device.create': 'ops:device:edit',
  'device.edit': 'ops:device:edit',
  'session.cancel': 'ops:session:cancel',
  'sku.edit': 'ops:sku:edit',
  'user.balance': 'ops:user:balance',
  'recharge.refund': 'ops:user:balance',
  'ota.publish': 'ops:ota:publish',
  'risk.blacklist': 'ops:risk:blacklist',
  'recon.run': 'ops:reconciliation:run',
  'replenish.edit': 'ops:replenishment:edit',
  'replenish.plan': 'ops:replenishment:edit',
  'rbac.assign': 'ops:rbac:assign',
  'rbac.role.save': 'ops:rbac:role'
};

async function loadPermissions(api) {
  try {
    const perms = await api('/api/v2/ops/admin/rbac/me/permissions', 'GET');
    userPermissions = new Set(perms || []);
    if (userPermissions.has('*')) {
      userPermissions = new Set(['*']);
    }
  } catch (e) {
    console.warn('load permissions failed, no permissions granted', e);
    userPermissions = new Set();
  }
  applyNavPermissions();
}

function hasPerm(code) {
  if (!code) return true;
  if (userPermissions.has('*')) return true;
  return userPermissions.has(code);
}

function hasPagePerm(page) {
  const perm = PAGE_PERM[page];
  if (!perm) return true;
  if (hasPerm(perm)) return true;
  if ((page === 'audit') && hasPerm('ops:dashboard:view')) return true;
  return false;
}

function applyNavPermissions() {
  document.querySelectorAll('.nav-item[data-page]').forEach(el => {
    const page = el.dataset.page;
    if (!hasPagePerm(page)) {
      el.classList.add('hidden');
    } else {
      el.classList.remove('hidden');
    }
  });
  document.querySelectorAll('[data-perm]').forEach(el => {
    const perm = el.getAttribute('data-perm');
    if (!hasPerm(perm)) {
      el.style.display = 'none';
    } else {
      el.style.display = '';
    }
  });
  document.querySelectorAll('.nav-section').forEach(section => {
    const items = section.querySelectorAll('.nav-item[data-page]');
    if (!items.length) return;
    const anyVisible = [...items].some(el => !el.classList.contains('hidden'));
    section.classList.toggle('hidden', !anyVisible);
  });
}

function permButton(actionKey, label, onclick, extraClass) {
  const perm = ACTION_PERM[actionKey];
  if (perm && !hasPerm(perm)) return '';
  const cls = extraClass || 'btn-primary btn-sm';
  return `<button class="${cls}" data-perm="${perm || ''}" onclick="${onclick}">${label}</button>`;
}

export { loadPermissions, hasPerm, permButton, applyNavPermissions, PAGE_PERM, ACTION_PERM };
