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

/** 中国大陆手机号：11 位，1 开头 */
function normalizePhone(v) {
  return (v || '').replace(/\D/g, '').slice(0, 11);
}

function isValidPhone(v) {
  return /^1\d{10}$/.test(normalizePhone(v));
}

function invalidPhoneMessage() {
  return '请输入11位有效手机号';
}

function formatApiError(err) {
  if (!err) return '未知错误';
  const msg = (err.message && String(err.message).trim()) || '';
  if (msg && /[\u4e00-\u9fff]/.test(msg)) return msg;
  const lower = msg.toLowerCase();
  if (lower.includes('missing token') || lower.includes('invalid token')) return '登录已失效，请重新登录';
  if (lower.includes('permission denied')) return '无权限执行此操作';
  if (lower.includes('consumer') || lower.includes('operator')) return '请使用运营账号登录后台';
  if (lower.includes('device not found') || lower.includes('device_not_found')) return '设备不存在';
  if (lower.includes('session_state') || lower.includes('session state')) return '会话状态异常，请刷新页面';
  if (lower.includes('occupied') || lower.includes('busy')) return '设备使用中，请稍后再试';
  if (lower.includes('balance')) return '余额不足';
  if (lower.includes('blacklist')) return '账号受限';
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

const UPLOAD_STATUS_LABEL = {
  NONE: '无需上传',
  LOCAL_QUEUED: '本地排队',
  UPLOADING: '上传中',
  UPLOADED: '已上传',
  FAILED: '上传失败'
};

function uploadStatusLabel(status) {
  return UPLOAD_STATUS_LABEL[status] || status || '-';
}

function onlineStatusBadge(status) {
  const s = String(status || 'UNKNOWN').toUpperCase();
  const online = s === 'ONLINE';
  const label = onlineStatusLabel(status);
  return `<span class="badge ${online ? 'badge-online' : 'badge-offline'}">${esc(label)}</span>`;
}

function onlineStatusLabel(status) {
  const s = String(status || 'UNKNOWN').toUpperCase();
  if (s === 'ONLINE') return '在线';
  if (s === 'OFFLINE') return '离线';
  return '未知';
}

const DISPUTE_STATUS_LABEL = {
  OPEN: '待审核',
  RESOLVED: '已结案',
  CLOSED: '已结案'
};

function disputeStatusLabel(status) {
  const key = String(status || '').toUpperCase();
  return DISPUTE_STATUS_LABEL[key] || '-';
}

function disputeStatusBadge(status) {
  const key = String(status || '').toUpperCase();
  const label = disputeStatusLabel(key);
  const cls = key === 'OPEN' ? 'badge-active' : key === 'RESOLVED' || key === 'CLOSED' ? 'badge-done' : 'badge-offline';
  return `<span class="badge ${cls}">${esc(label)}</span>`;
}

const PAY_CHANNEL_LABEL = { WECHAT: '微信', ALIPAY: '支付宝', MOCK: '模拟支付' };
const OTA_STATUS_LABEL = { PUBLISHED: '已发布', DRAFT: '草稿', REVOKED: '已撤回' };
const OTA_CHANNEL_LABEL = { STABLE: '稳定版', BETA: '测试版', GRAY: '灰度' };
const RECON_STATUS_LABEL = { PENDING: '待执行', RUNNING: '进行中', COMPLETED: '已完成', FAILED: '失败', MATCHED: '已对平', UNMATCHED: '有差异' };
const REPLENISH_STATUS_LABEL = { PENDING: '待处理', IN_PROGRESS: '进行中', COMPLETED: '已完成', CANCELLED: '已取消', OPEN: '待处理' };
const MERCHANT_STATUS_LABEL = { ACTIVE: '正常', INACTIVE: '停用', PENDING: '待审核' };
const RISK_SEVERITY_LABEL = { LOW: '低', MEDIUM: '中', HIGH: '高', CRITICAL: '严重' };
const RISK_EVENT_LABEL = {
  DOOR_OPEN_FAIL: '开门失败',
  DISPUTE_SPIKE: '争议异常',
  LOW_BALANCE: '余额不足',
  BLACKLIST_HIT: '黑名单命中'
};
const DEVICE_TYPE_LABEL = { AI_CABINET_V1: 'AI智能柜 V1' };
const FUSION_MODE_LABEL = { SINGLE: '单摄', MULTI: '多摄融合' };
const AUDIT_ACTION_LABEL = {
  DISPUTE_RESOLVE: '争议结案',
  USER_BALANCE: '调整余额',
  DEVICE_EDIT: '编辑设备',
  SKU_EDIT: '编辑商品',
  RBAC_ASSIGN: '分配角色',
  MERCHANT_EDIT: '编辑商户',
  REPLENISH_EDIT: '补货调整'
};
const AUDIT_TARGET_LABEL = {
  SESSION: '会话',
  ORDER: '订单',
  USER: '用户',
  DEVICE: '设备',
  SKU: '商品',
  DISPUTE: '争议',
  MERCHANT: '商户'
};

function payChannelLabel(channel) {
  return PAY_CHANNEL_LABEL[String(channel || '').toUpperCase()] || channel || '-';
}

function otaStatusLabel(status) {
  return OTA_STATUS_LABEL[String(status || '').toUpperCase()] || status || '-';
}

function otaChannelLabel(channel) {
  return OTA_CHANNEL_LABEL[String(channel || '').toUpperCase()] || channel || '-';
}

function reconStatusLabel(status) {
  return RECON_STATUS_LABEL[String(status || '').toUpperCase()] || status || '-';
}

function replenishStatusLabel(status) {
  return REPLENISH_STATUS_LABEL[String(status || '').toUpperCase()] || status || '-';
}

function merchantStatusLabel(status) {
  return MERCHANT_STATUS_LABEL[String(status || '').toUpperCase()] || status || '-';
}

function riskSeverityLabel(severity) {
  return RISK_SEVERITY_LABEL[String(severity || '').toUpperCase()] || severity || '-';
}

function riskEventLabel(eventType) {
  return RISK_EVENT_LABEL[String(eventType || '').toUpperCase()] || eventType || '-';
}

function deviceTypeLabel(type) {
  return DEVICE_TYPE_LABEL[String(type || '').toUpperCase()] || type || '-';
}

function fusionModeLabel(mode) {
  return FUSION_MODE_LABEL[String(mode || '').toUpperCase()] || mode || '-';
}

function auditActionLabel(action) {
  return AUDIT_ACTION_LABEL[String(action || '').toUpperCase()] || action || '-';
}

function auditTargetLabel(targetType) {
  return AUDIT_TARGET_LABEL[String(targetType || '').toUpperCase()] || targetType || '-';
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
  merchants: 'filters-table',
  rbac: 'table',
  'vision-mappings': 'filters-table',
  'upload-queue': 'filters-table'
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

let modalBlobUrl = null;

function trackModalBlobUrl(url) {
  revokeModalBlobUrl();
  modalBlobUrl = url;
}

function revokeModalBlobUrl() {
  if (modalBlobUrl) {
    URL.revokeObjectURL(modalBlobUrl);
    modalBlobUrl = null;
  }
}

function mediaKindFromUri(uri) {
  if (!uri) return 'unknown';
  const lower = String(uri).toLowerCase();
  if (/\.(jpe?g|png|gif|webp|bmp)(\?|$)/.test(lower)) return 'image';
  if (/\.(mp4|webm|mov|m4v)(\?|$)/.test(lower)) return 'video';
  return 'unknown';
}

function mediaActionLabel(uri) {
  return mediaKindFromUri(uri) === 'image' ? '查看截图' : '播放视频';
}

const SPLIT_STATUS_LABEL = {
  PENDING: '待处理',
  LEDGER_ONLY: '仅记账',
  ACCRUED: '待分账',
  WECHAT_SUBMITTED: '已提交',
  WECHAT_FAILED: '失败',
  SUBMITTED: '已提交',
  SUCCESS: '成功',
  FAILED: '失败'
};

function splitStatusBadge(status) {
  const key = (status || '').toUpperCase();
  const label = SPLIT_STATUS_LABEL[key] || status || '-';
  const cls = key === 'SUCCESS' || key === 'SUBMITTED' || key === 'WECHAT_SUBMITTED' ? 'badge-done'
    : key === 'FAILED' || key === 'WECHAT_FAILED' ? 'badge-fail'
    : key === 'ACCRUED' ? 'badge-active'
    : 'badge-offline';
  return `<span class="badge ${cls}">${esc(label)}</span>`;
}

/** 表格行单选 / 多选（复选框 + 行点击，Ctrl+点击多选） */
const tableSelectionStores = new Map();

function selStore(scope) {
  if (!tableSelectionStores.has(scope)) tableSelectionStores.set(scope, new Set());
  return tableSelectionStores.get(scope);
}

function selClear(scope) {
  selStore(scope).clear();
  selSync(scope);
}

function selSelected(scope) {
  return [...selStore(scope)];
}

function selToggle(scope, id, checked) {
  const sid = String(id);
  const set = selStore(scope);
  if (checked) set.add(sid);
  else set.delete(sid);
  selSync(scope);
}

function selToggleAll(scope, checked) {
  const set = selStore(scope);
  set.clear();
  if (checked) {
    document.querySelectorAll(`[data-sel-scope="${scope}"] .row-select-cb`).forEach((cb) => set.add(cb.value));
  }
  selSync(scope);
}

function selRowClick(scope, id, event) {
  if (event.target.closest('button, input, a, label, select')) return;
  const sid = String(id);
  const set = selStore(scope);
  if (event.ctrlKey || event.metaKey) {
    if (set.has(sid)) set.delete(sid);
    else set.add(sid);
  } else {
    set.clear();
    set.add(sid);
  }
  selSync(scope);
}

function selSync(scope) {
  const set = selStore(scope);
  document.querySelectorAll(`[data-sel-scope="${scope}"] .selectable-row`).forEach((row) => {
    row.classList.toggle('selected', set.has(row.dataset.rowId));
  });
  document.querySelectorAll(`[data-sel-scope="${scope}"] .row-select-cb`).forEach((cb) => {
    cb.checked = set.has(cb.value);
  });
  const headerCb = document.querySelector(`[data-sel-scope="${scope}"] thead .col-check input[type="checkbox"]`);
  if (headerCb) {
    const boxes = [...document.querySelectorAll(`[data-sel-scope="${scope}"] .row-select-cb`)];
    headerCb.checked = boxes.length > 0 && boxes.every((cb) => cb.checked);
    headerCb.indeterminate = !headerCb.checked && set.size > 0;
  }
  document.querySelectorAll(`[data-sel-bar="${scope}"]`).forEach((bar) => {
    bar.textContent = set.size ? `已选 ${set.size} 项（Ctrl+点击可多选）` : '';
  });
  document.querySelectorAll(`[data-sel-actions="${scope}"]`).forEach((el) => {
    el.classList.toggle('hidden', set.size === 0);
  });
  if (typeof document !== 'undefined') {
    document.dispatchEvent(new CustomEvent('selchange', { detail: { scope } }));
  }
}

function selBar(scope, extraActionsHtml = '') {
  const actions = extraActionsHtml
    ? `<span class="selection-actions hidden" data-sel-actions="${escAttr(scope)}">${extraActionsHtml}</span>`
    : '';
  return `<span class="selection-bar meta" data-sel-bar="${escAttr(scope)}"></span>${actions}`;
}

function selHeaderCell(scope) {
  return `<th class="col-check"><input type="checkbox" title="全选" onchange="selToggleAll('${escAttr(scope)}', this.checked)"></th>`;
}

function selCheckBox(scope, id) {
  const selected = selStore(scope).has(String(id));
  return `<input type="checkbox" class="row-select-cb" value="${escAttr(id)}" ${selected ? 'checked' : ''}
    onclick="event.stopPropagation()" onchange="selToggle('${escAttr(scope)}', '${escAttr(id)}', this.checked)">`;
}

function selCheckCell(scope, id) {
  return `<td class="col-check" onclick="event.stopPropagation()">${selCheckBox(scope, id)}</td>`;
}

function selItemOpen(scope, id, tag, extraClass = '', afterClick = '') {
  const selected = selStore(scope).has(String(id));
  const cls = ['selectable-row', selected ? 'selected' : '', extraClass].filter(Boolean).join(' ');
  const extra = afterClick ? `;${afterClick}` : '';
  return `<${tag} class="${cls}" data-row-id="${escAttr(id)}"
    onclick="selRowClick('${escAttr(scope)}', '${escAttr(id)}', event)${extra}">`;
}

function selRowOpen(scope, id, extraClass = '', afterClick = '') {
  return selItemOpen(scope, id, 'tr', extraClass, afterClick);
}

function selCardOpen(scope, id, extraClass = '', afterClick = '') {
  return selItemOpen(scope, id, 'div', extraClass, afterClick);
}

function selWrap(scope, innerHtml) {
  return `<div class="table-wrap" data-sel-scope="${escAttr(scope)}">${innerHtml}</div>`;
}

function debounce(fn, ms = 300) {
  let timer = null;
  return (...args) => {
    clearTimeout(timer);
    timer = setTimeout(() => fn(...args), ms);
  };
}

let modalEscHandler = null;

function teardownModalA11y() {
  if (modalEscHandler) {
    document.removeEventListener('keydown', modalEscHandler);
    modalEscHandler = null;
  }
  document.body.classList.remove('modal-open');
}

function enhanceOpenedModal(onEscape) {
  const root = document.getElementById('modalRoot');
  if (!root) return;
  const dialog = root.querySelector('.modal, .confirm-dialog');
  if (!dialog) return;
  dialog.setAttribute('role', 'dialog');
  dialog.setAttribute('aria-modal', 'true');
  const titleEl = dialog.querySelector('h3, .confirm-title');
  if (titleEl && !titleEl.id) titleEl.id = 'modalTitle_' + Date.now();
  if (titleEl) dialog.setAttribute('aria-labelledby', titleEl.id);
  teardownModalA11y();
  modalEscHandler = (e) => {
    if (e.key === 'Escape') {
      if (typeof onEscape === 'function') onEscape();
      else {
        const cancel = root.querySelector('[data-modal-cancel]');
        if (cancel) cancel.click();
        else if (typeof window.closeModal === 'function') window.closeModal();
      }
    }
  };
  document.addEventListener('keydown', modalEscHandler);
  document.body.classList.add('modal-open');
  const focusEl = dialog.querySelector('button, input, select, textarea');
  focusEl?.focus();
}

function showConfirm(message, options = {}) {
  const title = options.title || '请确认';
  const confirmText = options.confirmText || '确定';
  const cancelText = options.cancelText || '取消';
  const danger = options.danger ? ' btn-danger' : '';
  return new Promise((resolve) => {
    const root = document.getElementById('modalRoot');
    if (!root) {
      resolve(window.confirm(message));
      return;
    }
    const finish = (val) => {
      root.classList.add('hidden');
      root.innerHTML = '';
      teardownModalA11y();
      resolve(val);
    };
    root.innerHTML = `
      <div class="modal-backdrop" data-modal-backdrop>
        <div class="modal confirm-dialog" onclick="event.stopPropagation()">
          <h3 class="confirm-title">${esc(title)}</h3>
          <p class="confirm-msg">${esc(message)}</p>
          <div class="modal-actions">
            <button type="button" class="btn-ghost" data-modal-cancel>${esc(cancelText)}</button>
            <button type="button" class="btn-primary${danger}" data-modal-ok>${esc(confirmText)}</button>
          </div>
        </div>
      </div>`;
    root.classList.remove('hidden');
    enhanceOpenedModal(() => finish(false));
    root.querySelector('[data-modal-cancel]').onclick = () => finish(false);
    root.querySelector('[data-modal-ok]').onclick = () => finish(true);
    root.querySelector('[data-modal-backdrop]').onclick = (e) => {
      if (e.target === e.currentTarget) finish(false);
    };
  });
}

async function withSaveGuard(ev, fn, loadingText = '保存中…') {
  const btn = (ev && ev.target && ev.target.closest('button')) || null;
  if (btn?.disabled) return;
  const prev = btn ? btn.textContent : '';
  if (btn) {
    btn.disabled = true;
    btn.classList.add('btn-loading');
    btn.textContent = loadingText;
  }
  try {
    return await fn();
  } finally {
    if (btn) {
      btn.disabled = false;
      btn.classList.remove('btn-loading');
      btn.textContent = prev;
    }
  }
}

function buildPaginationHtml(data, type) {
  const totalPages = Math.max(1, Math.ceil(data.total / data.size));
  const page = data.page + 1;
  const sizes = [10, 20, 50, 100];
  const sizeOptions = sizes.map((s) =>
    `<option value="${s}" ${data.size === s ? 'selected' : ''}>${s} 条/页</option>`
  ).join('');
  return `<div class="pagination">
    <span class="pagination-meta">共 ${data.total} 条，第 ${page}/${totalPages} 页</span>
    <div class="pagination-controls">
      <button type="button" class="btn-ghost btn-sm" ${data.page <= 0 ? 'disabled' : ''} onclick="changePage('${escAttr(type)}', 0)">首页</button>
      <button type="button" class="btn-ghost btn-sm" ${data.page <= 0 ? 'disabled' : ''} onclick="changePage('${escAttr(type)}', ${data.page - 1})">上一页</button>
      <span class="pagination-jump">第 <input type="number" class="page-jump-input" min="1" max="${totalPages}" value="${page}"
        onkeydown="if(event.key==='Enter')jumpToPage('${escAttr(type)}', this.value)"> 页
        <button type="button" class="btn-ghost btn-sm" onclick="jumpToPage('${escAttr(type)}', this.previousElementSibling.value)">跳转</button></span>
      <button type="button" class="btn-ghost btn-sm" ${page >= totalPages ? 'disabled' : ''} onclick="changePage('${escAttr(type)}', ${data.page + 1})">下一页</button>
      <button type="button" class="btn-ghost btn-sm" ${page >= totalPages ? 'disabled' : ''} onclick="changePage('${escAttr(type)}', ${totalPages - 1})">末页</button>
      <select class="page-size-select" onchange="changePageSize('${escAttr(type)}', this.value)">${sizeOptions}</select>
    </div>
  </div>`;
}

function sortItems(items, field, dir) {
  if (!items || !items.length || !field) return items || [];
  const mult = dir === 'asc' ? 1 : -1;
  return [...items].sort((a, b) => {
    let av = a[field];
    let bv = b[field];
    if (av == null) av = '';
    if (bv == null) bv = '';
    if (typeof av === 'number' && typeof bv === 'number') return (av - bv) * mult;
    return String(av).localeCompare(String(bv), 'zh-CN') * mult;
  });
}

function sortableHeader(scope, field, label) {
  return `<th class="sortable-th" onclick="toggleTableSort('${escAttr(scope)}', '${escAttr(field)}')">${esc(label)}<span class="sort-indicator" data-sort-ind="${escAttr(scope)}-${escAttr(field)}"></span></th>`;
}

function forbiddenPageHtml(pageTitle) {
  return `<div class="forbidden-page card">
    <div class="forbidden-icon" aria-hidden="true">403</div>
    <h3>无权访问</h3>
    <p class="sub">您没有「${esc(pageTitle || '该页面')}」的访问权限，请联系管理员分配角色。</p>
    <button type="button" class="btn-primary" onclick="navigate('dashboard')">返回概览</button>
  </div>`;
}

const RECHARGE_STATUS_LABEL = {
  PAID: '已支付',
  PENDING: '待支付',
  REFUNDED: '已退款',
  CANCELLED: '已取消'
};

function rechargeStatusLabel(status) {
  return RECHARGE_STATUS_LABEL[status] || status || '-';
}

export {
  esc,
  escAttr,
  normalizePhone,
  isValidPhone,
  invalidPhoneMessage,
  formatApiError,
  isAuthError,
  handleAuthFailure,
  rechargeStatusBadge,
  sessionStateLabel,
  sessionStateBadge,
  uploadStatusLabel,
  onlineStatusBadge,
  onlineStatusLabel,
  disputeStatusLabel,
  disputeStatusBadge,
  payChannelLabel,
  otaStatusLabel,
  otaChannelLabel,
  reconStatusLabel,
  replenishStatusLabel,
  merchantStatusLabel,
  riskSeverityLabel,
  riskEventLabel,
  deviceTypeLabel,
  fusionModeLabel,
  auditActionLabel,
  auditTargetLabel,
  toast,
  pageRenderError,
  beginPageLoad,
  showTableLoading,
  refreshButton,
  emptyStateHtml,
  trackModalBlobUrl,
  revokeModalBlobUrl,
  mediaKindFromUri,
  mediaActionLabel,
  splitStatusBadge,
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
  forbiddenPageHtml,
  rechargeStatusLabel
};
