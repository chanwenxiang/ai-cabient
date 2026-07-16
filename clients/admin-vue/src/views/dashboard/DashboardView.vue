<template>
  <div v-loading="loading">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-head">
          <span class="title">运营工作台</span>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新数据</el-button>
        </div>
      </template>

      <el-row :gutter="12" class="stats-row">
        <el-col :xs="12" :sm="6">
          <div class="stat-tile">
            <div class="stat-label">设备在线率</div>
            <div class="stat-value">{{ onlineRate.toFixed(1) }}%</div>
            <div class="stat-hint">{{ stats.deviceOnline || 0 }} / {{ stats.deviceTotal || 0 }} 台</div>
          </div>
        </el-col>
        <el-col :xs="12" :sm="6">
          <div class="stat-tile">
            <div class="stat-label">今日营收</div>
            <div class="stat-value">¥{{ ((stats.revenueTodayCents || 0) / 100).toFixed(2) }}</div>
            <div class="stat-hint">实时交易汇总</div>
          </div>
        </el-col>
        <el-col :xs="12" :sm="6">
          <div class="stat-tile" :class="{ warn: totalIssues > 0 }">
            <div class="stat-label">待处理异常</div>
            <div class="stat-value">{{ totalIssues }}</div>
            <div class="stat-hint">{{ totalIssues ? '需要尽快处理' : '运行正常' }}</div>
          </div>
        </el-col>
        <el-col :xs="12" :sm="6">
          <div class="stat-tile">
            <div class="stat-label">进行中会话</div>
            <div class="stat-value">{{ stats.sessionActive || 0 }}</div>
            <div class="stat-hint">正在购物或结算</div>
          </div>
        </el-col>
      </el-row>
    </el-card>

    <el-card class="page-card queue-card" shadow="never">
      <template #header>
        <div class="card-head">
          <div>
            <span class="title">异常优先队列</span>
            <div class="header-hint">按严重程度排序，点击卡片或「处理」直达对应页面</div>
          </div>
        </div>
      </template>

      <el-row :gutter="12">
        <el-col v-for="item in quickLinks" :key="item.label" :xs="12" :sm="8" :md="6" :lg="4">
          <el-card shadow="hover" class="quick-card" @click="goQuick(item)">
            <div class="quick-label">{{ item.label }}</div>
            <div class="quick-value" :class="{ warn: item.count > 0 }">{{ item.count }}</div>
          </el-card>
        </el-col>
      </el-row>

      <div class="table-scroll">
        <div class="table-scroll-inner" style="min-width: 900px">
          <el-table
            :data="sortedActions"
            stripe
            border
            style="margin-top: 16px"
            :empty-text="totalIssues ? '暂无明细项' : '当前没有待处理异常'"
          >
        <template #empty>
          <el-empty :description="totalIssues ? '暂无明细项' : '运行正常，暂无待处理异常'" :image-size="72" />
        </template>
        <el-table-column label="优先级" width="92">
          <template #default="{ row }">
            <el-tag :type="priority(row.severity).tag" effect="light" size="small">
              {{ priority(row.severity).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="120">
          <template #default="{ row }">{{ typeLabel(row.type) }}</template>
        </el-table-column>
        <el-table-column prop="title" label="标题" min-width="140" show-overflow-tooltip />
        <el-table-column label="关联" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ contextLabel(row) }}</template>
        </el-table-column>
        <el-table-column prop="detail" label="详情" min-width="220" show-overflow-tooltip />
        <el-table-column label="操作" width="88" class-name="col-action" align="center">
          <template #default="{ row }">
            <TableActions
              :actions="[{ key: 'handle', label: '处理', icon: Right, type: 'primary' }]"
              @action="() => goAction(row)"
            />
          </template>
        </el-table-column>
          </el-table>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { Refresh, Right } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';
import TableActions from '@/components/TableActions.vue';

interface OpsStats {
  deviceTotal?: number;
  deviceOnline?: number;
  revenueTodayCents?: number;
  sessionActive?: number;
  sessionWaitingUpload?: number;
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

interface OpsWorkbench {
  openDisputes?: number;
  offlineDevices?: number;
  waitingUploads?: number;
  lowStockItems?: number;
  pendingReplenishments?: number;
  staleSessions?: number;
  reconciliationMismatches?: number;
  splitExceptions?: number;
  inTransitOverdue?: number;
  actionItems?: OpsActionItem[];
}

interface QuickLink {
  label: string;
  count: number;
  path: string;
  query?: Record<string, string | undefined>;
}

const router = useRouter();
const loading = ref(false);
const stats = ref<OpsStats>({});
const workbench = ref<OpsWorkbench | null>(null);

const quickLinks = computed<QuickLink[]>(() => [
  { label: '待审争议', count: workbench.value?.openDisputes || 0, path: '/disputes', query: { status: 'OPEN' } },
  {
    label: '离线设备',
    count: workbench.value?.offlineDevices || 0,
    path: '/devices',
    query: { online: 'OFFLINE' }
  },
  {
    label: '待上传',
    count: workbench.value?.waitingUploads || stats.value.sessionWaitingUpload || 0,
    path: '/upload-queue'
  },
  { label: '低库存', count: workbench.value?.lowStockItems || 0, path: '/replenishment' },
  {
    label: '补货任务',
    count: workbench.value?.pendingReplenishments || 0,
    path: '/replenishment',
    query: { tab: 'routes' }
  },
  { label: '异常会话', count: workbench.value?.staleSessions || 0, path: '/sessions' },
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
    path: '/replenishment',
    query: { tab: 'routes' }
  }
]);

const onlineRate = computed(() =>
  stats.value.deviceTotal ? ((stats.value.deviceOnline || 0) / stats.value.deviceTotal) * 100 : 0
);

const totalIssues = computed(() => quickLinks.value.reduce((sum, item) => sum + item.count, 0));

const sortedActions = computed(() => {
  const items = [...(workbench.value?.actionItems || [])];
  return items.sort((a, b) => {
    const diff = priority(b.severity).score - priority(a.severity).score;
    if (diff !== 0) return diff;
    return (a.title || '').localeCompare(b.title || '');
  });
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
  return parts.length ? parts.join(' · ') : '—';
}

function shortId(id: string) {
  return id.length > 14 ? `${id.slice(0, 6)}…${id.slice(-4)}` : id;
}

function goQuick(item: QuickLink) {
  if (item.query) {
    const query = Object.fromEntries(
      Object.entries(item.query).filter((entry): entry is [string, string] => !!entry[1])
    );
    router.push({ path: item.path, query });
    return;
  }
  router.push(item.path);
}

function queryOf(row: OpsActionItem): Record<string, string> {
  const q: Record<string, string> = {};
  if (row.deviceId) q.deviceId = row.deviceId;
  if (row.sessionId) q.sessionId = row.sessionId;
  if (row.ticketId) q.ticketId = row.ticketId;
  return q;
}

function goAction(row: OpsActionItem) {
  const q = queryOf(row);
  switch (row.type) {
    case 'DISPUTE':
      router.push({ path: '/disputes', query: { status: 'OPEN', ...q } });
      return;
    case 'UPLOAD_STUCK':
      router.push({ path: '/upload-queue', query: q });
      return;
    case 'SESSION_STALE':
      router.push({ path: '/sessions', query: { ...q, state: 'SHOPPING' } });
      return;
    case 'LOW_STOCK':
    case 'REPLENISHMENT':
    case 'IN_TRANSIT_OVERDUE':
      router.push({ path: '/replenishment', query: { tab: 'routes', ...q } });
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

async function load() {
  loading.value = true;
  try {
    const [s, wb] = await Promise.all([
      api.request<OpsStats>('/api/v2/ops/admin/stats', 'GET'),
      api.request<OpsWorkbench>('/api/v2/ops/admin/workbench', 'GET').catch(() => null)
    ]);
    stats.value = s;
    workbench.value = wb;
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<style scoped>
.card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.title {
  font-weight: 600;
}
.header-hint {
  font-size: 12px;
  color: var(--layout-muted);
  margin-top: 4px;
}
.queue-card {
  margin-top: 16px;
}
.stats-row {
  margin-bottom: 4px;
}
.stat-tile {
  background: var(--layout-bg, #f8fafc);
  border-radius: 10px;
  padding: 14px 16px;
  height: 100%;
}
.stat-tile.warn .stat-value {
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
}
.stat-hint {
  font-size: 12px;
  color: var(--layout-muted);
  margin-top: 8px;
}
.quick-card {
  cursor: pointer;
  text-align: center;
  margin-bottom: 12px;
  transition: transform 0.15s ease;
}
.quick-card:hover {
  transform: translateY(-2px);
}
.quick-label {
  color: var(--layout-muted);
  font-size: 13px;
}
.quick-value {
  font-size: 28px;
  font-weight: 700;
  margin-top: 8px;
  color: var(--layout-text);
}
.quick-value.warn {
  color: #f59e0b;
}
</style>
