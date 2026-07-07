import {
  esc,
  escAttr,
  formatApiError,
  handleAuthFailure,
  toast,
  pageRenderError,
  beginPageLoad,
  refreshButton,
  emptyStateHtml,
  rechargeStatusBadge,
  sessionStateLabel,
  sessionStateBadge,
  onlineStatusBadge,
  showTableLoading
} from './admin-common.js';
import { loadPermissions, permButton, hasPerm, applyNavPermissions } from './permissions.js';
import { adminRuntime } from './admin-runtime.js';

const BASE = (import.meta.env.VITE_API_BASE || '').replace(/\/$/, '') || window.location.origin;
let token = localStorage.getItem('admin_token') || '';
let skus = [];
let currentPage = 'dashboard';
const sessionFilters = { page: 0, size: 20, deviceId: '', state: '' };
const orderFilters = { page: 0, size: 20, deviceId: '' };
const rechargeFilters = { page: 0, size: 20, status: '', userId: '' };

const PAGE_TITLES = {
  dashboard: '数据概览',
  devices: '设备管理',
  sessions: '购物会话',
  orders: '订单管理',
  recharges: '充值管理',
  skus: '商品管理',
  users: '用户管理',
  reports: '设备报表',
  audit: '操作日志',
  recent: '最近操作',
  disputes: '争议审核',
  sla: 'SLA 监控',
  ota: '设备 OTA',
  risk: '风控',
  reconciliation: '对账',
  replenishment: '补货',
  rbac: '权限管理'
};
const userFilters = { page: 0, size: 20, phone: '' };
const auditFilters = { page: 0, size: 20 };
const recentFilters = { size: 20, mine: false };

function trimPhone(v) { return (v || '').replace(/\s/g, ''); }
function trimCode(v) { return (v || '').trim(); }
function fmtMoney(cents) { return '¥' + (cents / 100).toFixed(2); }
function fmtTime(iso) {
  if (!iso) return '-';
  return new Date(iso).toLocaleString('zh-CN');
}

async function api(path, method, body, auth = true) {
  const headers = { 'Content-Type': 'application/json' };
  if (auth && token) headers['Authorization'] = 'Bearer ' + token;
  const res = await fetch(BASE + path, { method, headers, body: body ? JSON.stringify(body) : undefined });
  const json = await res.json().catch(() => ({}));
  if (res.status === 401 || res.status === 403) {
    const err = new Error(formatApiError({ message: json.message, status: res.status })
      || (res.status === 403 ? '权限不足' : '登录已失效，请重新登录'));
    err.status = res.status;
    handleAuthFailure(err);
    throw err;
  }
  if (!res.ok || json.code !== 0) {
    const err = new Error(formatApiError({ message: json.message || json.error }) || JSON.stringify(json));
    err.status = res.status;
    throw err;
  }
  return json.data;
}

function showErr(id, msg) {
  const el = document.getElementById(id);
  el.textContent = msg;
  el.classList.remove('hidden');
}

function initLoginHints() {
  const isLocal = location.hostname === 'localhost' || location.hostname === '127.0.0.1';
  if (!isLocal) return;
  const phone = document.getElementById('phone');
  const code = document.getElementById('code');
  if (phone) phone.placeholder = '本地测试运营号 13900000001';
  if (code) code.placeholder = '本地固定 123456';
}

function setLoginLoading(loading, kind) {
  const loginBtn = document.getElementById('loginBtn');
  const sendCodeBtn = document.getElementById('sendCodeBtn');
  const phone = document.getElementById('phone');
  const code = document.getElementById('code');
  const busy = !!loading;
  if (kind === 'login') {
    if (loginBtn) {
      loginBtn.disabled = busy;
      loginBtn.classList.toggle('btn-loading', busy);
      loginBtn.textContent = busy ? '登录中…' : '登录';
    }
    if (sendCodeBtn) sendCodeBtn.disabled = busy;
    if (phone) phone.readOnly = busy;
    if (code) code.readOnly = busy;
  } else if (kind === 'code') {
    if (sendCodeBtn) {
      sendCodeBtn.disabled = busy;
      sendCodeBtn.classList.toggle('btn-loading', busy);
      sendCodeBtn.textContent = busy ? '发送中…' : '获取验证码';
    }
    if (loginBtn) loginBtn.disabled = busy;
    if (phone) phone.readOnly = busy;
  }
}

function initLoginForm() {
  const form = document.getElementById('loginForm');
  if (!form) return;
  form.addEventListener('submit', (e) => {
    e.preventDefault();
    login();
  });
}

async function sendCode() {
  const sendCodeBtn = document.getElementById('sendCodeBtn');
  if (sendCodeBtn?.disabled) return;
  const phone = trimPhone(document.getElementById('phone').value);
  if (!phone) {
    showErr('loginErr', '请输入手机号');
    return;
  }
  document.getElementById('loginErr').classList.add('hidden');
  setLoginLoading(true, 'code');
  try {
    await api(`/api/v2/auth/sms-code?phoneNumber=${encodeURIComponent(phone)}`, 'POST', null, false);
    const devHint = location.hostname === 'localhost' || location.hostname === '127.0.0.1'
      ? '（本地 dev 固定 123456，可直接登录）' : '';
    toast('验证码已发送' + devHint, 'ok');
    document.getElementById('code')?.focus();
  } catch (e) {
    showErr('loginErr', e.message);
  } finally {
    setLoginLoading(false, 'code');
  }
}

async function login() {
  const loginBtn = document.getElementById('loginBtn');
  if (loginBtn?.disabled) return;
  const phone = trimPhone(document.getElementById('phone').value);
  const code = trimCode(document.getElementById('code').value);
  if (!phone) {
    showErr('loginErr', '请输入手机号');
    document.getElementById('phone')?.focus();
    return;
  }
  if (!code) {
    showErr('loginErr', '请输入验证码');
    document.getElementById('code')?.focus();
    return;
  }
  document.getElementById('loginErr').classList.add('hidden');
  setLoginLoading(true, 'login');
  try {
    const data = await api('/api/v2/auth/admin-login', 'POST', { phoneNumber: phone, code }, false);
    token = data.token;
    localStorage.setItem('admin_token', token);
    localStorage.setItem('admin_userId', data.userId);
    localStorage.setItem('admin_phone', phone);
    showApp();
  } catch (e) {
    showErr('loginErr', e.message);
  } finally {
    setLoginLoading(false, 'login');
  }
}

function logout() {
  stopDevicesAutoRefresh();
  token = '';
  localStorage.removeItem('admin_token');
  localStorage.removeItem('admin_userId');
  localStorage.removeItem('admin_phone');
  document.getElementById('appView').classList.add('hidden');
  document.getElementById('loginView').classList.remove('hidden');
  setLoginLoading(false, 'login');
  setLoginLoading(false, 'code');
  document.getElementById('phone')?.focus();
}

function setUserInfoPlaceholder() {
  const el = document.getElementById('userInfo');
  if (!el) return;
  const phone = localStorage.getItem('admin_phone') || '';
  el.innerHTML = phone
    ? `<span class="user-name">运营账号</span><span class="user-detail">${esc(phone)} · 加载角色…</span>`
    : `<span class="user-name">运营账号</span><span class="user-detail">加载中…</span>`;
}

async function refreshUserInfo() {
  const el = document.getElementById('userInfo');
  if (!el) return;
  try {
    const me = await api('/api/v2/ops/admin/rbac/me', 'GET');
    localStorage.setItem('admin_userId', me.userId);
    if (me.phoneNumber) localStorage.setItem('admin_phone', me.phoneNumber);
    const displayName = me.name || '运营账号';
    const roles = (me.roleNames && me.roleNames.length)
      ? me.roleNames.join('、')
      : '未分配角色';
    const permHint = me.permissionCount > 0 ? ` · ${me.permissionCount} 项权限` : '';
    el.innerHTML = `<span class="user-name">${esc(displayName)}</span>`
      + `<span class="user-detail">${esc(me.phoneNumber || '-')} · ${esc(roles)}${esc(permHint)}</span>`;
  } catch (e) {
    if (!handleAuthFailure(e)) setUserInfoPlaceholder();
  }
}

function showApp() {
  document.getElementById('loginView').classList.add('hidden');
  document.getElementById('appView').classList.remove('hidden');
  setUserInfoPlaceholder();
  loadVisitedTabs();
  Promise.all([refreshUserInfo(), loadPermissions(api)]).then(() => {
    const page = getPageFromHash();
    navigate(page, { replaceHash: true, init: true });
  });
}

const MAX_VISITED_TABS = 12;
let visitedTabs = [];
let canGoBack = false;

function loadVisitedTabs() {
  try {
    const raw = sessionStorage.getItem('admin_visited_tabs');
    visitedTabs = raw ? JSON.parse(raw) : ['dashboard'];
    if (!Array.isArray(visitedTabs) || !visitedTabs.length) visitedTabs = ['dashboard'];
    visitedTabs = visitedTabs.filter(p => PAGE_TITLES[p]);
    if (!visitedTabs.includes('dashboard')) visitedTabs.unshift('dashboard');
  } catch {
    visitedTabs = ['dashboard'];
  }
}

function saveVisitedTabs() {
  sessionStorage.setItem('admin_visited_tabs', JSON.stringify(visitedTabs));
}

function recordVisitedTab(page) {
  if (!PAGE_TITLES[page]) return;
  visitedTabs = visitedTabs.filter(p => p !== page);
  visitedTabs.push(page);
  while (visitedTabs.length > MAX_VISITED_TABS) {
    const idx = visitedTabs.findIndex(p => p !== 'dashboard');
    if (idx >= 0) visitedTabs.splice(idx, 1);
    else break;
  }
  saveVisitedTabs();
}

function renderTagsView() {
  const el = document.getElementById('tagsView');
  if (!el) return;
  if (visitedTabs.length <= 1) {
    el.classList.add('hidden');
    el.innerHTML = '';
    return;
  }
  el.classList.remove('hidden');
  el.innerHTML = visitedTabs.map(p => {
    const active = p === currentPage;
    const closable = p !== 'dashboard';
    return `<button type="button" class="tag-item ${active ? 'active' : ''}" onclick="navigate('${escAttr(p)}')">
      <span>${esc(PAGE_TITLES[p] || p)}</span>
      ${closable ? `<span class="tag-close" onclick="event.stopPropagation();closeVisitedTab('${escAttr(p)}')" title="关闭">×</span>` : ''}
    </button>`;
  }).join('');
}

function closeVisitedTab(page) {
  if (page === 'dashboard') return;
  visitedTabs = visitedTabs.filter(p => p !== page);
  saveVisitedTabs();
  if (currentPage === page) {
    navigate(visitedTabs[visitedTabs.length - 1] || 'dashboard');
  } else {
    renderTagsView();
  }
}

function updateBackButton() {
  const btn = document.getElementById('navBackBtn');
  if (btn) btn.disabled = !canGoBack;
}

function navigateBack() {
  if (canGoBack) history.back();
}

function getPageFromHash() {
  const m = location.hash.match(/^#\/([a-z]+)$/);
  const page = m ? m[1] : 'dashboard';
  return PAGE_TITLES[page] ? page : 'dashboard';
}

function navigate(page, options = {}) {
  if (!PAGE_TITLES[page]) page = 'dashboard';
  const fromPopstate = !!options.fromPopstate;
  const hash = '#/' + page;

  if (page === currentPage && !fromPopstate && !options.force) {
    recordVisitedTab(page);
    renderTagsView();
    return;
  }

  if (!fromPopstate && !options.replaceHash) {
    if (location.hash !== hash) {
      history.pushState({ page }, '', hash);
      historyDepth += 1;
      canGoBack = historyDepth > 0;
      updateBackButton();
    }
    recordVisitedTab(page);
  } else if (options.replaceHash && location.hash !== hash) {
    history.replaceState({ page }, '', hash);
    if (options.init) recordVisitedTab(page);
  }

  currentPage = page;
  if (page !== 'devices') stopDevicesAutoRefresh();
  document.getElementById('pageTitle').textContent = PAGE_TITLES[page] || page;
  document.querySelectorAll('.nav-item').forEach(el => {
    el.classList.toggle('active', el.dataset.page === page);
  });
  renderTagsView();
  updateBackButton();
  beginPageLoad(page);
  const ops = adminRuntime.opsLoaders || {};
  const loaders = {
    dashboard: loadDashboard,
    devices: loadDevices,
    sessions: loadSessionsPage,
    orders: loadOrdersPage,
    recharges: loadRechargesPage,
    skus: loadSkusPage,
    users: loadUsersPage,
    reports: loadReportsPage,
    audit: loadAuditPage,
    recent: loadRecentPage,
    disputes: loadDisputes,
    sla: ops.sla || loadDashboard,
    ota: ops.ota || loadDashboard,
    risk: ops.risk || loadDashboard,
    reconciliation: ops.reconciliation || loadDashboard,
    replenishment: ops.replenishment || loadDashboard,
    rbac: ops.rbac || loadDashboard
  };
  (loaders[page] || loadDashboard)();
}

function stateBadge(state) {
  return sessionStateBadge(state);
}

function onlineBadge(status) {
  return onlineStatusBadge(status);
}

async function loadDashboard() {
  const el = document.getElementById('pageContent');
  const page = 'dashboard';
  try {
    const [s, trend, recent] = await Promise.all([
      api('/api/v2/ops/admin/stats', 'GET'),
      api('/api/v2/ops/admin/trend', 'GET'),
      api('/api/v2/ops/admin/audit-logs/recent?size=5&mine=false', 'GET').catch(() => [])
    ]);
    if (currentPage !== page) return;
    const days = trend.last7Days || [];
    const maxRevenue = Math.max(1, ...days.map(d => d.revenueCents));
    const chartHtml = days.length ? `
      <div class="card">
        <h3 style="margin:0 0 4px;font-size:1rem">近7日营收</h3>
        <div class="chart">${days.map(d => {
          const h = Math.max(4, Math.round((d.revenueCents / maxRevenue) * 140));
          const label = d.date.slice(5);
          return `<div class="chart-col">
            <div class="chart-val">${fmtMoney(d.revenueCents)}</div>
            <div class="chart-bar" style="height:${h}px" title="${d.orderCount} 单"></div>
            <div class="chart-label">${label}<br>${d.orderCount}单</div>
          </div>`;
        }).join('')}</div>
      </div>` : '';
    el.innerHTML = `
      <div class="card"><div class="filters">${refreshButton('loadDashboard()')}</div></div>
      <div class="stats">
        <div class="stat"><div class="label">设备总数</div><div class="value">${s.deviceTotal}</div></div>
        <div class="stat"><div class="label">在线设备</div><div class="value ok">${s.deviceOnline}</div></div>
        <div class="stat"><div class="label">进行中会话</div><div class="value warn">${s.sessionActive}</div></div>
        <div class="stat"><div class="label">今日会话</div><div class="value">${s.sessionToday}</div></div>
        <div class="stat"><div class="label">今日订单</div><div class="value">${s.orderToday}</div></div>
        <div class="stat"><div class="label">今日营收</div><div class="value ok">${fmtMoney(s.revenueTodayCents)}</div></div>
        <div class="stat"><div class="label">累计订单</div><div class="value">${s.orderTotal}</div></div>
        <div class="stat"><div class="label">累计营收</div><div class="value">${fmtMoney(s.revenueTotalCents)}</div></div>
        <div class="stat"><div class="label">待审争议</div><div class="value warn">${s.disputeOpen}</div></div>
      </div>
      ${chartHtml}
      ${recent && recent.length ? `
      <div class="card">
        <div class="pane-head">
          <h3 style="margin:0;font-size:1rem;color:var(--text)">最新动态</h3>
          <button class="btn-ghost btn-sm" onclick="navigate('audit')">操作日志</button>
        </div>
        ${typeof renderAuditTableHtml === 'function' ? renderAuditTableHtml(recent) : ''}
      </div>` : ''}
      <div class="card">
        <p class="meta">设备在线：模拟器/工控机每 30 秒上报心跳，2 分钟无心跳自动标记离线。用户余额可在「用户」页调整。</p>
      </div>`;
  } catch (e) {
    if (currentPage !== page) return;
    pageRenderError(el, e);
  }
}

async function exportCsv(path, filename) {
  const res = await fetch(BASE + path, { headers: { Authorization: 'Bearer ' + token } });
  if (res.status === 401 || res.status === 403) {
    handleAuthFailure({ status: res.status, message: '登录已失效' });
    throw new Error('登录已失效');
  }
  if (!res.ok) throw new Error('导出失败');
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}

async function loadDevices() {
  const el = document.getElementById('pageContent');
  const page = 'devices';
  try {
    const devices = await api('/api/v2/ops/admin/devices', 'GET');
    if (currentPage !== page) return;
    el.innerHTML = `
      <div class="card">
        <div class="filters">
          ${permButton('device.create', '注册新设备', 'showDeviceForm()', 'btn-primary')}
          ${refreshButton('loadDevices()')}
        </div>
      </div>
      ${devices.length ? `
      <div class="card" style="padding:0;overflow:hidden">
        <table>
          <thead><tr>
            <th>设备ID</th><th>名称</th><th>类型</th><th>状态</th><th>活跃会话</th><th>最后心跳</th><th>操作</th>
          </tr></thead>
          <tbody>${devices.map(d => `<tr>
            <td><code>${esc(d.deviceId)}</code></td>
            <td>${esc(d.deviceName || '-')}</td>
            <td>${esc(d.deviceType || '-')}</td>
            <td>${onlineBadge(d.onlineStatus)}</td>
            <td>${d.activeSessionId ? `${esc(d.activeSessionId)}<br>${stateBadge(d.activeSessionState)}` : '-'}</td>
            <td>${fmtTime(d.updatedAt)}</td>
            <td>${hasPerm('ops:device:edit') ? `<button class="btn-ghost btn-sm" onclick='showDeviceForm(${JSON.stringify(d)})'>编辑</button>` : '-'}</td>
          </tr>`).join('')}</tbody>
        </table>
      </div>` : `<div class="card">${emptyStateHtml('暂无设备', '点击「注册新设备」添加第一台柜机', 'loadDevices()')}</div>`}`;
    applyNavPermissions();
    startDevicesAutoRefresh();
  } catch (e) {
    if (currentPage !== page) return;
    pageRenderError(el, e);
  }
}

function showDeviceForm(device) {
  const isEdit = !!device;
  document.getElementById('modalRoot').innerHTML = `
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal" onclick="event.stopPropagation()">
        <h3>${isEdit ? '编辑设备' : '注册新设备'}</h3>
        <label>设备ID</label>
        <input id="dfId" value="${isEdit ? escAttr(device.deviceId) : ''}" ${isEdit ? 'disabled' : ''} placeholder="CAB-002">
        <label>设备名称</label>
        <input id="dfName" value="${isEdit ? escAttr(device.deviceName || '') : ''}" placeholder="1号柜">
        <label>设备类型</label>
        <input id="dfType" value="${isEdit ? escAttr(device.deviceType || 'AI_CABINET_V1') : 'AI_CABINET_V1'}">
        <div class="filters" style="margin-top:12px">
          <button class="btn-primary" onclick="saveDevice(${isEdit})">保存</button>
          <button class="btn-ghost" onclick="closeModal()">取消</button>
        </div>
      </div>
    </div>`;
  document.getElementById('modalRoot').classList.remove('hidden');
}

async function saveDevice(isEdit) {
  const deviceId = document.getElementById('dfId').value.trim();
  const deviceName = document.getElementById('dfName').value.trim();
  const deviceType = document.getElementById('dfType').value.trim();
  try {
    if (isEdit) {
      await api('/api/v2/ops/admin/devices/' + encodeURIComponent(deviceId), 'PATCH',
        { deviceName, deviceType });
    } else {
      await api('/api/v2/ops/admin/devices', 'POST', { deviceId, deviceName, deviceType });
    }
    closeModal();
    toast('保存成功', 'ok');
    loadDevices();
  } catch (e) {
    if (!handleAuthFailure(e)) toast('保存失败: ' + e.message, 'err');
  }
}

function loadSessionsPage() {
  document.getElementById('pageContent').innerHTML = `
    <div class="card">
      <div class="filters">
        <div><label>设备ID</label><input id="sfDevice" value="${sessionFilters.deviceId}" placeholder="CAB-001"></div>
        <div><label>状态</label>
          <select id="sfState">
            <option value="">全部</option>
            ${['CREATED','OPENING','SHOPPING','RECOGNIZING','WAITING_UPLOAD','SETTLING','COMPLETED','DISPUTED','FAILED','CANCELLED']
              .map(s => `<option value="${s}" ${sessionFilters.state === s ? 'selected' : ''}>${esc(sessionStateLabel(s))}</option>`).join('')}
          </select>
        </div>
        <div><button class="btn-primary" onclick="searchSessions()">查询</button></div>
        <div>${refreshButton('fetchSessions()')}</div>
        <div><button class="btn-ghost" onclick="exportSessionsCsv()">导出 CSV</button></div>
      </div>
      <div id="sessionTable"></div>
    </div>`;
  showTableLoading(document.getElementById('sessionTable'), 7, 6);
  fetchSessions();
}

async function searchSessions() {
  sessionFilters.deviceId = document.getElementById('sfDevice').value.trim();
  sessionFilters.state = document.getElementById('sfState').value;
  sessionFilters.page = 0;
  fetchSessions();
}

async function fetchSessions() {
  const table = document.getElementById('sessionTable');
  if (!table) return;
  showTableLoading(table, 7, 6);
  try {
    const q = new URLSearchParams({
      page: sessionFilters.page,
      size: sessionFilters.size,
      ...(sessionFilters.deviceId ? { deviceId: sessionFilters.deviceId } : {}),
      ...(sessionFilters.state ? { state: sessionFilters.state } : {})
    });
    const data = await api('/api/v2/ops/admin/sessions?' + q, 'GET');
    if (!data.items.length) {
      table.innerHTML = emptyStateHtml('暂无会话', '调整筛选条件或等待用户开门购物', 'fetchSessions()');
      return;
    }
    const canCancel = s => !['COMPLETED', 'CANCELLED'].includes(s.state);
    table.innerHTML = `
      <table>
        <thead><tr>
          <th>会话ID</th><th>用户</th><th>设备</th><th>状态</th><th>订单</th><th>创建时间</th><th>操作</th>
        </tr></thead>
        <tbody>${data.items.map(s => `<tr>
          <td><code>${esc(s.sessionId)}</code></td>
          <td>${esc(s.userId)}</td>
          <td>${esc(s.deviceId)}</td>
          <td>${stateBadge(s.state)}</td>
          <td>${esc(s.orderId || '-')}</td>
          <td>${fmtTime(s.createdAt)}</td>
          <td>${canCancel(s) && hasPerm('ops:session:cancel') ? `<button class="btn-danger btn-sm" onclick="cancelSession('${escAttr(s.sessionId)}')">取消</button>` : '-'}</td>
        </tr>`).join('')}</tbody>
      </table>
      ${renderPagination(data, 'session')}`;
  } catch (e) {
    pageRenderError(table, e, false);
  }
}

async function exportSessionsCsv() {
  try {
    const q = new URLSearchParams({
      ...(sessionFilters.deviceId ? { deviceId: sessionFilters.deviceId } : {}),
      ...(sessionFilters.state ? { state: sessionFilters.state } : {})
    });
    await exportCsv('/api/v2/ops/admin/sessions/export?' + q, 'sessions.csv');
  } catch (e) {
    if (!handleAuthFailure(e)) toast(e.message, 'err');
  }
}

async function cancelSession(sessionId) {
  if (!confirm('确认取消会话 ' + sessionId + '？设备将可再次开门。')) return;
  try {
    await api('/api/v2/ops/admin/sessions/' + sessionId + '/cancel', 'POST');
    toast('会话已取消', 'ok');
    fetchSessions();
    if (currentPage === 'devices') loadDevices();
  } catch (e) {
    if (!handleAuthFailure(e)) toast('失败: ' + e.message, 'err');
  }
}

function loadOrdersPage() {
  document.getElementById('pageContent').innerHTML = `
    <div class="card">
      <div class="filters">
        <div><label>设备ID</label><input id="ofDevice" value="${orderFilters.deviceId}" placeholder="可选"></div>
        <div><button class="btn-primary" onclick="searchOrders()">查询</button></div>
        <div>${refreshButton('fetchOrders()')}</div>
        <div><button class="btn-ghost" onclick="exportOrdersCsv()">导出 CSV</button></div>
      </div>
      <div id="orderTable"></div>
    </div>`;
  showTableLoading(document.getElementById('orderTable'), 8, 6);
  fetchOrders();
}

function searchOrders() {
  orderFilters.deviceId = document.getElementById('ofDevice').value.trim();
  orderFilters.page = 0;
  fetchOrders();
}

async function exportOrdersCsv() {
  try {
    const q = new URLSearchParams(orderFilters.deviceId ? { deviceId: orderFilters.deviceId } : {});
    await exportCsv('/api/v2/ops/admin/orders/export?' + q, 'orders.csv');
  } catch (e) {
    if (!handleAuthFailure(e)) toast(e.message, 'err');
  }
}

async function fetchOrders() {
  const table = document.getElementById('orderTable');
  if (!table) return;
  showTableLoading(table, 8, 6);
  try {
    const q = new URLSearchParams({
      page: orderFilters.page,
      size: orderFilters.size,
      ...(orderFilters.deviceId ? { deviceId: orderFilters.deviceId } : {})
    });
    const data = await api('/api/v2/ops/admin/orders?' + q, 'GET');
    if (!data.items.length) {
      table.innerHTML = emptyStateHtml('暂无订单', '完成购物后会在此展示订单记录', 'fetchOrders()');
      return;
    }
    table.innerHTML = `
      <table>
        <thead><tr>
          <th>订单ID</th><th>会话</th><th>用户</th><th>设备</th><th>金额</th><th>商品行</th><th>时间</th><th>操作</th>
        </tr></thead>
        <tbody>${data.items.map(o => `<tr>
          <td><code>${esc(o.orderId)}</code></td>
          <td>${esc(o.sessionId)}</td>
          <td>${esc(o.userId)}</td>
          <td>${esc(o.deviceId)}</td>
          <td>${fmtMoney(o.totalAmountCents)}</td>
          <td>${esc(o.lineCount)}</td>
          <td>${fmtTime(o.createdAt)}</td>
          <td><button class="btn-ghost btn-sm" onclick="showOrderDetail('${escAttr(o.orderId)}')">详情</button></td>
        </tr>`).join('')}</tbody>
      </table>
      ${renderPagination(data, 'order')}`;
  } catch (e) {
    pageRenderError(table, e, false);
  }
}

function renderPagination(data, type) {
  const totalPages = Math.max(1, Math.ceil(data.total / data.size));
  const page = data.page + 1;
  return `<div class="pagination">
    共 ${data.total} 条，第 ${page}/${totalPages} 页
    <button class="btn-ghost btn-sm" ${data.page <= 0 ? 'disabled' : ''} onclick="changePage('${type}', ${data.page - 1})">上一页</button>
    <button class="btn-ghost btn-sm" ${page >= totalPages ? 'disabled' : ''} onclick="changePage('${type}', ${data.page + 1})">下一页</button>
  </div>`;
}

function changePage(type, page) {
  if (type === 'session') {
    sessionFilters.page = Math.max(0, page);
    fetchSessions();
  } else if (type === 'user') {
    userFilters.page = Math.max(0, page);
    fetchUsers();
  } else if (type === 'audit') {
    auditFilters.page = Math.max(0, page);
    fetchAuditLogs();
  } else if (type === 'recharge') {
    rechargeFilters.page = Math.max(0, page);
    fetchRecharges();
  } else {
    orderFilters.page = Math.max(0, page);
    fetchOrders();
  }
}

async function showOrderDetail(orderId) {
  try {
    const o = await api('/api/v2/ops/admin/orders/' + orderId, 'GET');
    const lines = (o.lines || []).map(l =>
      `<tr><td>${esc(l.skuName)}</td><td>${esc(l.skuId)}</td><td>${esc(l.quantity)}</td><td>${fmtMoney(l.unitPriceCents)}</td><td>${fmtMoney(l.lineAmountCents)}</td></tr>`
    ).join('');
    document.getElementById('modalRoot').innerHTML = `
      <div class="modal-backdrop" onclick="closeModal(event)">
        <div class="modal" onclick="event.stopPropagation()">
          <h3>订单 ${esc(o.orderId)}</h3>
          <div class="meta">会话 ${esc(o.sessionId)} · 设备 ${esc(o.deviceId)} · 用户 ${esc(o.userId)}</div>
          <table style="margin-top:12px">
            <thead><tr><th>商品</th><th>SKU</th><th>数量</th><th>单价</th><th>小计</th></tr></thead>
            <tbody>${lines}</tbody>
          </table>
          <p style="margin-top:12px;font-weight:700">合计 ${fmtMoney(o.totalAmountCents)}</p>
          <button class="btn-ghost" onclick="closeModal()">关闭</button>
        </div>
      </div>`;
    document.getElementById('modalRoot').classList.remove('hidden');
  } catch (e) {
    if (!handleAuthFailure(e)) toast('加载失败: ' + e.message, 'err');
  }
}

function closeModal(e) {
  if (e && e.target !== e.currentTarget) return;
  document.getElementById('modalRoot').classList.add('hidden');
  document.getElementById('modalRoot').innerHTML = '';
}

async function loadSkus() {
  try {
    skus = await api('/api/v2/ops/admin/skus', 'GET');
  } catch (e) {
    skus = [{ skuId: 'SKU-DEMO-001', skuName: '演示商品', priceCents: 350 }];
  }
}

function loadSkusPage() {
  document.getElementById('pageContent').innerHTML = `
    <div class="card">
      <div class="filters">
        ${permButton('sku.edit', '新增商品', 'showSkuForm()', 'btn-primary')}
        ${refreshButton('loadSkusPage()')}
      </div>
      <div id="skuTable"></div>
    </div>`;
  showTableLoading(document.getElementById('skuTable'), 4, 5);
  fetchSkusTable();
}

async function fetchSkusTable() {
  const table = document.getElementById('skuTable');
  if (!table) return;
  showTableLoading(table, 4, 5);
  try {
    const list = await api('/api/v2/ops/admin/skus', 'GET');
    skus = list;
    if (!list.length) {
      table.innerHTML = emptyStateHtml('暂无商品', '添加 SKU 后可在争议审核中选择商品', 'fetchSkusTable()');
      return;
    }
    table.innerHTML = `
      <table>
        <thead><tr><th>SKU ID</th><th>名称</th><th>价格</th><th>操作</th></tr></thead>
        <tbody>${list.map(s => `<tr>
          <td><code>${esc(s.skuId)}</code></td>
          <td>${esc(s.skuName)}</td>
          <td>${fmtMoney(s.priceCents)}</td>
          <td>${hasPerm('ops:sku:edit') ? `<button class="btn-ghost btn-sm" onclick='showSkuForm(${JSON.stringify(s)})'>编辑</button>` : '-'}</td>
        </tr>`).join('')}</tbody>
      </table>`;
  } catch (e) {
    pageRenderError(table, e, false);
  }
}

function showSkuForm(sku) {
  const isEdit = !!sku;
  document.getElementById('modalRoot').innerHTML = `
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal" onclick="event.stopPropagation()">
        <h3>${isEdit ? '编辑商品' : '新增商品'}</h3>
        <label>SKU ID</label>
        <input id="skuId" value="${isEdit ? escAttr(sku.skuId) : ''}" ${isEdit ? 'disabled' : ''} placeholder="SKU-XXX-001">
        <label>商品名称</label>
        <input id="skuName" value="${isEdit ? escAttr(sku.skuName) : ''}" placeholder="可乐 330ml">
        <label>价格（分）</label>
        <input id="skuPrice" type="number" min="1" value="${isEdit ? sku.priceCents : 350}">
        <p class="meta">价格单位：分（350 = ¥3.50）</p>
        <div class="filters" style="margin-top:12px">
          <button class="btn-primary" onclick="saveSku(${isEdit})">保存</button>
          <button class="btn-ghost" onclick="closeModal()">取消</button>
        </div>
      </div>
    </div>`;
  document.getElementById('modalRoot').classList.remove('hidden');
}

async function saveSku(isEdit) {
  const skuId = document.getElementById('skuId').value.trim();
  const skuName = document.getElementById('skuName').value.trim();
  const priceCents = parseInt(document.getElementById('skuPrice').value, 10);
  if (!skuId || !skuName || !priceCents) { toast('请填写完整', 'err'); return; }
  try {
    const body = { skuId, skuName, priceCents };
    if (isEdit) {
      await api('/api/v2/ops/admin/skus/' + encodeURIComponent(skuId), 'PUT', body);
    } else {
      await api('/api/v2/ops/admin/skus', 'POST', body);
    }
    closeModal();
    toast('保存成功', 'ok');
    loadSkusPage();
  } catch (e) {
    if (!handleAuthFailure(e)) toast('保存失败: ' + e.message, 'err');
  }
}

function loadUsersPage() {
  document.getElementById('pageContent').innerHTML = `
    <div class="card">
      <div class="filters">
        <div><label>手机号</label><input id="ufPhone" value="${userFilters.phone}" placeholder="138"></div>
        <div><button class="btn-primary" onclick="searchUsers()">查询</button></div>
        <div>${refreshButton('fetchUsers()')}</div>
      </div>
      <div id="userTable"></div>
    </div>`;
  showTableLoading(document.getElementById('userTable'), 8, 6);
  fetchUsers();
}

function searchUsers() {
  userFilters.phone = document.getElementById('ufPhone').value.trim();
  userFilters.page = 0;
  fetchUsers();
}

async function fetchUsers() {
  const table = document.getElementById('userTable');
  if (!table) return;
  showTableLoading(table, 8, 6);
  try {
    const q = new URLSearchParams({
      page: userFilters.page,
      size: userFilters.size,
      ...(userFilters.phone ? { phone: userFilters.phone } : {})
    });
    const data = await api('/api/v2/ops/admin/users?' + q, 'GET');
    if (!data.items.length) {
      table.innerHTML = emptyStateHtml('暂无用户', '消费者通过小程序注册后会出现在此列表', 'fetchUsers()');
      return;
    }
    table.innerHTML = `
      <table>
        <thead><tr>
          <th>userId</th><th>手机号</th><th>姓名</th><th>角色</th><th>实名</th><th>余额</th><th>注册时间</th><th>操作</th>
        </tr></thead>
        <tbody>${data.items.map(u => `<tr>
          <td>${esc(u.userId)}</td>
          <td>${esc(u.phoneNumber)}</td>
          <td>${esc(u.name || '-')}</td>
          <td>${u.role === 'OPERATOR' ? '<span class="badge badge-active">运营</span>' : '消费者'}</td>
          <td>${u.verified ? '是' : '否'}</td>
          <td>${fmtMoney(u.balanceCents)}</td>
          <td>${fmtTime(u.createdAt)}</td>
          <td>${u.role === 'OPERATOR'
            ? (hasPerm('ops:rbac:assign') ? `<button class="btn-ghost btn-sm" onclick="showRbacAssignForUser(${u.userId})">分配角色</button>` : '-')
            : (hasPerm('ops:user:balance') ? `<button class="btn-ghost btn-sm" onclick="showBalanceForm(${u.userId}, ${u.balanceCents})">调余额</button>` : '-')}</td>
        </tr>`).join('')}</tbody>
      </table>
      ${renderPagination(data, 'user')}`;
  } catch (e) {
    pageRenderError(table, e, false);
  }
}

function showBalanceForm(userId, balanceCents) {
  document.getElementById('modalRoot').innerHTML = `
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal" onclick="event.stopPropagation()">
        <h3>调整余额 · userId ${userId}</h3>
        <p class="meta">当前余额 ${fmtMoney(balanceCents)}</p>
        <label>变动金额（分，正数充值/负数扣减）</label>
        <input id="deltaCents" type="number" value="1000" placeholder="1000 = 加10元">
        <p class="meta">例：1000 表示加 ¥10.00；-350 表示扣 ¥3.50</p>
        <div class="filters" style="margin-top:12px">
          <button class="btn-primary" onclick="saveBalance(${userId})">确认</button>
          <button class="btn-ghost" onclick="closeModal()">取消</button>
        </div>
      </div>
    </div>`;
  document.getElementById('modalRoot').classList.remove('hidden');
}

async function saveBalance(userId) {
  const deltaCents = parseInt(document.getElementById('deltaCents').value, 10);
  if (isNaN(deltaCents) || deltaCents === 0) { toast('请输入有效金额', 'err'); return; }
  try {
    await api('/api/v2/ops/admin/users/' + userId + '/balance', 'POST', { deltaCents });
    closeModal();
    fetchUsers();
    toast('余额已更新', 'ok');
  } catch (e) {
    if (!handleAuthFailure(e)) toast('失败: ' + e.message, 'err');
  }
}

function showRbacAssignForUser(userId) {
  if (typeof openRbacUserAssign === 'function') {
    openRbacUserAssign(userId);
  } else {
    navigate('rbac');
  }
}

async function loadReportsPage() {
  const el = document.getElementById('pageContent');
  const page = 'reports';
  try {
    const reports = await api('/api/v2/ops/admin/reports/devices', 'GET');
    if (currentPage !== page) return;
    el.innerHTML = `
      <div class="card">
        <div class="filters">${refreshButton('loadReportsPage()')}</div>
      </div>`;
    if (!reports.length) {
      el.innerHTML += `<div class="card">${emptyStateHtml('暂无设备报表', '注册设备并产生订单后自动生成统计', 'loadReportsPage()')}</div>`;
      return;
    }
    el.innerHTML += `
      <div class="card" style="padding:0;overflow:hidden">
        <table>
          <thead><tr>
            <th>设备</th><th>状态</th><th>累计订单</th><th>累计营收</th>
            <th>今日订单</th><th>今日营收</th><th>累计会话</th><th>进行中</th>
          </tr></thead>
          <tbody>${reports.map(r => `<tr>
            <td><code>${esc(r.deviceId)}</code><br><span class="meta">${esc(r.deviceName || '-')}</span></td>
            <td>${onlineBadge(r.onlineStatus)}</td>
            <td>${r.orderTotal}</td>
            <td>${fmtMoney(r.revenueTotalCents)}</td>
            <td>${r.orderToday}</td>
            <td>${fmtMoney(r.revenueTodayCents)}</td>
            <td>${r.sessionTotal}</td>
            <td>${r.sessionActive ? '<span class="badge badge-active">是</span>' : '-'}</td>
          </tr>`).join('')}</tbody>
        </table>
      </div>`;
  } catch (e) {
    if (currentPage !== page) return;
    pageRenderError(el, e);
  }
}

function loadAuditPage() {
  document.getElementById('pageContent').innerHTML = `
    <div class="card">
      <div class="filters">
        ${refreshButton('fetchAuditLogs()')}
      </div>
      <div id="auditTable"></div>
    </div>`;
  showTableLoading(document.getElementById('auditTable'), 5, 6);
  fetchAuditLogs();
}

async function fetchAuditLogs() {
  const table = document.getElementById('auditTable');
  if (!table) return;
  showTableLoading(table, 5, 6);
  try {
    const q = new URLSearchParams({ page: auditFilters.page, size: auditFilters.size });
    const data = await api('/api/v2/ops/admin/audit-logs?' + q, 'GET');
    if (!data.items.length) {
      table.innerHTML = emptyStateHtml('暂无操作记录', '运营人员的敏感操作会记录在此', 'fetchAuditLogs()');
      return;
    }
    const rows = data.items.map(l => `<tr>
      <td>${fmtTime(l.createdAt)}</td>
      <td>${typeof formatOperatorCell === 'function' ? formatOperatorCell(l) : esc(l.operatorId)}</td>
      <td><code>${esc(l.action)}</code></td>
      <td>${esc(l.targetType || '-')} ${esc(l.targetId || '')}</td>
      <td class="meta">${esc(l.detail || '-')}</td>
    </tr>`).join('');
    table.innerHTML = `
      <table>
        <thead><tr>
          <th>时间</th><th>操作人</th><th>动作</th><th>对象</th><th>详情</th>
        </tr></thead>
        <tbody>${rows}</tbody>
      </table>
      ${renderPagination(data, 'audit')}`;
  } catch (e) {
    pageRenderError(table, e, false);
  }
}

function loadRecentPage() {
  document.getElementById('pageContent').innerHTML = `
    <div class="card">
      <div class="filters">
        <button class="btn-ghost btn-sm ${!recentFilters.mine ? 'active-tab' : ''}" onclick="setRecentScope(false)">全部操作</button>
        <button class="btn-ghost btn-sm ${recentFilters.mine ? 'active-tab' : ''}" onclick="setRecentScope(true)">我的操作</button>
        ${refreshButton('fetchRecentLogs()')}
        <button class="btn-ghost btn-sm" onclick="navigate('audit')">完整操作日志</button>
      </div>
      <div id="recentTable"></div>
    </div>`;
  fetchRecentLogs();
}

function setRecentScope(mine) {
  recentFilters.mine = mine;
  loadRecentPage();
}

async function fetchRecentLogs() {
  const table = document.getElementById('recentTable');
  if (!table) return;
  table.innerHTML = '<p class="sub">加载中…</p>';
  try {
    const q = new URLSearchParams({ size: recentFilters.size, mine: recentFilters.mine ? 'true' : 'false' });
    const items = await api('/api/v2/ops/admin/audit-logs/recent?' + q, 'GET');
    table.innerHTML = typeof renderAuditTableHtml === 'function'
      ? renderAuditTableHtml(items)
      : emptyStateHtml('暂无操作记录', '运营后台的敏感操作会记录在此', 'fetchRecentLogs()');
  } catch (e) {
    pageRenderError(table, e, false);
  }
}

async function loadDisputes() {
  const el = document.getElementById('pageContent');
  const page = 'disputes';
  await loadSkus();
  if (currentPage !== page) return;
  try {
    const tickets = await api('/api/v2/ops/disputes', 'GET');
    if (currentPage !== page) return;
    if (!tickets || !tickets.length) {
      el.innerHTML = `
        <div class="card">
          <div class="filters">${refreshButton('loadDisputes()')}</div>
        </div>
        <div class="card">${emptyStateHtml('暂无待审核工单', '识别异常或用户申诉的工单会出现在此', 'loadDisputes()')}</div>`;
      return;
    }
    el.innerHTML = `
      <div class="card">
        <div class="filters">${refreshButton('loadDisputes()')}</div>
      </div>
      <div class="card">${tickets.map(renderTicket).join('')}</div>`;
  } catch (e) {
    if (currentPage !== page) return;
    pageRenderError(el, e);
  }
}

function renderTicket(t) {
  const skuOptions = skus.map(s =>
    `<option value="${escAttr(s.skuId)}">${esc(s.skuName)} (${esc(s.skuId)}) ${fmtMoney(s.priceCents)}</option>`
  ).join('');
  const video = t.videoPreviewUrl
    ? `<br><a href="${escAttr(t.videoPreviewUrl)}" target="_blank" rel="noopener">预览购物视频</a>`
    : (t.videoUri ? `<br>视频 ${esc(t.videoUri)}` : '');
  return `<div class="ticket">
    <div>${stateBadge(t.status)}</div>
    <div class="meta">工单 ${esc(t.ticketId)}<br>会话 ${esc(t.sessionId)}<br>原因 ${esc(t.reason || '-')}<br>创建 ${fmtTime(t.createdAt)}${video}</div>
    <div class="filters" style="margin-top:12px">
      <div style="flex:2"><label>商品</label><select class="sku-select">${skuOptions}</select></div>
      <div><label>数量</label><input type="number" class="qty-input" value="1" min="1"></div>
      <div><button class="btn-ok" onclick="resolveTicket('${escAttr(t.ticketId)}', this)">审核结案</button></div>
    </div>
  </div>`;
}

async function resolveTicket(ticketId, btn) {
  const card = btn.closest('.ticket');
  const skuId = card.querySelector('.sku-select').value;
  const qty = parseInt(card.querySelector('.qty-input').value, 10) || 1;
  if (!confirm(`确认结案：${skuId} × ${qty}？`)) return;
  btn.disabled = true;
  try {
    await api(`/api/v2/ops/disputes/${ticketId}/resolve`, 'POST', { items: [{ skuId, quantity: qty }] });
    toast('已结案', 'ok');
    loadDisputes();
  } catch (e) {
    if (!handleAuthFailure(e)) toast('失败: ' + e.message, 'err');
    btn.disabled = false;
  }
}

function loadRechargesPage() {
  document.getElementById('pageContent').innerHTML = `
    <div class="card">
      <div class="filters">
        <div><label>状态</label>
          <select id="rfStatus">
            <option value="">全部</option>
            ${['PENDING', 'PAID', 'REFUNDED', 'CANCELLED'].map(s =>
              `<option value="${s}" ${rechargeFilters.status === s ? 'selected' : ''}>${s}</option>`).join('')}
          </select>
        </div>
        <div><label>用户ID</label><input id="rfUserId" value="${escAttr(rechargeFilters.userId)}" placeholder="可选"></div>
        <div><button class="btn-primary" onclick="searchRecharges()">查询</button></div>
        <div>${refreshButton('fetchRecharges()')}</div>
      </div>
      <div id="rechargeTable"></div>
    </div>`;
  showTableLoading(document.getElementById('rechargeTable'), 10, 6);
  fetchRecharges();
}

function searchRecharges() {
  rechargeFilters.status = document.getElementById('rfStatus').value;
  rechargeFilters.userId = document.getElementById('rfUserId').value.trim();
  rechargeFilters.page = 0;
  fetchRecharges();
}

async function fetchRecharges() {
  const table = document.getElementById('rechargeTable');
  if (!table) return;
  showTableLoading(table, 10, 6);
  try {
    const q = new URLSearchParams({
      page: rechargeFilters.page,
      size: rechargeFilters.size,
      ...(rechargeFilters.status ? { status: rechargeFilters.status } : {}),
      ...(rechargeFilters.userId ? { userId: rechargeFilters.userId } : {})
    });
    const data = await api('/api/v2/ops/admin/recharges?' + q, 'GET');
    if (!data.items.length) {
      table.innerHTML = emptyStateHtml('暂无充值订单', '用户小程序充值成功后会出现在此列表', 'fetchRecharges()');
      return;
    }
    const canRefund = hasPerm('ops:user:balance');
    table.innerHTML = `
      <table>
        <thead><tr>
          <th>订单号</th><th>用户</th><th>金额</th><th>渠道</th><th>状态</th>
          <th>微信单号</th><th>创建</th><th>支付</th><th>退款</th><th>操作</th>
        </tr></thead>
        <tbody>${data.items.map(r => `<tr>
          <td><code>${esc(r.orderId)}</code></td>
          <td>${esc(r.userId)}</td>
          <td>${fmtMoney(r.amountCents)}</td>
          <td>${esc(r.channel || '-')}</td>
          <td>${rechargeStatusBadge(r.status)}</td>
          <td class="meta">${esc(r.wxTransactionId || '-')}</td>
          <td>${fmtTime(r.createdAt)}</td>
          <td>${fmtTime(r.paidAt)}</td>
          <td>${fmtTime(r.refundedAt)}</td>
          <td>${r.status === 'PAID' && canRefund
            ? `<button class="btn-danger btn-sm" onclick="refundRecharge('${escAttr(r.orderId)}', ${r.amountCents})">退款</button>`
            : '-'}</td>
        </tr>`).join('')}</tbody>
      </table>
      ${renderPagination(data, 'recharge')}`;
  } catch (e) {
    pageRenderError(table, e, false);
  }
}

async function refundRecharge(orderId, amountCents) {
  const reason = prompt(`确认退款订单 ${orderId}（${fmtMoney(amountCents)}）？\n可选填写退款原因：`, '');
  if (reason === null) return;
  try {
    await api('/api/v2/ops/admin/recharge/' + encodeURIComponent(orderId) + '/refund', 'POST',
      reason.trim() ? { reason: reason.trim() } : {});
    toast('退款成功', 'ok');
    fetchRecharges();
  } catch (e) {
    toast('退款失败: ' + e.message, 'err');
  }
}

initLoginHints();
initLoginForm();

Object.assign(adminRuntime, {
  api,
  getCurrentPage: () => currentPage,
  fmtTime,
  fmtMoney,
  closeModal
});

if (token) {
  api('/api/v2/ops/admin/rbac/me', 'GET')
    .then(() => showApp())
    .catch((e) => {
      if (!handleAuthFailure(e)) logout();
    });
}

let historyDepth = 0;
let devicesRefreshTimer = null;

function stopDevicesAutoRefresh() {
  if (devicesRefreshTimer) {
    clearInterval(devicesRefreshTimer);
    devicesRefreshTimer = null;
  }
}

function startDevicesAutoRefresh() {
  stopDevicesAutoRefresh();
  devicesRefreshTimer = setInterval(() => {
    if (currentPage === 'devices') loadDevices();
  }, 30000);
}

window.addEventListener('popstate', () => {
  if (!token || document.getElementById('appView').classList.contains('hidden')) return;
  historyDepth = Math.max(0, historyDepth - 1);
  canGoBack = historyDepth > 0;
  updateBackButton();
  const page = getPageFromHash();
  navigate(page, { fromPopstate: true });
});

Object.assign(window, {
  sendCode,
  login,
  logout,
  navigate,
  navigateBack,
  closeVisitedTab,
  loadDashboard,
  showDeviceForm,
  saveDevice,
  searchSessions,
  exportSessionsCsv,
  cancelSession,
  searchOrders,
  exportOrdersCsv,
  showOrderDetail,
  changePage,
  closeModal,
  loadSkusPage,
  showSkuForm,
  saveSku,
  searchUsers,
  showBalanceForm,
  saveBalance,
  showRbacAssignForUser,
  fetchAuditLogs,
  fetchRecentLogs,
  setRecentScope,
  loadRecentPage,
  resolveTicket,
  searchRecharges,
  refundRecharge
});
