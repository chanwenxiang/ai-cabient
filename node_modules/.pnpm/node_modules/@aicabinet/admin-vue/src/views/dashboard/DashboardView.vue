<template>
  <div v-loading="loading">
    <div class="page-heading"><div><h1>运营工作台</h1><p>先处理影响交易和用户体验的异常，再关注经营指标。</p></div><el-button :icon="Refresh" :loading="loading" @click="load">刷新数据</el-button></div>
    <el-row :gutter="16" class="stats-row">
      <el-col :xs="12" :sm="6"><el-card shadow="never" class="stat-card"><el-statistic title="设备在线率" :value="onlineRate" suffix="%" :precision="1" /><small>{{ stats.deviceOnline || 0 }} / {{ stats.deviceTotal || 0 }} 台</small></el-card></el-col>
      <el-col :xs="12" :sm="6"><el-card shadow="never" class="stat-card"><el-statistic title="今日营收" :value="(stats.revenueTodayCents || 0) / 100" prefix="¥" :precision="2" /><small>实时交易汇总</small></el-card></el-col>
      <el-col :xs="12" :sm="6"><el-card shadow="never" class="stat-card"><el-statistic title="待处理异常" :value="totalIssues" /><small :class="{ danger: totalIssues > 0 }">{{ totalIssues ? '需要尽快处理' : '运行正常' }}</small></el-card></el-col>
      <el-col :xs="12" :sm="6"><el-card shadow="never" class="stat-card"><el-statistic title="进行中会话" :value="stats.sessionActive || 0" /><small>正在购物或结算</small></el-card></el-col>
    </el-row>

    <el-card class="page-card" style="margin-top:16px">
      <template #header>
        <div style="display:flex;justify-content:space-between;align-items:center">
          <div><strong>异常优先队列</strong><div class="header-hint">按交易影响程度排序</div></div>
        </div>
      </template>
      <el-row :gutter="12">
        <el-col v-for="item in quickLinks" :key="item.label" :span="6">
          <el-card shadow="hover" class="quick-card" @click="router.push(item.path)">
            <div class="quick-label">{{ item.label }}</div>
            <div class="quick-value" :class="{ warn: item.count > 0 }">{{ item.count }}</div>
          </el-card>
        </el-col>
      </el-row>
      <el-table :data="sortedActions" stripe style="margin-top:16px" empty-text="当前没有待处理异常">
        <el-table-column label="优先级" width="100"><template #default="{ row }"><el-tag :type="priority(row.type).tag" effect="light">{{ priority(row.type).label }}</el-tag></template></el-table-column>
        <el-table-column label="类型" width="120"><template #default="{ row }">{{ typeLabel(row.type) }}</template></el-table-column>
        <el-table-column prop="title" label="标题" />
        <el-table-column prop="detail" label="详情" />
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button link type="primary" @click="goAction(row)">处理</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';

interface OpsStats {
  deviceTotal?: number;
  deviceOnline?: number;
  revenueTodayCents?: number;
  disputeOpen?: number;
  sessionActive?: number;
  sessionWaitingUpload?: number;
}

interface OpsWorkbench {
  openDisputes?: number;
  offlineDevices?: number;
  waitingUploads?: number;
  lowStockItems?: number;
  staleSessions?: number;
  reconciliationMismatches?: number;
  actionItems?: { type: string; title: string; detail?: string; deviceId?: string; ticketId?: string }[];
}

const router = useRouter();
const loading = ref(false);
const stats = ref<OpsStats>({});
const workbench = ref<OpsWorkbench | null>(null);

const quickLinks = computed(() => [
  { label: '待审争议', count: workbench.value?.openDisputes || 0, path: '/disputes' },
  { label: '离线设备', count: workbench.value?.offlineDevices || 0, path: '/devices' },
  { label: '待上传', count: workbench.value?.waitingUploads || stats.value.sessionWaitingUpload || 0, path: '/upload-queue' },
  { label: '低库存', count: workbench.value?.lowStockItems || 0, path: '/replenishment' },
  { label: '异常会话', count: workbench.value?.staleSessions || 0, path: '/sessions' },
  { label: '对账差异', count: workbench.value?.reconciliationMismatches || 0, path: '/reconciliation' }
]);
const onlineRate = computed(() => stats.value.deviceTotal ? (stats.value.deviceOnline || 0) / stats.value.deviceTotal * 100 : 0);
const totalIssues = computed(() => quickLinks.value.reduce((sum, item) => sum + item.count, 0));
const sortedActions = computed(() => [...(workbench.value?.actionItems || [])].sort((a, b) => priority(b.type).score - priority(a.type).score));
function priority(type = '') { if (['SESSION_STALE','UPLOAD_STUCK','DISPUTE'].includes(type)) return { score: 3, label: '紧急', tag: 'danger' as const }; if (['DEVICE_OFFLINE','RECONCILIATION_MISMATCH'].includes(type)) return { score: 2, label: '较高', tag: 'warning' as const }; return { score: 1, label: '一般', tag: 'info' as const }; }
function typeLabel(type = '') {
  return ({
    DISPUTE: '账单争议',
    DEVICE_OFFLINE: '设备离线',
    UPLOAD_STUCK: '录像滞留',
    SESSION_STALE: '会话超时',
    LOW_STOCK: '库存不足',
    RECONCILIATION_MISMATCH: '对账差异',
    SPLIT_EXCEPTION: '分账异常'
  } as Record<string, string>)[type] || type;
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

function goAction(row: { type?: string; ticketId?: string; deviceId?: string }) {
  if (row.type === 'DISPUTE') router.push('/disputes');
  else if (row.type === 'UPLOAD_STUCK') router.push('/upload-queue');
  else if (row.type === 'SESSION_STALE') router.push('/sessions');
  else if (row.type === 'LOW_STOCK') router.push('/replenishment');
  else if (row.type === 'SPLIT_EXCEPTION' || row.type === 'RECONCILIATION_MISMATCH') router.push('/merchants');
  else if (row.deviceId) router.push(`/devices/${encodeURIComponent(row.deviceId)}`);
}

onMounted(load);
</script>

<style scoped>
.page-heading{display:flex;align-items:flex-start;justify-content:space-between;margin-bottom:20px}.page-heading h1{margin:0;font-size:24px}.page-heading p{margin:6px 0 0;color:var(--layout-muted)}.stats-row { margin-bottom: 8px; }.stat-card{height:100%}.stat-card small{display:block;margin-top:10px;color:var(--layout-muted)}.stat-card small.danger{color:#dc2626}.header-hint{font-size:12px;color:var(--layout-muted);margin-top:3px}
.quick-card { cursor: pointer; text-align: center; }
.quick-label { color: var(--layout-muted); font-size: 13px; }
.quick-value { font-size: 28px; font-weight: 700; margin-top: 8px; color: var(--layout-text); }
.quick-value.warn { color: #f59e0b; }
</style>
