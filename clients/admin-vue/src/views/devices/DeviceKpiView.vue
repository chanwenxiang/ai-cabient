<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">设备可用性</span>
            <span class="hint">默认当天实时口径；选择日期后查询该日期快照</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-date-picker
            v-model="date"
            type="date"
            value-format="YYYY-MM-DD"
            :clearable="false"
            :disabled-date="(d: Date) => d.getTime() > Date.now()"
            @change="load"
          />
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <div v-loading="loading" class="kpi-grid">
      <div class="kpi-card">
        <div class="kpi-label">统计日期</div>
        <div class="kpi-value">{{ row?.kpiDate || '—' }}</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">设备总数</div>
        <div class="kpi-value">{{ row?.deviceTotal ?? '—' }}</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">离线事件</div>
        <div class="kpi-value">{{ row?.offlineEvents ?? '—' }}</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">自动锁机</div>
        <div class="kpi-value">{{ row?.autoLockCount ?? '—' }}</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">自动解锁</div>
        <div class="kpi-value">{{ row?.autoUnlockCount ?? '—' }}</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">人工解锁</div>
        <div class="kpi-value">{{ row?.manualUnlockCount ?? '—' }}</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">平均锁定时长</div>
        <div class="kpi-value">{{ formatHours(row?.avgLockHours) }}</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">平均恢复时长</div>
        <div class="kpi-value">{{ formatHours(row?.avgRecoverHours) }}</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">人工介入率</div>
        <div class="kpi-value">{{ formatRate(row?.manualInterventionRate) }}</div>
      </div>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';

interface DeviceKpiRow {
  kpiDate: string;
  deviceTotal: number;
  offlineEvents: number;
  autoLockCount: number;
  autoUnlockCount: number;
  manualUnlockCount: number;
  avgLockHours?: number | null;
  avgRecoverHours?: number | null;
  manualInterventionRate?: number | null;
}

function todayStr() {
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

const loading = ref(false);
const date = ref(todayStr());
const row = ref<DeviceKpiRow | null>(null);

function formatHours(v?: number | null) {
  if (v == null) return '—';
  return `${v.toFixed(2)} h`;
}

function formatRate(v?: number | null) {
  if (v == null) return '—';
  return `${(v * 100).toFixed(1)} %`;
}

async function load() {
  loading.value = true;
  try {
    const q = date.value ? `?date=${encodeURIComponent(date.value)}` : '';
    row.value = await api.request<DeviceKpiRow>(
      `/api/v2/ops/admin/device-availability-kpi${q}`,
      'GET'
    );
  } catch (e: any) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<style scoped>
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 14px;
}
.kpi-card {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 14px 16px;
  background: var(--el-bg-color);
}
.kpi-label {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  margin-bottom: 8px;
}
.kpi-value {
  font-size: 22px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
</style>
