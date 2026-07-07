/** SLA / OTA / 风控 / 对账 / 补货 / RBAC 扩展模块 */
import {
  esc,
  escAttr,
  handleAuthFailure,
  toast,
  pageRenderError,
  refreshButton,
  emptyStateHtml,
  showTableLoading
} from './admin-common.js';
import { permButton, applyNavPermissions, hasPerm } from './permissions.js';
import { adminRuntime } from './admin-runtime.js';

const api = (...args) => adminRuntime.api(...args);
const isCurrentPage = (page) => adminRuntime.getCurrentPage() === page;
const fmtTime = (iso) => adminRuntime.fmtTime(iso);
const fmtMoney = (cents) => adminRuntime.fmtMoney(cents);

function opsRenderError(el, err) {
  pageRenderError(el, err, true);
}

async function loadSlaPage() {
  const el = document.getElementById('pageContent');
  const page = 'sla';
  try {
    const d = await api('/api/v2/ops/admin/sla', 'GET');
    if (!isCurrentPage(page)) return;
    const rt = d.realtime || {};
    el.innerHTML = `
      <div class="card"><div class="filters">${refreshButton('loadSlaPage()')}</div></div>
      <div class="cards">
        <div class="card"><div class="card-label">24h 开门成功率</div><div class="card-value">${pct(rt.doorSuccessRate24h)}</div></div>
        <div class="card"><div class="card-label">24h 平均识别耗时</div><div class="card-value">${esc(rt.avgRecognizeMs24h || 0)} ms</div></div>
        <div class="card"><div class="card-label">当前设备在线率</div><div class="card-value">${pct(rt.deviceOnlineRateNow)}</div></div>
      </div>
      <h3>日快照 ${esc(d.snapshotDate || '-')}</h3>
      <table class="table"><thead><tr>
        <th>开门尝试</th><th>成功</th><th>成功率</th><th>识别均耗</th><th>P95</th><th>设备数</th><th>在线峰值</th>
      </tr></thead><tbody><tr>
        <td>${esc(d.doorOpenAttempts ?? 0)}</td><td>${esc(d.doorOpenSuccess ?? 0)}</td><td>${pct(d.doorSuccessRate)}</td>
        <td>${esc(d.avgRecognizeMs ?? 0)} ms</td><td>${esc(d.p95RecognizeMs ?? 0)} ms</td>
        <td>${esc(d.deviceTotal ?? 0)}</td><td>${esc(d.deviceOnlinePeak ?? 0)}</td>
      </tr></tbody></table>`;
  } catch (e) {
    if (!isCurrentPage(page)) return;
    opsRenderError(el, e);
  }
}

async function loadOtaPage() {
  const el = document.getElementById('pageContent');
  const page = 'ota';
  try {
    const list = await api('/api/v2/ops/admin/ota/releases', 'GET');
    if (!isCurrentPage(page)) return;
    const rows = (list || []).map(r => `<tr>
      <td>${esc(r.appVersion)}</td><td>${esc(r.channel)}</td><td>${r.mandatory ? '是' : '否'}</td>
      <td>${esc(r.grayPercent ?? 100)}%</td><td>${esc(r.status)}</td><td>${fmtTime(r.publishedAt)}</td>
      <td>${r.downloadUrl
        ? `<a href="${escAttr(r.downloadUrl)}" target="_blank" rel="noopener">下载</a>`
        : esc(r.objectStorageUri || '-')}</td>
    </tr>`).join('');
    el.innerHTML = `
      <div class="filters">
        ${permButton('ota.publish', '发布新版本', 'showOtaPublishForm()', 'btn-primary btn-sm')}
        ${refreshButton('loadOtaPage()')}
      </div>
      <div id="otaPublishForm" class="hidden card" style="margin:12px 0;padding:12px">
        <label>版本号</label><input id="otaVersion" placeholder="1.2.0">
        <label>渠道</label><input id="otaChannel" value="STABLE">
        <label>灰度比例 (0-100)</label><input id="otaGray" type="number" value="100" min="0" max="100">
        <label>对象存储 URI (MinIO/OSS)</label><input id="otaUri" placeholder="s3://cabinet-videos/ota/app-1.2.0.apk">
        <label>下载 URL（可选，无 URI 时填写）</label><input id="otaUrl" placeholder="https://...">
        <label><input type="checkbox" id="otaMandatory"> 强制升级</label>
        <button class="btn-primary btn-sm" onclick="publishOta()">提交发布</button>
      </div>
      ${(list && list.length) ? `<table class="table"><thead><tr>
        <th>版本</th><th>渠道</th><th>强制</th><th>灰度</th><th>状态</th><th>发布时间</th><th>包</th>
      </tr></thead><tbody>${rows}</tbody></table>` : emptyStateHtml('暂无 OTA 发布', '发布柜机 APK 后设备可检查更新', 'loadOtaPage()')}
      <p class="sub">柜机检查更新：GET /internal/v1/devices/{id}/ota/check?currentVersion=…</p>`;
    applyNavPermissions();
  } catch (e) {
    if (!isCurrentPage(page)) return;
    opsRenderError(el, e);
  }
}

function showOtaPublishForm() {
  document.getElementById('otaPublishForm').classList.toggle('hidden');
}

async function publishOta() {
  const body = {
    appVersion: document.getElementById('otaVersion').value.trim(),
    channel: document.getElementById('otaChannel').value.trim() || 'STABLE',
    mandatory: document.getElementById('otaMandatory').checked,
    grayPercent: parseInt(document.getElementById('otaGray').value, 10) || 100,
    objectStorageUri: document.getElementById('otaUri').value.trim() || null,
    downloadUrl: document.getElementById('otaUrl').value.trim() || null,
    status: 'PUBLISHED'
  };
  if (!body.appVersion) { toast('请填写版本号', 'err'); return; }
  try {
    await api('/api/v2/ops/admin/ota/releases', 'POST', body);
    toast('已发布', 'ok');
    loadOtaPage();
  } catch (e) {
    if (!handleAuthFailure(e)) toast('发布失败: ' + e.message, 'err');
  }
}

async function loadRiskPage() {
  const el = document.getElementById('pageContent');
  const page = 'risk';
  try {
    const [events, blacklist] = await Promise.all([
      api('/api/v2/ops/admin/risk/events?page=0&size=20', 'GET'),
      api('/api/v2/ops/admin/risk/blacklist', 'GET')
    ]);
    if (!isCurrentPage(page)) return;
    const evRows = (events.items || []).map(e => `<tr>
      <td>${fmtTime(e.createdAt)}</td><td>${esc(e.eventType)}</td><td>${esc(e.severity)}</td>
      <td>${esc(e.userId || '-')}</td><td>${esc(e.deviceId || '-')}</td><td>${esc(e.detail || '')}</td>
    </tr>`).join('');
    const blRows = (blacklist || []).map(b => `<tr>
      <td>${esc(b.userId)}</td><td>${esc(b.reason)}</td><td>${esc(b.source)}</td><td>${fmtTime(b.expiresAt)}</td>
    </tr>`).join('');
    el.innerHTML = `
      <div class="card"><div class="filters">${refreshButton('loadRiskPage()')}</div></div>
      <h3>风控事件</h3>
      ${(events.items || []).length
        ? `<table class="table"><thead><tr><th>时间</th><th>类型</th><th>级别</th><th>用户</th><th>设备</th><th>详情</th></tr></thead>
      <tbody>${evRows}</tbody></table>`
        : emptyStateHtml('暂无风控事件', '触发风控规则后会在此展示', 'loadRiskPage()')}
      <h3>黑名单</h3>
      ${(blacklist || []).length
        ? `<table class="table"><thead><tr><th>用户</th><th>原因</th><th>来源</th><th>过期</th></tr></thead>
      <tbody>${blRows}</tbody></table>`
        : emptyStateHtml('暂无黑名单用户', '手动拉黑或自动风控命中后会出现在此', 'loadRiskPage()')}`;
  } catch (e) {
    if (!isCurrentPage(page)) return;
    opsRenderError(el, e);
  }
}

async function loadReconciliationPage() {
  const el = document.getElementById('pageContent');
  const today = new Date().toISOString().slice(0, 10);
  const monthAgo = new Date(Date.now() - 30 * 86400000).toISOString().slice(0, 10);
  el.innerHTML = `
    <div class="filters">
      <div><label>开始</label><input id="reconFrom" type="date" value="${monthAgo}"></div>
      <div><label>结束</label><input id="reconTo" type="date" value="${today}"></div>
      <div><label>渠道</label>
        <select id="reconChannel"><option value="WECHAT">微信</option><option value="ALIPAY">支付宝</option><option value="MOCK">Mock</option></select>
      </div>
      <div><button class="btn-ghost btn-sm" onclick="fetchReconciliationList()">查询</button></div>
      <div>${refreshButton('fetchReconciliationList()')}</div>
      ${permButton('recon.run', '执行对账', 'runReconToday()', 'btn-primary btn-sm')}
    </div>
    <div id="reconTable"></div>`;
  applyNavPermissions();
  showTableLoading(document.getElementById('reconTable'), 8, 6);
  fetchReconciliationList();
}

async function fetchReconciliationList() {
  const table = document.getElementById('reconTable');
  if (!table) return;
  showTableLoading(table, 8, 6);
  try {
    const from = document.getElementById('reconFrom')?.value;
    const to = document.getElementById('reconTo')?.value;
    const q = new URLSearchParams();
    if (from) q.set('from', from);
    if (to) q.set('to', to);
    const list = await api('/api/v2/ops/admin/reconciliation?' + q, 'GET');
    if (!list || !list.length) {
      table.innerHTML = emptyStateHtml('暂无对账记录', '选择日期范围后查询，或执行对账任务', 'fetchReconciliationList()');
      return;
    }
    const rows = (list || []).map(r => `<tr style="cursor:pointer" onclick="showReconDetail(${esc(r.reconId)})">
      <td>${esc(r.reconDate)}</td><td>${esc(r.channel)}</td>
      <td>${fmtMoney(r.platformTotal)}</td><td>${fmtMoney(r.ledgerTotal)}</td>
      <td>${fmtMoney(r.diffCents)}</td>
      <td>${esc(r.matchedCount ?? 0)}/${esc(r.unmatchedCount ?? 0)}</td>
      <td>${esc(r.status)}</td><td>${fmtTime(r.completedAt)}</td>
    </tr>`).join('');
    table.innerHTML = `
      <table class="table"><thead><tr>
        <th>日期</th><th>渠道</th><th>平台总额</th><th>账本总额</th><th>差额</th>
        <th>匹配/未匹配</th><th>状态</th><th>完成时间</th>
      </tr></thead><tbody>${rows || '<tr><td colspan="8">暂无记录</td></tr>'}</tbody></table>
      <p class="sub">点击行查看明细</p>`;
  } catch (e) {
    opsRenderError(table, e);
  }
}

async function runReconToday() {
  const today = new Date().toISOString().slice(0, 10);
  const channel = document.getElementById('reconChannel')?.value || 'WECHAT';
  try {
    await api(`/api/v2/ops/admin/reconciliation/run?date=${today}&channel=${channel}`, 'POST');
    toast('对账任务已提交', 'ok');
    fetchReconciliationList();
  } catch (e) {
    if (!handleAuthFailure(e)) toast('对账失败: ' + e.message, 'err');
  }
}

async function showReconDetail(reconId) {
  try {
    const d = await api('/api/v2/ops/admin/reconciliation/' + reconId, 'GET');
    const s = d.summary;
    const lines = (d.lines || []);
    const unmatched = lines.filter(l => !l.matched);
    const lineRows = lines.slice(0, 100).map(l => `<tr class="${l.matched ? '' : 'err'}">
      <td>${esc(l.platformTradeNo)}</td><td>${esc(l.merchantOrderNo || '-')}</td>
      <td>${fmtMoney(l.amountCents)}</td><td>${esc(l.tradeType || '-')}</td>
      <td>${l.matched ? '✓' : '✗'}</td><td>${fmtTime(l.tradeTime)}</td>
    </tr>`).join('');
    document.getElementById('modalRoot').innerHTML = `
      <div class="modal-backdrop" onclick="closeModal(event)">
        <div class="modal modal-wide" onclick="event.stopPropagation()">
          <h3>对账明细 #${esc(reconId)} · ${esc(s.reconDate)} · ${esc(s.channel)}</h3>
          <p class="meta">平台 ${fmtMoney(s.platformTotal)} / 账本 ${fmtMoney(s.ledgerTotal)} / 差额 ${fmtMoney(s.diffCents)} · ${esc(s.status)}</p>
          ${unmatched.length ? `<p class="err">未匹配 ${esc(unmatched.length)} 笔</p>` : ''}
          <table class="table"><thead><tr>
            <th>平台流水</th><th>商户单号</th><th>金额</th><th>类型</th><th>匹配</th><th>时间</th>
          </tr></thead><tbody>${lineRows || '<tr><td colspan="6">无明细</td></tr>'}</tbody></table>
          ${lines.length > 100 ? '<p class="sub">仅显示前 100 条</p>' : ''}
          <button class="btn-ghost" onclick="closeModal()">关闭</button>
        </div>
      </div>`;
    document.getElementById('modalRoot').classList.remove('hidden');
  } catch (e) {
    if (!handleAuthFailure(e)) toast('加载失败: ' + e.message, 'err');
  }
}

async function loadReplenishmentPage() {
  const el = document.getElementById('pageContent');
  const page = 'replenishment';
  try {
    const [routes, inventory] = await Promise.all([
      api('/api/v2/ops/admin/replenishment/routes', 'GET'),
      api('/api/v2/ops/admin/inventory', 'GET')
    ]);
    if (!isCurrentPage(page)) return;
    const routeRows = (routes || []).map(r => `<tr>
      <td>${esc(r.routeName)}</td><td>${esc(r.plannedDate)}</td><td>${esc(r.status)}</td>
      <td>${esc((r.tasks || []).length)}</td><td>${esc(r.assigneeUserId || '-')}</td>
    </tr>`).join('');
    const invRows = (inventory || []).map(i => `<tr>
      <td>${esc(i.deviceId)}</td><td>${esc(i.skuId)}</td><td>${esc(i.quantity)}/${esc(i.capacity)}</td>
      <td>${esc(i.lowThreshold)}</td>
    </tr>`).join('');
    el.innerHTML = `
      <div class="filters">
        ${permButton('replenish.plan', '规划路线', 'planReplenishmentRoute()', 'btn-primary btn-sm')}
        ${refreshButton('loadReplenishmentPage()')}
      </div>
      <h3>补货路线</h3>
      ${(routes || []).length
        ? `<table class="table"><thead><tr><th>名称</th><th>计划日</th><th>状态</th><th>任务数</th><th>负责人</th></tr></thead>
      <tbody>${routeRows}</tbody></table>`
        : emptyStateHtml('暂无补货路线', '点击「规划路线」创建补货任务', 'loadReplenishmentPage()')}
      <h3>柜内库存</h3>
      ${(inventory || []).length
        ? `<table class="table"><thead><tr><th>设备</th><th>SKU</th><th>库存/容量</th><th>低库存阈值</th></tr></thead>
      <tbody>${invRows}</tbody></table>`
        : emptyStateHtml('暂无库存数据', '设备上报或运营录入库存后会显示', 'loadReplenishmentPage()')}
      <p class="sub">补货员 App：GET /api/v2/ops/admin/replenishment/my-tasks</p>`;
    applyNavPermissions();
  } catch (e) {
    if (!isCurrentPage(page)) return;
    opsRenderError(el, e);
  }
}

async function planReplenishmentRoute() {
  const name = prompt('路线名称', '补货路线-' + new Date().toISOString().slice(0, 10));
  if (!name) return;
  const deviceIdsRaw = prompt('设备 ID 列表（逗号分隔）', 'CAB-001,CAB-002');
  if (!deviceIdsRaw) return;
  const deviceIds = deviceIdsRaw.split(',').map(s => s.trim()).filter(Boolean);
  const assignee = parseInt(prompt('负责人 userId', localStorage.getItem('admin_userId') || '100000001'), 10);
  const today = new Date().toISOString().slice(0, 10);
  const startLat = parseFloat(prompt('起点纬度（可选）', '31.23') || '31.23');
  const startLng = parseFloat(prompt('起点经度（可选）', '121.47') || '121.47');
  try {
    await api('/api/v2/ops/admin/replenishment/plan', 'POST', {
      routeName: name,
      assigneeUserId: assignee,
      plannedDate: today,
      deviceIds,
      startLatitude: startLat,
      startLongitude: startLng
    });
    toast('路线已规划', 'ok');
    loadReplenishmentPage();
  } catch (e) {
    if (!handleAuthFailure(e)) toast('规划失败: ' + e.message, 'err');
  }
}

async function loadRbacPage() {
  const el = document.getElementById('pageContent');
  const page = 'rbac';
  try {
    const [roles, perms, me] = await Promise.all([
      api('/api/v2/ops/admin/rbac/roles', 'GET'),
      api('/api/v2/ops/admin/rbac/permissions', 'GET'),
      api('/api/v2/ops/admin/rbac/me', 'GET')
    ]);
    if (!isCurrentPage(page)) return;
    window._rbacState = {
      tab: window._rbacState?.tab || 'roles',
      selectedRoleId: window._rbacState?.selectedRoleId || (roles[0]?.roleId ?? null),
      selectedUserId: window._rbacState?.selectedUserId || null,
      roles: roles || [],
      perms: perms || [],
      rolePermIds: new Set(),
      operatorFilters: window._rbacState?.operatorFilters || { page: 0, size: 20, phone: '' },
      recentScope: window._rbacState?.recentScope || 'all'
    };
    window._rbacRoles = roles || [];
    const roleNames = (me?.roleNames || []).join('、') || '未分配';
    el.innerHTML = `
      <div class="card rbac-profile">
        <div class="rbac-profile-main">
          <strong>${esc(me?.name || me?.phoneNumber || '运营账号')}</strong>
          <span class="sub">${esc(me?.phoneNumber || '')}</span>
        </div>
        <div class="rbac-profile-meta">
          <span>角色：${esc(roleNames)}</span>
          <span>权限项：${esc(me?.permissionCount ?? 0)}</span>
        </div>
        <div class="filters">${refreshButton('loadRbacPage()')}</div>
      </div>
      <div class="tabs rbac-tabs">
        <button type="button" class="tab ${window._rbacState.tab === 'roles' ? 'active' : ''}" onclick="switchRbacTab('roles')">角色权限</button>
        ${hasPerm('ops:rbac:assign') ? `<button type="button" class="tab ${window._rbacState.tab === 'users' ? 'active' : ''}" onclick="switchRbacTab('users')">用户授权</button>` : ''}
      </div>
      <div id="rbacPanel"></div>`;
    applyNavPermissions();
    await renderRbacPanel();
  } catch (e) {
    if (!isCurrentPage(page)) return;
    opsRenderError(el, e);
  }
}

function switchRbacTab(tab) {
  if (!window._rbacState) return;
  window._rbacState.tab = tab;
  document.querySelectorAll('.rbac-tabs .tab').forEach(btn => {
    btn.classList.toggle('active', btn.textContent.includes(
      tab === 'roles' ? '角色' : tab === 'users' ? '用户' : '最近'
    ));
  });
  renderRbacPanel();
}

async function renderRbacPanel() {
  const panel = document.getElementById('rbacPanel');
  if (!panel || !window._rbacState) return;
  const { tab } = window._rbacState;
  if (tab === 'roles') {
    panel.innerHTML = `
      <div class="rbac-split">
        <div class="card rbac-pane">
          <h3 class="pane-title">角色列表</h3>
          <table class="table rbac-role-table">
            <thead><tr><th>角色</th><th>标识</th><th>权限</th></tr></thead>
            <tbody>${(window._rbacState.roles || []).map(r => `
              <tr class="rbac-role-row ${window._rbacState.selectedRoleId === r.roleId ? 'selected' : ''}"
                  onclick="selectRbacRole(${r.roleId})">
                <td>${esc(r.roleName)}</td>
                <td><code>${esc(r.roleKey)}</code></td>
                <td class="meta">${esc((r.permissions || [])[0] || '-')}</td>
              </tr>`).join('')}</tbody>
          </table>
        </div>
        <div class="card rbac-pane" id="rbacPermPane">
          <p class="sub">选择左侧角色以配置菜单权限</p>
        </div>
      </div>`;
    if (window._rbacState.selectedRoleId) {
      await loadRolePermissionTree(window._rbacState.selectedRoleId);
    }
  } else if (tab === 'users') {
    panel.innerHTML = `
      <div class="rbac-split">
        <div class="card rbac-pane">
          <h3 class="pane-title">运营账号</h3>
          <div class="filters">
            <div><label>手机号</label>
              <input id="rbacOpPhone" placeholder="搜索手机号" value="${escAttr(window._rbacState.operatorFilters.phone)}"></div>
            <button class="btn-primary btn-sm" onclick="searchRbacOperators()">搜索</button>
          </div>
          <div id="rbacOperatorList"></div>
        </div>
        <div class="card rbac-pane" id="rbacUserRolePane">
          <p class="sub">选择左侧运营账号分配角色</p>
        </div>
      </div>`;
    await fetchRbacOperators();
  }
}

function buildPermTree(perms) {
  const map = new Map();
  const roots = [];
  (perms || []).forEach(p => map.set(p.permissionId, { ...p, children: [] }));
  (perms || []).forEach(p => {
    const node = map.get(p.permissionId);
    if (p.parentId && p.parentId !== 0 && map.has(p.parentId)) {
      map.get(p.parentId).children.push(node);
    } else {
      roots.push(node);
    }
  });
  const sortNodes = nodes => {
    nodes.sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0));
    nodes.forEach(n => sortNodes(n.children));
  };
  sortNodes(roots);
  return roots;
}

function permTypeLabel(type) {
  return { M: '目录', C: '菜单', F: '按钮' }[type] || type;
}

function renderPermTreeNodes(nodes, checkedIds, depth = 0) {
  return (nodes || []).map(n => {
    const checked = checkedIds.has(n.permissionId);
    const childHtml = n.children?.length
      ? `<div class="perm-children">${renderPermTreeNodes(n.children, checkedIds, depth + 1)}</div>`
      : '';
    return `
      <div class="perm-tree-node" style="padding-left:${depth * 18}px">
        <label class="perm-tree-label">
          <input type="checkbox" class="perm-cb" data-id="${n.permissionId}" ${checked ? 'checked' : ''}
            onchange="onPermCheckChange(this, ${n.permissionId})">
          <span class="perm-type perm-type-${escAttr(n.permType)}">${esc(permTypeLabel(n.permType))}</span>
          <span class="perm-name">${esc(n.permName)}</span>
          <code class="perm-code">${esc(n.permCode)}</code>
        </label>
      </div>${childHtml}`;
  }).join('');
}

function collectPermDescendants(permId, perms) {
  const ids = [permId];
  (perms || []).filter(p => p.parentId === permId).forEach(p => {
    ids.push(...collectPermDescendants(p.permissionId, perms));
  });
  return ids;
}

function onPermCheckChange(el, permId) {
  const pane = document.getElementById('rbacPermPane');
  if (!pane) return;
  const checked = el.checked;
  const ids = collectPermDescendants(permId, window._rbacState.perms);
  ids.forEach(id => {
    const cb = pane.querySelector('.perm-cb[data-id="' + id + '"]');
    if (cb) cb.checked = checked;
  });
  if (checked) {
    let pid = (window._rbacState.perms.find(p => p.permissionId === permId) || {}).parentId;
    while (pid && pid !== 0) {
      const parentCb = pane.querySelector('.perm-cb[data-id="' + pid + '"]');
      if (parentCb) parentCb.checked = true;
      pid = (window._rbacState.perms.find(p => p.permissionId === pid) || {}).parentId;
    }
  }
}

async function selectRbacRole(roleId) {
  window._rbacState.selectedRoleId = roleId;
  document.querySelectorAll('.rbac-role-row').forEach(row => {
    row.classList.toggle('selected', row.getAttribute('onclick')?.includes('(' + roleId + ')'));
  });
  await loadRolePermissionTree(roleId);
}

async function loadRolePermissionTree(roleId) {
  const pane = document.getElementById('rbacPermPane');
  if (!pane) return;
  pane.innerHTML = '<p class="sub">加载权限树…</p>';
  try {
    const data = await api('/api/v2/ops/admin/rbac/roles/' + roleId + '/permissions', 'GET');
    const role = (window._rbacState.roles || []).find(r => r.roleId === roleId);
    const checkedIds = new Set(data.permissionIds || []);
    window._rbacState.rolePermIds = checkedIds;
    const isAdmin = role?.roleKey === 'admin';
    const tree = buildPermTree(window._rbacState.perms);
    pane.innerHTML = `
      <div class="pane-head">
        <h3 class="pane-title">${esc(role?.roleName || data.roleName)} · 菜单权限</h3>
        ${isAdmin ? '<span class="badge badge-done">超级管理员不可编辑</span>' :
          permButton('rbac.role.save', '保存权限', 'saveRolePermissions()', 'btn-primary btn-sm')}
      </div>
      <div class="perm-tree">${renderPermTreeNodes(tree, checkedIds)}</div>`;
    applyNavPermissions();
  } catch (e) {
    if (!handleAuthFailure(e)) pane.innerHTML = '<p class="err">' + esc(e.message) + '</p>';
  }
}

async function saveRolePermissions() {
  const roleId = window._rbacState?.selectedRoleId;
  if (!roleId) return;
  const ids = [...document.querySelectorAll('#rbacPermPane .perm-cb:checked')]
    .map(el => parseInt(el.dataset.id, 10));
  try {
    await api('/api/v2/ops/admin/rbac/roles/' + roleId + '/permissions', 'PUT', ids);
    toast('角色权限已保存', 'ok');
    loadRolePermissionTree(roleId);
  } catch (e) {
    if (!handleAuthFailure(e)) toast('保存失败: ' + e.message, 'err');
  }
}

function searchRbacOperators() {
  window._rbacState.operatorFilters.phone = (document.getElementById('rbacOpPhone')?.value || '').trim();
  window._rbacState.operatorFilters.page = 0;
  fetchRbacOperators();
}

async function fetchRbacOperators() {
  const list = document.getElementById('rbacOperatorList');
  if (!list) return;
  list.innerHTML = '<p class="sub">加载中…</p>';
  try {
    const f = window._rbacState.operatorFilters;
    const q = new URLSearchParams({ page: f.page, size: f.size });
    if (f.phone) q.set('phone', f.phone);
    const data = await api('/api/v2/ops/admin/rbac/operators?' + q, 'GET');
    if (!data.items.length) {
      list.innerHTML = emptyStateHtml('暂无运营账号', '运营账号 userId ≥ 100000000', 'searchRbacOperators()');
      return;
    }
    list.innerHTML = `
      <table class="table">
        <thead><tr><th>手机号</th><th>姓名</th><th>当前角色</th></tr></thead>
        <tbody>${data.items.map(u => `
          <tr class="rbac-user-row ${window._rbacState.selectedUserId === u.userId ? 'selected' : ''}"
              onclick="selectRbacUser(${u.userId})">
            <td>${esc(u.phoneNumber)}</td>
            <td>${esc(u.name || '-')}</td>
            <td class="meta">${esc((u.roleNames || []).join('、') || '未分配')}</td>
          </tr>`).join('')}</tbody>
      </table>
      ${renderRbacOperatorPagination(data)}`;
  } catch (e) {
    if (!handleAuthFailure(e)) list.innerHTML = '<p class="err">' + esc(e.message) + '</p>';
  }
}

function renderRbacOperatorPagination(data) {
  const f = window._rbacState.operatorFilters;
  const totalPages = Math.max(1, Math.ceil((data.total || 0) / f.size));
  const page = f.page + 1;
  return `<div class="pagination">
    共 ${data.total || 0} 条 · 第 ${page}/${totalPages} 页
    <button class="btn-ghost btn-sm" ${f.page <= 0 ? 'disabled' : ''} onclick="changeRbacOperatorPage(${f.page - 1})">上一页</button>
    <button class="btn-ghost btn-sm" ${page >= totalPages ? 'disabled' : ''} onclick="changeRbacOperatorPage(${f.page + 1})">下一页</button>
  </div>`;
}

function changeRbacOperatorPage(page) {
  window._rbacState.operatorFilters.page = Math.max(0, page);
  fetchRbacOperators();
}

async function selectRbacUser(userId) {
  window._rbacState.selectedUserId = userId;
  document.querySelectorAll('.rbac-user-row').forEach(row => {
    row.classList.toggle('selected', row.getAttribute('onclick')?.includes('(' + userId + ')'));
  });
  const pane = document.getElementById('rbacUserRolePane');
  if (!pane) return;
  pane.innerHTML = '<p class="sub">加载角色…</p>';
  try {
    const data = await api('/api/v2/ops/admin/rbac/users/' + userId + '/roles', 'GET');
    const assigned = new Set(data.roleIds || []);
    const checks = (window._rbacRoles || []).map(r =>
      `<label class="role-check-item">
        <input type="checkbox" class="rbac-role-cb" value="${escAttr(r.roleId)}" ${assigned.has(r.roleId) ? 'checked' : ''}>
        <span>${esc(r.roleName)}</span>
        <code>${esc(r.roleKey)}</code>
      </label>`
    ).join('');
    pane.innerHTML = `
      <h3 class="pane-title">分配角色 · 用户 ${esc(userId)}</h3>
      <div class="role-check-list">${checks || '<p class="sub">无可用角色</p>'}</div>
      ${permButton('rbac.assign', '保存授权', 'saveUserRoles()', 'btn-primary btn-sm')}`;
    applyNavPermissions();
  } catch (e) {
    if (!handleAuthFailure(e)) pane.innerHTML = '<p class="err">' + esc(e.message) + '</p>';
  }
}

async function saveUserRoles() {
  const userId = window._rbacState?.selectedUserId;
  if (!userId) { toast('请先选择运营账号', 'err'); return; }
  const roleIds = [...document.querySelectorAll('.rbac-role-cb:checked')].map(el => parseInt(el.value, 10));
  try {
    await api('/api/v2/ops/admin/rbac/users/' + userId + '/roles', 'PUT', roleIds);
    toast('用户授权已保存', 'ok');
    fetchRbacOperators();
    selectRbacUser(userId);
  } catch (e) {
    if (!handleAuthFailure(e)) toast('保存失败: ' + e.message, 'err');
  }
}

function setRbacRecentScope(scope) {
  window._rbacState.recentScope = scope;
  renderRbacPanel();
}

async function fetchRbacRecent() {
  const table = document.getElementById('rbacRecentTable');
  if (!table) return;
  table.innerHTML = '<p class="sub">加载中…</p>';
  try {
    const mine = window._rbacState.recentScope === 'mine';
    const q = new URLSearchParams({ size: 15, mine: mine ? 'true' : 'false' });
    const items = await api('/api/v2/ops/admin/audit-logs/recent?' + q, 'GET');
    table.innerHTML = renderAuditTableHtml(items);
  } catch (e) {
    if (!handleAuthFailure(e)) table.innerHTML = '<p class="err">' + esc(e.message) + '</p>';
  }
}

function formatOperatorCell(log) {
  if (log.operatorPhone || log.operatorName) {
    return `${esc(log.operatorName || '-')}<br><span class="meta">${esc(log.operatorPhone || log.operatorId)}</span>`;
  }
  return esc(log.operatorId);
}

function renderAuditTableHtml(items) {
  if (!items || !items.length) {
    return emptyStateHtml('暂无操作记录', '运营后台的敏感操作会记录在此');
  }
  return `
    <table class="table">
      <thead><tr>
        <th>时间</th><th>操作人</th><th>动作</th><th>对象</th><th>详情</th>
      </tr></thead>
      <tbody>${items.map(l => `<tr>
        <td>${fmtTime(l.createdAt)}</td>
        <td>${formatOperatorCell(l)}</td>
        <td><code>${esc(l.action)}</code></td>
        <td>${esc(l.targetType || '-')} ${esc(l.targetId || '')}</td>
        <td class="meta">${esc(l.detail || '-')}</td>
      </tr>`).join('')}</tbody>
    </table>`;
}

function openRbacUserAssign(userId) {
  window._rbacState = window._rbacState || {};
  window._rbacState.tab = 'users';
  window._rbacState.selectedUserId = userId;
  navigate('rbac');
}

function pct(v) {
  if (v == null) return '-';
  return (v * 100).toFixed(1) + '%';
}

adminRuntime.opsLoaders = {
  sla: loadSlaPage,
  ota: loadOtaPage,
  risk: loadRiskPage,
  reconciliation: loadReconciliationPage,
  replenishment: loadReplenishmentPage,
  rbac: loadRbacPage
};

Object.assign(window, {
  loadSlaPage,
  loadOtaPage,
  loadRiskPage,
  loadReconciliationPage,
  loadReplenishmentPage,
  loadRbacPage,
  showOtaPublishForm,
  publishOta,
  fetchReconciliationList,
  runReconToday,
  showReconDetail,
  planReplenishmentRoute,
  switchRbacTab,
  selectRbacRole,
  saveRolePermissions,
  searchRbacOperators,
  changeRbacOperatorPage,
  selectRbacUser,
  saveUserRoles,
  setRbacRecentScope,
  fetchRbacRecent,
  onPermCheckChange,
  openRbacUserAssign,
  renderAuditTableHtml,
  formatOperatorCell
});
