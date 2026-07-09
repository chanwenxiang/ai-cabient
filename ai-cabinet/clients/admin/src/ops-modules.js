/** SLA / OTA / 风控 / 对账 / 补货 / RBAC 扩展模块 */
import {
  esc,
  escAttr,
  handleAuthFailure,
  toast,
  pageRenderError,
  refreshButton,
  emptyStateHtml,
  showTableLoading,
  sessionStateBadge,
  uploadStatusLabel,
  splitStatusBadge,
  mediaActionLabel,
  otaStatusLabel,
  otaChannelLabel,
  payChannelLabel,
  reconStatusLabel,
  replenishStatusLabel,
  merchantStatusLabel,
  riskSeverityLabel,
  riskEventLabel,
  fusionModeLabel,
  auditActionLabel,
  auditTargetLabel,
  onlineStatusBadge,
  formatApiError,
  onlineStatusLabel,
  selClear,
  selSelected,
  selToggle,
  selSync,
  selBar,
  selHeaderCell,
  selCheckBox,
  selCheckCell,
  selRowOpen,
  selWrap,
  showConfirm,
  withSaveGuard,
  buildPaginationHtml,
  debounce,
  enhanceOpenedModal
} from './admin-common.js';
import { permButton, applyNavPermissions, hasPerm } from './permissions.js';
import { adminRuntime } from './admin-runtime.js';

const api = (...args) => adminRuntime.api(...args);
const isCurrentPage = (page) => adminRuntime.getCurrentPage() === page;
const fmtTime = (iso) => adminRuntime.fmtTime(iso);
const fmtMoney = (cents) => adminRuntime.fmtMoney(cents);
const closeModal = (...args) => adminRuntime.closeModal(...args);

function openOpsModal(html, onEscape) {
  const root = document.getElementById('modalRoot');
  root.innerHTML = html;
  root.classList.remove('hidden');
  enhanceOpenedModal(onEscape || (() => closeModal()));
}

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
        <div class="card"><div class="card-label">待审争议</div><div class="card-value">${esc(rt.disputeOpen ?? 0)}</div></div>
        <div class="card"><div class="card-label">SLA 超时争议</div><div class="card-value ${rt.disputeOverdue > 0 ? 'warn' : ''}">${esc(rt.disputeOverdue ?? 0)}</div></div>
        <div class="card"><div class="card-label">24h 争议结案</div><div class="card-value">${esc(rt.disputeResolved24h ?? 0)}</div></div>
        <div class="card"><div class="card-label">24h SLA 达标率</div><div class="card-value">${pct(rt.disputeSlaCompliance24h ?? 1)}</div></div>
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
  selClear('ota');
  try {
    const list = await api('/api/v2/ops/admin/ota/releases', 'GET');
    if (!isCurrentPage(page)) return;
    const rows = (list || []).map(r => `
      ${selRowOpen('ota', r.releaseId)}
      ${selCheckCell('ota', r.releaseId)}
      <td>${esc(r.appVersion)}</td><td>${esc(otaChannelLabel(r.channel))}</td><td>${r.mandatory ? '是' : '否'}</td>
      <td>${esc(r.grayPercent ?? 100)}%</td><td>${esc(otaStatusLabel(r.status))}</td><td>${fmtTime(r.publishedAt)}</td>
      <td onclick="event.stopPropagation()">${r.downloadUrl
        ? `<a href="${escAttr(r.downloadUrl)}" target="_blank" rel="noopener">下载</a>`
        : esc(r.objectStorageUri || '-')}</td>
    </tr>`).join('');
    el.innerHTML = `
      <div class="filters">
        ${permButton('ota.publish', '发布新版本', 'showOtaPublishForm()', 'btn-primary btn-sm')}
        ${selBar('ota')}
        ${refreshButton('loadOtaPage()')}
      </div>
      <div id="otaPublishForm" class="hidden card" style="margin:12px 0;padding:12px">
        <label>版本号</label><input id="otaVersion" placeholder="1.2.0">
        <label>渠道</label><input id="otaChannel" value="STABLE">
        <label>灰度比例 (0-100)</label><input id="otaGray" type="number" value="100" min="0" max="100">
        <label>对象存储 URI (MinIO/OSS)</label><input id="otaUri" placeholder="s3://cabinet-videos/ota/app-1.2.0.apk">
        <label>下载 URL（可选，无 URI 时填写）</label><input id="otaUrl" placeholder="https://...">
        <label><input type="checkbox" id="otaMandatory"> 强制升级</label>
        <button type="button" class="btn-primary btn-sm" onclick="publishOta(event)">提交发布</button>
      </div>
      ${(list && list.length) ? selWrap('ota', `<table class="table"><thead><tr>
        ${selHeaderCell('ota')}
        <th>版本</th><th>渠道</th><th>强制</th><th>灰度</th><th>状态</th><th>发布时间</th><th>包</th>
      </tr></thead><tbody>${rows}</tbody></table>`) : emptyStateHtml('暂无 OTA 发布', '发布柜机 APK 后设备可检查更新', 'loadOtaPage()')}
      <p class="sub">柜机检查更新：GET /internal/v1/devices/{id}/ota/check?currentVersion=…</p>`;
    selSync('ota');
    applyNavPermissions();
  } catch (e) {
    if (!isCurrentPage(page)) return;
    opsRenderError(el, e);
  }
}

function showOtaPublishForm() {
  document.getElementById('otaPublishForm').classList.toggle('hidden');
}

async function publishOta(ev) {
  await withSaveGuard(ev, async () => {
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
  }, '发布中…');
}

async function loadRiskPage() {
  const el = document.getElementById('pageContent');
  const page = 'risk';
  selClear('riskEvents');
  selClear('blacklist');
  try {
    const [events, blacklist] = await Promise.all([
      api('/api/v2/ops/admin/risk/events?page=0&size=20', 'GET'),
      api('/api/v2/ops/admin/risk/blacklist', 'GET')
    ]);
    if (!isCurrentPage(page)) return;
    const evRows = (events.items || []).map(e => `
      ${selRowOpen('riskEvents', e.eventId)}
      ${selCheckCell('riskEvents', e.eventId)}
      <td>${fmtTime(e.createdAt)}</td><td>${esc(riskEventLabel(e.eventType))}</td><td>${esc(riskSeverityLabel(e.severity))}</td>
      <td>${esc(e.userId || '-')}</td><td>${esc(e.deviceId || '-')}</td><td>${esc(e.detail || '')}</td>
    </tr>`).join('');
    const blRows = (blacklist || []).map(b => `
      ${selRowOpen('blacklist', b.userId)}
      ${selCheckCell('blacklist', b.userId)}
      <td>${esc(b.userId)}</td><td>${esc(b.reason)}</td><td>${esc(b.source)}</td><td>${fmtTime(b.expiresAt)}</td>
      <td onclick="event.stopPropagation()">${hasPerm('ops:risk:blacklist') ? `<button class="btn-ghost btn-sm btn-danger" onclick="removeBlacklist(${b.userId})">解除</button>` : '-'}</td>
    </tr>`).join('');
    el.innerHTML = `
      <div class="card">
        <div class="filters">
          ${refreshButton('loadRiskPage()')}
          ${permButton('risk.blacklist', '添加黑名单', 'showBlacklistForm()', 'btn-primary btn-sm')}
        </div>
      </div>
      <h3>风控事件 ${selBar('riskEvents')}</h3>
      ${(events.items || []).length
        ? selWrap('riskEvents', `<table class="table"><thead><tr>
          ${selHeaderCell('riskEvents')}
          <th>时间</th><th>类型</th><th>级别</th><th>用户</th><th>设备</th><th>详情</th>
        </tr></thead><tbody>${evRows}</tbody></table>`)
        : emptyStateHtml('暂无风控事件', '触发风控规则后会在此展示', 'loadRiskPage()')}
      <h3>黑名单 ${selBar('blacklist')}</h3>
      ${(blacklist || []).length
        ? selWrap('blacklist', `<table class="table"><thead><tr>
          ${selHeaderCell('blacklist')}
          <th>用户</th><th>原因</th><th>来源</th><th>过期</th><th>操作</th>
        </tr></thead><tbody>${blRows}</tbody></table>`)
        : emptyStateHtml('暂无黑名单用户', '手动拉黑或自动风控命中后会出现在此', 'loadRiskPage()')}`;
    selSync('riskEvents');
    selSync('blacklist');
    applyNavPermissions();
  } catch (e) {
    if (!isCurrentPage(page)) return;
    opsRenderError(el, e);
  }
}

function showBlacklistForm() {
  openOpsModal(`
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal" onclick="event.stopPropagation()">
        <h3>添加黑名单</h3>
        <label>用户 ID</label>
        <input id="blUserId" type="number" min="1" placeholder="10001">
        <label>原因</label>
        <input id="blReason" placeholder="恶意申诉 / 频繁异常">
        <label>过期时间（可选，ISO 格式留空=永久）</label>
        <input id="blExpires" placeholder="2026-12-31T23:59:59Z">
        <div class="filters" style="margin-top:12px">
          <button type="button" class="btn-primary" onclick="saveBlacklist(event)">确认拉黑</button>
          <button type="button" class="btn-ghost" data-modal-cancel onclick="closeModal()">取消</button>
        </div>
      </div>
    </div>`);
}

async function saveBlacklist(ev) {
  await withSaveGuard(ev, async () => {
  const userId = parseInt(document.getElementById('blUserId').value, 10);
  const reason = document.getElementById('blReason').value.trim();
  const expiresRaw = document.getElementById('blExpires').value.trim();
  if (!userId || !reason) { toast('请填写用户 ID 和原因', 'err'); return; }
  try {
    const body = { userId, reason };
    if (expiresRaw) body.expiresAt = expiresRaw;
    await api('/api/v2/ops/admin/risk/blacklist', 'POST', body);
    closeModal();
    toast('已加入黑名单', 'ok');
    loadRiskPage();
  } catch (e) {
    if (!handleAuthFailure(e)) toast('操作失败: ' + e.message, 'err');
  }
  }, '提交中…');
}

const debouncedSearchRbacOperators = debounce(() => searchRbacOperators(), 350);

async function removeBlacklist(userId) {
  if (!await showConfirm(`确认解除用户 ${userId} 的黑名单？`, { title: '解除黑名单', danger: true })) return;
  try {
    await api('/api/v2/ops/admin/risk/blacklist/' + userId, 'DELETE');
    toast('已解除', 'ok');
    loadRiskPage();
  } catch (e) {
    if (!handleAuthFailure(e)) toast('操作失败: ' + e.message, 'err');
  }
}

async function loadReconciliationPage() {
  const el = document.getElementById('pageContent');
  const today = new Date().toISOString().slice(0, 10);
  const monthAgo = new Date(Date.now() - 30 * 86400000).toISOString().slice(0, 10);
  selClear('reconciliation');
  el.innerHTML = `
    <div class="filters">
      <div><label>开始</label><input id="reconFrom" type="date" value="${monthAgo}"></div>
      <div><label>结束</label><input id="reconTo" type="date" value="${today}"></div>
      <div><label>渠道</label>
        <select id="reconChannel"><option value="WECHAT">微信</option><option value="ALIPAY">支付宝</option><option value="MOCK">Mock</option></select>
      </div>
      <div><button class="btn-ghost btn-sm" onclick="fetchReconciliationList()">查询</button></div>
      <div>${refreshButton('fetchReconciliationList()')}</div>
      ${selBar('reconciliation')}
      ${permButton('recon.run', '执行对账', 'runReconToday(event)', 'btn-primary btn-sm')}
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
    const rows = (list || []).map(r => `
      ${selRowOpen('reconciliation', r.reconId)}
      ${selCheckCell('reconciliation', r.reconId)}
      <td>${esc(r.reconDate)}</td><td>${esc(payChannelLabel(r.channel))}</td>
      <td>${fmtMoney(r.platformTotal)}</td><td>${fmtMoney(r.ledgerTotal)}</td>
      <td>${fmtMoney(r.diffCents)}</td>
      <td>${esc(r.matchedCount ?? 0)}/${esc(r.unmatchedCount ?? 0)}</td>
      <td>${esc(reconStatusLabel(r.status))}</td><td>${fmtTime(r.completedAt)}</td>
      <td onclick="event.stopPropagation()"><button type="button" class="btn-ghost btn-sm" onclick="showReconDetail(${esc(r.reconId)})">明细</button></td>
    </tr>`).join('');
    table.innerHTML = selWrap('reconciliation', `
      <table class="table"><thead><tr>
        ${selHeaderCell('reconciliation')}
        <th>日期</th><th>渠道</th><th>平台总额</th><th>账本总额</th><th>差额</th>
        <th>匹配/未匹配</th><th>状态</th><th>完成时间</th><th>操作</th>
      </tr></thead><tbody>${rows || '<tr><td colspan="10">暂无记录</td></tr>'}</tbody></table>`);
    selSync('reconciliation');
  } catch (e) {
    opsRenderError(table, e);
  }
}

async function runReconToday(ev) {
  await withSaveGuard(ev, async () => {
  const today = new Date().toISOString().slice(0, 10);
  const channel = document.getElementById('reconChannel')?.value || 'WECHAT';
  try {
    await api(`/api/v2/ops/admin/reconciliation/run?date=${today}&channel=${channel}`, 'POST');
    toast('对账任务已提交', 'ok');
    fetchReconciliationList();
  } catch (e) {
    if (!handleAuthFailure(e)) toast('对账失败: ' + e.message, 'err');
  }
  }, '执行中…');
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
    openOpsModal(`
      <div class="modal-backdrop" onclick="closeModal(event)">
        <div class="modal modal-wide" onclick="event.stopPropagation()">
          <h3>对账明细 #${esc(reconId)} · ${esc(s.reconDate)} · ${esc(s.channel)}</h3>
          <p class="meta">平台 ${fmtMoney(s.platformTotal)} / 账本 ${fmtMoney(s.ledgerTotal)} / 差额 ${fmtMoney(s.diffCents)} · ${esc(reconStatusLabel(s.status))}</p>
          ${unmatched.length ? `<p class="err">未匹配 ${esc(unmatched.length)} 笔</p>` : ''}
          <table class="table"><thead><tr>
            <th>平台流水</th><th>商户单号</th><th>金额</th><th>类型</th><th>匹配</th><th>时间</th>
          </tr></thead><tbody>${lineRows || '<tr><td colspan="6">无明细</td></tr>'}</tbody></table>
          ${lines.length > 100 ? '<p class="sub">仅显示前 100 条</p>' : ''}
          <button type="button" class="btn-ghost" data-modal-cancel onclick="closeModal()">关闭</button>
        </div>
      </div>`);
  } catch (e) {
    if (!handleAuthFailure(e)) toast('加载失败: ' + e.message, 'err');
  }
}

async function loadReplenishmentPage() {
  const el = document.getElementById('pageContent');
  const page = 'replenishment';
  selClear('replenInventory');
  const lowOnly = replenishmentFilters.lowStockOnly;
  try {
    const invPath = '/api/v2/ops/admin/inventory' + (lowOnly ? '?lowStockOnly=true' : '');
    const [routes, inventory, skuList, alerts] = await Promise.all([
      api('/api/v2/ops/admin/replenishment/routes', 'GET'),
      api(invPath, 'GET'),
      api('/api/v2/ops/admin/skus', 'GET').catch(() => []),
      api('/api/v2/ops/admin/expiry/alerts', 'GET').catch(() => [])
    ]);
    if (!isCurrentPage(page)) return;
    const skuById = Object.fromEntries((skuList || []).map(s => [s.skuId, s]));
    const skuLabel = (skuId) => {
      const s = skuById[skuId];
      return s ? `${esc(s.skuName)} <code>${esc(skuId)}</code>` : `<code>${esc(skuId)}</code>`;
    };
    const lowCount = (inventory || []).filter(i => i.quantity <= i.lowThreshold).length;
    const alertRows = (alerts || []).map(a => `<tr>
      <td>${esc(a.deviceId)}</td><td>${skuLabel(a.skuId)}</td>
      <td><code>${esc(a.batchNo || '-')}</code></td><td>${esc(a.quantity)}</td>
      <td><span class="badge badge-active">${esc(a.reason)}</span></td>
      <td>${fmtTime(a.createdAt)}</td>
    </tr>`).join('');
    const routeRows = (routes || []).map(r => {
      const tasks = (r.tasks || []).map(t => `<tr>
        <td>${esc(t.deviceId)}</td><td>${esc(replenishStatusLabel(t.status))}</td>
        <td>${t.completedAt ? fmtTime(t.completedAt) : '-'}</td>
        <td>${t.status !== 'COMPLETED' && hasPerm('ops:replenishment:edit')
          ? `<button class="btn-ghost btn-sm" onclick="showReplenishmentLinesForm(${t.taskId},'${escAttr(t.deviceId)}')">录入行</button>
             <button class="btn-ghost btn-sm" onclick="completeReplenishmentTask(${t.taskId})">完成</button>` : '-'}</td>
      </tr>`).join('');
      const taskTable = tasks
        ? `<table class="table sub-table"><thead><tr><th>设备</th><th>状态</th><th>完成时间</th><th>操作</th></tr></thead><tbody>${tasks}</tbody></table>`
        : '<span class="meta">无任务</span>';
      return `<tr><td colspan="5">
        <div><strong>${esc(r.routeName)}</strong> · ${esc(r.plannedDate)} · ${esc(replenishStatusLabel(r.status))} · 负责人 ${esc(r.assigneeUserId || '-')}</div>
        ${taskTable}
      </td></tr>`;
    }).join('');
    const deviceIds = [...new Set((inventory || []).map(i => i.deviceId))];
    const invRows = (inventory || []).map(i => {
      const low = i.quantity <= i.lowThreshold;
      const rowId = `${i.deviceId}:${i.skuId}`;
      return `
      ${selRowOpen('replenInventory', rowId, low ? 'row-low-stock' : '')}
      ${selCheckCell('replenInventory', rowId)}
      <td>${esc(i.deviceId)}</td><td>${skuLabel(i.skuId)}</td>
      <td>${esc(i.quantity)}/${esc(i.capacity)}${low ? ' <span class="badge badge-active">低库存</span>' : ''}</td>
      <td>${esc(i.lowThreshold)}</td>
      <td onclick="event.stopPropagation()">
        ${hasPerm('ops:replenishment:edit')
          ? `<button class="btn-ghost btn-sm" onclick='showInventoryForm(${JSON.stringify(i)})'>编辑</button>` : ''}
        <button class="btn-ghost btn-sm" onclick="viewDeviceLots('${escAttr(i.deviceId)}')">批次</button>
      </td>
    </tr>`;
    }).join('');
    el.innerHTML = `
      <div class="filters">
        ${permButton('replenish.plan', '规划路线', 'showReplenishmentPlanForm()', 'btn-primary btn-sm')}
        ${permButton('replenish.edit', '录入库存', 'showInventoryForm()', 'btn-ghost btn-sm')}
        ${permButton('replenish.edit', 'SKU 盘点', 'showSkuStocktakeForm()', 'btn-ghost btn-sm')}
        ${permButton('replenish.edit', '报损', 'showWriteOffForm()', 'btn-ghost btn-sm')}
        ${lowCount > 0 && hasPerm('ops:replenishment:edit')
          ? `<button type="button" class="btn-ok btn-sm" onclick="planRouteFromLowStock()">从低库存生成路线 (${lowCount})</button>` : ''}
        <label class="filter-check"><input type="checkbox" id="replLowOnly" ${lowOnly ? 'checked' : ''} onchange="toggleReplenishmentLowStock()"> 仅低库存</label>
        ${selBar('replenInventory')}
        ${refreshButton('loadReplenishmentPage()')}
      </div>
      <p class="meta">${lowOnly ? `当前显示 ${inventory.length} 条低库存记录` : `共 ${inventory.length} 条库存，其中 ${lowCount} 条低库存`}</p>
      <h3>效期告警 / 待下架</h3>
      ${(alerts || []).length
        ? `<table class="table"><thead><tr><th>设备</th><th>商品</th><th>批次</th><th>数量</th><th>原因</th><th>创建时间</th></tr></thead><tbody>${alertRows}</tbody></table>`
        : '<p class="meta">暂无待下架任务</p>'}
      <h3>补货路线</h3>
      ${(routes || []).length
        ? `<table class="table"><tbody>${routeRows}</tbody></table>`
        : emptyStateHtml('暂无补货路线', '点击「规划路线」创建补货任务', 'loadReplenishmentPage()')}
      <h3>柜内库存</h3>
      ${deviceIds.length ? `<p class="meta">设备：${deviceIds.map(d => `<button class="btn-ghost btn-sm" onclick="viewDeviceLots('${escAttr(d)}')">${esc(d)} 批次</button>`).join(' ')}</p>` : ''}
      ${(inventory || []).length
        ? selWrap('replenInventory', `<table class="table"><thead><tr>
          ${selHeaderCell('replenInventory')}
          <th>设备</th><th>商品</th><th>库存/容量</th><th>低库存阈值</th><th>操作</th>
        </tr></thead><tbody>${invRows}</tbody></table>`)
        : emptyStateHtml(lowOnly ? '暂无低库存 SKU' : '暂无库存数据', lowOnly ? '所有 SKU 库存充足' : '点击「录入库存」添加柜内 SKU 数量', 'loadReplenishmentPage()')}`;
    selSync('replenInventory');
    applyNavPermissions();
  } catch (e) {
    if (!isCurrentPage(page)) return;
    opsRenderError(el, e);
  }
}

function toggleReplenishmentLowStock() {
  const cb = document.getElementById('replLowOnly');
  replenishmentFilters.lowStockOnly = !!cb?.checked;
  loadReplenishmentPage();
}

async function planRouteFromLowStock() {
  if (!hasPerm('ops:replenishment:edit')) return;
  try {
    const inventory = await api('/api/v2/ops/admin/inventory?lowStockOnly=true', 'GET');
    const lowItems = inventory || [];
    if (!lowItems.length) {
      toast('暂无低库存 SKU', 'err');
      return;
    }
    const deviceIds = [...new Set(lowItems.map(i => i.deviceId))];
    const today = new Date().toISOString().slice(0, 10);
    const notesByDevice = {};
    lowItems.forEach(i => {
      notesByDevice[i.deviceId] = (notesByDevice[i.deviceId] || []).concat(`${i.skuId}×${i.quantity}`);
    });
    if (!await showConfirm(`将为 ${deviceIds.length} 台设备创建补货路线，涉及 ${lowItems.length} 个低库存 SKU？`, { title: '创建补货路线' })) return;
    await api('/api/v2/ops/admin/replenishment/routes', 'POST', {
      routeName: `低库存补货-${today}`,
      plannedDate: today,
      assigneeUserId: parseInt(localStorage.getItem('admin_userId') || '100000001', 10),
      tasks: deviceIds.map(deviceId => ({
        deviceId,
        notes: '低库存: ' + (notesByDevice[deviceId] || []).join('; ')
      }))
    });
    toast('补货路线已创建', 'ok');
    replenishmentFilters.lowStockOnly = false;
    loadReplenishmentPage();
  } catch (e) {
    if (!handleAuthFailure(e)) toast('创建失败: ' + e.message, 'err');
  }
}

function showInventoryForm(item) {
  const isEdit = !!item;
  openOpsModal(`
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal" onclick="event.stopPropagation()">
        <h3>${isEdit ? '编辑库存' : '录入库存'}</h3>
        <label>设备 ID</label>
        <input id="invDevice" value="${isEdit ? escAttr(item.deviceId) : 'CAB-001'}" ${isEdit ? 'disabled' : ''}>
        <label>SKU ID</label>
        <input id="invSku" value="${isEdit ? escAttr(item.skuId) : 'SKU-DEMO-001'}" ${isEdit ? 'disabled' : ''}>
        <div class="filters">
          <div><label>当前数量</label><input id="invQty" type="number" min="0" value="${isEdit ? item.quantity : 0}"></div>
          <div><label>容量</label><input id="invCap" type="number" min="1" value="${isEdit ? item.capacity : 20}"></div>
        </div>
        <label>低库存阈值</label>
        <input id="invLow" type="number" min="0" value="${isEdit ? item.lowThreshold : 3}">
        <div class="filters" style="margin-top:12px">
          <button type="button" class="btn-primary" onclick="saveInventory(event)">保存</button>
          <button type="button" class="btn-ghost" data-modal-cancel onclick="closeModal()">取消</button>
        </div>
      </div>
    </div>`);
}

async function saveInventory(ev) {
  await withSaveGuard(ev, async () => {
  const deviceId = document.getElementById('invDevice').value.trim();
  const skuId = document.getElementById('invSku').value.trim();
  const quantity = parseInt(document.getElementById('invQty').value, 10);
  const capacity = parseInt(document.getElementById('invCap').value, 10);
  const lowThreshold = parseInt(document.getElementById('invLow').value, 10);
  if (!deviceId || !skuId || Number.isNaN(quantity) || Number.isNaN(capacity)) {
    toast('请填写完整', 'err');
    return;
  }
  try {
    await api('/api/v2/ops/admin/inventory', 'PUT', {
      deviceId, skuId, quantity, capacity, lowThreshold: lowThreshold || 0
    });
    closeModal();
    toast('库存已保存', 'ok');
    loadReplenishmentPage();
  } catch (e) {
    if (!handleAuthFailure(e)) toast('保存失败: ' + e.message, 'err');
  }
  });
}

function showSkuStocktakeForm(item) {
  const isEdit = !!item;
  openOpsModal(`
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal" onclick="event.stopPropagation()">
        <h3>SKU 盘点调整</h3>
        <p class="meta">按 SKU 汇总账面与实盘差异，写入批次流水（FEFO 缩账或补录）。</p>
        <label>设备 ID</label>
        <input id="stkDevice" value="${isEdit ? escAttr(item.deviceId) : 'CAB-001'}">
        <label>SKU ID</label>
        <input id="stkSku" value="${isEdit ? escAttr(item.skuId) : 'SKU-DEMO-001'}">
        <label>实盘数量</label>
        <input id="stkQty" type="number" min="0" value="${isEdit ? item.quantity : 0}">
        <label>备注</label>
        <input id="stkNote" placeholder="可选">
        <div class="filters" style="margin-top:12px">
          <button type="button" class="btn-primary" onclick="saveSkuStocktake(event)">提交盘点</button>
          <button type="button" class="btn-ghost" data-modal-cancel onclick="closeModal()">取消</button>
        </div>
      </div>
    </div>`);
}

async function saveSkuStocktake(ev) {
  await withSaveGuard(ev, async () => {
    const deviceId = document.getElementById('stkDevice').value.trim();
    const skuId = document.getElementById('stkSku').value.trim();
    const countedQuantity = parseInt(document.getElementById('stkQty').value, 10);
    const note = document.getElementById('stkNote').value.trim() || null;
    if (!deviceId || !skuId || Number.isNaN(countedQuantity)) {
      toast('请填写完整', 'err');
      return;
    }
    try {
      await api('/api/v2/ops/admin/inventory/stocktake', 'POST', { deviceId, skuId, countedQuantity, note });
      closeModal();
      toast('盘点已提交', 'ok');
      loadReplenishmentPage();
    } catch (e) {
      if (!handleAuthFailure(e)) toast('盘点失败: ' + e.message, 'err');
    }
  });
}

function showWriteOffForm(prefill) {
  const p = prefill || {};
  openOpsModal(`
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal" onclick="event.stopPropagation()">
        <h3>库存报损</h3>
        <label>设备 ID</label>
        <input id="woDevice" value="${escAttr(p.deviceId || 'CAB-001')}">
        <label>SKU ID</label>
        <input id="woSku" value="${escAttr(p.skuId || 'SKU-DEMO-001')}">
        <label>批次号（可选，空则 FEFO）</label>
        <input id="woBatch" value="${escAttr(p.batchNo || '')}">
        <label>数量</label>
        <input id="woQty" type="number" min="1" value="${p.quantity || 1}">
        <label>原因</label>
        <select id="woReason">
          <option value="EXPIRED">过期 EXPIRED</option>
          <option value="DAMAGED">破损 DAMAGED</option>
          <option value="THEFT">盗损 THEFT</option>
          <option value="OTHER">其他 OTHER</option>
        </select>
        <div class="filters" style="margin-top:12px">
          <button type="button" class="btn-warn" onclick="saveWriteOff(event)">确认报损</button>
          <button type="button" class="btn-ghost" data-modal-cancel onclick="closeModal()">取消</button>
        </div>
      </div>
    </div>`);
}

async function saveWriteOff(ev) {
  await withSaveGuard(ev, async () => {
    const deviceId = document.getElementById('woDevice').value.trim();
    const skuId = document.getElementById('woSku').value.trim();
    const batchNo = document.getElementById('woBatch').value.trim() || null;
    const quantity = parseInt(document.getElementById('woQty').value, 10);
    const reason = document.getElementById('woReason').value;
    if (!deviceId || !skuId || !quantity || quantity < 1) {
      toast('请填写完整', 'err');
      return;
    }
    if (!await showConfirm(`确认报损 ${skuId} × ${quantity}？`, { title: '报损确认' })) return;
    try {
      await api('/api/v2/ops/admin/inventory/write-off', 'POST', { deviceId, skuId, batchNo, quantity, reason });
      closeModal();
      toast('报损已记录', 'ok');
      loadReplenishmentPage();
    } catch (e) {
      if (!handleAuthFailure(e)) toast('报损失败: ' + e.message, 'err');
    }
  });
}

async function completeReplenishmentTask(taskId) {
  if (!await showConfirm(`确认完成任务 #${taskId}？将应用已录入的补货行并更新批次库存。`, { title: '完成任务' })) return;
  try {
    await api('/api/v2/ops/admin/replenishment/tasks/' + taskId + '/complete', 'POST');
    toast('任务已完成', 'ok');
    loadReplenishmentPage();
  } catch (e) {
    if (!handleAuthFailure(e)) toast('操作失败: ' + e.message, 'err');
  }
}

async function showReplenishmentLinesForm(taskId, deviceId) {
  let skus = [];
  try {
    skus = await api('/api/v2/ops/admin/skus', 'GET');
  } catch (e) {
    if (handleAuthFailure(e)) return;
  }
  const skuOptions = (skus || []).map(s =>
    `<option value="${escAttr(s.skuId)}">${esc(s.skuName)} (${esc(s.skuId)})</option>`).join('');
  const today = new Date().toISOString().slice(0, 10);
  const expiryDefault = new Date(Date.now() + 30 * 86400000).toISOString().slice(0, 10);
  openOpsModal(`
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal" style="max-width:720px" onclick="event.stopPropagation()">
        <h3>补货行项目 · 任务 #${taskId}</h3>
        <p class="meta">设备 <code>${esc(deviceId)}</code> · 完成前提交上架/下架明细，完成时将写入批次与库存流水</p>
        <div id="replLinesContainer"></div>
        <button type="button" class="btn-ghost btn-sm" onclick="addReplenishmentLineRow()">+ 添加一行</button>
        <div class="filters" style="margin-top:12px">
          <button type="button" class="btn-primary" onclick="saveReplenishmentLines(event, ${taskId})">保存行项目</button>
          <button type="button" class="btn-ghost" data-modal-cancel onclick="closeModal()">取消</button>
        </div>
      </div>
    </div>`);
  window._replLineSkuOptions = skuOptions;
  window._replLineDefaults = { today, expiryDefault };
  try {
    const existing = await api('/api/v2/ops/admin/replenishment/tasks/' + taskId + '/lines', 'GET');
    const container = document.getElementById('replLinesContainer');
    if (!container) return;
    if (existing?.length) {
      existing.forEach(line => addReplenishmentLineRow(line));
    } else {
      addReplenishmentLineRow();
    }
  } catch (e) {
    addReplenishmentLineRow();
    if (!handleAuthFailure(e)) toast('加载已有行失败: ' + e.message, 'err');
  }
}

function addReplenishmentLineRow(line) {
  const container = document.getElementById('replLinesContainer');
  if (!container) return;
  const skuOptions = window._replLineSkuOptions || '';
  const d = window._replLineDefaults || { today: '', expiryDefault: '' };
  const lineType = line?.lineType || 'RESTOCK';
  const skuId = line?.skuId || '';
  const idx = container.children.length;
  const row = document.createElement('div');
  row.className = 'card';
  row.style.marginBottom = '10px';
  row.innerHTML = `
    <div class="filters form-grid">
      <div><label>类型</label>
        <select class="repl-line-type">
          <option value="RESTOCK" ${lineType === 'RESTOCK' ? 'selected' : ''}>上架 RESTOCK</option>
          <option value="PULL_OFF" ${lineType === 'PULL_OFF' ? 'selected' : ''}>下架 PULL_OFF</option>
        </select>
      </div>
      <div><label>SKU</label>
        <select class="repl-line-sku"><option value="">选择商品</option>${skuOptions}</select>
      </div>
      <div><label>数量</label><input class="repl-line-qty" type="number" min="1" value="${line?.quantity || 1}"></div>
      <div><label>批次号</label><input class="repl-line-batch" value="${escAttr(line?.batchNo || '')}" placeholder="B20260701-001"></div>
      <div><label>生产日期</label><input class="repl-line-prod" type="date" value="${line?.productionDate || d.today}"></div>
      <div><label>到期日</label><input class="repl-line-exp" type="date" value="${line?.expiryDate || d.expiryDefault}"></div>
      <div><label>货道</label><input class="repl-line-slot" value="${escAttr(line?.slotId || '')}" placeholder="A1"></div>
    </div>
    <button type="button" class="btn-ghost btn-sm" onclick="this.closest('.card').remove()">删除此行</button>`;
  container.appendChild(row);
  if (skuId) {
    const sel = row.querySelector('.repl-line-sku');
    if (sel) sel.value = skuId;
  }
}

async function saveReplenishmentLines(ev, taskId) {
  if (ev) ev.preventDefault();
  const container = document.getElementById('replLinesContainer');
  if (!container) return;
  const lines = [];
  container.querySelectorAll('.card').forEach(card => {
    const lineType = card.querySelector('.repl-line-type')?.value || 'RESTOCK';
    const skuId = card.querySelector('.repl-line-sku')?.value?.trim();
    const quantity = parseInt(card.querySelector('.repl-line-qty')?.value, 10);
    const batchNo = card.querySelector('.repl-line-batch')?.value?.trim() || null;
    const productionDate = card.querySelector('.repl-line-prod')?.value || null;
    const expiryDate = card.querySelector('.repl-line-exp')?.value || null;
    const slotId = card.querySelector('.repl-line-slot')?.value?.trim() || null;
    if (!skuId || !quantity) return;
    lines.push({ lineType, skuId, quantity, batchNo, productionDate, expiryDate, slotId });
  });
  if (!lines.length) {
    toast('请至少填写一行有效明细', 'err');
    return;
  }
  try {
    await api('/api/v2/ops/admin/replenishment/tasks/' + taskId + '/lines', 'POST', { lines });
    closeModal();
    toast('补货行已保存', 'ok');
    loadReplenishmentPage();
  } catch (e) {
    if (!handleAuthFailure(e)) toast('保存失败: ' + e.message, 'err');
  }
}

async function viewDeviceLots(deviceId) {
  try {
    const lots = await api('/api/v2/ops/admin/devices/' + encodeURIComponent(deviceId) + '/lots', 'GET');
    const rows = (lots || []).map(l => `<tr>
      <td><code>${esc(l.batchNo)}</code></td><td>${skuLabelFromCache(l.skuId)}</td>
      <td>${esc(l.quantity)}</td><td>${esc(l.expiryDate || '-')}</td>
      <td>${esc(lotStatusLabel(l.status))}</td><td>${esc(l.slotId || '-')}</td>
      <td>${hasPerm('ops:replenishment:edit') && l.quantity > 0
        ? `<button class="btn-ghost btn-sm" onclick='showWriteOffForm(${JSON.stringify({ deviceId, skuId: l.skuId, batchNo: l.batchNo, quantity: l.quantity })})'>报损</button>`
        : '-'}</td>
    </tr>`).join('');
    openOpsModal(`
      <div class="modal-backdrop" onclick="closeModal(event)">
        <div class="modal" style="max-width:800px" onclick="event.stopPropagation()">
          <h3>设备批次 · ${esc(deviceId)}</h3>
          ${(lots || []).length
            ? `<table class="table"><thead><tr><th>批次</th><th>商品</th><th>数量</th><th>到期</th><th>状态</th><th>货道</th><th>操作</th></tr></thead><tbody>${rows}</tbody></table>`
            : '<p class="meta">暂无批次记录（可通过补货行 RESTOCK 入库）</p>'}
          <div class="filters" style="margin-top:12px">
            <button type="button" class="btn-ghost" data-modal-cancel onclick="closeModal()">关闭</button>
          </div>
        </div>
      </div>`);
  } catch (e) {
    if (!handleAuthFailure(e)) toast('加载批次失败: ' + e.message, 'err');
  }
}

function lotStatusLabel(status) {
  const map = { ON_SALE: '在售', NEAR_EXPIRY: '临期', BLOCKED: '禁售', DEPLETED: '售罄' };
  return map[status] || status || '-';
}

function skuLabelFromCache(skuId) {
  return `<code>${esc(skuId)}</code>`;
}

async function showReplenishmentPlanForm() {
  const today = new Date().toISOString().slice(0, 10);
  openOpsModal(`
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal" style="max-width:560px" onclick="event.stopPropagation()">
        <h3>规划补货路线</h3>
        <label>路线名称</label>
        <input id="rpName" value="补货路线-${today}">
        <label>选择设备</label>
        <div id="rpDeviceList"><p class="meta">加载设备中…</p></div>
        <div class="filters">
          <div><label>负责人 userId</label>
            <input id="rpAssignee" type="number" value="${escAttr(localStorage.getItem('admin_userId') || '100000001')}"></div>
          <div><label>计划日期</label><input id="rpDate" type="date" value="${today}"></div>
        </div>
        <div class="filters">
          <div><label>起点纬度</label><input id="rpLat" type="number" step="0.0001" value="31.23"></div>
          <div><label>起点经度</label><input id="rpLng" type="number" step="0.0001" value="121.47"></div>
        </div>
        <div class="filters" style="margin-top:12px">
          <button type="button" class="btn-primary" onclick="saveReplenishmentPlan(event)">创建路线</button>
          <button type="button" class="btn-ghost" data-modal-cancel onclick="closeModal()">取消</button>
        </div>
      </div>
    </div>`);
  try {
    const devices = await api('/api/v2/ops/admin/devices', 'GET');
    const listEl = document.getElementById('rpDeviceList');
    if (!listEl) return;
    if (!devices?.length) {
      listEl.innerHTML = '<p class="meta">暂无设备，请先在设备管理注册</p>';
      return;
    }
    const rows = devices.map(d => `
      <label class="device-check">
        <input type="checkbox" class="rp-device-cb" value="${escAttr(d.deviceId)}">
        <span class="device-check-main">${esc(d.deviceId)} · ${esc(d.deviceName || '-')}</span>
        <span class="meta">${esc(d.merchantName || '未绑定商户')} · ${esc(onlineStatusLabel(d.onlineStatus))}</span>
      </label>`).join('');
    listEl.innerHTML = `
      <div class="device-check-toolbar">
        <button type="button" class="btn-ghost btn-sm" onclick="toggleAllReplenishmentDevices(true)">全选</button>
        <button type="button" class="btn-ghost btn-sm" onclick="toggleAllReplenishmentDevices(false)">清空</button>
      </div>
      <div class="device-check-list">${rows}</div>`;
  } catch (e) {
    const listEl = document.getElementById('rpDeviceList');
    if (listEl && !handleAuthFailure(e)) {
      listEl.innerHTML = `<p class="meta">加载设备失败：${esc(e.message)}</p>`;
    }
  }
}

function getSelectedReplenishmentDevices() {
  return [...document.querySelectorAll('.rp-device-cb:checked')].map(el => el.value);
}

function toggleAllReplenishmentDevices(checked) {
  document.querySelectorAll('.rp-device-cb').forEach(cb => { cb.checked = checked; });
}

async function saveReplenishmentPlan(ev) {
  await withSaveGuard(ev, async () => {
  const routeName = document.getElementById('rpName').value.trim();
  const deviceIds = getSelectedReplenishmentDevices();
  const assigneeUserId = parseInt(document.getElementById('rpAssignee').value, 10);
  const plannedDate = document.getElementById('rpDate').value;
  const startLatitude = parseFloat(document.getElementById('rpLat').value);
  const startLongitude = parseFloat(document.getElementById('rpLng').value);
  if (!routeName || !deviceIds.length || !plannedDate || Number.isNaN(assigneeUserId)) {
    toast('请填写路线名称、设备和负责人', 'err');
    return;
  }
  try {
    await api('/api/v2/ops/admin/replenishment/plan', 'POST', {
      routeName, assigneeUserId, plannedDate, deviceIds, startLatitude, startLongitude
    });
    closeModal();
    toast('路线已规划', 'ok');
    loadReplenishmentPage();
  } catch (e) {
    if (!handleAuthFailure(e)) toast('规划失败: ' + e.message, 'err');
  }
  }, '创建中…');
}

async function loadRbacPage() {
  const el = document.getElementById('pageContent');
  const page = 'rbac';
  selClear('rbacRoles');
  selClear('rbacOperators');
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
          <h3 class="pane-title">角色列表 ${selBar('rbacRoles')}</h3>
          ${selWrap('rbacRoles', `<table class="table rbac-role-table">
            <thead><tr>
              ${selHeaderCell('rbacRoles')}
              <th>角色</th><th>标识</th><th>权限</th>
            </tr></thead>
            <tbody>${(window._rbacState.roles || []).map(r => `
              ${selRowOpen('rbacRoles', r.roleId, window._rbacState.selectedRoleId === r.roleId ? 'rbac-role-row selected' : 'rbac-role-row', `if (!event.ctrlKey && !event.metaKey) selectRbacRole(${r.roleId})`)}
              ${selCheckCell('rbacRoles', r.roleId)}
              <td>${esc(r.roleName)}</td>
              <td><code>${esc(r.roleKey)}</code></td>
              <td class="meta">${esc((r.permissions || [])[0] || '-')}</td>
            </tr>`).join('')}</tbody>
          </table>`)}
        </div>
        <div class="card rbac-pane" id="rbacPermPane">
          <p class="sub">选择左侧角色以配置菜单权限</p>
        </div>
      </div>`;
    selSync('rbacRoles');
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
              <input id="rbacOpPhone" placeholder="搜索手机号" value="${escAttr(window._rbacState.operatorFilters.phone)}" oninput="debouncedSearchRbacOperators()"></div>
            <button class="btn-primary btn-sm" onclick="searchRbacOperators()">搜索</button>
            ${selBar('rbacOperators')}
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
          permButton('rbac.role.save', '保存权限', 'saveRolePermissions(event)', 'btn-primary btn-sm')}
      </div>
      <div class="perm-tree">${renderPermTreeNodes(tree, checkedIds)}</div>`;
    applyNavPermissions();
  } catch (e) {
    if (!handleAuthFailure(e)) pane.innerHTML = '<p class="err">' + esc(e.message) + '</p>';
  }
}

async function saveRolePermissions(ev) {
  await withSaveGuard(ev, async () => {
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
  });
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
    list.innerHTML = selWrap('rbacOperators', `
      <table class="table">
        <thead><tr>
          ${selHeaderCell('rbacOperators')}
          <th>手机号</th><th>姓名</th><th>当前角色</th>
        </tr></thead>
        <tbody>${data.items.map(u => `
          ${selRowOpen('rbacOperators', u.userId, window._rbacState.selectedUserId === u.userId ? 'rbac-user-row selected' : 'rbac-user-row', `if (!event.ctrlKey && !event.metaKey) selectRbacUser(${u.userId})`)}
          ${selCheckCell('rbacOperators', u.userId)}
          <td>${esc(u.phoneNumber)}</td>
          <td>${esc(u.name || '-')}</td>
          <td class="meta">${esc((u.roleNames || []).join('、') || '未分配')}</td>
        </tr>`).join('')}</tbody>
      </table>`)
      + renderRbacOperatorPagination(data);
    selSync('rbacOperators');
  } catch (e) {
    if (!handleAuthFailure(e)) list.innerHTML = '<p class="err">' + esc(e.message) + '</p>';
  }
}

async function selectRbacUser(userId) {
  window._rbacState.selectedUserId = userId;
  document.querySelectorAll('.rbac-user-row').forEach(row => {
    row.classList.toggle('selected', row.getAttribute('onclick')?.includes('(' + userId + ')'));
  });
  const pane = document.getElementById('rbacUserRolePane');
  if (!pane) return;
  pane.innerHTML = '<p class="sub">加载授权…</p>';
  try {
    const [roleData, merchantData] = await Promise.all([
      api('/api/v2/ops/admin/rbac/users/' + userId + '/roles', 'GET'),
      api('/api/v2/ops/admin/rbac/users/' + userId + '/merchants', 'GET')
    ]);
    const assignedRoles = new Set(roleData.roleIds || []);
    const assignedMerchants = new Set(merchantData.merchantIds || []);
    const roleChecks = (window._rbacRoles || []).map(r =>
      `<label class="role-check-item">
        <input type="checkbox" class="rbac-role-cb" value="${escAttr(r.roleId)}" ${assignedRoles.has(r.roleId) ? 'checked' : ''}>
        <span>${esc(r.roleName)}</span>
        <code>${esc(r.roleKey)}</code>
      </label>`
    ).join('');
    let merchants = window._rbacMerchants || [];
    if (!merchants.length) {
      try {
        merchants = await api('/api/v2/ops/admin/merchants', 'GET');
        window._rbacMerchants = merchants;
      } catch (_) { /* merchant list optional for non-admin */ }
    }
    const merchantChecks = merchants.map(m =>
      `<label class="role-check-item">
        <input type="checkbox" class="rbac-merchant-cb" value="${escAttr(m.merchantId)}" ${assignedMerchants.has(m.merchantId) ? 'checked' : ''}>
        <span>${esc(m.merchantName)}</span>
        <code>${esc(m.merchantId)}</code>
      </label>`
    ).join('');
    pane.innerHTML = `
      <h3 class="pane-title">分配角色 · 用户 ${esc(userId)}</h3>
      <div class="role-check-list">${roleChecks || '<p class="sub">无可用角色</p>'}</div>
      ${permButton('rbac.assign', '保存角色', 'saveUserRoles(event)', 'btn-primary btn-sm')}
      <h3 class="pane-title" style="margin-top:20px">数据范围 · 商户</h3>
      <p class="sub">不勾选任何商户 = 全局可见；勾选后仅可见对应商户的设备/订单/分账。</p>
      <div class="role-check-list">${merchantChecks || '<p class="sub">暂无商户，请先在商户分账页创建</p>'}</div>
      ${permButton('rbac.assign', '保存商户范围', 'saveUserMerchants(event)', 'btn-primary btn-sm')}`;
    applyNavPermissions();
  } catch (e) {
    if (!handleAuthFailure(e)) pane.innerHTML = '<p class="err">' + esc(e.message) + '</p>';
  }
}

async function saveUserMerchants(ev) {
  await withSaveGuard(ev, async () => {
  const userId = window._rbacState?.selectedUserId;
  if (!userId) { toast('请先选择运营账号', 'err'); return; }
  const merchantIds = [...document.querySelectorAll('.rbac-merchant-cb:checked')].map(el => el.value);
  try {
    await api('/api/v2/ops/admin/rbac/users/' + userId + '/merchants', 'PUT', merchantIds);
    toast('商户数据范围已保存', 'ok');
    selectRbacUser(userId);
  } catch (e) {
    if (!handleAuthFailure(e)) toast('保存失败: ' + e.message, 'err');
  }
  });
}

async function saveUserRoles(ev) {
  await withSaveGuard(ev, async () => {
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
  });
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
    table.innerHTML = renderAuditTableHtml(items, 'rbacRecent');
    selSync('rbacRecent');
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

function renderAuditTableHtml(items, scope = 'audit') {
  if (!items || !items.length) {
    return emptyStateHtml('暂无操作记录', '运营后台的敏感操作会记录在此');
  }
  return selWrap(scope, `
    <table class="table">
      <thead><tr>
        ${selHeaderCell(scope)}
        <th>时间</th><th>操作人</th><th>动作</th><th>对象</th><th>详情</th>
      </tr></thead>
      <tbody>${items.map(l => `
        ${selRowOpen(scope, l.logId)}
        ${selCheckCell(scope, l.logId)}
        <td>${fmtTime(l.createdAt)}</td>
        <td>${formatOperatorCell(l)}</td>
        <td>${esc(auditActionLabel(l.action))}</td>
        <td>${esc(auditTargetLabel(l.targetType))} ${esc(l.targetId || '')}</td>
        <td class="meta">${esc(l.detail || '-')}</td>
      </tr>`).join('')}</tbody>
    </table>`);
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

const uploadQueueFilters = { page: 0, size: 20, deviceId: '' };
const replenishmentFilters = { lowStockOnly: false };
const UPLOAD_OVERDUE_HOURS = 2;

function waitAgeLabel(sinceIso) {
  if (!sinceIso) return '-';
  const ms = Date.now() - new Date(sinceIso).getTime();
  if (ms < 0) return '刚刚';
  const hours = ms / 3600000;
  if (hours < 1) return `${Math.max(1, Math.floor(ms / 60000))} 分钟`;
  if (hours < 24) return `${Math.floor(hours)} 小时`;
  return `${Math.floor(hours / 24)} 天`;
}

function isUploadOverdue(sinceIso, thresholdHours = UPLOAD_OVERDUE_HOURS) {
  if (!sinceIso) return false;
  return (Date.now() - new Date(sinceIso).getTime()) > thresholdHours * 3600000;
}

async function loadVisionMappingsPage() {
  const el = document.getElementById('pageContent');
  const page = 'vision-mappings';
  selClear('visionYolo');
  selClear('visionAliyun');
  try {
    const [mappings, skuList] = await Promise.all([
      api('/api/v2/ops/admin/vision-mappings', 'GET'),
      api('/api/v2/ops/admin/skus', 'GET')
    ]);
    if (!isCurrentPage(page)) return;
    const skuById = Object.fromEntries((skuList || []).map(s => [s.skuId, s]));
    const skuLabel = (skuId) => {
      const s = skuById[skuId];
      return s ? `${esc(s.skuName)} <code>${esc(skuId)}</code>` : `<code>${esc(skuId)}</code>`;
    };
    const skuOpts = (skuList || []).map(s =>
      `<option value="${escAttr(s.skuId)}">${esc(s.skuName)} (${esc(s.skuId)})</option>`
    ).join('');
    const yoloRows = (mappings.yolo || []).map(m => `
      ${selRowOpen('visionYolo', m.className)}
      ${selCheckCell('visionYolo', m.className)}
      <td><code>${esc(m.className)}</code></td>
      <td>${skuLabel(m.skuId)}</td>
      <td>${esc(m.minConfidence)}</td>
      <td onclick="event.stopPropagation()">${hasPerm('ops:vision:edit') ? `<button class="btn-danger btn-sm" onclick="deleteYoloMapping('${escAttr(m.className)}')">删除</button>` : '-'}</td>
    </tr>`).join('');
    const aliyunRows = (mappings.aliyun || []).map(m => `
      ${selRowOpen('visionAliyun', m.categoryId)}
      ${selCheckCell('visionAliyun', m.categoryId)}
      <td><code>${esc(m.categoryId)}</code></td>
      <td>${esc(m.categoryName || '-')}</td>
      <td>${skuLabel(m.skuId)}</td>
      <td>${esc(m.minConfidence)}</td>
      <td onclick="event.stopPropagation()">${hasPerm('ops:vision:edit') ? `<button class="btn-danger btn-sm" onclick="deleteAliyunMapping('${escAttr(m.categoryId)}')">删除</button>` : '-'}</td>
    </tr>`).join('');
    el.innerHTML = `
      <div class="card"><div class="filters">${refreshButton('loadVisionMappingsPage()')}</div></div>
      <div class="card">
        <h3 style="margin-top:0">YOLO 类名 → SKU（本地联调） ${selBar('visionYolo')}</h3>
        ${hasPerm('ops:vision:edit') ? `
        <div class="filters">
          <div><label>类名</label><input id="ymClass" placeholder="bottle"></div>
          <div><label>SKU</label><select id="ymSku">${skuOpts}</select></div>
          <div><label>最低置信度</label><input id="ymConf" type="number" step="0.01" min="0" max="1" value="0.5"></div>
          <div><button type="button" class="btn-primary btn-sm" onclick="saveYoloMapping(event)">保存</button></div>
        </div>` : ''}
        ${yoloRows ? selWrap('visionYolo', `<table class="table"><thead><tr>
          ${selHeaderCell('visionYolo')}
          <th>类名</th><th>SKU</th><th>置信度</th><th>操作</th>
        </tr></thead><tbody>${yoloRows}</tbody></table>`)
          : emptyStateHtml('暂无 YOLO 映射', '添加 COCO 类名与商品 SKU 的对应关系', 'loadVisionMappingsPage()')}
      </div>
      <div class="card">
        <h3 style="margin-top:0">阿里云类目 → SKU（生产） ${selBar('visionAliyun')}</h3>
        ${hasPerm('ops:vision:edit') ? `
        <div class="filters">
          <div><label>类目 ID</label><input id="amCatId" placeholder="201234567"></div>
          <div><label>类目名称</label><input id="amCatName" placeholder="碳酸饮料"></div>
          <div><label>SKU</label><select id="amSku">${skuOpts}</select></div>
          <div><label>最低置信度</label><input id="amConf" type="number" step="0.01" min="0" max="1" value="0.7"></div>
          <div><button type="button" class="btn-primary btn-sm" onclick="saveAliyunMapping(event)">保存</button></div>
        </div>` : ''}
        ${aliyunRows ? selWrap('visionAliyun', `<table class="table"><thead><tr>
          ${selHeaderCell('visionAliyun')}
          <th>类目ID</th><th>名称</th><th>SKU</th><th>置信度</th><th>操作</th>
        </tr></thead><tbody>${aliyunRows}</tbody></table>`)
          : emptyStateHtml('暂无阿里云映射', '对接商品理解 API 后在此维护类目与 SKU', 'loadVisionMappingsPage()')}
      </div>`;
    selSync('visionYolo');
    selSync('visionAliyun');
    applyNavPermissions();
  } catch (e) {
    if (!isCurrentPage(page)) return;
    opsRenderError(el, e);
  }
}

async function saveYoloMapping(ev) {
  await withSaveGuard(ev, async () => {
  const className = document.getElementById('ymClass').value.trim();
  const skuId = document.getElementById('ymSku').value;
  const minConfidence = parseFloat(document.getElementById('ymConf').value) || 0.5;
  if (!className) {
    toast('请填写类名', 'err');
    return;
  }
  try {
    await api('/api/v2/ops/admin/vision-mappings/yolo', 'POST', { className, skuId, minConfidence });
    toast('已保存', 'ok');
    loadVisionMappingsPage();
  } catch (e) {
    if (!handleAuthFailure(e)) toast('保存失败: ' + e.message, 'err');
  }
  });
}

async function deleteYoloMapping(className) {
  if (!await showConfirm(`删除 YOLO 映射 ${className}？`, { title: '删除映射', danger: true })) return;
  try {
    await api('/api/v2/ops/admin/vision-mappings/yolo/' + encodeURIComponent(className), 'DELETE');
    toast('已删除', 'ok');
    loadVisionMappingsPage();
  } catch (e) {
    if (!handleAuthFailure(e)) toast('删除失败: ' + e.message, 'err');
  }
}

async function saveAliyunMapping(ev) {
  await withSaveGuard(ev, async () => {
  const categoryId = document.getElementById('amCatId').value.trim();
  const categoryName = document.getElementById('amCatName').value.trim();
  const skuId = document.getElementById('amSku').value;
  const minConfidence = parseFloat(document.getElementById('amConf').value) || 0.7;
  if (!categoryId) {
    toast('请填写类目 ID', 'err');
    return;
  }
  try {
    await api('/api/v2/ops/admin/vision-mappings/aliyun', 'POST',
      { categoryId, categoryName, skuId, minConfidence });
    toast('已保存', 'ok');
    loadVisionMappingsPage();
  } catch (e) {
    if (!handleAuthFailure(e)) toast('保存失败: ' + e.message, 'err');
  }
  });
}

async function deleteAliyunMapping(categoryId) {
  if (!await showConfirm(`删除阿里云映射 ${categoryId}？`, { title: '删除映射', danger: true })) return;
  try {
    await api('/api/v2/ops/admin/vision-mappings/aliyun/' + encodeURIComponent(categoryId), 'DELETE');
    toast('已删除', 'ok');
    loadVisionMappingsPage();
  } catch (e) {
    if (!handleAuthFailure(e)) toast('删除失败: ' + e.message, 'err');
  }
}

async function loadUploadQueuePage() {
  const el = document.getElementById('pageContent');
  const page = 'upload-queue';
  selClear('uploadQueue');
  el.innerHTML = `
    <div class="card">
      <div class="filters">
        <div><label>设备ID</label><input id="uqDevice" value="${escAttr(uploadQueueFilters.deviceId)}" placeholder="可选"></div>
        <div><button class="btn-primary" onclick="searchUploadQueue()">查询</button></div>
        <div>${refreshButton('fetchUploadQueue()')}</div>
        ${selBar('uploadQueue')}
      </div>
      <div id="uploadQueueTable"></div>
    </div>`;
  showTableLoading(document.getElementById('uploadQueueTable'), 8, 6);
  fetchUploadQueue();
}

function searchUploadQueue() {
  uploadQueueFilters.deviceId = document.getElementById('uqDevice').value.trim();
  uploadQueueFilters.page = 0;
  fetchUploadQueue();
}

async function fetchUploadQueue() {
  const table = document.getElementById('uploadQueueTable');
  if (!table) return;
  showTableLoading(table, 8, 6);
  try {
    const q = new URLSearchParams({
      page: uploadQueueFilters.page,
      size: uploadQueueFilters.size,
      state: 'WAITING_UPLOAD',
      ...(uploadQueueFilters.deviceId ? { deviceId: uploadQueueFilters.deviceId } : {})
    });
    const data = await api('/api/v2/ops/admin/sessions?' + q, 'GET');
    if (!data.items.length) {
      table.innerHTML = emptyStateHtml('暂无待上传会话', '断网续传或视频未上传的会话会出现在此', 'fetchUploadQueue()');
      return;
    }
    const overdueCount = data.items.filter(s => isUploadOverdue(s.closeTime || s.updatedAt)).length;
    const summaryHtml = `<div class="stats stats-inline">
      <div class="stat"><div class="label">本页待上传</div><div class="value warn">${data.items.length}</div></div>
      <div class="stat"><div class="label">超时 (&gt;${UPLOAD_OVERDUE_HOURS}h)</div><div class="value ${overdueCount ? 'warn' : 'ok'}">${overdueCount}</div></div>
      <div class="stat"><div class="label">合计</div><div class="value">${data.total}</div></div>
    </div>`;
    table.innerHTML = summaryHtml + selWrap('uploadQueue', `
      <table>
        <thead><tr>
          ${selHeaderCell('uploadQueue')}
          <th>会话ID</th><th>用户</th><th>设备</th><th>上传状态</th><th>等待时长</th><th>融合模式</th><th>视频</th><th>关门时间</th><th>更新时间</th>
        </tr></thead>
        <tbody>${data.items.map(s => {
          const since = s.closeTime || s.updatedAt;
          const overdue = isUploadOverdue(since);
          return `
          ${selRowOpen('uploadQueue', s.sessionId, overdue ? 'row-overdue' : '')}
          ${selCheckCell('uploadQueue', s.sessionId)}
          <td><code>${esc(s.sessionId)}</code>${overdue ? ' <span class="badge badge-fail">超时</span>' : ''}</td>
          <td>${esc(s.userId)}</td>
          <td>${esc(s.deviceId)}</td>
          <td>${esc(uploadStatusLabel(s.uploadStatus))}</td>
          <td>${esc(waitAgeLabel(since))}</td>
          <td>${esc(fusionModeLabel(s.cameraFusionMode))}</td>
          <td onclick="event.stopPropagation()">${s.videoUri || s.videoPreviewUrl
            ? `<button type="button" class="btn-ghost btn-sm" onclick="showSessionVideo('${escAttr(s.sessionId)}', '${escAttr(s.videoUri || '')}')">${mediaActionLabel(s.videoUri)}</button>`
            : esc(s.videoUri || '-')}</td>
          <td>${fmtTime(s.closeTime)}</td>
          <td>${fmtTime(s.updatedAt)}</td>
        </tr>`;
        }).join('')}</tbody>
      </table>`)
      + renderUploadPagination(data);
    selSync('uploadQueue');
  } catch (e) {
    pageRenderError(table, e, false);
  }
}

function renderUploadPagination(data) {
  return buildPaginationHtml(data, 'upload');
}

function renderRbacOperatorPagination(data) {
  const f = window._rbacState.operatorFilters;
  return buildPaginationHtml({ page: f.page, size: f.size, total: data.total || 0 }, 'rbacOp');
}

function renderMerchantSplitPagination(data) {
  return buildPaginationHtml(data, 'merchantSplit');
}

const merchantSplitFilters = { page: 0, size: 20, merchantId: '', status: 'PENDING' };

async function loadMerchantsPage() {
  const el = document.getElementById('pageContent');
  const page = 'merchants';
  selClear('merchants');
  selClear('merchantSplits');
  let psBanner = '';
  if (hasPerm('ops:merchant:split')) {
    try {
      const ps = await api('/api/v2/ops/admin/merchants/profit-sharing/status', 'GET');
      const tone = ps.apiReady ? 'ok' : 'warn';
      psBanner = `<div class="demo-banner" style="${ps.apiReady ? 'background:#f6ffed;border-color:#b7eb8f;color:#389e0d' : ''}">
        分账：${ps.enabled ? '已启用' : '未启用'} · 微信支付 ${esc(ps.wechatPayConfigured)} · API ${ps.apiReady ? '就绪' : '未就绪'}
        · 重试 ${ps.retryEnabled ? '开' : '关'}(${ps.retryBatchSize}/批)
        <span class="meta"> — ${esc(ps.note || '')}</span>
      </div>`;
    } catch (_) { /* optional */ }
  }
  el.innerHTML = `
    ${psBanner}
    <div class="card">
      <div class="filters">
        <button class="btn-primary btn-sm" data-perm="ops:merchant:edit" onclick="showMerchantForm()">新增商户</button>
        ${selBar('merchants')}
        ${refreshButton('loadMerchantsPage()')}
      </div>
      <div id="merchantTable" class="sub">加载中…</div>
    </div>
    <div class="card">
      <h3 style="margin-top:0">分账明细</h3>
      <div class="filters">
        <div><label>商户ID</label><input id="msMerchant" value="${escAttr(merchantSplitFilters.merchantId)}" placeholder="可选"></div>
        <div><label>状态</label>
          <select id="msStatus">
            <option value="">全部</option>
            <option value="PENDING" ${merchantSplitFilters.status === 'PENDING' ? 'selected' : ''}>待处理</option>
            <option value="ACCRUED" ${merchantSplitFilters.status === 'ACCRUED' ? 'selected' : ''}>待分账</option>
            <option value="LEDGER_ONLY" ${merchantSplitFilters.status === 'LEDGER_ONLY' ? 'selected' : ''}>仅记账</option>
            <option value="WECHAT_SUBMITTED" ${merchantSplitFilters.status === 'WECHAT_SUBMITTED' ? 'selected' : ''}>已提交</option>
            <option value="WECHAT_FAILED" ${merchantSplitFilters.status === 'WECHAT_FAILED' ? 'selected' : ''}>失败</option>
            <option value="SUBMITTED" ${merchantSplitFilters.status === 'SUBMITTED' ? 'selected' : ''}>已提交(旧)</option>
            <option value="SUCCESS" ${merchantSplitFilters.status === 'SUCCESS' ? 'selected' : ''}>成功</option>
            <option value="FAILED" ${merchantSplitFilters.status === 'FAILED' ? 'selected' : ''}>失败(旧)</option>
          </select>
        </div>
        <div><button class="btn-primary btn-sm" onclick="searchMerchantSplits()">查询</button></div>
        <div>${refreshButton('fetchMerchantSplits()')}</div>
        ${selBar('merchantSplits')}
        <div><button class="btn-ghost btn-sm" onclick="exportMerchantSplits()">导出 CSV</button></div>
        ${hasPerm('ops:merchant:split') ? `<div><button class="btn-ok btn-sm" id="batchSplitBtn" onclick="batchSubmitProfitSharing()" disabled>批量提交微信分账</button></div>` : ''}
      </div>
      <div id="merchantSplitTable"></div>
    </div>`;
  applyNavPermissions();
  fetchMerchantsTable();
  fetchMerchantSplits();
}

async function fetchMerchantsTable() {
  const table = document.getElementById('merchantTable');
  if (!table) return;
  try {
    const list = await api('/api/v2/ops/admin/merchants', 'GET');
    if (!list || !list.length) {
      table.innerHTML = emptyStateHtml('暂无商户', '点击「新增商户」创建加盟商/直营主体', 'loadMerchantsPage()');
      return;
    }
    table.innerHTML = selWrap('merchants', `<table class="table"><thead><tr>
      ${selHeaderCell('merchants')}
      <th>商户ID</th><th>名称</th><th>平台抽成</th><th>设备数</th><th>状态</th><th>操作</th>
    </tr></thead><tbody>${list.map(m => `
      ${selRowOpen('merchants', m.merchantId)}
      ${selCheckCell('merchants', m.merchantId)}
      <td><code>${esc(m.merchantId)}</code></td>
      <td>${esc(m.merchantName)}</td>
      <td>${(m.platformRateBps / 100).toFixed(1)}%</td>
      <td>${esc(m.deviceCount)}</td>
      <td>${esc(merchantStatusLabel(m.status))}</td>
      <td onclick="event.stopPropagation()">${hasPerm('ops:merchant:edit') ? `<button class="btn-ghost btn-sm" onclick='showMerchantForm(${JSON.stringify(m)})'>编辑</button>` : '-'}</td>
    </tr>`).join('')}</tbody></table>`);
    selSync('merchants');
  } catch (e) {
    pageRenderError(table, e, false);
  }
}

function showMerchantForm(merchant) {
  const isEdit = !!merchant;
  openOpsModal(`
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal" onclick="event.stopPropagation()">
        <h3>${isEdit ? '编辑商户' : '新增商户'}</h3>
        <label>商户ID</label>
        <input id="mfId" value="${isEdit ? escAttr(merchant.merchantId) : ''}" ${isEdit ? 'disabled' : ''} placeholder="MCH-001">
        <label>商户名称</label>
        <input id="mfName" value="${isEdit ? escAttr(merchant.merchantName) : ''}">
        <label>联系电话</label>
        <input id="mfPhone" value="${isEdit ? escAttr(merchant.contactPhone || '') : ''}">
        <label>平台抽成（基点，1000=10%）</label>
        <input id="mfRate" type="number" min="0" max="10000" value="${isEdit ? escAttr(merchant.platformRateBps) : '1000'}">
        <label>微信分账接收方 ID（可选）</label>
        <input id="mfWx" value="${isEdit ? escAttr(merchant.wechatReceiverId || '') : ''}">
        <label>备注</label>
        <input id="mfRemark" value="${isEdit ? escAttr(merchant.remark || '') : ''}">
        <div class="filters" style="margin-top:12px">
          <button type="button" class="btn-primary" onclick="saveMerchant(event, ${isEdit})">保存</button>
          <button type="button" class="btn-ghost" data-modal-cancel onclick="closeModal()">取消</button>
        </div>
      </div>
    </div>`);
}

async function saveMerchant(ev, isEdit) {
  await withSaveGuard(ev, async () => {
  const body = {
    merchantId: document.getElementById('mfId').value.trim(),
    merchantName: document.getElementById('mfName').value.trim(),
    contactPhone: document.getElementById('mfPhone').value.trim(),
    platformRateBps: parseInt(document.getElementById('mfRate').value, 10) || 1000,
    wechatReceiverId: document.getElementById('mfWx').value.trim(),
    remark: document.getElementById('mfRemark').value.trim(),
    status: 'ACTIVE'
  };
  if (!body.merchantId || !body.merchantName) {
    toast('请填写商户 ID 和名称', 'err');
    return;
  }
  try {
    await api('/api/v2/ops/admin/merchants', 'POST', body);
    closeModal();
    toast('保存成功', 'ok');
    loadMerchantsPage();
  } catch (e) {
    if (!handleAuthFailure(e)) toast('保存失败: ' + e.message, 'err');
  }
  });
}

function searchMerchantSplits() {
  merchantSplitFilters.merchantId = document.getElementById('msMerchant').value.trim();
  merchantSplitFilters.status = document.getElementById('msStatus').value;
  merchantSplitFilters.page = 0;
  selClear('merchantSplits');
  fetchMerchantSplits();
}

function updateBatchSplitButton() {
  const btn = document.getElementById('batchSplitBtn');
  if (!btn) return;
  const count = selSelected('merchantSplits').length;
  btn.disabled = count === 0;
  btn.textContent = count ? `批量提交微信分账 (${count})` : '批量提交微信分账';
}

async function fetchMerchantSplits() {
  const table = document.getElementById('merchantSplitTable');
  if (!table) return;
  showTableLoading(table, 8, 6);
  try {
    const q = new URLSearchParams({
      page: merchantSplitFilters.page,
      size: merchantSplitFilters.size,
      ...(merchantSplitFilters.merchantId ? { merchantId: merchantSplitFilters.merchantId } : {}),
      ...(merchantSplitFilters.status ? { status: merchantSplitFilters.status } : {})
    });
    const data = await api('/api/v2/ops/admin/merchants/revenue-splits?' + q, 'GET');
    if (!data.items.length) {
      table.innerHTML = emptyStateHtml('暂无分账记录', merchantSplitFilters.status === 'PENDING'
        ? '没有待处理的分账，订单结算后会自动记账'
        : '订单结算后会按设备所属商户自动记账', 'fetchMerchantSplits()');
      updateBatchSplitButton();
      return;
    }
    table.innerHTML = selWrap('merchantSplits', `<table><thead><tr>
      ${selHeaderCell('merchantSplits')}
      <th>分账ID</th><th>订单</th><th>商户</th><th>设备</th><th>总额</th><th>平台</th><th>商户收入</th><th>状态</th><th>时间</th><th>操作</th>
    </tr></thead><tbody>${data.items.map(s => {
      const submittable = canSubmitProfitSharing(s);
      const refreshable = canRefreshProfitSharing(s);
      return `
      ${selRowOpen('merchantSplits', s.splitId)}
      ${selCheckCell('merchantSplits', s.splitId)}
      <td><code>${esc(s.splitId)}</code></td>
      <td>${esc(s.orderId)}</td>
      <td>${esc(s.merchantName || s.merchantId)}</td>
      <td>${esc(s.deviceId)}</td>
      <td>${fmtMoney(s.grossCents)}</td>
      <td>${fmtMoney(s.platformCents)}</td>
      <td>${fmtMoney(s.merchantCents)}</td>
      <td>${splitStatusBadge(s.status)}${s.failureReason ? ` <span class="meta" title="${escAttr(s.failureReason)}">!</span>` : ''}</td>
      <td>${fmtTime(s.createdAt)}</td>
      <td onclick="event.stopPropagation()">${[
        submittable ? `<button type="button" class="btn-ghost btn-sm" onclick="showWeChatSubmitForm('${escAttr(s.splitId)}', '${escAttr(s.wechatTransactionId || '')}')">提交</button>` : '',
        refreshable ? `<button type="button" class="btn-ghost btn-sm" onclick="refreshWeChatProfitSharing('${escAttr(s.splitId)}')">刷新</button>` : ''
      ].filter(Boolean).join(' ') || '-'}</td>
    </tr>`;
    }).join('')}</tbody></table>`)
    + renderMerchantSplitPagination(data);
    selSync('merchantSplits');
    updateBatchSplitButton();
  } catch (e) {
    pageRenderError(table, e, false);
  }
}

function canSubmitProfitSharing(split) {
  if (!hasPerm('ops:merchant:split')) return false;
  const st = (split.status || '').toUpperCase();
  return st === 'ACCRUED' || st === 'LEDGER_ONLY' || st === 'WECHAT_FAILED' || st === 'FAILED';
}

function canRefreshProfitSharing(split) {
  if (!hasPerm('ops:merchant:split')) return false;
  const st = (split.status || '').toUpperCase();
  return (st === 'WECHAT_SUBMITTED' || st === 'WECHAT_FAILED')
      && !!(split.wechatOutOrderNo && split.wechatTransactionId);
}

function showWeChatSubmitForm(splitId, defaultTxn) {
  openOpsModal(`
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal" onclick="event.stopPropagation()">
        <h3>提交微信分账</h3>
        <p class="meta">分账ID <code>${esc(splitId)}</code></p>
        <label>微信交易单号 wxTransactionId</label>
        <input id="wxTxnId" value="${escAttr(defaultTxn || '')}" placeholder="余额支付订单需手动填写">
        <p class="meta">购物订单为余额支付时，需填写对应微信充值/支付流水号。</p>
        <div class="filters" style="margin-top:12px">
          <button type="button" class="btn-primary" onclick="submitWeChatProfitSharing(event, '${escAttr(splitId)}')">提交</button>
          <button type="button" class="btn-ghost" data-modal-cancel onclick="closeModal()">取消</button>
        </div>
      </div>
    </div>`);
}

async function submitWeChatProfitSharing(ev, splitId) {
  await withSaveGuard(ev, async () => {
  const wxTxn = document.getElementById('wxTxnId')?.value.trim() || '';
  try {
    await api('/api/v2/ops/admin/merchants/revenue-splits/' + encodeURIComponent(splitId) + '/wechat-submit', 'POST',
      wxTxn ? { wxTransactionId: wxTxn } : {});
    closeModal();
    toast('分账已提交', 'ok');
    fetchMerchantSplits();
  } catch (e) {
    if (!handleAuthFailure(e)) toast('提交失败: ' + e.message, 'err');
  }
  }, '提交中…');
}

async function refreshWeChatProfitSharing(splitId) {
  try {
    await api('/api/v2/ops/admin/merchants/revenue-splits/' + encodeURIComponent(splitId) + '/wechat-refresh', 'POST', {});
    toast('状态已刷新', 'ok');
    fetchMerchantSplits();
  } catch (e) {
    if (!handleAuthFailure(e)) toast('刷新失败: ' + e.message, 'err');
  }
}

async function batchSubmitProfitSharing() {
  const ids = selSelected('merchantSplits');
  if (!ids.length) return;
  if (!await showConfirm(`确认批量提交 ${ids.length} 笔微信分账？\n已有 wxTransactionId 的记录将自动提交；缺少流水号的会跳过。`, { title: '批量分账' })) return;
  const btn = document.getElementById('batchSplitBtn');
  if (btn) btn.disabled = true;
  let ok = 0;
  let skip = 0;
  const failed = [];
  for (const splitId of ids) {
    try {
      await api('/api/v2/ops/admin/merchants/revenue-splits/' + encodeURIComponent(splitId) + '/wechat-submit', 'POST', {});
      ok += 1;
      selToggle('merchantSplits', splitId, false);
    } catch (e) {
      if (handleAuthFailure(e)) break;
      const msg = e.message || String(e);
      if (/wxTransactionId|流水|余额支付/i.test(msg)) skip += 1;
      else failed.push(`${splitId}: ${msg}`);
    }
  }
  const parts = [`成功 ${ok} 笔`];
  if (skip) parts.push(`跳过 ${skip} 笔（缺流水号）`);
  if (failed.length) parts.push(`失败 ${failed.length} 笔`);
  toast(parts.join('，'), failed.length ? 'err' : 'ok');
  if (failed.length) console.warn('批量分账失败:', failed);
  fetchMerchantSplits();
}

async function exportMerchantSplits() {
  try {
    const base = (import.meta.env.VITE_API_BASE || '').replace(/\/$/, '') || window.location.origin;
    const q = new URLSearchParams({
      ...(merchantSplitFilters.merchantId ? { merchantId: merchantSplitFilters.merchantId } : {}),
      ...(merchantSplitFilters.status ? { status: merchantSplitFilters.status } : {})
    });
    const res = await fetch(base + '/api/v2/ops/admin/merchants/revenue-splits/export?' + q, {
      headers: { Authorization: 'Bearer ' + localStorage.getItem('admin_token') }
    });
    if (!res.ok) throw new Error('导出失败');
    const blob = await res.blob();
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'revenue-splits.csv';
    a.click();
    URL.revokeObjectURL(url);
  } catch (e) {
    toast('导出失败: ' + e.message, 'err');
  }
}

async function loadWarehousePage() {
  const el = document.getElementById('pageContent');
  const page = 'warehouse';
  const transitDevice = window._transitDeviceFilter || '';
  try {
    const transitPath = transitDevice
      ? '/api/v2/ops/admin/warehouse/in-transit?deviceId=' + encodeURIComponent(transitDevice)
      : '/api/v2/ops/admin/warehouse/in-transit';
    const [warehouses, inventory, outbounds, skus, inTransit] = await Promise.all([
      api('/api/v2/ops/admin/warehouse/list', 'GET'),
      api('/api/v2/ops/admin/warehouse/inventory', 'GET'),
      api('/api/v2/ops/admin/warehouse/outbounds', 'GET'),
      api('/api/v2/ops/admin/skus', 'GET').catch(() => []),
      api(transitPath, 'GET').catch(() => [])
    ]);
    if (!isCurrentPage(page)) return;
    const skuById = Object.fromEntries((skus || []).map(s => [s.skuId, s]));
    const skuLabel = (id) => skuById[id]?.skuName || id;
    const wh = (warehouses || [])[0];
    const invRows = (inventory || []).map(i => `<tr>
      <td><code>${esc(i.batchNo)}</code></td>
      <td>${esc(skuLabel(i.skuId))}</td>
      <td>${esc(i.quantity)}</td>
      <td>${esc(i.expiryDate || '-')}</td>
    </tr>`).join('');
    const outRows = (outbounds || []).slice(0, 10).map(o => {
      const lineSummary = (o.lines || []).map(l =>
        `${esc(skuLabel(l.skuId))}×${l.quantity}@${esc(l.batchNo)}→${esc(l.deviceId || '-')}`).join('<br>');
      return `<tr>
        <td>#${o.outboundId}</td><td>${esc(o.status)}</td><td>${o.routeId || '-'}</td>
        <td>${lineSummary || '-'}</td>
        <td onclick="event.stopPropagation()">${hasPerm('ops:replenishment:edit') && o.status !== 'SHIPPED'
          ? `<button class="btn-ghost btn-sm" onclick="pickWarehouseOutbound(${o.outboundId})">拣货</button>
             <button class="btn-ok btn-sm" onclick="shipWarehouseOutbound(${o.outboundId})">出库</button>` : '-'}</td>
      </tr>`;
    }).join('');
    const now = Date.now();
    const transitRows = (inTransit || []).map(t => {
      const ageH = t.createdAt ? Math.round((now - new Date(t.createdAt).getTime()) / 3600000) : 0;
      const overdue = ageH >= 24;
      return `<tr class="${overdue ? 'warn-row' : ''}">
        <td><code>${esc(t.deviceId)}</code></td>
        <td>${esc(skuLabel(t.skuId))}</td>
        <td><code>${esc(t.batchNo)}</code></td>
        <td>${t.quantity}</td>
        <td>#${t.outboundId}</td>
        <td>${fmtTime(t.createdAt)}${overdue ? ' <span class="warn-text">超24h</span>' : ''}</td>
      </tr>`;
    }).join('');
    el.innerHTML = `
      <div class="filters">
        ${permButton('replenish.edit', '仓库入库', 'showWarehouseInboundForm()', 'btn-primary btn-sm')}
        ${refreshButton('loadWarehousePage()')}
      </div>
      <p class="meta">${wh ? `当前仓库：${esc(wh.warehouseName)} (${esc(wh.warehouseId)})` : '暂无仓库'}</p>
      <h3>仓库批次库存</h3>
      ${(inventory || []).length
        ? `<table class="table"><thead><tr><th>批次</th><th>商品</th><th>数量</th><th>到期</th></tr></thead><tbody>${invRows}</tbody></table>`
        : emptyStateHtml('仓库无库存', '点击「仓库入库」添加批次', 'loadWarehousePage()')}
      <h3>出库单（FEFO 拣货）</h3>
      ${(outbounds || []).length
        ? `<table class="table"><thead><tr><th>ID</th><th>状态</th><th>路线</th><th>明细</th><th>操作</th></tr></thead><tbody>${outRows}</tbody></table>`
        : '<p class="meta">规划补货路线后自动生成出库单</p>'}
      <h3 style="margin-top:24px">在途库存（发往柜机，未签收）</h3>
      <div class="filters">
        <div><label>柜机筛选</label><input id="transitDeviceFilter" value="${escAttr(transitDevice)}" placeholder="留空=全部"></div>
        <button type="button" class="btn-ghost btn-sm" onclick="filterInTransit()">查询</button>
      </div>
      ${(inTransit || []).length
        ? `<table class="table"><thead><tr><th>柜机</th><th>SKU</th><th>批次</th><th>数量</th><th>出库单</th><th>发运时间</th></tr></thead><tbody>${transitRows}</tbody></table>`
        : emptyStateHtml('无在途库存', '出库发运后、补货签收前会显示在此', 'loadWarehousePage()')}`;
    applyNavPermissions();
  } catch (e) {
    if (!isCurrentPage(page)) return;
    opsRenderError(el, e);
  }
}

function filterInTransit() {
  window._transitDeviceFilter = document.getElementById('transitDeviceFilter')?.value?.trim() || '';
  loadWarehousePage();
}

async function showWarehouseInboundForm() {
  const skus = await api('/api/v2/ops/admin/skus', 'GET').catch(() => []);
  const skuOptions = (skus || []).filter(s => s.status === 'ACTIVE').map(s =>
    `<option value="${escAttr(s.skuId)}">${esc(s.skuName)}</option>`).join('');
  const today = new Date().toISOString().slice(0, 10);
  const expiry = new Date(Date.now() + 30 * 86400000).toISOString().slice(0, 10);
  openOpsModal(`
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal" style="max-width:560px" onclick="event.stopPropagation()">
        <h3>仓库入库</h3>
        <label>SKU</label><select id="whInSku">${skuOptions}</select>
        <label>批次号</label><input id="whInBatch" placeholder="B-WH-001">
        <div class="filters form-grid">
          <div><label>数量</label><input id="whInQty" type="number" min="1" value="10"></div>
          <div><label>到期日</label><input id="whInExpiry" type="date" value="${expiry}"></div>
        </div>
        <label>生产日期</label><input id="whInProd" type="date" value="${today}">
        <div class="filters" style="margin-top:12px">
          <button type="button" class="btn-primary" onclick="saveWarehouseInbound(event)">确认入库</button>
          <button type="button" class="btn-ghost" data-modal-cancel onclick="closeModal()">取消</button>
        </div>
      </div>
    </div>`);
}

async function saveWarehouseInbound(ev) {
  if (ev) ev.preventDefault();
  const skuId = document.getElementById('whInSku')?.value;
  const batchNo = document.getElementById('whInBatch')?.value?.trim();
  const quantity = parseInt(document.getElementById('whInQty')?.value, 10);
  const expiryDate = document.getElementById('whInExpiry')?.value;
  const productionDate = document.getElementById('whInProd')?.value;
  if (!skuId || !batchNo || !quantity || !expiryDate) { toast('请填写完整', 'err'); return; }
  try {
    await api('/api/v2/ops/admin/warehouse/inbound', 'POST', {
      warehouseId: 'WH-DEMO-001',
      refNo: 'IN-' + Date.now(),
      lines: [{ skuId, batchNo, quantity, expiryDate, productionDate }]
    });
    closeModal();
    toast('入库成功', 'ok');
    loadWarehousePage();
  } catch (e) {
    if (!handleAuthFailure(e)) toast('入库失败: ' + e.message, 'err');
  }
}

async function pickWarehouseOutbound(outboundId) {
  try {
    await api('/api/v2/ops/admin/warehouse/outbounds/' + outboundId + '/pick', 'POST');
    toast('已标记拣货', 'ok');
    loadWarehousePage();
  } catch (e) {
    if (!handleAuthFailure(e)) toast('操作失败: ' + e.message, 'err');
  }
}

async function shipWarehouseOutbound(outboundId) {
  if (!await showConfirm('确认出库？将扣减仓库库存。', { title: '出库确认' })) return;
  try {
    await api('/api/v2/ops/admin/warehouse/outbounds/' + outboundId + '/ship', 'POST');
    toast('出库完成', 'ok');
    loadWarehousePage();
  } catch (e) {
    if (!handleAuthFailure(e)) toast('出库失败: ' + e.message, 'err');
  }
}

adminRuntime.opsLoaders = {
  sla: loadSlaPage,
  ota: loadOtaPage,
  risk: loadRiskPage,
  reconciliation: loadReconciliationPage,
  replenishment: loadReplenishmentPage,
  warehouse: loadWarehousePage,
  rbac: loadRbacPage,
  visionMappings: loadVisionMappingsPage,
  uploadQueue: loadUploadQueuePage,
  merchants: loadMerchantsPage
};

Object.assign(window, {
  loadSlaPage,
  loadOtaPage,
  loadRiskPage,
  showBlacklistForm,
  saveBlacklist,
  removeBlacklist,
  loadReconciliationPage,
  loadReplenishmentPage,
  showInventoryForm,
  showSkuStocktakeForm,
  saveSkuStocktake,
  showWriteOffForm,
  saveWriteOff,
  saveInventory,
  completeReplenishmentTask,
  loadRbacPage,
  showOtaPublishForm,
  publishOta,
  fetchReconciliationList,
  runReconToday,
  showReconDetail,
  showReplenishmentPlanForm,
  saveReplenishmentPlan,
  getSelectedReplenishmentDevices,
  toggleAllReplenishmentDevices,
  switchRbacTab,
  selectRbacRole,
  saveRolePermissions,
  searchRbacOperators,
  debouncedSearchRbacOperators,
  selectRbacUser,
  saveUserRoles,
  saveUserMerchants,
  setRbacRecentScope,
  fetchRbacRecent,
  onPermCheckChange,
  openRbacUserAssign,
  renderAuditTableHtml,
  formatOperatorCell,
  loadVisionMappingsPage,
  saveYoloMapping,
  deleteYoloMapping,
  saveAliyunMapping,
  deleteAliyunMapping,
  loadUploadQueuePage,
  searchUploadQueue,
  fetchUploadQueue,
  loadMerchantsPage,
  showMerchantForm,
  saveMerchant,
  searchMerchantSplits,
  fetchMerchantSplits,
  exportMerchantSplits,
  showWeChatSubmitForm,
  submitWeChatProfitSharing,
  batchSubmitProfitSharing,
  toggleReplenishmentLowStock,
  planRouteFromLowStock
});

document.addEventListener('selchange', (e) => {
  if (e.detail?.scope === 'merchantSplits') updateBatchSplitButton();
});

window.merchantSplitFilters = merchantSplitFilters;
window.uploadQueueFilters = uploadQueueFilters;
window.replenishmentFilters = replenishmentFilters;
window.showReplenishmentLinesForm = showReplenishmentLinesForm;
window.addReplenishmentLineRow = addReplenishmentLineRow;
window.saveReplenishmentLines = saveReplenishmentLines;
window.viewDeviceLots = viewDeviceLots;
window.loadWarehousePage = loadWarehousePage;
window.filterInTransit = filterInTransit;
window.refreshWeChatProfitSharing = refreshWeChatProfitSharing;
window.showWarehouseInboundForm = showWarehouseInboundForm;
window.saveWarehouseInbound = saveWarehouseInbound;
window.pickWarehouseOutbound = pickWarehouseOutbound;
window.shipWarehouseOutbound = shipWarehouseOutbound;
