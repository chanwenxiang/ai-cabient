/** 运营后台公共工具：XSS 转义、401 处理、状态徽章 */

function esc(value) {
  if (value == null) return '';
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function escAttr(value) {
  return esc(value).replace(/`/g, '&#96;');
}

function formatApiError(err) {
  if (!err) return '未知错误';
  const msg = (err.message && String(err.message).trim()) || '';
  if (msg && /[\u4e00-\u9fff]/.test(msg)) return msg;
  const lower = msg.toLowerCase();
  if (lower.includes('missing token') || lower.includes('invalid token')) return '登录已失效，请重新登录';
  if (lower.includes('permission denied')) return '无权限执行此操作';
  if (lower.includes('consumer') || lower.includes('operator')) return '请使用运营账号登录后台';
  return msg || '请求失败';
}

function isAuthError(err) {
  const msg = formatApiError(err);
  return err && (err.status === 401 || err.status === 403
    || /401|403|登录已失效|无权限|权限不足/i.test(msg));
}

function handleAuthFailure(err) {
  if (!isAuthError(err)) return false;
  if (typeof logout === 'function') logout();
  if (typeof showErr === 'function') {
    showErr('loginErr', err.status === 403 ? '权限不足或登录已失效，请重新登录' : '登录已失效，请重新登录');
  }
  return true;
}

function rechargeStatusBadge(status) {
  const s = status || '-';
  const labels = { PAID: '已支付', PENDING: '待支付', REFUNDED: '已退款', CANCELLED: '已取消' };
  const label = labels[s] || s;
  const cls = s === 'PAID' ? 'badge-done'
    : s === 'PENDING' ? 'badge-active'
    : s === 'REFUNDED' ? 'badge-fail'
    : s === 'CANCELLED' ? 'badge-offline'
    : 'badge-active';
  return `<span class="badge ${cls}">${esc(label)}</span>`;
}

const SESSION_STATE_LABEL = {
  CREATED: '已创建',
  OPENING: '开门中',
  SHOPPING: '购物中',
  RECOGNIZING: '识别中',
  WAITING_UPLOAD: '等待上传',
  SETTLING: '结算中',
  COMPLETED: '已完成',
  DISPUTED: '待审核',
  FAILED: '失败',
  CANCELLED: '已取消'
};

function sessionStateLabel(state) {
  return SESSION_STATE_LABEL[state] || state || '-';
}

function sessionStateBadge(state) {
  if (!state) return '-';
  const cls = ['COMPLETED', 'CANCELLED'].includes(state) ? 'badge-done'
    : ['FAILED', 'DISPUTED'].includes(state) ? 'badge-fail'
    : 'badge-active';
  return `<span class="badge ${cls}">${esc(sessionStateLabel(state))}</span>`;
}

function onlineStatusBadge(status) {
  const s = String(status || 'UNKNOWN').toUpperCase();
  const online = s === 'ONLINE';
  const label = online ? '在线' : s === 'OFFLINE' ? '离线' : status || '-';
  return `<span class="badge ${online ? 'badge-online' : 'badge-offline'}">${esc(label)}</span>`;
}

function toast(msg, type) {
  const root = document.getElementById('toastRoot');
  if (!root) {
    alert(msg);
    return;
  }
  const el = document.createElement('div');
  el.className = 'toast toast-' + (type || 'info');
  el.textContent = msg;
  root.appendChild(el);
  setTimeout(() => el.classList.add('show'), 10);
  setTimeout(() => {
    el.classList.remove('show');
    setTimeout(() => el.remove(), 300);
  }, 3200);
}

/** 页面/表格加载失败时的统一渲染 */
function pageRenderError(el, err, asCard) {
  if (handleAuthFailure(err)) return;
  if (!el) return;
  const cls = asCard !== false ? 'card err' : 'err';
  el.innerHTML = `<div class="${cls}">${esc(err.message || '加载失败')}</div>`;
}

/** 各页面加载骨架类型 */
const PAGE_LOAD_VARIANT = {
  dashboard: 'stats',
  devices: 'table',
  sessions: 'filters-table',
  orders: 'filters-table',
  recharges: 'filters-table',
  skus: 'filters-table',
  users: 'filters-table',
  reports: 'table',
  audit: 'filters-table',
  recent: 'filters-table',
  disputes: 'table',
  sla: 'stats',
  ota: 'filters-table',
  risk: 'table',
  reconciliation: 'filters-table',
  replenishment: 'table',
  rbac: 'table'
};

function skeletonBar(w) {
  return `<div class="skel-bar" style="width:${w}"></div>`;
}

function skeletonTableRows(cols, rows) {
  let html = '';
  for (let r = 0; r < rows; r++) {
    html += '<tr class="skel-row">';
    for (let c = 0; c < cols; c++) {
      html += `<td><div class="skel-bar skel-cell"></div></td>`;
    }
    html += '</tr>';
  }
  return html;
}

function tableLoadingHtml(cols, rows) {
  cols = cols || 6;
  rows = rows || 5;
  let head = '<tr>';
  for (let c = 0; c < cols; c++) {
    head += '<th><div class="skel-bar skel-th"></div></th>';
  }
  head += '</tr>';
  return `<div class="skeleton-table-wrap card" style="padding:0;overflow:hidden">
    <table class="skeleton-table"><thead>${head}</thead>
    <tbody>${skeletonTableRows(cols, rows)}</tbody></table></div>`;
}

function filtersLoadingHtml() {
  return `<div class="card skeleton-filters">
    <div class="skel-filter-row">
      ${skeletonBar('120px')}${skeletonBar('160px')}${skeletonBar('72px')}
    </div>
  </div>`;
}

function statsLoadingHtml() {
  const cards = Array.from({ length: 8 }, () =>
    `<div class="stat skel-stat"><div class="skel-bar skel-label"></div><div class="skel-bar skel-value"></div></div>`
  ).join('');
  return `<div class="page-loading">
    <div class="stats">${cards}</div>
    <div class="card skel-chart">
      <div class="skel-bar skel-title"></div>
      <div class="skel-bars">${Array.from({ length: 7 }, () => '<div class="skel-chart-bar"></div>').join('')}</div>
    </div>
  </div>`;
}

function pageLoadingHtml(variant) {
  if (variant === 'stats') return statsLoadingHtml();
  if (variant === 'filters-table') {
    return filtersLoadingHtml() + tableLoadingHtml(6, 6);
  }
  return tableLoadingHtml(6, 8);
}

function beginPageLoad(page) {
  const el = document.getElementById('pageContent');
  if (!el) return;
  el.innerHTML = pageLoadingHtml(PAGE_LOAD_VARIANT[page] || 'table');
}

function showTableLoading(el, cols, rows) {
  if (!el) return;
  el.innerHTML = tableLoadingHtml(cols, rows);
}

/** 列表工具栏「刷新」按钮 */
function refreshButton(onclick, label) {
  const action = escAttr(onclick);
  const text = esc(label || '刷新');
  return `<button type="button" class="btn-ghost btn-sm" onclick="${action}">${text}</button>`;
}

/** 表格/页面空状态（可选刷新） */
function emptyStateHtml(title, hint, refreshOnclick) {
  const refresh = refreshOnclick
    ? `<div class="empty-actions">${refreshButton(refreshOnclick)}</div>`
    : '';
  return `<div class="empty-state">
    <div class="empty-icon" aria-hidden="true"></div>
    <div class="empty-title">${esc(title)}</div>
    ${hint ? `<div class="empty-hint">${esc(hint)}</div>` : ''}
    ${refresh}
  </div>`;
}

export {
  esc,
  escAttr,
  formatApiError,
  isAuthError,
  handleAuthFailure,
  rechargeStatusBadge,
  sessionStateLabel,
  sessionStateBadge,
  onlineStatusBadge,
  toast,
  pageRenderError,
  beginPageLoad,
  showTableLoading,
  refreshButton,
  emptyStateHtml
};
