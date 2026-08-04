<template>
  <div v-loading="loading" class="workbench">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="page-card-head">
          <div class="page-card-head__meta">
            <div class="page-card-head__title">
              <span class="title">运营工作台</span>
              <span class="hint">今日快照与待办；补货开门请在「补货调度」或补货员小程序操作</span>
            </div>
          </div>
          <div class="page-card-head__actions">
            <el-button
              v-hasPermi="['ops:replenishment:list']"
              type="primary"
              plain
              @click="goPath('/replenishment')"
            >
              补货调度
            </el-button>
            <el-button v-hasPermi="['ops:dispute']" plain @click="goPath('/disputes')">争议审核</el-button>
            <el-button v-hasPermi="['ops:device:list']" plain @click="goPath('/devices')">设备管理</el-button>
            <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
          </div>
        </div>
      </template>

      <el-row :gutter="12" class="stats-row">
        <el-col :xs="12" :sm="6">
          <div
            class="stat-tile"
            :class="{ 'is-clickable': canAccessPath('/devices') }"
            :role="canAccessPath('/devices') ? 'button' : undefined"
            :tabindex="canAccessPath('/devices') ? 0 : undefined"
            @click="goPath('/devices', { salesLocked: 'false' })"
            @keydown.enter="goPath('/devices', { salesLocked: 'false' })"
          >
            <div class="stat-label">在售货柜</div>
            <div class="stat-value">{{ workbench?.devicesOnSale ?? '无' }}</div>
            <div class="stat-hint">
              停售 {{ workbench?.devicesSalesLocked ?? 0 }}
              <template v-if="canAccessPath('/devices')"> · 查看设备</template>
            </div>
          </div>
        </el-col>
        <el-col :xs="12" :sm="6">
          <div
            class="stat-tile"
            :class="{ 'is-clickable': canAccessPath('/devices') }"
            :role="canAccessPath('/devices') ? 'button' : undefined"
            :tabindex="canAccessPath('/devices') ? 0 : undefined"
            @click="goDevicesByOnlineRate"
            @keydown.enter="goDevicesByOnlineRate"
          >
            <div class="stat-label">设备在线率</div>
            <div class="stat-value">{{ onlineRate.toFixed(1) }}%</div>
            <div class="stat-hint">
              {{ stats.deviceOnline || 0 }} / {{ stats.deviceTotal || 0 }} 台
              <template v-if="canAccessPath('/devices')">
                · {{ (workbench?.offlineDevices || 0) > 0 ? '查看离线' : '查看设备' }}
              </template>
            </div>
          </div>
        </el-col>
        <el-col :xs="12" :sm="6">
          <div
            class="stat-tile"
            :class="{ 'is-clickable': canAccessPath('/finance') }"
            :role="canAccessPath('/finance') ? 'button' : undefined"
            :tabindex="canAccessPath('/finance') ? 0 : undefined"
            @click="goPath('/finance')"
            @keydown.enter="goPath('/finance')"
          >
            <div class="stat-label">今日营收</div>
            <div class="stat-value">¥{{ ((stats.revenueTodayCents || 0) / 100).toFixed(2) }}</div>
            <div class="stat-hint">{{ canAccessPath('/finance') ? '查看财务毛利' : '今日快照' }}</div>
          </div>
        </el-col>
        <el-col :xs="12" :sm="6">
          <div
            class="stat-tile"
            :class="{
              'is-clickable': canAccessPath('/exceptions'),
              warn: openExceptionCount > 0
            }"
            :role="canAccessPath('/exceptions') ? 'button' : undefined"
            :tabindex="canAccessPath('/exceptions') ? 0 : undefined"
            @click="goExceptions"
            @keydown.enter="goExceptions"
          >
            <div class="stat-label">待处理异常</div>
            <div class="stat-value">{{ openExceptionCount }}</div>
            <div class="stat-hint">
              <template v-if="canAccessPath('/exceptions')">
                {{ openExceptionCount ? '进入异常中心' : (totalIssues ? `其它待办 ${totalIssues}` : '运行正常') }}
              </template>
              <template v-else>{{ totalIssues ? `其它待办 ${totalIssues}` : '运行正常' }}</template>
            </div>
          </div>
        </el-col>
      </el-row>
    </el-card>

    <el-card class="page-card queue-card" shadow="never">
      <template #header>
        <div class="page-card-head">
          <div class="page-card-head__meta">
            <div class="page-card-head__title">
              <span class="title">异常优先队列</span>
              <span class="hint">有待办的入口优先展示；下方列表按严重程度排序，点「处理」直达</span>
            </div>
          </div>
          <div class="page-card-head__actions">
            <el-checkbox v-model="showZeroLinks">显示无待办入口</el-checkbox>
          </div>
        </div>
      </template>

      <el-row :gutter="10" class="quick-row">
        <el-col
          v-for="item in visibleQuickLinks"
          :key="item.label"
          :xs="12"
          :sm="8"
          :md="6"
          :lg="4"
          :xl="3"
        >
          <button
            type="button"
            class="quick-tile"
            :class="{ warn: item.count > 0, muted: item.count === 0 }"
            @click="goQuick(item)"
          >
            <span class="quick-label">{{ item.label }}</span>
            <span class="quick-value">{{ item.count }}</span>
          </button>
        </el-col>
      </el-row>
      <p v-if="!visibleQuickLinks.length" class="empty-quick">
        {{ totalIssues > 0 ? '有待办但当前账号无对应入口权限' : '当前无待办入口，勾选「显示无待办入口」可查看全部' }}
      </p>

      <div class="table-toolbar">
        <span class="table-meta">待处理明细 {{ sortedActions.length }} 条</span>
        <el-radio-group v-model="severityFilter" size="small">
          <el-radio-button value="all">全部</el-radio-button>
          <el-radio-button value="urgent">仅紧急</el-radio-button>
        </el-radio-group>
      </div>

      <div class="table-scroll">
        <div class="table-scroll-inner">
          <el-table
            class="action-table"
            :data="pagedActions"
            stripe
            border
            :empty-text="filteredActions.length ? '暂无明细项' : '运行正常，暂无待处理异常'"
          >
            <template #empty>
              <el-empty
                :description="filteredActions.length ? '暂无明细项' : '运行正常，暂无待处理异常'"
                :image-size="72"
              />
            </template>
            <el-table-column label="优先级" width="92" align="center">
              <template #default="{ row }">
                <el-tag :type="priority(row.severity).tag" effect="light" size="small">
                  {{ priority(row.severity).label }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="类型" width="110" align="center">
              <template #default="{ row }">{{ typeLabel(row.type) }}</template>
            </el-table-column>
            <el-table-column prop="title" label="标题" min-width="140" show-overflow-tooltip align="center" class-name="col-text" />
            <el-table-column label="关联" min-width="160" show-overflow-tooltip align="center" class-name="col-text">
              <template #default="{ row }">{{ contextLabel(row) }}</template>
            </el-table-column>
            <el-table-column prop="detail" label="详情" min-width="220" show-overflow-tooltip align="center" class-name="col-text" />
            <el-table-column label="操作" width="88" class-name="col-action" align="center" fixed="right">
              <template #default="{ row }">
                <TableActions
                  v-if="canHandleAction(row)"
                  :actions="[{ key: 'handle', label: '处理', icon: Right, type: 'primary' }]"
                  @action="() => goAction(row)"
                />
                <span v-else class="no-perm">—</span>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </div>
      <div v-if="filteredActions.length > pageSize" class="page-pager">
        <el-pagination
          v-model:current-page="page"
          :page-size="pageSize"
          :total="filteredActions.length"
          layout="total, prev, pager, next"
          background
        />
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { Refresh, Right } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';
import TableActions from '@/components/TableActions.vue';
import { useNavAccess } from '@/composables/useNavAccess';
import type { OpsWorkbench, PageResult } from '@aicabinet/shared-types';

interface OpsStats {
  deviceTotal?: number;
  deviceOnline?: number;
  revenueTodayCents?: number;
  sessionActive?: number;
  sessionWaitingUpload?: number;
  lowStockSkuCount?: number;
  nearExpiryLotCount?: number;
}

interface OpsActionItem {
  type: string;
  severity?: string;
  title: string;
  detail?: string;
  deviceId?: string;
  sessionId?: string;
  ticketId?: string;
  skuId?: string;
  taskId?: number;
}

interface QuickLink {
  label: string;
  count: number;
  path: string;
  query?: Record<string, string | undefined>;
}

const { router, canAccessPath, goPath } = useNavAccess();
const loading = ref(false);
const stats = ref<OpsStats>({});
const workbench = ref<OpsWorkbench | null>(null);
const openExceptionCount = ref(0);
const showZeroLinks = ref(false);
const severityFilter = ref<'all' | 'urgent'>('all');
const page = ref(1);
const pageSize = 10;

const quickLinks = computed<QuickLink[]>(() => [
  {
    label: '异常中心',
    count: openExceptionCount.value,
    path: '/exceptions',
    query: { status: 'OPEN' }
  },
  { label: '待审争议', count: workbench.value?.openDisputes || 0, path: '/disputes', query: { status: 'OPEN' } },
  {
    label: '超时待支付',
    count: workbench.value?.pendingUnpaidOrders || 0,
    path: '/orders',
    query: { status: 'PENDING', overdue: '1' }
  },
  {
    label: '停售货柜',
    count: workbench.value?.devicesSalesLocked || 0,
    path: '/devices',
    query: { salesLocked: 'true' }
  },
  {
    label: '离线设备',
    count: workbench.value?.offlineDevices || 0,
    path: '/devices',
    query: { online: 'OFFLINE' }
  },
  {
    label: '待上传',
    count: workbench.value?.waitingUploads || stats.value.sessionWaitingUpload || 0,
    path: '/upload-queue',
    query: { stuck: '1' }
  },
          {
            label: '缺货柜/SKU',
            count: stats.value.lowStockSkuCount || workbench.value?.lowStockItems || 0,
            path: '/stock-health',
            query: { dimension: 'LOW' }
          },
          {
            label: '临期批次',
            count: stats.value.nearExpiryLotCount || 0,
            path: '/stock-health',
            query: { dimension: 'NEAR_EXPIRY' }
          },
          {
            label: '补货任务',
            count: workbench.value?.pendingReplenishments || 0,
            path: '/replenishment',
            query: { tab: 'routes' }
          },
  {
    label: '异常会话',
    count: workbench.value?.staleSessions || 0,
    path: '/sessions',
    query: { stuck: '1' }
  },
  {
    label: '对账差异',
    count: workbench.value?.reconciliationMismatches || 0,
    path: '/reconciliation',
    query: { status: 'MISMATCH' }
  },
  {
    label: '分账异常',
    count: workbench.value?.splitExceptions || 0,
    path: '/merchants',
    query: { tab: 'splits' }
  },
  {
    label: '签收超时',
    count: workbench.value?.inTransitOverdue || 0,
    path: '/warehouse',
    query: { tab: 'transit', overdue: '1' }
  }
]);

const accessibleQuickLinks = computed(() =>
  quickLinks.value.filter((item) => canAccessPath(item.path))
);

const visibleQuickLinks = computed(() => {
  const list = showZeroLinks.value
    ? accessibleQuickLinks.value
    : accessibleQuickLinks.value.filter((item) => item.count > 0);
  return [...list].sort((a, b) => b.count - a.count);
});

function actionTargetPath(row: OpsActionItem) {
  switch (row.type) {
    case 'DISPUTE':
      return '/disputes';
    case 'UPLOAD_STUCK':
      return '/upload-queue';
    case 'SESSION_STALE':
      return '/sessions';
    case 'LOW_STOCK':
    case 'REPLENISHMENT':
      return '/replenishment';
    case 'IN_TRANSIT_OVERDUE':
      return '/warehouse';
    case 'RECON_MISMATCH':
    case 'RECONCILIATION_MISMATCH':
      return '/reconciliation';
    case 'SPLIT_EXCEPTION':
      return '/merchants';
    case 'DEVICE_OFFLINE':
    default:
      return '/devices';
  }
}

function canHandleAction(row: OpsActionItem) {
  return canAccessPath(actionTargetPath(row));
}

const onlineRate = computed(() =>
  stats.value.deviceTotal ? ((stats.value.deviceOnline || 0) / stats.value.deviceTotal) * 100 : 0
);

const totalIssues = computed(() =>
  accessibleQuickLinks.value.reduce((sum, item) => sum + item.count, 0)
);

const sortedActions = computed(() => {
  const items = [...(workbench.value?.actionItems || [])];
  return items.sort((a, b) => {
    const diff = priority(b.severity).score - priority(a.severity).score;
    if (diff !== 0) return diff;
    return (a.title || '').localeCompare(b.title || '');
  });
});

const filteredActions = computed(() => {
  if (severityFilter.value !== 'urgent') return sortedActions.value;
  return sortedActions.value.filter((row) => priority(row.severity).score >= 3);
});

const pagedActions = computed(() => {
  const start = (page.value - 1) * pageSize;
  return filteredActions.value.slice(start, start + pageSize);
});

watch(severityFilter, () => {
  page.value = 1;
});
watch(filteredActions, (list) => {
  const maxPage = Math.max(1, Math.ceil(list.length / pageSize) || 1);
  if (page.value > maxPage) page.value = maxPage;
});

function priority(severity = '') {
  const s = severity.toUpperCase();
  if (s === 'CRITICAL' || s === 'HIGH') return { score: 3, label: '紧急', tag: 'danger' as const };
  if (s === 'MEDIUM') return { score: 2, label: '较高', tag: 'warning' as const };
  return { score: 1, label: '一般', tag: 'info' as const };
}

function typeLabel(type = '') {
  return (
    {
      DISPUTE: '账单争议',
      DEVICE_OFFLINE: '设备离线',
      UPLOAD_STUCK: '录像滞留',
      SESSION_STALE: '会话超时',
      LOW_STOCK: '库存不足',
      REPLENISHMENT: '补货任务',
      RECON_MISMATCH: '对账差异',
      RECONCILIATION_MISMATCH: '对账差异',
      SPLIT_EXCEPTION: '分账异常',
      IN_TRANSIT_OVERDUE: '签收超时'
    } as Record<string, string>
  )[type] || type;
}

function contextLabel(row: OpsActionItem) {
  const parts: string[] = [];
  if (row.deviceId) parts.push(`设备 ${row.deviceId}`);
  if (row.sessionId) parts.push(`会话 ${shortId(row.sessionId)}`);
  if (row.ticketId) parts.push(`工单 ${shortId(row.ticketId)}`);
  if (row.skuId) parts.push(`SKU ${row.skuId}`);
  return parts.length ? parts.join(' · ') : '无';
}

function shortId(id: string) {
  return id.length > 14 ? `${id.slice(0, 6)}…${id.slice(-4)}` : id;
}

function goQuick(item: QuickLink) {
  const query = item.query
    ? Object.fromEntries(
        Object.entries(item.query).filter((entry): entry is [string, string] => !!entry[1])
      )
    : undefined;
  goPath(item.path, query);
}

function goExceptions() {
  goPath('/exceptions', { status: 'OPEN' });
}

function goDevicesByOnlineRate() {
  const offline = workbench.value?.offlineDevices || 0;
  goPath('/devices', offline > 0 ? { online: 'OFFLINE' } : { online: 'ONLINE' });
}

function queryOf(row: OpsActionItem): Record<string, string> {
  const q: Record<string, string> = {};
  if (row.deviceId) q.deviceId = row.deviceId;
  if (row.sessionId) q.sessionId = row.sessionId;
  if (row.ticketId) q.ticketId = row.ticketId;
  return q;
}

function goAction(row: OpsActionItem) {
  if (!canHandleAction(row)) {
    ElMessage.warning('无访问权限');
    return;
  }
  const q = queryOf(row);
  switch (row.type) {
    case 'DISPUTE':
      router.push({ path: '/disputes', query: { status: 'OPEN', ...q } });
      return;
    case 'UPLOAD_STUCK':
      router.push({ path: '/upload-queue', query: { stuck: '1', ...q } });
      return;
    case 'SESSION_STALE':
      // Do not force SHOPPING — stale scan covers WAITING_UPLOAD / RECOGNIZING / SETTLING.
      router.push({ path: '/sessions', query: { stuck: '1', ...q } });
      return;
    case 'LOW_STOCK':
    case 'REPLENISHMENT':
      router.push({ path: '/replenishment', query: { tab: row.type === 'LOW_STOCK' ? 'shortage' : 'routes', ...q } });
      return;
    case 'IN_TRANSIT_OVERDUE':
      router.push({ path: '/warehouse', query: { tab: 'transit', overdue: '1', ...q } });
      return;
    case 'RECON_MISMATCH':
    case 'RECONCILIATION_MISMATCH':
      router.push({ path: '/reconciliation', query: { status: 'MISMATCH' } });
      return;
    case 'SPLIT_EXCEPTION':
      router.push({ path: '/merchants', query: { tab: 'splits' } });
      return;
    case 'DEVICE_OFFLINE':
      if (row.deviceId) {
        router.push(`/devices/${encodeURIComponent(row.deviceId)}`);
        return;
      }
      router.push({ path: '/devices', query: { online: 'OFFLINE' } });
      return;
    default:
      if (row.deviceId) router.push(`/devices/${encodeURIComponent(row.deviceId)}`);
  }
}

async function fetchWorkbenchBundle() {
  // 窄权限角色可能对 stats/workbench 403；勿互相拖垮
  const [s, wb, ex] = await Promise.all([
    api.request<OpsStats>('/api/v2/ops/admin/stats', 'GET').catch(() => null),
    api.request<OpsWorkbench>('/api/v2/ops/admin/workbench', 'GET').catch(() => null),
    canAccessPath('/exceptions')
      ? api
          .request<PageResult<{ exceptionId: string }>>('/api/v2/ops/admin/exceptions?status=OPEN&page=0&size=1', 'GET')
          .catch(() => null)
      : Promise.resolve(null)
  ]);
  return { s, wb, ex };
}

async function load(opts?: { silent?: boolean }) {
  loading.value = true;
  try {
    let { s, wb, ex } = await fetchWorkbenchBundle();
    // 登录刚进页时偶发 token/网关未就绪，静默重试一次，避免误报「加载失败」
    if (!s && !wb) {
      await new Promise((r) => setTimeout(r, 450));
      ({ s, wb, ex } = await fetchWorkbenchBundle());
    }
    if (!s && !wb) {
      if (!opts?.silent) {
        ElMessage.warning('工作台暂无数据，请稍后点刷新重试');
      }
      stats.value = {};
      workbench.value = null;
      openExceptionCount.value = 0;
      return;
    }
    stats.value = s || {};
    workbench.value = wb;
    openExceptionCount.value = ex?.total || 0;
  } catch (e) {
    if (!opts?.silent) {
      ElMessage.error(e instanceof Error ? e.message : '加载失败');
    }
  } finally {
    loading.value = false;
  }
}

onMounted(() => load({ silent: true }));
</script>

<style scoped>
.workbench {
  min-width: 0;
}
.page-card-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  flex-wrap: wrap;
}
.page-card-head__meta { min-width: 0; }
.page-card-head__title { display: flex; flex-direction: column; gap: 4px; }
.page-card-head__actions { display: flex; gap: 8px; align-items: center; }
.title { font-weight: 600; font-size: 15px; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.4; }
.no-perm { color: var(--el-text-color-placeholder); font-size: 13px; }
.queue-card {
  margin-top: 16px;
}
.stats-row {
  margin-bottom: 4px;
}
.stat-tile {
  background: var(--el-fill-color-light);
  border: 1px solid var(--layout-border);
  border-radius: 10px;
  padding: 14px 16px;
  height: 100%;
  box-sizing: border-box;
}
.stat-tile.is-clickable {
  cursor: pointer;
  transition: border-color 0.15s ease, box-shadow 0.15s ease, transform 0.15s ease;
}
.stat-tile.is-clickable:hover,
.stat-tile.is-clickable:focus-visible {
  transform: translateY(-1px);
  border-color: color-mix(in srgb, var(--app-primary, #0f766e) 45%, var(--layout-border));
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--app-primary, #0f766e) 18%, transparent);
  outline: none;
}
.stat-tile.warn {
  border-color: color-mix(in srgb, #dc2626 35%, var(--layout-border));
}
.stat-tile.warn .stat-value,
.quick-tile.warn .quick-value {
  color: #dc2626;
}
.stat-label {
  font-size: 13px;
  color: var(--layout-muted);
}
.stat-value {
  font-size: 26px;
  font-weight: 700;
  margin-top: 6px;
  line-height: 1.2;
  color: var(--layout-text);
}
.stat-hint {
  font-size: 12px;
  color: var(--layout-muted);
  margin-top: 8px;
}
.quick-row {
  margin-bottom: 4px;
}
.quick-tile {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  width: 100%;
  margin-bottom: 10px;
  padding: 12px 14px;
  border: 1px solid var(--layout-border);
  border-radius: 10px;
  background: var(--el-fill-color-blank, var(--layout-card));
  cursor: pointer;
  text-align: center;
  transition: border-color 0.15s ease, transform 0.15s ease, background 0.15s ease;
}
.quick-tile:hover,
.quick-tile:focus-visible {
  transform: translateY(-1px);
  border-color: color-mix(in srgb, var(--app-primary, #0f766e) 40%, var(--layout-border));
  outline: none;
}
.quick-tile.muted {
  opacity: 0.55;
}
.quick-tile.warn {
  border-color: color-mix(in srgb, #dc2626 28%, var(--layout-border));
  background: color-mix(in srgb, #dc2626 4%, var(--layout-card));
}
.quick-label {
  color: var(--layout-muted);
  font-size: 13px;
}
.quick-value {
  font-size: 24px;
  font-weight: 700;
  margin-top: 6px;
  line-height: 1.15;
  color: var(--layout-text);
}
.empty-quick {
  margin: 0 0 12px;
  color: var(--layout-muted);
  font-size: 13px;
}
.table-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin: 8px 0 12px;
}
.table-meta {
  font-size: 13px;
  color: var(--layout-muted);
}
.action-table :deep(th.col-text > .cell),
.action-table :deep(td.col-text > .cell) {
  text-align: center;
}
.page-pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
</style>
