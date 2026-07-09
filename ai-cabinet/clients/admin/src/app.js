import {
  esc,
  escAttr,
  normalizePhone,
  isValidPhone,
  invalidPhoneMessage,
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
  uploadStatusLabel,
  onlineStatusBadge,
  showTableLoading,
  trackModalBlobUrl,
  revokeModalBlobUrl,
  mediaKindFromUri,
  mediaActionLabel,
  selClear,
  selSelected,
  selToggle,
  selToggleAll,
  selRowClick,
  selSync,
  selBar,
  selHeaderCell,
  selCheckBox,
  selCheckCell,
  selRowOpen,
  selCardOpen,
  selWrap,
  debounce,
  teardownModalA11y,
  enhanceOpenedModal,
  showConfirm,
  withSaveGuard,
  buildPaginationHtml,
  sortItems,
  sortableHeader,
  listFilterBar,
  filterField,
  forbiddenPageHtml,
  rechargeStatusLabel,
  disputeStatusBadge,
  payChannelLabel,
  deviceTypeLabel,
  deviceRunStatusBadge,
  deviceRunStatusMeta,
  renderDeviceLivePanel,
  handleRefreshClick
} from './admin-common.js';
import { toggleTheme } from './theme.js';
import { renderDashboardAnalytics } from './dashboard-charts.js';
import { loadPermissions, permButton, hasPerm, hasPagePerm, didPermissionsLoadFail, applyNavPermissions } from './permissions.js';
import { adminRuntime } from './admin-runtime.js';

const BASE = (import.meta.env.VITE_API_BASE || '').replace(/\/$/, '') || window.location.origin;
let token = localStorage.getItem('admin_token') || '';
let skus = [];
let currentPage = '';
const sessionFilters = { page: 0, size: 20, deviceId: '', state: '' };
const orderFilters = { page: 0, size: 20, deviceId: '' };
const rechargeFilters = { page: 0, size: 20, status: '', userId: '' };
const skuFilters = { name: '', status: '' };
const deviceFilters = { keyword: '' };

const PAGE_TITLES = {
  dashboard: '数据概览',
  devices: '设备管理',
  sessions: '开门记录',
  orders: '订单管理',
  recharges: '充值管理',
  skus: '商品管理',
  users: '用户管理',
  reports: '设备报表',
  audit: '操作日志',
  recent: '最近操作',
  disputes: '争议审核',
  'vision-mappings': '识别配置',
  'upload-queue': '录像上传',
  sla: '服务时效',
  ota: '固件升级',
  risk: '风控',
  reconciliation: '对账',
  replenishment: '补货',
  warehouse: '仓库',
  finance: '财务毛利',
  merchants: '商户分账',
  rbac: '权限管理'
};
const userFilters = { page: 0, size: 20, phone: '', name: '', role: '', verified: '' };
const auditFilters = { page: 0, size: 20 };
const recentFilters = { size: 20, mine: false };
const disputeFilters = { page: 0, size: 20, status: 'OPEN', sessionId: '', deviceId: '' };
const tableSort = {
  sessions: { field: 'createdAt', dir: 'desc' },
  orders: { field: 'createdAt', dir: 'desc' },
  users: { field: 'userId', dir: 'desc' }
};

const debouncedSearchSessions = debounce(() => searchSessions(), 350);
const debouncedSearchOrders = debounce(() => searchOrders(), 350);
const debouncedSearchUsers = debounce(() => searchUsers(), 350);

function pctRate(v) {
  if (v == null || Number.isNaN(v)) return '-';
  return (Number(v) * 100).toFixed(1) + '%';
}

function trimPhone(v) { return normalizePhone(v); }

function requireValidPhone(raw) {
  const phone = normalizePhone(raw);
  if (!phone) return { ok: false, message: '请输入手机号' };
  if (!isValidPhone(phone)) return { ok: false, message: invalidPhoneMessage() };
  return { ok: true, phone };
}
function trimCode(v) { return (v || '').trim(); }
function fmtMoney(cents) { return '¥' + (cents / 100).toFixed(2); }
function fmtTime(iso) {
  if (!iso) return '-';
  return new Date(iso).toLocaleString('zh-CN');
}

const SESSION_TTL_MS = 30 * 60 * 1000;
const REFRESH_BEFORE_MS = 8 * 60 * 1000;
const REFRESH_CHECK_MS = 5 * 60 * 1000;
let tokenExpiresAt = parseInt(localStorage.getItem('admin_token_expires') || '0', 10) || 0;
let sessionTtlMs = SESSION_TTL_MS;
let lastActivityAt = Date.now();
let refreshTimer = null;
let refreshInFlight = null;

function noteActivity() {
  lastActivityAt = Date.now();
}

function applyTokenSession(data, phone) {
  token = data.token;
  localStorage.setItem('admin_token', token);
  localStorage.setItem('admin_userId', data.userId);
  if (phone) localStorage.setItem('admin_phone', phone);
  sessionTtlMs = (data.expiresInSeconds || 1800) * 1000;
  tokenExpiresAt = Date.now() + sessionTtlMs;
  localStorage.setItem('admin_token_expires', String(tokenExpiresAt));
  if (data.serverBootEpoch != null) persistServerBoot(data.serverBootEpoch);
  noteActivity();
}

async function refreshTokenSilently() {
  if (!token) return false;
  if (refreshInFlight) return refreshInFlight;
  refreshInFlight = (async () => {
    const res = await fetch(BASE + '/api/v2/auth/refresh', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token }
    });
    const json = await res.json().catch(() => ({}));
    if (!res.ok || json.code !== 0) {
      const err = new Error(formatApiError({ message: json.message, status: res.status }) || '登录已失效');
      err.status = res.status;
      throw err;
    }
    applyTokenSession(json.data);
    return true;
  })();
  try {
    return await refreshInFlight;
  } finally {
    refreshInFlight = null;
  }
}

async function maybeRefreshToken() {
  if (!token) return;
  if (Date.now() - lastActivityAt > sessionTtlMs) return;
  if (tokenExpiresAt - Date.now() > REFRESH_BEFORE_MS) return;
  await refreshTokenSilently();
}

function onUserActivity() {
  noteActivity();
}

function startTokenRefreshLoop() {
  stopTokenRefreshLoop();
  noteActivity();
  document.addEventListener('click', onUserActivity);
  document.addEventListener('keydown', onUserActivity);
  refreshTimer = setInterval(() => {
    maybeRefreshToken().catch(() => {});
  }, REFRESH_CHECK_MS);
}

function stopTokenRefreshLoop() {
  if (refreshTimer) {
    clearInterval(refreshTimer);
    refreshTimer = null;
  }
  document.removeEventListener('click', onUserActivity);
  document.removeEventListener('keydown', onUserActivity);
}

async function api(path, method, body, auth = true, retried = false) {
  if (auth && token) {
    noteActivity();
    try { await maybeRefreshToken(); } catch (_) { /* 续期失败由本次请求处理 */ }
  }
  const headers = { 'Content-Type': 'application/json' };
  if (auth && token) headers['Authorization'] = 'Bearer ' + token;
  const res = await fetch(BASE + path, { method, headers, body: body ? JSON.stringify(body) : undefined });
  const json = await res.json().catch(() => ({}));
  if (res.status === 401 && auth && !retried) {
    try {
      await refreshTokenSilently();
      return api(path, method, body, auth, true);
    } catch (_) { /* 续期失败，走下方登出 */ }
  }
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

async function fetchBlob(path, retried = false) {
  if (token) {
    noteActivity();
    try { await maybeRefreshToken(); } catch (_) {}
  }
  const headers = {};
  if (token) headers['Authorization'] = 'Bearer ' + token;
  const res = await fetch(BASE + path, { headers });
  if (res.status === 401 && !retried) {
    try {
      await refreshTokenSilently();
      return fetchBlob(path, true);
    } catch (_) {}
  }
  if (res.status === 401 || res.status === 403) {
    const json = await res.json().catch(() => ({}));
    const err = new Error(formatApiError({ message: json.message, status: res.status })
      || (res.status === 403 ? '权限不足' : '登录已失效，请重新登录'));
    err.status = res.status;
    handleAuthFailure(err);
    throw err;
  }
  const contentType = res.headers.get('content-type') || '';
  if (!res.ok) {
    if (contentType.includes('application/json')) {
      const json = await res.json().catch(() => ({}));
      throw new Error(formatApiError({ message: json.message, status: res.status }) || '视频加载失败');
    }
    throw new Error(`视频加载失败 (${res.status})`);
  }
  if (contentType.includes('application/json')) {
    const json = await res.json().catch(() => ({}));
    throw new Error(formatApiError({ message: json.message, status: res.status }) || '视频不存在');
  }
  const blob = await res.blob();
  if ((!blob.type || blob.type === 'application/octet-stream') && contentType.includes('video/')) {
    return new Blob([blob], { type: contentType.split(';')[0].trim() });
  }
  if ((!blob.type || blob.type === 'application/octet-stream') && contentType.includes('image/')) {
    return new Blob([blob], { type: contentType.split(';')[0].trim() });
  }
  return blob;
}

function resolveMediaType(contentType, uriHint, kind) {
  const header = (contentType || '').split(';')[0].trim().toLowerCase();
  if (header.startsWith('image/') || header.startsWith('video/')) return header;
  const lower = String(uriHint || '').toLowerCase();
  if (lower.endsWith('.png')) return 'image/png';
  if (/\.(jpe?g)$/.test(lower)) return 'image/jpeg';
  if (lower.endsWith('.webp')) return 'image/webp';
  if (lower.endsWith('.webm')) return 'video/webm';
  if (lower.endsWith('.mov')) return 'video/quicktime';
  return kind === 'image' ? 'image/jpeg' : 'video/mp4';
}

async function fetchSessionMedia(sessionId, uriHint) {
  const authToken = localStorage.getItem('admin_token') || token;
  if (!authToken) throw new Error('请先登录');
  const res = await fetch(`${BASE}/api/v2/ops/admin/sessions/${encodeURIComponent(sessionId)}/video`, {
    headers: { Authorization: 'Bearer ' + authToken }
  });
  if (res.status === 401 || res.status === 403) {
    const json = await res.json().catch(() => ({}));
    const err = new Error(formatApiError({ message: json.message, status: res.status })
      || (res.status === 403 ? '权限不足' : '登录已失效，请重新登录'));
    err.status = res.status;
    handleAuthFailure(err);
    throw err;
  }
  const contentType = res.headers.get('content-type') || '';
  if (!res.ok) {
    if (contentType.includes('application/json')) {
      const json = await res.json().catch(() => ({}));
      throw new Error(formatApiError({ message: json.message, status: res.status }) || '视频加载失败');
    }
    throw new Error(`视频加载失败 (${res.status})`);
  }
  if (contentType.includes('application/json')) {
    const json = await res.json().catch(() => ({}));
    throw new Error(formatApiError({ message: json.message, status: res.status }) || '视频不存在');
  }
  const blob = await res.blob();
  let kind = 'video';
  if (contentType.startsWith('image/')) kind = 'image';
  else if (contentType.startsWith('video/')) kind = 'video';
  else {
    const fromUri = mediaKindFromUri(uriHint);
    if (fromUri !== 'unknown') kind = fromUri;
  }
  const resolvedType = resolveMediaType(contentType, uriHint, kind);
  if (resolvedType.startsWith('image/')) kind = 'image';
  if (resolvedType.startsWith('video/')) kind = 'video';
  const typedBlob = (!blob.type || blob.type === 'application/octet-stream')
    ? new Blob([blob], { type: resolvedType })
    : blob;
  return { blob: typedBlob, kind, contentType: resolvedType };
}

function showErr(id, msg) {
  const el = document.getElementById(id);
  el.textContent = msg;
  el.classList.remove('hidden');
}

function persistServerBoot(epoch) {
  if (epoch != null) localStorage.setItem('admin_server_boot', String(epoch));
}

function isServerBootStale(currentBoot) {
  const saved = localStorage.getItem('admin_server_boot');
  if (!saved) return true;
  return String(currentBoot) !== saved;
}

async function fetchServerBoot() {
  return api('/api/v2/auth/server-boot', 'GET', null, false);
}

async function tryRestoreSession() {
  if (!token) {
    document.getElementById('loginView')?.classList.remove('hidden');
    document.getElementById('appView')?.classList.add('hidden');
    return;
  }
  try {
    const boot = await fetchServerBoot();
    if (isServerBootStale(boot)) {
      logout();
      toast('服务已重启，请重新登录', 'warn');
      return;
    }
    // 用当前 token 探测是否仍被服务端接受（含 boot epoch）
    await fetch(BASE + '/api/v2/ops/admin/rbac/me', {
      headers: { Authorization: 'Bearer ' + token }
    }).then(async (res) => {
      if (res.status === 401 || res.status === 403) {
        throw Object.assign(new Error('登录已失效'), { status: res.status });
      }
      const json = await res.json().catch(() => ({}));
      if (!res.ok || json.code !== 0) {
        throw Object.assign(new Error(json.message || '登录已失效'), { status: res.status });
      }
    });
    persistServerBoot(boot);
    showApp();
  } catch (e) {
    if (!handleAuthFailure(e)) logout();
  }
}

function initLoginHints() {
  const phone = document.getElementById('phone');
  if (phone && !phone.value && localStorage.getItem('admin_phone')) {
    phone.value = localStorage.getItem('admin_phone');
  }
}

let loginMode = localStorage.getItem('admin_login_mode') || 'password';

function switchLoginMode(mode) {
  loginMode = mode === 'sms' ? 'sms' : 'password';
  localStorage.setItem('admin_login_mode', loginMode);
  document.querySelectorAll('.login-tab').forEach((btn) => {
    btn.classList.toggle('active', btn.dataset.mode === loginMode);
  });
  const pwdBlock = document.getElementById('loginPasswordBlock');
  const smsBlock = document.getElementById('loginSmsBlock');
  if (pwdBlock) pwdBlock.classList.toggle('hidden', loginMode !== 'password');
  if (smsBlock) smsBlock.classList.toggle('hidden', loginMode !== 'sms');
  document.getElementById('loginErr')?.classList.add('hidden');
  if (loginMode === 'password') document.getElementById('password')?.focus();
  else document.getElementById('code')?.focus();
}

let codeCountdownTimer = null;

function startCodeCountdown(seconds = 60) {
  const btn = document.getElementById('sendCodeBtn');
  if (!btn) return;
  let left = seconds;
  btn.disabled = true;
  const tick = () => {
    if (left <= 0) {
      clearInterval(codeCountdownTimer);
      codeCountdownTimer = null;
      btn.disabled = false;
      btn.textContent = '获取验证码';
      return;
    }
    btn.textContent = `${left}s 后重发`;
    left -= 1;
  };
  tick();
  codeCountdownTimer = setInterval(tick, 1000);
}

function setLoginLoading(loading, kind) {
  const loginBtn = document.getElementById('loginBtn');
  const sendCodeBtn = document.getElementById('sendCodeBtn');
  const phone = document.getElementById('phone');
  const code = document.getElementById('code');
  const password = document.getElementById('password');
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
    if (password) password.readOnly = busy;
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
  const phoneInput = document.getElementById('phone');
  if (phoneInput && !phoneInput.dataset.bound) {
    phoneInput.dataset.bound = '1';
    phoneInput.maxLength = 11;
    phoneInput.addEventListener('input', () => {
      phoneInput.value = normalizePhone(phoneInput.value);
    });
  }
}

async function sendCode() {
  const sendCodeBtn = document.getElementById('sendCodeBtn');
  if (sendCodeBtn?.disabled) return;
  const check = requireValidPhone(document.getElementById('phone').value);
  if (!check.ok) {
    showErr('loginErr', check.message);
    document.getElementById('phone')?.focus();
    return;
  }
  const phone = check.phone;
  document.getElementById('loginErr').classList.add('hidden');
  setLoginLoading(true, 'code');
  try {
    await api(`/api/v2/auth/sms-code?phoneNumber=${encodeURIComponent(phone)}`, 'POST', null, false);
    startCodeCountdown(60);
    toast('验证码已发送', 'ok');
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
  const check = requireValidPhone(document.getElementById('phone').value);
  if (!check.ok) {
    showErr('loginErr', check.message);
    document.getElementById('phone')?.focus();
    return;
  }
  const phone = check.phone;
  document.getElementById('loginErr').classList.add('hidden');
  setLoginLoading(true, 'login');
  try {
    let data;
    if (loginMode === 'password') {
      const pwd = document.getElementById('password')?.value || '';
      if (!pwd) {
        setLoginLoading(false, 'login');
        showErr('loginErr', '请输入密码');
        document.getElementById('password')?.focus();
        return;
      }
      data = await api('/api/v2/auth/admin-password-login', 'POST', { phoneNumber: phone, password: pwd }, false);
    } else {
      const code = trimCode(document.getElementById('code').value);
      if (!code) {
        setLoginLoading(false, 'login');
        showErr('loginErr', '请输入验证码');
        document.getElementById('code')?.focus();
        return;
      }
      data = await api('/api/v2/auth/admin-login', 'POST', { phoneNumber: phone, code }, false);
    }
    applyTokenSession(data, phone);
    showApp();
  } catch (e) {
    showErr('loginErr', e.message);
  } finally {
    setLoginLoading(false, 'login');
  }
}

function logout() {
  stopDevicesAutoRefresh();
  stopTokenRefreshLoop();
  if (codeCountdownTimer) {
    clearInterval(codeCountdownTimer);
    codeCountdownTimer = null;
  }
  token = '';
  tokenExpiresAt = 0;
  localStorage.removeItem('admin_token');
  localStorage.removeItem('admin_userId');
  localStorage.removeItem('admin_server_boot');
  localStorage.removeItem('admin_token_expires');
  sessionStorage.removeItem('admin_visited_tabs');
  visitedTabs = ['dashboard'];
  historyDepth = 0;
  canGoBack = false;
  currentPage = '';
  try { history.replaceState(null, '', location.pathname + location.search); } catch { /* ignore */ }
  const tagsView = document.getElementById('tagsView');
  if (tagsView) {
    tagsView.classList.add('hidden');
    tagsView.innerHTML = '';
  }
  document.getElementById('appView').classList.add('hidden');
  document.getElementById('loginView').classList.remove('hidden');
  const pageContent = document.getElementById('pageContent');
  if (pageContent) pageContent.innerHTML = '';
  setLoginLoading(false, 'login');
  setLoginLoading(false, 'code');
  const sendCodeBtn = document.getElementById('sendCodeBtn');
  if (sendCodeBtn) {
    sendCodeBtn.disabled = false;
    sendCodeBtn.textContent = '获取验证码';
  }
  initLoginHints();
  document.getElementById('phone')?.focus();
}

function renderUserInfo(el, displayName, detailHtml) {
  const initial = (displayName || '运').trim().charAt(0).toUpperCase();
  el.innerHTML = `<div class="user-pill" title="${escAttr(displayName)}">
    <span class="user-avatar" aria-hidden="true">${esc(initial)}</span>
    <span class="user-text">
      <span class="user-name">${esc(displayName)}</span>
      <span class="user-detail">${detailHtml}</span>
    </span>
  </div>`;
}

function setUserInfoPlaceholder() {
  const el = document.getElementById('userInfo');
  if (!el) return;
  const phone = localStorage.getItem('admin_phone') || '';
  renderUserInfo(el, '运营账号', phone ? `${esc(phone)} · 加载角色…` : '加载中…');
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
    renderUserInfo(el, displayName, `${esc(me.phoneNumber || '-')} · ${esc(roles)}${esc(permHint)}`);
  } catch (e) {
    if (!handleAuthFailure(e)) setUserInfoPlaceholder();
  }
}

function showApp() {
  document.getElementById('loginView').classList.add('hidden');
  document.getElementById('appView').classList.remove('hidden');
  startTokenRefreshLoop();
  setUserInfoPlaceholder();
  loadVisitedTabs();
  const pageContent = document.getElementById('pageContent');
  if (pageContent) beginPageLoad('dashboard');
  Promise.all([refreshUserInfo(), loadPermissions(api)]).then(([, permOk]) => {
    if (!permOk) toast('权限加载失败，部分功能不可用，请刷新页面重试', 'warn');
    const page = getPageFromHash();
    initNavSections(page);
    navigate(page, { replaceHash: true, init: true });
  }).catch((e) => {
    if (!handleAuthFailure(e)) {
      pageRenderError(pageContent, e);
    }
  });
}

const MAX_VISITED_TABS = 12;
let visitedTabs = [];
let historyDepth = 0;
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

function toggleSidebar(open) {
  const sidebar = document.querySelector('.sidebar');
  const backdrop = document.getElementById('sidebarBackdrop');
  if (!sidebar) return;
  const shouldOpen = open === undefined ? !sidebar.classList.contains('open') : !!open;
  sidebar.classList.toggle('open', shouldOpen);
  backdrop?.classList.toggle('hidden', !shouldOpen);
}

const NAV_SECTIONS_KEY = 'admin_nav_sections';

function getNavSectionState() {
  try {
    return JSON.parse(localStorage.getItem(NAV_SECTIONS_KEY) || '{}');
  } catch {
    return {};
  }
}

function saveNavSectionState(group, expanded) {
  const state = getNavSectionState();
  state[group] = expanded;
  localStorage.setItem(NAV_SECTIONS_KEY, JSON.stringify(state));
}

function setNavSectionExpanded(group, expanded) {
  const section = document.querySelector(`.nav-section[data-nav-group="${group}"]`);
  if (!section) return;
  section.classList.toggle('collapsed', !expanded);
  const btn = section.querySelector('.nav-section-toggle');
  if (btn) btn.setAttribute('aria-expanded', expanded ? 'true' : 'false');
}

function toggleNavSection(group) {
  const section = document.querySelector(`.nav-section[data-nav-group="${group}"]`);
  if (!section || section.classList.contains('hidden')) return;
  const expanded = section.classList.contains('collapsed');
  setNavSectionExpanded(group, expanded);
  saveNavSectionState(group, expanded);
}

function syncNavSections(activePage) {
  document.querySelectorAll('.nav-section[data-nav-group]').forEach((section) => {
    if (section.classList.contains('hidden')) return;
    const group = section.dataset.navGroup;
    const hasActive = !!section.querySelector(`.nav-item[data-page="${activePage}"]:not(.hidden)`);
    if (hasActive) {
      setNavSectionExpanded(group, true);
      saveNavSectionState(group, true);
    }
  });
}

function initNavSections(activePage) {
  const state = getNavSectionState();
  document.querySelectorAll('.nav-section[data-nav-group]').forEach((section) => {
    if (section.classList.contains('hidden')) return;
    const group = section.dataset.navGroup;
    const hasActive = !!section.querySelector(`.nav-item[data-page="${activePage}"]:not(.hidden)`);
    let expanded;
    if (hasActive) expanded = true;
    else if (state[group] != null) expanded = !!state[group];
    else expanded = group === 'overview';
    setNavSectionExpanded(group, expanded);
  });
}

function setTableSort(scope, field, dir) {
  tableSort[scope] = { field, dir };
  if (scope === 'sessions') fetchSessions();
  else if (scope === 'orders') fetchOrders();
  else if (scope === 'users') fetchUsers();
}

function toggleTableSort(scope, field) {
  const cur = tableSort[scope] || { field, dir: 'desc' };
  setTableSort(scope, field, cur.field === field && cur.dir === 'desc' ? 'asc' : 'desc');
}

function navigate(page, options = {}) {
  if (!PAGE_TITLES[page]) page = 'dashboard';
  const fromPopstate = !!options.fromPopstate;
  const hash = '#/' + page;

  if (!hasPagePerm(page)) {
    currentPage = page;
    document.getElementById('pageTitle').textContent = PAGE_TITLES[page] || page;
    document.querySelectorAll('.nav-item').forEach(el => {
      el.classList.toggle('active', el.dataset.page === page);
    });
    syncNavSections(page);
    renderTagsView();
    document.getElementById('pageContent').innerHTML = forbiddenPageHtml(PAGE_TITLES[page]);
    if (!fromPopstate && location.hash !== hash) {
      history.pushState({ page, forbidden: true }, '', hash);
    }
    return;
  }
  if (page === currentPage && !fromPopstate && !options.force && !options.init) {
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
  toggleSidebar(false);
  if (page !== 'devices' && page !== 'dashboard') stopDevicesAutoRefresh();
  document.getElementById('pageTitle').textContent = PAGE_TITLES[page] || page;
  document.querySelectorAll('.nav-item').forEach(el => {
    el.classList.toggle('active', el.dataset.page === page);
  });
  syncNavSections(page);
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
    'vision-mappings': ops.visionMappings || loadDashboard,
    'upload-queue': ops.uploadQueue || loadDashboard,
    sla: ops.sla || loadDashboard,
    ota: ops.ota || loadDashboard,
    risk: ops.risk || loadDashboard,
    reconciliation: ops.reconciliation || loadDashboard,
    replenishment: ops.replenishment || loadDashboard,
    warehouse: ops.warehouse || loadDashboard,
    finance: loadFinancePage,
    merchants: ops.merchants || loadDashboard,
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

function dashboardOfflineAlert(allOffline) {
  if (!allOffline) return '';
  return `
    <div class="dash-alert">
      <div class="dash-alert-main">
        <span class="dash-alert-dot" aria-hidden="true"></span>
        <span>当前没有在线设备，顾客可能无法正常开门购物</span>
        <button type="button" class="dash-alert-toggle" onclick="this.closest('.dash-alert').classList.toggle('expanded')">查看常见原因</button>
      </div>
      <div class="dash-alert-detail">
        <ul style="margin:0;padding-left:1.2em">
          <li>柜机断电或网络断开</li>
          <li>设备长时间未联网（超过 2 分钟会显示离线）</li>
          <li>现场设备故障，需运维人员检修</li>
        </ul>
        <p class="meta" style="margin:8px 0 0">建议前往「设备管理」查看各柜机最近在线时间，并联系现场人员排查。</p>
      </div>
    </div>`;
}

function workbenchTarget(item) {
  const type = String(item?.type || '').toUpperCase();
  if (type === 'DISPUTE') return "navigate('disputes')";
  if (type === 'UPLOAD_STUCK') return "navigate('upload-queue')";
  if (type === 'DEVICE_OFFLINE') return "navigate('devices')";
  if (type === 'LOW_STOCK' || type === 'REPLENISHMENT') return "navigate('replenishment')";
  return "navigate('dashboard')";
}

function renderOpsWorkbench(workbench) {
  if (!workbench) return '';
  const items = workbench.actionItems || [];
  const summary = [
    ['Open disputes', workbench.openDisputes],
    ['Overdue', workbench.overdueDisputes],
    ['Offline devices', workbench.offlineDevices],
    ['Waiting upload', workbench.waitingUploads],
    ['Low stock', workbench.lowStockItems],
    ['Replenishment', workbench.pendingReplenishments]
  ].map(([label, value]) =>
    `<div class="workbench-pill"><span>${esc(label)}</span><strong>${esc(value ?? 0)}</strong></div>`
  ).join('');
  const rows = items.slice(0, 8).map((item) => {
    const sev = String(item.severity || 'LOW').toLowerCase();
    const meta = [item.deviceId, item.sessionId, item.skuId].filter(Boolean).join(' · ');
    return `<button type="button" class="workbench-item sev-${escAttr(sev)}" onclick="${workbenchTarget(item)}">
      <span class="workbench-sev">${esc(item.severity || 'LOW')}</span>
      <span class="workbench-main">
        <strong>${esc(item.title || item.type || '-')}</strong>
        <small>${esc(item.detail || meta || '-')}</small>
      </span>
      <span class="workbench-meta">${esc(meta)}</span>
    </button>`;
  }).join('');
  return `<section class="card workbench-card">
    <div class="pane-head">
      <div>
        <h3 style="margin:0;font-size:1rem;color:var(--text)">Operations workbench</h3>
        <p class="sub" style="margin:4px 0 0">Prioritized issues that affect checkout, replenishment, and SLA.</p>
      </div>
      <button type="button" class="btn-ghost btn-sm" onclick="navigate('disputes')">Review</button>
    </div>
    <div class="workbench-summary">${summary}</div>
    ${rows ? `<div class="workbench-list">${rows}</div>` : `<div class="empty-state"><div class="empty-title">No urgent action</div></div>`}
  </section>`;
}

async function loadDashboard() {
  const el = document.getElementById('pageContent');
  const page = 'dashboard';
  try {
    const [s, trend, opsTrend, recent, fin, devices, workbench] = await Promise.all([
      api('/api/v2/ops/admin/stats', 'GET'),
      api('/api/v2/ops/admin/trend', 'GET'),
      api('/api/v2/ops/admin/trend/ops', 'GET'),
      api('/api/v2/ops/admin/audit-logs/recent?size=5&mine=false', 'GET').catch(() => []),
      api('/api/v2/ops/admin/finance/stats', 'GET').catch(() => null),
      api('/api/v2/ops/admin/devices', 'GET').catch(() => []),
      api('/api/v2/ops/admin/workbench', 'GET').catch(() => null)
    ]);
    if (currentPage !== page) return;
    const statLink = (label, value, cls, pageTarget, hint) => {
      let onclick = `navigate('${pageTarget}')`;
      if (hint === 'lowStock') {
        onclick = `window.replenishmentFilters&&(window.replenishmentFilters.lowStockOnly=true);navigate('replenishment')`;
      } else if (hint === 'pendingSplit') {
        onclick = `window.merchantSplitFilters&&(window.merchantSplitFilters.status='PENDING');navigate('merchants')`;
      } else if (hint === 'slotDiscrepancy') {
        onclick = `showSlotDiscrepancies()`;
      }
      return `<div class="stat stat-click" role="button" tabindex="0" onclick="${onclick}" title="点击查看">
        <div class="label">${label}</div><div class="value ${cls || ''}">${value}</div>
      </div>`;
    };
    el.innerHTML = `
      <div class="dashboard-head">
        <div>
          <h3 class="dashboard-head-title">核心指标</h3>
          <p class="dashboard-head-sub">今日运营数据 · 设备约每 30 秒更新在线状态</p>
        </div>
        ${refreshButton('loadDashboard()', '刷新')}
      </div>
      ${dashboardOfflineAlert(s.deviceTotal > 0 && s.deviceOnline === 0)}
      <div class="stats">
        <div class="stat"><div class="label">设备总数</div><div class="value">${s.deviceTotal}</div></div>
        <div class="stat"><div class="label">在线设备</div><div class="value ${s.deviceOnline === 0 ? 'warn' : 'ok'}">${s.deviceOnline}</div></div>
        <div class="stat stat-click" role="button" tabindex="0" onclick="navigate('devices')" title="有活跃会话或补货任务的设备数">
          <div class="label">占用设备</div><div class="value ${s.deviceOccupied > 0 ? 'warn' : ''}">${s.deviceOccupied ?? 0}</div>
        </div>
        <div class="stat stat-click" role="button" tabindex="0" onclick="navigate('sessions')" title="进行中的购物/识别/结算会话数">
          <div class="label">进行中会话</div><div class="value warn">${s.sessionActive}</div>
        </div>
        <div class="stat"><div class="label">今日会话</div><div class="value">${s.sessionToday}</div></div>
        <div class="stat"><div class="label">今日订单</div><div class="value">${s.orderToday}</div></div>
        <div class="stat"><div class="label">今日营收</div><div class="value ok">${fmtMoney(s.revenueTodayCents)}</div></div>
        ${fin ? `<div class="stat stat-click" role="button" tabindex="0" onclick="navigate('finance')" title="点击查看毛利报表"><div class="label">今日毛利</div><div class="value ok">${fmtMoney(fin.grossMarginTodayCents)}</div></div>` : ''}
        ${fin ? `<div class="stat stat-click" role="button" tabindex="0" onclick="navigate('finance')"><div class="label">今日销售成本</div><div class="value">${fmtMoney(fin.cogsTodayCents)}</div></div>` : ''}
        ${fin ? `<div class="stat"><div class="label">今日报损</div><div class="value ${fin.writeOffTodayCents > 0 ? 'warn' : ''}">${fmtMoney(fin.writeOffTodayCents)}</div></div>` : ''}
        ${statLink('待审争议', s.disputeOpen, 'warn', 'disputes')}
        ${statLink('超时未处理争议', s.disputeOverdue ?? 0, (s.disputeOverdue > 0 ? 'warn' : ''), 'disputes')}
        ${statLink('即将超时争议', s.disputeNearSla ?? 0, (s.disputeNearSla > 0 ? 'warn' : ''), 'disputes')}
        ${statLink('待上传会话', s.sessionWaitingUpload ?? 0, 'warn', 'upload-queue')}
        ${statLink('低库存商品', s.lowStockSkuCount ?? 0, (s.lowStockSkuCount > 0 ? 'warn' : ''), 'replenishment', 'lowStock')}
        ${statLink('临期批次', s.nearExpiryLotCount ?? 0, (s.nearExpiryLotCount > 0 ? 'warn' : ''), 'replenishment')}
        ${statLink('过期库存', s.expiredLotCount ?? 0, (s.expiredLotCount > 0 ? 'warn' : ''), 'replenishment')}
        ${statLink('待下架', s.pullOffOpenCount ?? 0, (s.pullOffOpenCount > 0 ? 'warn' : ''), 'replenishment')}
        ${statLink('账实差异货道', s.slotDiscrepancyCount ?? 0, (s.slotDiscrepancyCount > 0 ? 'warn' : ''), 'devices', 'slotDiscrepancy')}
        ${statLink('待分账', s.pendingSplitCount ?? 0, (s.pendingSplitCount > 0 ? 'warn' : ''), 'merchants', 'pendingSplit')}
        <div class="stat"><div class="label">24h 开门成功率</div><div class="value ok">${pctRate(s.doorSuccessRate24h)}</div></div>
        ${statLink('24h 争议率', pctRate(s.disputeRate24h), '', 'disputes')}
        <div class="stat"><div class="label">24h 自动识别率</div><div class="value ok">${pctRate(s.recognitionAutoRate24h)}</div></div>
      </div>
      ${renderOpsWorkbench(workbench)}
      ${renderDeviceLivePanel(devices, { fmtTime, refreshFn: 'refreshDashboardDevicePanel()' })}
      ${renderDashboardAnalytics(s, trend, opsTrend)}
      ${recent && recent.length ? `
      <div class="card">
        <div class="pane-head">
          <h3 style="margin:0;font-size:1rem;color:var(--text)">最新动态</h3>
          <button class="btn-ghost btn-sm" onclick="navigate('audit')">操作日志</button>
        </div>
        ${typeof renderAuditTableHtml === 'function' ? renderAuditTableHtml(recent) : ''}
      </div>` : ''}`;
    startDevicesAutoRefresh();
  } catch (e) {
    if (currentPage !== page) return;
    pageRenderError(el, e);
  }
}

async function refreshDashboardDevicePanel() {
  if (currentPage !== 'dashboard') return;
  const panel = document.getElementById('deviceLivePanel');
  if (!panel) {
    loadDashboard();
    return;
  }
  try {
    const devices = await api('/api/v2/ops/admin/devices', 'GET');
    if (currentPage !== 'dashboard') return;
    panel.outerHTML = renderDeviceLivePanel(devices, { fmtTime, refreshFn: 'refreshDashboardDevicePanel()' });
  } catch (e) {
    if (!handleAuthFailure(e)) toast('刷新柜机状态失败: ' + formatApiError(e), 'err');
  }
}

async function loadFinancePage() {
  const el = document.getElementById('pageContent');
  const page = 'finance';
  try {
    const report = await api('/api/v2/ops/admin/finance/report?days=7', 'GET');
    if (currentPage !== page) return;
    const s = report.summary || {};
    const dailyRows = (report.daily || []).map(d => `
      <tr>
        <td>${esc(d.date)}</td>
        <td>${fmtMoney(d.revenueCents)}</td>
        <td>${fmtMoney(d.cogsCents)}</td>
        <td class="${d.grossMarginCents >= 0 ? 'ok-text' : 'warn-text'}">${fmtMoney(d.grossMarginCents)}</td>
        <td>${fmtMoney(d.writeOffCents)}</td>
      </tr>`).join('');
    const skuRows = (report.topSkus || []).map(row => `
      <tr>
        <td>${esc(row.skuName || row.skuId)}</td>
        <td><code>${esc(row.skuId)}</code></td>
        <td>${esc(row.qtySold)}</td>
        <td>${fmtMoney(row.revenueCents)}</td>
        <td>${fmtMoney(row.cogsCents)}</td>
        <td class="${row.grossMarginCents >= 0 ? 'ok-text' : 'warn-text'}">${fmtMoney(row.grossMarginCents)}</td>
      </tr>`).join('');
    el.innerHTML = `
      <div class="dashboard-head">
        <div>
          <h3 class="dashboard-head-title">财务毛利</h3>
          <p class="dashboard-head-sub">营收减去商品采购成本 · 近 7 日明细</p>
        </div>
        ${refreshButton('loadFinancePage()')}
      </div>
      <div class="stats">
        <div class="stat"><div class="label">今日营收</div><div class="value ok">${fmtMoney(s.revenueTodayCents)}</div></div>
        <div class="stat"><div class="label">今日销售成本</div><div class="value">${fmtMoney(s.cogsTodayCents)}</div></div>
        <div class="stat"><div class="label">今日毛利</div><div class="value ok">${fmtMoney(s.grossMarginTodayCents)}</div></div>
        <div class="stat"><div class="label">今日报损</div><div class="value ${s.writeOffTodayCents > 0 ? 'warn' : ''}">${fmtMoney(s.writeOffTodayCents)}</div></div>
        <div class="stat"><div class="label">累计营收</div><div class="value">${fmtMoney(s.revenueTotalCents)}</div></div>
        <div class="stat"><div class="label">累计销售成本</div><div class="value">${fmtMoney(s.cogsTotalCents)}</div></div>
        <div class="stat"><div class="label">累计毛利</div><div class="value ok">${fmtMoney(s.grossMarginTotalCents)}</div></div>
      </div>
      <div class="card">
        <h3 style="margin-top:0">近 7 日趋势</h3>
        <table class="data-table">
          <thead><tr><th>日期</th><th>营收</th><th>销售成本</th><th>毛利</th><th>报损</th></tr></thead>
          <tbody>${dailyRows || '<tr><td colspan="5" class="meta">暂无数据</td></tr>'}</tbody>
        </table>
      </div>
      <div class="card">
        <h3 style="margin-top:0">商品毛利排行（近 7 日 Top 20）</h3>
        <p class="meta">请在「商品管理」填写各商品的采购成本，系统会在订单结算时自动计算毛利</p>
        <table class="data-table">
          <thead><tr><th>商品</th><th>商品编号</th><th>销量</th><th>营收</th><th>销售成本</th><th>毛利</th></tr></thead>
          <tbody>${skuRows || '<tr><td colspan="6" class="meta">暂无销售</td></tr>'}</tbody>
        </table>
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
  selClear('devices');
  try {
    const devices = await api('/api/v2/ops/admin/devices', 'GET');
    if (currentPage !== page) return;
    el.innerHTML = `
      <div class="card list-page-card">
        ${listFilterBar({
          onSearch: 'searchDevices()',
          onReset: 'resetDeviceFilters()',
          refreshFn: 'loadDevices()',
          extraHtml: `${permButton('device.create', '注册新设备', 'showDeviceForm()', 'btn-primary btn-sm')}${selBar('devices')}`,
          fieldsHtml: filterField('关键词', `<input id="devKeyword" value="${escAttr(deviceFilters.keyword)}" placeholder="设备编号 / 名称 / 商户">`)
        })}
      </div>
      ${devices.length ? `
      <div class="card list-page-card" style="padding-top:0">
        ${selWrap('devices', `<table class="data-table">
          <thead><tr>
            ${selHeaderCell('devices')}
            <th>设备编号</th><th>名称</th><th>运行状态</th><th>当前开门</th><th>最近在线</th><th>商户</th><th>类型</th><th class="col-actions">操作</th>
          </tr></thead>
          <tbody>${filterDeviceList(devices).map(d => `
            ${selRowOpen('devices', d.deviceId)}
            ${selCheckCell('devices', d.deviceId)}
            <td><code>${esc(d.deviceId)}</code></td>
            <td>${esc(d.deviceName || '-')}</td>
            <td title="${escAttr(deviceRunStatusMeta(d).hint)}">${deviceRunStatusBadge(d)}</td>
            <td>${d.activeSessionId ? `${stateBadge(d.activeSessionState)}` : '<span class="meta">无</span>'}</td>
            <td>${fmtTime(d.updatedAt)}</td>
            <td>${esc(d.merchantName || d.merchantId || '-')}</td>
            <td>${esc(deviceTypeLabel(d.deviceType))}</td>
            <td class="col-actions" onclick="event.stopPropagation()"><div class="row-actions">${hasPerm('ops:device:edit') ? `<button type="button" class="btn-ghost btn-sm" onclick='showDeviceForm(${JSON.stringify(d)})'>编辑</button>` : ''}
              <button type="button" class="btn-ghost btn-sm" onclick="viewDeviceDetail('${escAttr(d.deviceId)}')">详情</button></div></td>
          </tr>`).join('')}</tbody>
        </table>`)}
      </div>` : `<div class="card">${emptyStateHtml('暂无设备', '点击「注册新设备」添加第一台柜机', 'loadDevices()')}</div>`}`;
    selSync('devices');
    applyNavPermissions();
    startDevicesAutoRefresh();
  } catch (e) {
    if (currentPage !== page) return;
    pageRenderError(el, e);
  }
}

function filterDeviceList(devices) {
  const kw = (deviceFilters.keyword || '').trim().toLowerCase();
  if (!kw) return devices;
  return devices.filter(d => [d.deviceId, d.deviceName, d.merchantName, d.merchantId]
    .some(v => String(v || '').toLowerCase().includes(kw)));
}

function searchDevices() {
  deviceFilters.keyword = document.getElementById('devKeyword')?.value.trim() || '';
  loadDevices();
}

function resetDeviceFilters() {
  deviceFilters.keyword = '';
  loadDevices();
}

async function showDeviceForm(device) {
  const isEdit = !!device;
  let merchantOptions = '<option value="">未绑定</option>';
  try {
    const merchants = await api('/api/v2/ops/admin/merchants', 'GET');
    merchantOptions += (merchants || []).map(m =>
      `<option value="${escAttr(m.merchantId)}" ${isEdit && device.merchantId === m.merchantId ? 'selected' : ''}>${esc(m.merchantName)} (${esc(m.merchantId)})</option>`
    ).join('');
  } catch (_) { /* ignore */ }
  openModalHtml(`
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal" onclick="event.stopPropagation()">
        <h3>${isEdit ? '编辑设备' : '注册新设备'}</h3>
        <label>设备编号</label>
        <input id="dfId" value="${isEdit ? escAttr(device.deviceId) : ''}" ${isEdit ? 'disabled' : ''} placeholder="CAB-002">
        <label>设备名称</label>
        <input id="dfName" value="${isEdit ? escAttr(device.deviceName || '') : ''}" placeholder="1号柜">
        <label>所属商户</label>
        <select id="dfMerchant">${merchantOptions}</select>
        <label>设备类型</label>
        <input id="dfType" value="${isEdit ? escAttr(device.deviceType || 'AI_CABINET_V1') : 'AI_CABINET_V1'}">
        <div class="filters" style="margin-top:12px">
          <button type="button" class="btn-primary" onclick="saveDevice(event, ${isEdit})">保存</button>
          <button type="button" class="btn-ghost" data-modal-cancel onclick="closeModal()">取消</button>
        </div>
      </div>
    </div>`);
}

function slotStockStatusLabel(status) {
  const map = { FULL: '满', OK: '正常', LOW: '低库存', OOS: '缺货', DISABLED: '未启用' };
  return map[status] || status || '-';
}

function slotStockBadge(status) {
  const cls = status === 'FULL' || status === 'OK' ? 'badge-active'
    : status === 'LOW' ? 'badge-warn' : status === 'OOS' ? 'badge-danger' : 'badge-muted';
  return `<span class="badge ${cls}">${esc(slotStockStatusLabel(status))}</span>`;
}

async function applyPlanogramTemplate(deviceId) {
  if (!hasPerm('ops:device:edit')) return;
  try {
    const n = await api('/api/v2/ops/admin/devices/' + encodeURIComponent(deviceId) + '/slots/apply-template', 'POST');
    toast(`已套用模板，新增 ${n} 个货道`, 'ok');
    viewDeviceDetail(deviceId);
  } catch (e) {
    if (!handleAuthFailure(e)) toast('套用模板失败: ' + e.message, 'err');
  }
}

async function viewDeviceDetail(deviceId) {
  try {
    const [detail, discrepancies, suggests] = await Promise.all([
      api('/api/v2/ops/admin/devices/' + encodeURIComponent(deviceId) + '/detail', 'GET'),
      api('/api/v2/ops/admin/slots/discrepancies?deviceId=' + encodeURIComponent(deviceId), 'GET').catch(() => []),
      api('/api/v2/ops/admin/replenishment/suggest?deviceId=' + encodeURIComponent(deviceId), 'GET').catch(() => [])
    ]);
    const m = detail.metrics || {};
    const slots = detail.slots || [];
    const maxRow = slots.reduce((n, s) => Math.max(n, s.rowNo || 0), 0);
    const maxCol = slots.reduce((n, s) => Math.max(n, s.colNo || 0), 0);
    const canEdit = hasPerm('ops:device:edit');
    const canStocktake = hasPerm('ops:replenishment:edit');
    const slotGrid = [];
    for (let r = 1; r <= Math.max(maxRow, 1); r++) {
      for (let c = 1; c <= Math.max(maxCol, 1); c++) {
        const s = slots.find(x => x.rowNo === r && x.colNo === c);
        if (!s) continue;
        const diff = s.hasDiscrepancy
          ? `<div class="slot-diff">账${s.bookQty} / 实${s.lastPhysicalQty} (${s.qtyDiff > 0 ? '+' : ''}${s.qtyDiff})</div>` : '';
        const click = canEdit
          ? `onclick="showSlotEditor('${escAttr(deviceId)}', '${escAttr(s.slotCode)}')"`
          : (canStocktake ? `onclick="promptSlotStocktakeFor('${escAttr(deviceId)}','${escAttr(s.slotCode)}',${s.bookQty})"` : '');
        slotGrid.push(`
          <div class="slot-cell slot-${escAttr((s.stockStatus || 'disabled').toLowerCase())}${s.hasDiscrepancy ? ' slot-mismatch' : ''} ${click ? 'slot-clickable' : ''}" ${click} title="${esc(s.assignedSkuName || s.assignedSkuId || '未配置')}">
            <div class="slot-code">${esc(s.slotCode)}</div>
            <div class="slot-sku">${esc(s.assignedSkuName || s.assignedSkuId || '-')}</div>
            <div class="slot-qty">${s.bookQty}/${s.parLevel || '-'}</div>
            <div class="slot-meta">${slotStockBadge(s.stockStatus)} ${s.fillRatePct}%</div>
            ${diff}
          </div>`);
      }
    }
    const suggestRows = (suggests || []).map(s => `
      <tr>
        <td>${esc(s.skuId)}</td>
        <td>${esc(s.currentQty)}</td>
        <td>${esc(s.inTransitQty ?? 0)}</td>
        <td>${esc(s.suggestQty)}</td>
        <td>${esc(s.soldQty7d ?? 0)}</td>
        <td>${esc(s.ropPoint ?? 0)}</td>
        <td><span class="badge badge-active">${esc(s.suggestReason || 'PAR')}</span></td>
      </tr>`).join('');
    const discRows = (discrepancies || []).map(d => `
      <tr>
        <td><code>${esc(d.slotCode)}</code></td>
        <td>${esc(d.assignedSkuName || d.assignedSkuId || '-')}</td>
        <td>${d.bookQty}</td>
        <td>${d.physicalQty}</td>
        <td class="${d.qtyDiff !== 0 ? 'warn-text' : ''}">${d.qtyDiff > 0 ? '+' : ''}${d.qtyDiff}</td>
        <td>${fmtTime(d.lastPhysicalAt)}</td>
        <td>${canStocktake ? `<button class="btn-ghost btn-sm" onclick="promptSlotStocktakeFor('${escAttr(deviceId)}','${escAttr(d.slotCode)}',${d.bookQty})">重盘</button>` : '-'}</td>
      </tr>`).join('');
    const skuRows = (detail.skuInventory || []).map(i => `
      <tr><td>${esc(i.skuId)}</td><td>${i.quantity}/${i.capacity}</td><td>${esc(i.lowThreshold)}</td></tr>`).join('');
    openModalHtml(`
      <div class="modal-backdrop device-detail-backdrop" onclick="closeModal(event)">
        <div class="modal modal-wide" onclick="event.stopPropagation()">
          <div class="device-detail-head">
            <div>
              <h3>${esc(detail.device?.deviceName || deviceId)}</h3>
              <p class="meta"><code>${esc(deviceId)}</code> · ${onlineBadge(detail.device?.onlineStatus)} · 最近在线 ${fmtTime(detail.device?.updatedAt)}</p>
            </div>
            <button type="button" class="btn-ghost btn-sm" data-modal-cancel onclick="closeModal()">关闭</button>
          </div>
          <div class="device-kpi-grid">
            <div class="kpi-card"><div class="kpi-label">补货率</div><div class="kpi-value">${m.fillRatePct ?? 0}%</div></div>
            <div class="kpi-card"><div class="kpi-label">缺货率</div><div class="kpi-value">${m.oosRatePct ?? 0}%</div></div>
            <div class="kpi-card"><div class="kpi-label">缺货通道</div><div class="kpi-value">${m.oosSlotCount ?? 0}</div></div>
            <div class="kpi-card"><div class="kpi-label">低库存通道</div><div class="kpi-value">${m.lowStockSlotCount ?? 0}</div></div>
            <div class="kpi-card"><div class="kpi-label">库存准确率</div><div class="kpi-value">${m.inventoryAccuracyPct ?? 100}%</div></div>
            <div class="kpi-card"><div class="kpi-label">上次补货</div><div class="kpi-value kpi-sm">${m.lastRestockAt ? fmtTime(m.lastRestockAt) : '暂无'}</div></div>
          </div>
          <div class="pane-head">
            <h4 style="margin:0">陈列图（货道）</h4>
            <div>
              ${canEdit && !slots.length ? `<button type="button" class="btn-ghost btn-sm" onclick="applyPlanogramTemplate('${escAttr(deviceId)}')">套用默认模板</button>` : ''}
              ${canEdit ? `<button type="button" class="btn-primary btn-sm" onclick="showSlotEditor('${escAttr(deviceId)}', null)">添加货道</button>` : ''}
            </div>
          </div>
          <p class="meta">${canEdit ? '点击货道可编辑配置；' : ''}${canStocktake ? '可盘点更新实测数量' : ''}</p>
          <div class="slot-grid">${slotGrid.length ? slotGrid.join('') : '<p class="meta">暂无货道配置</p>'}</div>
          ${discRows ? `
          <h4 style="margin-top:16px;color:var(--warn)">账实差异告警 (${discrepancies.length})</h4>
          <table class="table"><thead><tr><th>货道</th><th>商品</th><th>账面</th><th>实测</th><th>差异</th><th>盘点时间</th><th>操作</th></tr></thead><tbody>${discRows}</tbody></table>` : ''}
          <h4 style="margin-top:16px">商品库存汇总</h4>
          ${skuRows ? `<table class="table"><thead><tr><th>商品</th><th>数量/容量</th><th>低库存线</th></tr></thead><tbody>${skuRows}</tbody></table>` : '<p class="meta">暂无</p>'}
          ${(suggests || []).length ? `
          <h4 style="margin-top:16px">动销 ROP 补货建议</h4>
          <table class="table"><thead><tr><th>商品</th><th>账面</th><th>在途</th><th>建议量</th><th>7日销量</th><th>补货点</th><th>策略</th></tr></thead><tbody>${suggestRows}</tbody></table>` : ''}
          <div class="filters" style="margin-top:12px">
            ${canStocktake ? `<button type="button" class="btn-ghost btn-sm" onclick="promptSlotStocktake('${escAttr(deviceId)}')">通道盘点</button>` : ''}
            ${canStocktake ? `<button type="button" class="btn-ghost btn-sm" onclick="closeModal();navigate('replenishment')">去补货管理</button>` : ''}
          </div>
        </div>
      </div>`);
  } catch (e) {
    if (!handleAuthFailure(e)) toast('加载设备详情失败: ' + e.message, 'err');
  }
}

async function showSlotDiscrepancies() {
  try {
    const alerts = await api('/api/v2/ops/admin/slots/discrepancies', 'GET');
    const rows = (alerts || []).map(d => `
      <tr>
        <td><button class="btn-link" onclick="closeModal();viewDeviceDetail('${escAttr(d.deviceId)}')">${esc(d.deviceName || d.deviceId)}</button></td>
        <td><code>${esc(d.deviceId)}</code></td>
        <td><code>${esc(d.slotCode)}</code></td>
        <td>${esc(d.assignedSkuName || d.assignedSkuId || '-')}</td>
        <td>${d.bookQty}</td>
        <td>${d.physicalQty}</td>
        <td class="warn-text">${d.qtyDiff > 0 ? '+' : ''}${d.qtyDiff}</td>
        <td>${fmtTime(d.lastPhysicalAt)}</td>
      </tr>`).join('');
    openModalHtml(`
      <div class="modal-backdrop" onclick="closeModal(event)">
        <div class="modal modal-wide" onclick="event.stopPropagation()">
          <div class="device-detail-head">
            <h3>账实差异货道</h3>
            <button type="button" class="btn-ghost btn-sm" data-modal-cancel onclick="closeModal()">关闭</button>
          </div>
          ${rows ? `<table class="table"><thead><tr><th>设备</th><th>ID</th><th>货道</th><th>SKU</th><th>账面</th><th>实测</th><th>差异</th><th>盘点时间</th></tr></thead><tbody>${rows}</tbody></table>`
            : emptyStateHtml('暂无账实差异', '完成通道盘点后将在此显示账面与实测不一致的货道', 'closeModal()')}
        </div>
      </div>`);
  } catch (e) {
    if (!handleAuthFailure(e)) toast('加载差异告警失败: ' + e.message, 'err');
  }
}

async function showSlotEditor(deviceId, slotCode) {
  let slot = null;
  if (slotCode) {
    const slots = await api('/api/v2/ops/admin/devices/' + encodeURIComponent(deviceId) + '/slots', 'GET');
    slot = (slots || []).find(s => s.slotCode === slotCode) || null;
  }
  let skuOptions = '<option value="">未绑定</option>';
  try {
    const skus = await api('/api/v2/ops/admin/skus', 'GET');
    skuOptions += (skus || []).map(s =>
      `<option value="${escAttr(s.skuId)}" ${slot && slot.assignedSkuId === s.skuId ? 'selected' : ''}>${esc(s.skuName)} (${esc(s.skuId)})</option>`
    ).join('');
  } catch (_) { /* ignore */ }
  const isEdit = !!slot;
  const nextRow = slot?.rowNo || 1;
  const nextCol = slot?.colNo || 1;
  openModalHtml(`
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal" onclick="event.stopPropagation()">
        <h3>${isEdit ? '编辑货道' : '添加货道'} · ${esc(deviceId)}</h3>
        <div class="form-grid">
          <div><label>货道编号</label>
            <input id="seCode" value="${escAttr(slot?.slotCode || '')}" ${isEdit ? 'disabled' : ''} placeholder="A1"></div>
          <div><label>行</label><input id="seRow" type="number" min="1" value="${nextRow}"></div>
          <div><label>列</label><input id="seCol" type="number" min="1" value="${nextCol}"></div>
          <div><label>类型</label>
            <select id="seType">
              <option value="SHELF" ${(!slot || slot.slotType === 'SHELF') ? 'selected' : ''}>层架 SHELF</option>
              <option value="HOOK" ${slot?.slotType === 'HOOK' ? 'selected' : ''}>挂钩 HOOK</option>
              <option value="BASKET" ${slot?.slotType === 'BASKET' ? 'selected' : ''}>篮筐 BASKET</option>
            </select></div>
          <div style="grid-column:1/-1"><label>绑定商品</label><select id="seSku">${skuOptions}</select></div>
          <div><label>标准容量 (PAR)</label><input id="sePar" type="number" min="0" value="${slot?.parLevel ?? 8}"></div>
          <div><label>补货线 (MIN)</label><input id="seMin" type="number" min="0" value="${slot?.minLevel ?? 2}"></div>
          <div><label>最大容量</label><input id="seMax" type="number" min="0" value="${slot?.maxLevel ?? slot?.parLevel ?? 8}"></div>
          <div><label>启用</label>
            <select id="seEnabled">
              <option value="true" ${!slot || slot.enabled !== false ? 'selected' : ''}>是</option>
              <option value="false" ${slot && slot.enabled === false ? 'selected' : ''}>否</option>
            </select></div>
        </div>
        <div class="filters" style="margin-top:12px">
          <button type="button" class="btn-primary" onclick="saveSlotConfig(event, '${escAttr(deviceId)}', ${isEdit})">保存</button>
          ${isEdit && hasPerm('ops:device:edit') ? `<button type="button" class="btn-ghost" onclick="deleteSlotConfig('${escAttr(deviceId)}','${escAttr(slot.slotCode)}')">删除货道</button>` : ''}
          ${hasPerm('ops:replenishment:edit') && isEdit ? `<button type="button" class="btn-ghost" onclick="promptSlotStocktakeFor('${escAttr(deviceId)}','${escAttr(slot.slotCode)}',${slot.bookQty ?? 0})">盘点此货道</button>` : ''}
          <button type="button" class="btn-ghost" data-modal-cancel onclick="closeModal();viewDeviceDetail('${escAttr(deviceId)}')">返回详情</button>
        </div>
      </div>
    </div>`);
}

async function saveSlotConfig(ev, deviceId, isEdit) {
  await withSaveGuard(ev, async () => {
    const slotCode = (document.getElementById('seCode')?.value || '').trim().toUpperCase();
    const rowNo = parseInt(document.getElementById('seRow').value, 10);
    const colNo = parseInt(document.getElementById('seCol').value, 10);
    const parLevel = parseInt(document.getElementById('sePar').value, 10);
    const minLevel = parseInt(document.getElementById('seMin').value, 10);
    const maxLevel = parseInt(document.getElementById('seMax').value, 10);
    if (!slotCode || Number.isNaN(rowNo) || Number.isNaN(colNo) || Number.isNaN(parLevel)) {
      toast('请填写货道编号、行列与标准容量', 'err');
      return;
    }
    try {
      await api('/api/v2/ops/admin/devices/' + encodeURIComponent(deviceId) + '/slots', 'PUT', [{
        slotCode, rowNo, colNo,
        slotType: document.getElementById('seType').value,
        assignedSkuId: document.getElementById('seSku').value || null,
        parLevel, minLevel, maxLevel: Number.isNaN(maxLevel) ? parLevel : maxLevel,
        enabled: document.getElementById('seEnabled').value === 'true'
      }]);
      toast('货道已保存', 'ok');
      closeModal();
      viewDeviceDetail(deviceId);
    } catch (e) {
      if (!handleAuthFailure(e)) toast('保存失败: ' + e.message, 'err');
    }
  });
}

async function deleteSlotConfig(deviceId, slotCode) {
  if (!await showConfirm(`确认删除货道 ${slotCode}？仅无账面库存时可删除。`, { title: '删除货道' })) return;
  try {
    await api('/api/v2/ops/admin/devices/' + encodeURIComponent(deviceId) + '/slots/' + encodeURIComponent(slotCode), 'DELETE');
    toast('货道已删除', 'ok');
    closeModal();
    viewDeviceDetail(deviceId);
  } catch (e) {
    if (!handleAuthFailure(e)) toast('删除失败: ' + e.message, 'err');
  }
}

async function promptSlotStocktakeFor(deviceId, slotCode, bookQty) {
  const qtyStr = prompt(`货道 ${slotCode} 实测数量\n当前账面：${bookQty}`, String(bookQty));
  if (qtyStr == null || qtyStr === '') return;
  const physicalQty = parseInt(qtyStr, 10);
  if (Number.isNaN(physicalQty) || physicalQty < 0) {
    toast('请输入有效数量', 'err');
    return;
  }
  try {
    await api('/api/v2/ops/admin/devices/' + encodeURIComponent(deviceId) + '/slots/stocktake', 'POST', {
      slotCode: slotCode.trim(), physicalQty
    });
    toast('盘点已记录', 'ok');
    closeModal();
    viewDeviceDetail(deviceId);
  } catch (e) {
    if (!handleAuthFailure(e)) toast('盘点失败: ' + e.message, 'err');
  }
}

async function promptSlotStocktake(deviceId) {
  const slotCode = prompt('货道编号（如 A1）');
  if (!slotCode) return;
  const qtyStr = prompt('实测数量');
  if (qtyStr == null || qtyStr === '') return;
  const physicalQty = parseInt(qtyStr, 10);
  if (Number.isNaN(physicalQty) || physicalQty < 0) {
    toast('请输入有效数量', 'err');
    return;
  }
  try {
    await api('/api/v2/ops/admin/devices/' + encodeURIComponent(deviceId) + '/slots/stocktake', 'POST', {
      slotCode: slotCode.trim(), physicalQty
    });
    toast('盘点已记录', 'ok');
    viewDeviceDetail(deviceId);
  } catch (e) {
    if (!handleAuthFailure(e)) toast('盘点失败: ' + e.message, 'err');
  }
}

async function saveDevice(ev, isEdit) {
  await withSaveGuard(ev, async () => {
  const deviceId = document.getElementById('dfId').value.trim();
  const deviceName = document.getElementById('dfName').value.trim();
  const deviceType = document.getElementById('dfType').value.trim();
  const merchantId = document.getElementById('dfMerchant')?.value || '';
  try {
    if (isEdit) {
      await api('/api/v2/ops/admin/devices/' + encodeURIComponent(deviceId), 'PATCH',
        { deviceName, deviceType, merchantId });
    } else {
      await api('/api/v2/ops/admin/devices', 'POST',
        { deviceId, deviceName, deviceType, merchantId: merchantId || null });
    }
    closeModal();
    toast('保存成功', 'ok');
    loadDevices();
  } catch (e) {
    if (!handleAuthFailure(e)) toast('保存失败: ' + e.message, 'err');
  }
  });
}

function loadSessionsPage() {
  selClear('sessions');
  document.getElementById('pageContent').innerHTML = `
    <div class="card list-page-card">
      ${listFilterBar({
        onSearch: 'searchSessions()',
        onReset: 'resetSessionFilters()',
        refreshFn: 'fetchSessions()',
        extraHtml: `<button type="button" class="btn-ghost btn-sm" onclick="exportSessionsCsv()">导出 CSV</button>${selBar('sessions', '<button type="button" class="btn-ghost btn-sm" onclick="selClear(\'sessions\')">清除选择</button>')}`,
        fieldsHtml: `
          ${filterField('设备编号', `<input id="sfDevice" value="${escAttr(sessionFilters.deviceId)}" placeholder="CAB-001">`)}
          ${filterField('状态', `<select id="sfState">
            <option value="">全部</option>
            ${['CREATED','OPENING','SHOPPING','RECOGNIZING','WAITING_UPLOAD','SETTLING','COMPLETED','DISPUTED','FAILED','CANCELLED']
              .map(s => `<option value="${s}" ${sessionFilters.state === s ? 'selected' : ''}>${esc(sessionStateLabel(s))}</option>`).join('')}
          </select>`)}`
      })}
      <div id="sessionTable"></div>
    </div>`;
  showTableLoading(document.getElementById('sessionTable'), 7, 6);
  fetchSessions();
}

function resetSessionFilters() {
  sessionFilters.deviceId = '';
  sessionFilters.state = '';
  sessionFilters.page = 0;
  loadSessionsPage();
}

async function searchSessions() {
  sessionFilters.deviceId = document.getElementById('sfDevice').value.trim();
  sessionFilters.state = document.getElementById('sfState').value;
  sessionFilters.page = 0;
  fetchSessions();
}

function renderSessionActions(s) {
  const actions = [];
  if (s.orderId && hasPerm('ops:order:list')) {
    actions.push(`<button type="button" class="btn-ghost btn-sm" onclick="showOrderDetail('${escAttr(s.orderId)}')">订单</button>`);
  }
  if (s.state === 'DISPUTED' && hasPerm('ops:dispute')) {
    actions.push(`<button type="button" class="btn-ghost btn-sm" onclick="openDisputeForSession('${escAttr(s.sessionId)}')">争议</button>`);
  }
  if (!['COMPLETED', 'CANCELLED'].includes(s.state) && hasPerm('ops:session:cancel')) {
    actions.push(`<button type="button" class="btn-danger btn-sm" onclick="cancelSession('${escAttr(s.sessionId)}')">取消</button>`);
  }
  return actions.length ? `<div class="row-actions">${actions.join('')}</div>` : '<span class="meta">-</span>';
}

function openDisputeForSession(sessionId) {
  disputeFilters.sessionId = sessionId;
  disputeFilters.page = 0;
  navigate('disputes');
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
    const items = sortItems(data.items, tableSort.sessions.field, tableSort.sessions.dir);
    table.innerHTML = selWrap('sessions', `
      <table class="data-table table-sessions">
        <thead><tr>
          ${selHeaderCell('sessions')}
          <th>记录编号</th><th>用户</th><th>设备</th><th>状态</th><th>录像</th><th>订单</th><th>视频</th>
          ${sortableHeader('sessions', 'createdAt', '创建时间', tableSort.sessions)}<th class="col-actions">操作</th>
        </tr></thead>
        <tbody>${items.map(s => `
          ${selRowOpen('sessions', s.sessionId)}
          ${selCheckCell('sessions', s.sessionId)}
          <td><code>${esc(s.sessionId)}</code></td>
          <td>${esc(s.userId)}</td>
          <td>${esc(s.deviceId)}</td>
          <td>${stateBadge(s.state)}</td>
          <td>${esc(uploadStatusLabel(s.uploadStatus))}</td>
          <td>${s.orderId ? `<code class="meta">${esc(s.orderId)}</code>` : '-'}</td>
          <td onclick="event.stopPropagation()">${s.videoUri || s.videoPreviewUrl
            ? `<button type="button" class="btn-ghost btn-sm" onclick="showSessionVideo('${escAttr(s.sessionId)}', '${escAttr(s.videoUri || '')}')">${mediaActionLabel(s.videoUri)}</button>`
            : '-'}</td>
          <td>${fmtTime(s.createdAt)}</td>
          <td class="col-actions" onclick="event.stopPropagation()">${renderSessionActions(s)}</td>
        </tr>`).join('')}</tbody>
      </table>`)
      + renderPagination(data, 'session');
    selSync('sessions');
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
  if (!await showConfirm('确认取消会话 ' + sessionId + '？设备将可再次开门。', { title: '取消会话', danger: true })) return;
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
  selClear('orders');
  document.getElementById('pageContent').innerHTML = `
    <div class="card list-page-card">
      ${listFilterBar({
        onSearch: 'searchOrders()',
        onReset: 'resetOrderFilters()',
        refreshFn: 'fetchOrders()',
        extraHtml: `<button type="button" class="btn-ghost btn-sm" onclick="exportOrdersCsv()">导出 CSV</button>${selBar('orders')}`,
        fieldsHtml: filterField('设备编号', `<input id="ofDevice" value="${escAttr(orderFilters.deviceId)}" placeholder="留空=全部">`)
      })}
      <div id="orderTable"></div>
    </div>`;
  showTableLoading(document.getElementById('orderTable'), 8, 6);
  fetchOrders();
}

function resetOrderFilters() {
  orderFilters.deviceId = '';
  orderFilters.page = 0;
  loadOrdersPage();
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
    const items = sortItems(data.items, tableSort.orders.field, tableSort.orders.dir);
    table.innerHTML = selWrap('orders', `
      <table class="data-table">
        <thead><tr>
          ${selHeaderCell('orders')}
          <th>订单号</th><th>开门记录</th>${sortableHeader('orders', 'userId', '用户', tableSort.orders)}<th>设备</th>
          ${sortableHeader('orders', 'totalAmountCents', '金额', tableSort.orders)}<th>商品行</th>
          ${sortableHeader('orders', 'createdAt', '时间', tableSort.orders)}<th class="col-actions">操作</th>
        </tr></thead>
        <tbody>${items.map(o => `
          ${selRowOpen('orders', o.orderId)}
          ${selCheckCell('orders', o.orderId)}
          <td><code>${esc(o.orderId)}</code></td>
          <td>${esc(o.sessionId)}</td>
          <td>${esc(o.userId)}</td>
          <td>${esc(o.deviceId)}</td>
          <td>${fmtMoney(o.totalAmountCents)}</td>
          <td>${esc(o.lineCount)}</td>
          <td>${fmtTime(o.createdAt)}</td>
          <td class="col-actions" onclick="event.stopPropagation()"><div class="row-actions"><button class="btn-ghost btn-sm" onclick="showOrderDetail('${escAttr(o.orderId)}')">详情</button></div></td>
        </tr>`).join('')}</tbody>
      </table>`)
      + renderPagination(data, 'order');
    selSync('orders');
  } catch (e) {
    pageRenderError(table, e, false);
  }
}

function renderPagination(data, type) {
  return buildPaginationHtml(data, type);
}

function changePageSize(type, size) {
  const n = parseInt(size, 10) || 20;
  if (type === 'session') sessionFilters.size = n;
  else if (type === 'user') userFilters.size = n;
  else if (type === 'audit') auditFilters.size = n;
  else if (type === 'recharge') rechargeFilters.size = n;
  else if (type === 'dispute') disputeFilters.size = n;
  else if (type === 'upload') {
    if (window.uploadQueueFilters) window.uploadQueueFilters.size = n;
  } else if (type === 'merchantSplit') {
    if (window.merchantSplitFilters) window.merchantSplitFilters.size = n;
  } else if (type === 'rbacOp') {
    if (window._rbacState?.operatorFilters) window._rbacState.operatorFilters.size = n;
  } else orderFilters.size = n;
  changePage(type, 0);
}

function jumpToPage(type, pageNum) {
  const p = Math.max(1, parseInt(pageNum, 10) || 1) - 1;
  changePage(type, p);
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
  } else if (type === 'dispute') {
    disputeFilters.page = Math.max(0, page);
    fetchDisputes();
  } else if (type === 'upload') {
    if (window.uploadQueueFilters) window.uploadQueueFilters.page = Math.max(0, page);
    if (typeof fetchUploadQueue === 'function') fetchUploadQueue();
  } else if (type === 'merchantSplit') {
    if (window.merchantSplitFilters) window.merchantSplitFilters.page = Math.max(0, page);
    if (typeof fetchMerchantSplits === 'function') fetchMerchantSplits();
  } else if (type === 'rbacOp') {
    if (window._rbacState?.operatorFilters) window._rbacState.operatorFilters.page = Math.max(0, page);
    if (typeof fetchRbacOperators === 'function') fetchRbacOperators();
  } else {
    orderFilters.page = Math.max(0, page);
    fetchOrders();
  }
}

async function showOrderDetail(orderId) {
  try {
    const o = await api('/api/v2/ops/admin/orders/' + orderId, 'GET');
    const lines = (o.lines || []).map(l =>
      `<tr><td>${esc(l.skuName)}</td><td>${esc(l.skuId)}</td><td>${esc(l.quantity)}</td><td><code>${esc(l.batchNo || '-')}</code></td><td>${fmtMoney(l.unitPriceCents)}</td><td>${fmtMoney(l.lineAmountCents)}</td></tr>`
    ).join('');
    openModalHtml(`
      <div class="modal-backdrop" onclick="closeModal(event)">
        <div class="modal" onclick="event.stopPropagation()">
          <h3>订单 ${esc(o.orderId)}</h3>
          <div class="meta">会话 ${esc(o.sessionId)} · 设备 ${esc(o.deviceId)} · 用户 ${esc(o.userId)}</div>
          <table style="margin-top:12px">
            <thead><tr><th>商品</th><th>SKU</th><th>数量</th><th>批次</th><th>单价</th><th>小计</th></tr></thead>
            <tbody>${lines}</tbody>
          </table>
          <p style="margin-top:12px;font-weight:700">合计 ${fmtMoney(o.totalAmountCents)}</p>
          <button type="button" class="btn-ghost" data-modal-cancel onclick="closeModal()">关闭</button>
        </div>
      </div>`);
  } catch (e) {
    if (!handleAuthFailure(e)) toast('加载失败: ' + e.message, 'err');
  }
}

function closeModal(e) {
  if (e && e.target !== e.currentTarget) return;
  revokeModalBlobUrl();
  teardownModalA11y();
  document.getElementById('modalRoot').classList.add('hidden');
  document.getElementById('modalRoot').innerHTML = '';
}

function openModalHtml(html, onEscape) {
  document.getElementById('modalRoot').innerHTML = html;
  document.getElementById('modalRoot').classList.remove('hidden');
  enhanceOpenedModal(onEscape || (() => closeModal()));
}

async function loadSkus() {
  try {
    skus = sortSkuList(await api('/api/v2/ops/admin/skus', 'GET'));
  } catch (e) {
    skus = [{ skuId: 'SKU-DEMO-001', skuName: '示例商品', priceCents: 350, status: 'ACTIVE', visionEnabled: true }];
  }
}

function loadSkusPage() {
  selClear('skus');
  document.getElementById('pageContent').innerHTML = `
    <div class="card list-page-card">
      ${listFilterBar({
        onSearch: 'searchSkus()',
        onReset: 'resetSkuFilters()',
        refreshFn: 'fetchSkusTable()',
        extraHtml: `${permButton('sku.edit', '新增商品', 'showSkuForm()', 'btn-primary btn-sm')}${permButton('sku.edit', '编辑所选', 'editSelectedSku()', 'btn-ghost btn-sm')}${selBar('skus')}`,
        fieldsHtml: `
          ${filterField('商品名称', `<input id="skuFilterName" value="${escAttr(skuFilters.name)}" placeholder="支持模糊搜索">`)}
          ${filterField('状态', `<select id="skuFilterStatus">
            <option value="">全部</option>
            <option value="ACTIVE" ${skuFilters.status === 'ACTIVE' ? 'selected' : ''}>上架</option>
            <option value="INACTIVE" ${skuFilters.status === 'INACTIVE' ? 'selected' : ''}>下架</option>
          </select>`)}`
      })}
      <div id="skuTable"></div>
    </div>`;
  showTableLoading(document.getElementById('skuTable'), 10, 5);
  fetchSkusTable();
}

function searchSkus() {
  skuFilters.name = document.getElementById('skuFilterName')?.value.trim() || '';
  skuFilters.status = document.getElementById('skuFilterStatus')?.value || '';
  fetchSkusTable();
}

function resetSkuFilters() {
  skuFilters.name = '';
  skuFilters.status = '';
  loadSkusPage();
}

function filterSkuList(list) {
  const name = (skuFilters.name || '').trim().toLowerCase();
  const status = skuFilters.status || '';
  return (list || []).filter(s => {
    if (status && s.status !== status) return false;
    if (name && !String(s.skuName || '').toLowerCase().includes(name) && !String(s.skuId || '').toLowerCase().includes(name)) return false;
    return true;
  });
}

function editSelectedSku() {
  const ids = selSelected('skus');
  if (ids.length !== 1) {
    toast('请勾选恰好 1 个商品再编辑', 'err');
    return;
  }
  showSkuFormById(ids[0]);
}

function skuStatusLabel(status) {
  return status === 'INACTIVE' ? '下架' : '上架';
}

function skuImageCell(url, name) {
  if (!url) return '<span class="meta">-</span>';
  return `<img src="${escAttr(url)}" alt="${escAttr(name || '')}" class="sku-thumb" loading="lazy"
    referrerpolicy="no-referrer"
    onerror="this.replaceWith(Object.assign(document.createElement('span'),{className:'meta',textContent:'无图'}))">`;
}

function sortSkuList(list) {
  return [...(list || [])].sort((a, b) => String(a.skuId).localeCompare(String(b.skuId), 'zh-CN'));
}

function showSkuFormById(skuId) {
  const sku = skus.find((s) => s.skuId === skuId);
  if (!sku) {
    toast('商品不存在或列表未刷新', 'err');
    return;
  }
  showSkuForm(sku);
}

async function fetchSkusTable() {
  const table = document.getElementById('skuTable');
  if (!table) return;
  showTableLoading(table, 9, 5);
  try {
    skus = await api('/api/v2/ops/admin/skus', 'GET');
    const filtered = sortSkuList(filterSkuList(skus));
    if (!filtered.length) {
      table.innerHTML = emptyStateHtml('暂无商品', skuFilters.name || skuFilters.status ? '调整筛选条件后重试' : '添加商品后可在争议审核中选择', 'fetchSkusTable()');
      return;
    }
    table.innerHTML = selWrap('skus', `
      <table class="data-table table-sku">
        <thead><tr>
          ${selHeaderCell('skus')}
          <th>商品编号</th><th>名称</th><th>分类</th><th>价格</th><th>重量(g)</th><th>条码</th><th>状态</th><th>图片</th><th class="col-actions">操作</th>
        </tr></thead>
        <tbody>${filtered.map(s => `
          ${selRowOpen('skus', s.skuId)}
          ${selCheckCell('skus', s.skuId)}
          <td><code>${esc(s.skuId)}</code></td>
          <td>${esc(s.skuName)}</td>
          <td>${esc(s.category || '-')}</td>
          <td>${fmtMoney(s.priceCents)}</td>
          <td>${s.weightGrams != null ? esc(s.weightGrams) : '-'}</td>
          <td>${esc(s.barcode || '-')}</td>
          <td>${skuStatusLabel(s.status)}${s.visionEnabled === false ? ' · 无视觉' : ''}</td>
          <td>${skuImageCell(s.imageUrl, s.skuName)}</td>
          <td class="col-actions" onclick="event.stopPropagation()"><div class="row-actions">${hasPerm('ops:sku:edit')
            ? `<button type="button" class="btn-ghost btn-sm" onclick="showSkuFormById('${escAttr(s.skuId)}')">编辑</button>` : '<span class="meta">-</span>'}</div></td>
        </tr>`).join('')}</tbody>
      </table>`);
    selSync('skus');
  } catch (e) {
    pageRenderError(table, e, false);
  }
}

function showSkuForm(sku) {
  const isEdit = !!sku;
  openModalHtml(`
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal" style="max-width:640px" onclick="event.stopPropagation()">
        <h3>${isEdit ? '编辑商品' : '新增商品'}</h3>
        <label>商品编号</label>
        <input id="skuId" value="${isEdit ? escAttr(sku.skuId) : ''}" ${isEdit ? 'disabled' : ''} placeholder="例如 SKU-COLA-001">
        <label>商品名称</label>
        <input id="skuName" value="${isEdit ? escAttr(sku.skuName) : ''}" placeholder="可乐 330ml">
        <div class="filters form-grid">
          <div><label>分类</label><input id="skuCategory" value="${isEdit ? escAttr(sku.category || '') : ''}" placeholder="饮料"></div>
          <div><label>条码</label><input id="skuBarcode" value="${isEdit ? escAttr(sku.barcode || '') : ''}" placeholder="6901234567890"></div>
          <div><label>销售价（元）</label><input id="skuPrice" type="number" min="0.01" step="0.01" value="${isEdit ? (sku.priceCents / 100).toFixed(2) : '3.50'}"></div>
          <div><label>采购成本（元）</label><input id="skuCost" type="number" min="0" step="0.01" value="${isEdit && sku.purchaseCostCents != null ? (sku.purchaseCostCents / 100).toFixed(2) : ''}" placeholder="2.80"></div>
          <div><label>重量（克）</label><input id="skuWeight" type="number" min="0" value="${isEdit && sku.weightGrams != null ? sku.weightGrams : ''}" placeholder="330"></div>
        </div>
        <p class="meta">价格与成本以元为单位填写，用于售价展示与毛利计算</p>
        <label>商品描述</label>
        <textarea id="skuDescription" rows="3" placeholder="规格、口味、包装说明等">${isEdit ? esc(sku.description || '') : ''}</textarea>
        <div class="filters">
          <div><label>状态</label>
            <select id="skuStatus">
              <option value="ACTIVE" ${!isEdit || sku.status !== 'INACTIVE' ? 'selected' : ''}>上架</option>
              <option value="INACTIVE" ${isEdit && sku.status === 'INACTIVE' ? 'selected' : ''}>下架</option>
            </select>
          </div>
          <div style="display:flex;align-items:flex-end;padding-bottom:8px">
            <label style="display:flex;align-items:center;gap:8px;margin:0">
              <input id="skuVisionEnabled" type="checkbox" ${!isEdit || sku.visionEnabled !== false ? 'checked' : ''}>
              参与视觉识别
            </label>
          </div>
        </div>
        <h4 style="margin:12px 0 8px">保质期 / 效期</h4>
        <div class="filters form-grid">
          <div><label>保质期（天）</label>
            <input id="skuShelfLife" type="number" min="0" value="${isEdit && sku.shelfLifeDays != null ? sku.shelfLifeDays : ''}" placeholder="180"></div>
          <div><label>临期提醒（天）</label>
            <input id="skuNearExpiry" type="number" min="1" value="${isEdit ? (sku.nearExpiryDays ?? 7) : 7}"></div>
          <div><label>到期前禁售（天）</label>
            <input id="skuBlockSale" type="number" min="0" value="${isEdit ? (sku.blockSaleDaysBeforeExpiry ?? 0) : 0}"></div>
          <div><label>存储类型</label>
            <select id="skuStorageType">
              <option value="AMBIENT" ${!isEdit || sku.storageType === 'AMBIENT' || !sku.storageType ? 'selected' : ''}>常温</option>
              <option value="CHILLED" ${isEdit && sku.storageType === 'CHILLED' ? 'selected' : ''}>冷藏</option>
              <option value="FROZEN" ${isEdit && sku.storageType === 'FROZEN' ? 'selected' : ''}>冷冻</option>
            </select>
          </div>
        </div>
        <label>图片 URL</label>
        <input id="skuImageUrl" value="${isEdit ? escAttr(sku.imageUrl || '') : ''}" placeholder="https://example.com/cola.jpg" oninput="previewSkuImage()">
        <div id="skuImagePreview" class="sku-preview-wrap">${isEdit && sku.imageUrl
          ? `<img src="${escAttr(sku.imageUrl)}" alt="预览" class="sku-preview" referrerpolicy="no-referrer">`
          : '<span class="meta">填写 URL 后显示预览</span>'}</div>
        <div class="filters" style="margin-top:12px">
          <button type="button" class="btn-primary" onclick="saveSku(event, ${isEdit})">保存</button>
          <button type="button" class="btn-ghost" data-modal-cancel onclick="closeModal()">取消</button>
        </div>
      </div>
    </div>`);
}

function previewSkuImage() {
  const url = document.getElementById('skuImageUrl')?.value.trim();
  const box = document.getElementById('skuImagePreview');
  if (!box) return;
  if (!url) {
    box.innerHTML = '<span class="meta">填写 URL 后显示预览</span>';
    return;
  }
  box.innerHTML = `<img src="${escAttr(url)}" alt="预览" class="sku-preview"
    onerror="this.parentElement.innerHTML='<span class=\\'meta\\'>图片无法加载</span>'">`;
}

async function saveSku(ev, isEdit) {
  await withSaveGuard(ev, async () => {
  const skuId = document.getElementById('skuId').value.trim();
  const skuName = document.getElementById('skuName').value.trim();
  const priceYuan = parseFloat(document.getElementById('skuPrice').value);
  const costRaw = document.getElementById('skuCost').value.trim();
  const priceCents = Math.round(priceYuan * 100);
  const purchaseCostCents = costRaw ? Math.round(parseFloat(costRaw) * 100) : null;
  const weightRaw = document.getElementById('skuWeight').value.trim();
  const weightGrams = weightRaw ? parseInt(weightRaw, 10) : null;
  const imageUrl = document.getElementById('skuImageUrl').value.trim();
  const category = document.getElementById('skuCategory').value.trim();
  const barcode = document.getElementById('skuBarcode').value.trim();
  const description = document.getElementById('skuDescription').value.trim();
  const status = document.getElementById('skuStatus').value;
  const visionEnabled = document.getElementById('skuVisionEnabled').checked;
  const shelfRaw = document.getElementById('skuShelfLife').value.trim();
  const shelfLifeDays = shelfRaw ? parseInt(shelfRaw, 10) : null;
  const nearExpiryDays = parseInt(document.getElementById('skuNearExpiry').value, 10) || 7;
  const blockSaleDaysBeforeExpiry = parseInt(document.getElementById('skuBlockSale').value, 10) || 0;
  const storageType = document.getElementById('skuStorageType').value || 'AMBIENT';
  if (!skuId || !skuName || !priceCents || Number.isNaN(priceYuan) || priceYuan <= 0) { toast('请填写商品编号、名称和有效售价', 'err'); return; }
  try {
    const body = {
      skuId, skuName, priceCents, status, visionEnabled,
      nearExpiryDays, blockSaleDaysBeforeExpiry, storageType,
      ...(shelfLifeDays != null && !Number.isNaN(shelfLifeDays) ? { shelfLifeDays } : {}),
      ...(weightGrams != null && !Number.isNaN(weightGrams) ? { weightGrams } : {}),
      ...(purchaseCostCents != null && !Number.isNaN(purchaseCostCents) ? { purchaseCostCents } : {}),
      ...(imageUrl ? { imageUrl } : {}),
      ...(category ? { category } : {}),
      ...(barcode ? { barcode } : {}),
      ...(description ? { description } : {})
    };
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
  });
}

function loadUsersPage() {
  selClear('users');
  document.getElementById('pageContent').innerHTML = `
    <div class="card list-page-card">
      ${listFilterBar({
        onSearch: 'searchUsers()',
        onReset: 'resetUserFilters()',
        refreshFn: 'fetchUsers()',
        extraHtml: selBar('users'),
        fieldsHtml: `
          ${filterField('手机号', `<input id="ufPhone" value="${escAttr(userFilters.phone)}" placeholder="支持模糊搜索">`)}
          ${filterField('姓名', `<input id="ufName" value="${escAttr(userFilters.name)}" placeholder="支持模糊搜索">`)}
          ${filterField('角色', `<select id="ufRole">
            <option value="">全部</option>
            <option value="CONSUMER" ${userFilters.role === 'CONSUMER' ? 'selected' : ''}>消费者</option>
            <option value="OPERATOR" ${userFilters.role === 'OPERATOR' ? 'selected' : ''}>运营</option>
          </select>`)}
          ${filterField('实名状态', `<select id="ufVerified">
            <option value="">全部</option>
            <option value="true" ${userFilters.verified === 'true' ? 'selected' : ''}>已实名</option>
            <option value="false" ${userFilters.verified === 'false' ? 'selected' : ''}>未实名</option>
          </select>`)}`
      })}
      <div id="userTable"></div>
    </div>`;
  showTableLoading(document.getElementById('userTable'), 8, 6);
  fetchUsers();
}

function readUserFiltersFromDom() {
  userFilters.phone = document.getElementById('ufPhone')?.value.trim() || '';
  userFilters.name = document.getElementById('ufName')?.value.trim() || '';
  userFilters.role = document.getElementById('ufRole')?.value || '';
  userFilters.verified = document.getElementById('ufVerified')?.value || '';
}

function resetUserFilters() {
  userFilters.phone = '';
  userFilters.name = '';
  userFilters.role = '';
  userFilters.verified = '';
  userFilters.page = 0;
  loadUsersPage();
}

function searchUsers() {
  readUserFiltersFromDom();
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
      ...(userFilters.phone ? { phone: userFilters.phone } : {}),
      ...(userFilters.name ? { name: userFilters.name } : {}),
      ...(userFilters.role ? { role: userFilters.role } : {}),
      ...(userFilters.verified !== '' ? { verified: userFilters.verified } : {})
    });
    const data = await api('/api/v2/ops/admin/users?' + q, 'GET');
    if (!data.items.length) {
      table.innerHTML = emptyStateHtml('暂无用户', '消费者通过小程序注册后会出现在此列表', 'fetchUsers()');
      return;
    }
    const items = sortItems(data.items, tableSort.users.field, tableSort.users.dir);
    table.innerHTML = selWrap('users', `
      <table class="data-table">
        <thead><tr>
          ${selHeaderCell('users')}
          ${sortableHeader('users', 'userId', '用户编号', tableSort.users)}
          <th>手机号</th><th>姓名</th><th>角色</th><th>实名</th>
          ${sortableHeader('users', 'balanceCents', '余额', tableSort.users)}
          ${sortableHeader('users', 'createdAt', '注册时间', tableSort.users)}
          <th class="col-actions">操作</th>
        </tr></thead>
        <tbody>${items.map(u => `
          ${selRowOpen('users', u.userId)}
          ${selCheckCell('users', u.userId)}
          <td>${esc(u.userId)}</td>
          <td>${esc(u.phoneNumber)}</td>
          <td>${esc(u.name || '-')}</td>
          <td>${u.role === 'OPERATOR' ? '<span class="badge badge-active">运营</span>' : '<span class="badge badge-done">消费者</span>'}</td>
          <td>${u.verified ? '<span class="badge badge-done">已实名</span>' : '<span class="meta">未实名</span>'}</td>
          <td>${fmtMoney(u.balanceCents)}</td>
          <td>${fmtTime(u.createdAt)}</td>
          <td class="col-actions" onclick="event.stopPropagation()"><div class="row-actions">${u.role === 'OPERATOR'
            ? (hasPerm('ops:rbac:assign') ? `<button class="btn-ghost btn-sm" onclick="showRbacAssignForUser(${u.userId})">分配角色</button>` : '<span class="meta">-</span>')
            : `${hasPerm('ops:user:balance') ? `<button class="btn-ghost btn-sm" onclick="showBalanceForm(${u.userId}, ${u.balanceCents})">调余额</button>` : ''}
                ${hasPerm('ops:user:list') ? (u.verified
                  ? `<button class="btn-ghost btn-sm" onclick="showVerifyUserForm(${u.userId}, false, '${escAttr(u.name || '')}')">取消实名</button>`
                  : `<button class="btn-ghost btn-sm" onclick="showVerifyUserForm(${u.userId}, true, '')">标记实名</button>`) : ''}`}</div></td>
        </tr>`).join('')}</tbody>
      </table>`)
      + renderPagination(data, 'user');
    selSync('users');
  } catch (e) {
    pageRenderError(table, e, false);
  }
}

function showBalanceForm(userId, balanceCents) {
  openModalHtml(`
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal" onclick="event.stopPropagation()">
        <h3>调整余额</h3>
        <p class="meta">当前余额 ${fmtMoney(balanceCents)}</p>
        <label>调整金额（元，正数充值 / 负数扣减）</label>
        <input id="deltaYuan" type="number" step="0.01" value="10.00" placeholder="10.00">
        <p class="meta">例：10 表示加 ¥10.00；-3.5 表示扣 ¥3.50</p>
        <div class="filters" style="margin-top:12px">
          <button type="button" class="btn-primary" onclick="saveBalance(event, ${userId})">确认</button>
          <button type="button" class="btn-ghost" data-modal-cancel onclick="closeModal()">取消</button>
        </div>
      </div>
    </div>`);
}

async function saveBalance(ev, userId) {
  await withSaveGuard(ev, async () => {
  const deltaYuan = parseFloat(document.getElementById('deltaYuan').value);
  const deltaCents = Math.round(deltaYuan * 100);
  if (isNaN(deltaYuan) || deltaCents === 0) { toast('请输入有效金额', 'err'); return; }
  try {
    await api('/api/v2/ops/admin/users/' + userId + '/balance', 'POST', { deltaCents });
    closeModal();
    fetchUsers();
    toast('余额已更新', 'ok');
  } catch (e) {
    if (!handleAuthFailure(e)) toast('失败: ' + e.message, 'err');
  }
  });
}

async function setUserVerified(userId, verified, realName) {
  try {
    await api('/api/v2/ops/admin/users/' + userId + '/verify', 'POST', {
      verified,
      ...(realName ? { realName } : {})
    });
    fetchUsers();
    toast('实名状态已更新', 'ok');
  } catch (e) {
    if (!handleAuthFailure(e)) toast('失败: ' + e.message, 'err');
  }
}

async function showVerifyUserForm(userId, verified, currentName) {
  const title = verified ? `标记实名 · userId ${userId}` : `取消实名 · userId ${userId}`;
  if (!verified) {
    if (!await showConfirm(`确认取消用户 ${userId} 的实名状态？`, { title: '取消实名', danger: true })) return;
    return setUserVerified(userId, false);
  }
  openModalHtml(`
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal" onclick="event.stopPropagation()">
        <h3>${esc(title)}</h3>
        <label>真实姓名（可选）</label>
        <input id="verifyRealName" value="${escAttr(currentName || '')}" placeholder="张三">
        <div class="filters" style="margin-top:12px">
          <button type="button" class="btn-primary" onclick="saveVerifyUser(event, ${userId})">确认实名</button>
          <button type="button" class="btn-ghost" data-modal-cancel onclick="closeModal()">取消</button>
        </div>
      </div>
    </div>`);
}

async function saveVerifyUser(ev, userId) {
  await withSaveGuard(ev, async () => {
    const realName = document.getElementById('verifyRealName').value.trim();
    closeModal();
    await setUserVerified(userId, true, realName || undefined);
  });
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
  selClear('reports');
  try {
    const reports = await api('/api/v2/ops/admin/reports/devices', 'GET');
    if (currentPage !== page) return;
    el.innerHTML = `
      <div class="card list-page-card">
        ${listFilterBar({
          refreshFn: 'loadReportsPage()',
          extraHtml: selBar('reports'),
          fieldsHtml: ''
        })}
      </div>`;
    if (!reports.length) {
      el.innerHTML += `<div class="card">${emptyStateHtml('暂无设备报表', '注册设备并产生订单后自动生成统计', 'loadReportsPage()')}</div>`;
      return;
    }
    el.innerHTML += `
      <div class="card list-page-card" style="padding-top:0">
        ${selWrap('reports', `<table class="data-table">
          <thead><tr>
            ${selHeaderCell('reports')}
            <th>设备</th><th>状态</th><th>累计订单</th><th>累计营收</th>
            <th>今日订单</th><th>今日营收</th><th>累计会话</th><th>进行中</th>
          </tr></thead>
          <tbody>${reports.map(r => `
            ${selRowOpen('reports', r.deviceId)}
            ${selCheckCell('reports', r.deviceId)}
            <td><code>${esc(r.deviceId)}</code><br><span class="meta">${esc(r.deviceName || '-')}</span></td>
            <td>${onlineBadge(r.onlineStatus)}</td>
            <td>${r.orderTotal}</td>
            <td>${fmtMoney(r.revenueTotalCents)}</td>
            <td>${r.orderToday}</td>
            <td>${fmtMoney(r.revenueTodayCents)}</td>
            <td>${r.sessionTotal}</td>
            <td>${r.sessionActive ? '<span class="badge badge-active">是</span>' : '-'}</td>
          </tr>`).join('')}</tbody>
        </table>`)}
      </div>`;
    selSync('reports');
  } catch (e) {
    if (currentPage !== page) return;
    pageRenderError(el, e);
  }
}

function loadAuditPage() {
  selClear('audit');
  document.getElementById('pageContent').innerHTML = `
    <div class="card list-page-card">
      ${listFilterBar({
        refreshFn: 'fetchAuditLogs()',
        extraHtml: selBar('audit'),
        fieldsHtml: ''
      })}
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
    table.innerHTML = (typeof renderAuditTableHtml === 'function'
      ? renderAuditTableHtml(data.items, 'audit')
      : '') + renderPagination(data, 'audit');
    selSync('audit');
  } catch (e) {
    pageRenderError(table, e, false);
  }
}

function loadRecentPage() {
  selClear('recentAudit');
  document.getElementById('pageContent').innerHTML = `
    <div class="card list-page-card">
      <div class="list-filter-bar">
        <div class="list-filter-fields">
          <button type="button" class="btn-ghost btn-sm ${!recentFilters.mine ? 'active-tab' : ''}" onclick="setRecentScope(false)">全部操作</button>
          <button type="button" class="btn-ghost btn-sm ${recentFilters.mine ? 'active-tab' : ''}" onclick="setRecentScope(true)">我的操作</button>
        </div>
        <div class="list-filter-actions">
          ${refreshButton('fetchRecentLogs()')}
          ${selBar('recentAudit')}
          <button type="button" class="btn-ghost btn-sm" onclick="navigate('audit')">完整操作日志</button>
        </div>
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
      ? renderAuditTableHtml(items, 'recentAudit')
      : emptyStateHtml('暂无操作记录', '运营后台的敏感操作会记录在此', 'fetchRecentLogs()');
    selSync('recentAudit');
  } catch (e) {
    pageRenderError(table, e, false);
  }
}

async function loadDisputes() {
  const el = document.getElementById('pageContent');
  const page = 'disputes';
  selClear('disputes');
  await loadSkus();
  if (currentPage !== page) return;
  el.innerHTML = `
    <div class="card list-page-card">
      ${listFilterBar({
        onSearch: 'searchDisputes()',
        onReset: 'resetDisputeFilters()',
        refreshFn: 'fetchDisputes()',
        extraHtml: `${selBar('disputes')}<label class="filter-check"><input type="checkbox" title="全选" onchange="selToggleAll('disputes', this.checked)"> 全选</label>`,
        fieldsHtml: `
          ${filterField('状态', `<select id="dfStatus">
            <option value="OPEN" ${disputeFilters.status === 'OPEN' ? 'selected' : ''}>待审核</option>
            <option value="RESOLVED" ${disputeFilters.status === 'RESOLVED' ? 'selected' : ''}>已结案</option>
            <option value="" ${!disputeFilters.status ? 'selected' : ''}>全部</option>
          </select>`)}
          ${filterField('开门记录', `<input id="dfSession" value="${escAttr(disputeFilters.sessionId)}" placeholder="可选">`)}
          ${filterField('设备编号', `<input id="dfDevice" value="${escAttr(disputeFilters.deviceId)}" placeholder="CAB-001">`)}`
      })}
      <div id="disputeList"></div>
    </div>`;
  showTableLoading(document.getElementById('disputeList'), 1, 4);
  fetchDisputes();
}

function resetDisputeFilters() {
  disputeFilters.status = 'OPEN';
  disputeFilters.sessionId = '';
  disputeFilters.deviceId = '';
  disputeFilters.page = 0;
  loadDisputes();
}

function searchDisputes() {
  disputeFilters.status = document.getElementById('dfStatus').value;
  disputeFilters.sessionId = document.getElementById('dfSession').value.trim();
  disputeFilters.deviceId = document.getElementById('dfDevice').value.trim();
  disputeFilters.page = 0;
  fetchDisputes();
}

async function fetchDisputes() {
  const list = document.getElementById('disputeList');
  if (!list) return;
  showTableLoading(list, 1, 4);
  try {
    await loadSkus();
    const q = new URLSearchParams({
      page: disputeFilters.page,
      size: disputeFilters.size,
      status: disputeFilters.status || 'ALL',
      ...(disputeFilters.sessionId ? { sessionId: disputeFilters.sessionId } : {}),
      ...(disputeFilters.deviceId ? { deviceId: disputeFilters.deviceId } : {})
    });
    const data = await api('/api/v2/ops/disputes?' + q, 'GET');
    if (!data.items.length) {
      list.innerHTML = emptyStateHtml('暂无争议工单', '识别异常或用户申诉的工单会出现在此', 'fetchDisputes()');
      return;
    }
    list.innerHTML = selWrap('disputes', data.items.map(renderTicket).join(''))
      + renderPagination(data, 'dispute');
    selSync('disputes');
    applyNavPermissions();
  } catch (e) {
    pageRenderError(list, e, false);
  }
}

const disputeSuggestions = {};

function disputeAgeLabel(createdAt) {
  if (!createdAt) return '-';
  const hours = (Date.now() - new Date(createdAt).getTime()) / 3600000;
  if (hours < 1) return '刚刚提交';
  if (hours < 24) return `${Math.floor(hours)} 小时前`;
  return `${Math.floor(hours / 24)} 天前`;
}

function disputeSkuOptions(selected) {
  if (!skus.length) {
    return '<option value="">暂无商品，请先在商品管理添加</option>';
  }
  return skus.map(s =>
    `<option value="${escAttr(s.skuId)}" ${s.skuId === selected ? 'selected' : ''}>${esc(s.skuName)} (${esc(s.skuId)}) ${fmtMoney(s.priceCents)}</option>`
  ).join('');
}

function disputeLineHtml(skuId, qty) {
  const defaultSku = skuId || skus[0]?.skuId || '';
  return `<div class="dispute-line filters" style="margin-top:8px">
    <div class="dispute-sku-field"><label>商品</label><select class="sku-select">${disputeSkuOptions(defaultSku)}</select></div>
    <div class="dispute-qty-field"><label>数量</label><input type="number" class="qty-input" value="${qty || 1}" min="1"></div>
    <div class="dispute-action-field"><button type="button" class="btn-ghost btn-sm" onclick="removeDisputeLine(this)">移除</button></div>
  </div>`;
}

function addDisputeLine(ticketId) {
  const card = document.querySelector(`.ticket[data-ticket="${ticketId}"]`);
  if (!card) return;
  const box = card.querySelector('.dispute-lines');
  box.insertAdjacentHTML('beforeend', disputeLineHtml(skus[0]?.skuId, 1));
}

function applyDisputeSuggestion(ticketId) {
  const items = disputeSuggestions[ticketId] || [];
  const card = document.querySelector(`.ticket[data-ticket="${ticketId}"]`);
  if (!card || !items.length) {
    toast('无识别建议可采纳', 'err');
    return;
  }
  const box = card.querySelector('.dispute-lines');
  box.innerHTML = items.map(i => disputeLineHtml(i.skuId, i.quantity)).join('');
}

function removeDisputeLine(btn) {
  const card = btn.closest('.ticket');
  const box = card.querySelector('.dispute-lines');
  if (box.querySelectorAll('.dispute-line').length <= 1) {
    toast('至少保留一行商品', 'err');
    return;
  }
  btn.closest('.dispute-line').remove();
}

function renderSuggestedItems(items) {
  if (!items || !items.length) return '<div class="meta">识别建议：无</div>';
  const lines = items.map(i => {
    const batch = i.batchNo ? ` @${esc(i.batchNo)}` : '';
    return `${esc(i.skuName || i.skuId)} × ${esc(i.quantity)}${batch}`;
  }).join('；');
  return `<div class="meta">识别建议：${lines}</div>`;
}

function renderTicket(t) {
  disputeSuggestions[t.ticketId] = t.suggestedItems || [];
  const isOpen = t.status === 'OPEN';
  const age = disputeAgeLabel(t.createdAt);
  const ageWarn = isOpen && (t.slaOverdue || (Date.now() - new Date(t.createdAt).getTime()) > 48 * 3600000);
  const slaMeta = isOpen && t.slaDueAt
    ? `<div class="meta">处理截止 ${fmtTime(t.slaDueAt)}${t.slaHoursRemaining != null ? ` · 剩余 ${t.slaHoursRemaining} 小时` : ''}</div>`
    : '';
  const videoBtn = t.sessionId && (t.videoUri || t.videoPreviewUrl)
    ? `<button type="button" class="btn-ghost btn-sm" onclick="showSessionVideo('${escAttr(t.sessionId)}', '${escAttr(t.videoUri || '')}')">${mediaActionLabel(t.videoUri)}</button>`
    : (t.sessionId ? `<span class="meta">无视频</span>` : '');
  const resolvedBlock = !isOpen && t.resolutionItems && t.resolutionItems.length
    ? `<div class="meta">结案商品：${t.resolutionItems.map(i => `${esc(i.skuId)} × ${esc(i.quantity)}`).join('；')}</div>`
    : '';
  const firstSku = (t.suggestedItems && t.suggestedItems[0]?.skuId) || skus[0]?.skuId;
  const billedMeta = t.billedAmountCents != null
    ? `<div class="meta">已扣款 ¥${(t.billedAmountCents / 100).toFixed(2)}${t.orderId ? ` · 订单 ${esc(t.orderId)}` : ''}</div>`
    : (t.sessionState === 'DISPUTED' ? '<div class="meta">待扣款（识别待审核）</div>' : '');
  const actionBlock = isOpen ? `
    <div class="dispute-lines">${disputeLineHtml(firstSku, (t.suggestedItems && t.suggestedItems[0]?.quantity) || 1)}</div>
    <div class="filters" style="margin-top:8px">
      <button type="button" class="btn-ghost btn-sm" onclick="addDisputeLine('${escAttr(t.ticketId)}')">添加商品</button>
      <button type="button" class="btn-ghost btn-sm" onclick="applyDisputeSuggestion('${escAttr(t.ticketId)}')">采用识别建议</button>
      <button type="button" class="btn-ok btn-sm" onclick="resolveTicket('${escAttr(t.ticketId)}', this, 'CONFIRM')">确认扣款</button>
      <button type="button" class="btn-danger btn-sm" onclick="resolveTicket('${escAttr(t.ticketId)}', this, 'WAIVE')">免单退款</button>
    </div>` : '';
  return `${selCardOpen('disputes', t.ticketId, 'ticket')}
    <div class="ticket-check" onclick="event.stopPropagation()">${selCheckBox('disputes', t.ticketId)}</div>
    <div>${disputeStatusBadge(t.status)}${ageWarn ? ' <span class="badge badge-fail">超时待审</span>' : ''}</div>
    <div class="meta">工单 ${esc(t.ticketId)} · 设备 ${esc(t.deviceId || '-')} · 会话 ${esc(t.sessionId)}</div>
    <div class="meta">原因 ${esc(t.reason || '-')} · 等待 ${esc(age)} · 创建 ${fmtTime(t.createdAt)}${t.resolvedAt ? ` · 结案 ${fmtTime(t.resolvedAt)}` : ''}</div>
    ${slaMeta}
    ${billedMeta}
    ${renderSuggestedItems(t.suggestedItems)}
    ${resolvedBlock}
    <div class="filters" style="margin-top:8px">${videoBtn}</div>
    ${actionBlock}
  </div>`;
}

function renderVideoModalHtml(title, subtitle) {
  return `
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal modal-wide" onclick="event.stopPropagation()">
        <h3>${esc(title || '购物录像')}</h3>
        ${subtitle ? `<p class="meta">${esc(subtitle)}</p>` : ''}
        <p id="videoLoadHint" class="meta">正在加载…</p>
        <div id="sessionMediaHost" class="session-media-host">
          <video id="sessionVideoPlayer" controls autoplay muted playsinline preload="auto" class="session-media-video hidden"></video>
        </div>
        <p class="meta">若无法播放，请确认该开门记录已上传购物录像，或稍后重试。</p>
        <div class="modal-actions"><button type="button" class="btn-ghost" onclick="closeModal()">关闭</button></div>
      </div>
    </div>`;
}

async function showSessionVideo(sessionId, videoUri) {
  const authToken = localStorage.getItem('admin_token') || token;
  if (!authToken) {
    toast('请先登录', 'err');
    return;
  }
  revokeModalBlobUrl();
  const root = document.getElementById('modalRoot');
  const uriHint = videoUri || '';
  const isImage = mediaKindFromUri(uriHint) === 'image';
  const title = isImage ? '购物截图' : '购物视频';
  const subtitle = isImage
    ? '该会话上传的是静态截图（非视频），可用于辅助审核。'
    : '';
  root.innerHTML = renderVideoModalHtml(title, subtitle);
  root.classList.remove('hidden');
  const hint = root.querySelector('#videoLoadHint');
  const host = root.querySelector('#sessionMediaHost');
  const video = root.querySelector('#sessionVideoPlayer');

  try {
    const media = await fetchSessionMedia(sessionId, uriHint);
    const blobUrl = URL.createObjectURL(media.blob);
    trackModalBlobUrl(blobUrl);
    hint.classList.add('hidden');
    if (media.kind === 'image') {
      video.classList.add('hidden');
      host.innerHTML = `<img src="${escAttr(blobUrl)}" alt="购物截图" class="session-media-image">`;
    } else {
      video.classList.remove('hidden');
      video.src = blobUrl;
      video.load();
      video.play().catch(() => {});
      video.addEventListener('error', () => {
        hint.className = 'err video-err';
        hint.textContent = '视频解码失败：文件可能已损坏或格式不受支持。';
        hint.classList.remove('hidden');
      }, { once: true });
    }
  } catch (e) {
    hint.className = 'err video-err';
    hint.textContent = formatApiError(e) || '加载失败：该记录可能没有录像，或录像尚未上传完成。';
  }
}

function showDisputeVideo(sessionId, videoUri) {
  return showSessionVideo(sessionId, videoUri);
}

async function resolveTicket(ticketId, btn, resolutionType = 'CONFIRM') {
  const card = btn.closest('.ticket');
  const type = (resolutionType || 'CONFIRM').toUpperCase();
  if (type === 'WAIVE') {
    if (!await showConfirm('确认免单？将退还该会话已扣款项（如有）。', { title: '免单结案', danger: true })) return;
    await withSaveGuard({ target: btn }, async () => {
      try {
        const result = await api(`/api/v2/ops/disputes/${ticketId}/resolve`, 'POST', { items: [], resolutionType: 'WAIVE' });
        toast(result.message || '已免单', 'ok');
        fetchDisputes();
      } catch (e) {
        if (!handleAuthFailure(e)) toast('失败: ' + e.message, 'err');
        throw e;
      }
    }, '结案中…');
    return;
  }
  const items = [];
  card.querySelectorAll('.dispute-line').forEach(line => {
    const skuId = line.querySelector('.sku-select').value;
    const qty = parseInt(line.querySelector('.qty-input').value, 10) || 0;
    if (skuId && qty > 0) items.push({ skuId, quantity: qty });
  });
  if (!items.length) {
    toast('请至少添加一件商品', 'err');
    return;
  }
  const summary = items.map(i => `${i.skuId} × ${i.quantity}`).join('；');
  if (!await showConfirm(`确认按以下商品结算？\n${summary}`, { title: '确认扣款' })) return;
  await withSaveGuard({ target: btn }, async () => {
  try {
    const result = await api(`/api/v2/ops/disputes/${ticketId}/resolve`, 'POST', { items, resolutionType: type });
    toast(result.message || '已结案', 'ok');
    fetchDisputes();
  } catch (e) {
    if (!handleAuthFailure(e)) toast('失败: ' + e.message, 'err');
    throw e;
  }
  }, '结案中…');
}

function loadRechargesPage() {
  selClear('recharges');
  document.getElementById('pageContent').innerHTML = `
    <div class="card list-page-card">
      ${listFilterBar({
        onSearch: 'searchRecharges()',
        onReset: 'resetRechargeFilters()',
        refreshFn: 'fetchRecharges()',
        extraHtml: selBar('recharges'),
        fieldsHtml: `
          ${filterField('状态', `<select id="rfStatus">
            <option value="">全部</option>
            ${['PENDING', 'PAID', 'REFUNDED', 'CANCELLED'].map(s =>
              `<option value="${s}" ${rechargeFilters.status === s ? 'selected' : ''}>${rechargeStatusLabel(s)}</option>`).join('')}
          </select>`)}
          ${filterField('用户编号', `<input id="rfUserId" value="${escAttr(rechargeFilters.userId)}" placeholder="留空=全部">`)}`
      })}
      <div id="rechargeTable"></div>
    </div>`;
  showTableLoading(document.getElementById('rechargeTable'), 10, 6);
  fetchRecharges();
}

function resetRechargeFilters() {
  rechargeFilters.status = '';
  rechargeFilters.userId = '';
  rechargeFilters.page = 0;
  loadRechargesPage();
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
    table.innerHTML = selWrap('recharges', `
      <table class="data-table">
        <thead><tr>
          ${selHeaderCell('recharges')}
          <th>订单号</th><th>用户</th><th>金额</th><th>渠道</th><th>状态</th>
          <th>微信单号</th><th>创建</th><th>支付</th><th>退款</th><th class="col-actions">操作</th>
        </tr></thead>
        <tbody>${data.items.map(r => `
          ${selRowOpen('recharges', r.orderId)}
          ${selCheckCell('recharges', r.orderId)}
          <td><code>${esc(r.orderId)}</code></td>
          <td>${esc(r.userId)}</td>
          <td>${fmtMoney(r.amountCents)}</td>
          <td>${esc(payChannelLabel(r.channel))}</td>
          <td>${rechargeStatusBadge(r.status)}</td>
          <td class="meta">${esc(r.wxTransactionId || '-')}</td>
          <td>${fmtTime(r.createdAt)}</td>
          <td>${fmtTime(r.paidAt)}</td>
          <td>${fmtTime(r.refundedAt)}</td>
          <td class="col-actions" onclick="event.stopPropagation()"><div class="row-actions">${r.status === 'PAID' && canRefund
            ? `<button class="btn-danger btn-sm" onclick="refundRecharge('${escAttr(r.orderId)}', ${r.amountCents})">退款</button>`
            : '<span class="meta">-</span>'}</div></td>
        </tr>`).join('')}</tbody>
      </table>`)
      + renderPagination(data, 'recharge');
    selSync('recharges');
  } catch (e) {
    pageRenderError(table, e, false);
  }
}

function showRefundRechargeForm(orderId, amountCents) {
  openModalHtml(`
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal" onclick="event.stopPropagation()">
        <h3>确认退款</h3>
        <p>订单 <code>${esc(orderId)}</code>，金额 <strong>${fmtMoney(amountCents)}</strong></p>
        <label>退款原因（可选）</label>
        <textarea id="refundReason" rows="3" placeholder="用户申请、重复支付等"></textarea>
        <div class="filters" style="margin-top:12px">
          <button type="button" class="btn-danger" onclick="confirmRefundRecharge(event, '${escAttr(orderId)}')">确认退款</button>
          <button type="button" class="btn-ghost" data-modal-cancel onclick="closeModal()">取消</button>
        </div>
      </div>
    </div>`);
}

async function confirmRefundRecharge(ev, orderId) {
  await withSaveGuard(ev, async () => {
  const reason = document.getElementById('refundReason')?.value.trim() || '';
  try {
    await api('/api/v2/ops/admin/recharge/' + encodeURIComponent(orderId) + '/refund', 'POST',
      reason ? { reason } : {});
    closeModal();
    toast('退款成功', 'ok');
    fetchRecharges();
  } catch (e) {
    if (!handleAuthFailure(e)) toast('退款失败: ' + e.message, 'err');
  }
  }, '退款中…');
}

function refundRecharge(orderId, amountCents) {
  showRefundRechargeForm(orderId, amountCents);
}

initLoginHints();
initLoginForm();
switchLoginMode(loginMode);

tryRestoreSession();
Object.assign(adminRuntime, {
  api,
  getCurrentPage: () => currentPage,
  fmtTime,
  fmtMoney,
  closeModal
});

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
    else if (currentPage === 'dashboard') refreshDashboardDevicePanel();
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
  switchLoginMode,
  logout,
  navigate,
  navigateBack,
  closeVisitedTab,
  handleRefreshClick,
  loadDashboard,
  refreshDashboardDevicePanel,
  showDeviceForm,
  saveDevice,
  viewDeviceDetail,
  applyPlanogramTemplate,
  loadFinancePage,
  showSlotDiscrepancies,
  showSlotEditor,
  saveSlotConfig,
  deleteSlotConfig,
  promptSlotStocktake,
  promptSlotStocktakeFor,
  searchSessions,
  exportSessionsCsv,
  cancelSession,
  openDisputeForSession,
  searchOrders,
  exportOrdersCsv,
  showOrderDetail,
  changePage,
  changePageSize,
  jumpToPage,
  toggleSidebar,
  toggleNavSection,
  toggleTheme,
  toggleTableSort,
  setTableSort,
  debouncedSearchSessions,
  debouncedSearchOrders,
  debouncedSearchUsers,
  resetUserFilters,
  resetSessionFilters,
  resetOrderFilters,
  resetRechargeFilters,
  resetDisputeFilters,
  searchUsers,
  searchDevices,
  resetDeviceFilters,
  searchSkus,
  resetSkuFilters,
  closeModal,
  loadSkusPage,
  showSkuForm,
  showSkuFormById,
  editSelectedSku,
  previewSkuImage,
  saveSku,
  selToggle,
  selToggleAll,
  selRowClick,
  selClear,
  selSync,
  showBalanceForm,
  saveBalance,
  setUserVerified,
  showVerifyUserForm,
  saveVerifyUser,
  showRbacAssignForUser,
  fetchAuditLogs,
  fetchRecentLogs,
  setRecentScope,
  loadRecentPage,
  resolveTicket,
  addDisputeLine,
  applyDisputeSuggestion,
  removeDisputeLine,
  searchDisputes,
  fetchDisputes,
  showSessionVideo,
  showDisputeVideo,
  searchRecharges,
  refundRecharge,
  showRefundRechargeForm,
  confirmRefundRecharge
});
