<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">设备可用性</span>
            <span class="hint">默认当天实时口径；选择日期后查询该日快照</span>
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
        <div class="kpi-value">{{ row?.kpiDate || date || '暂无' }}</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">设备总数</div>
        <div class="kpi-value">{{ countText(row?.deviceTotal) }}</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">离线事件</div>
        <div class="kpi-value">{{ countText(row?.offlineEvents) }}</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">自动锁机</div>
        <div class="kpi-value">{{ countText(row?.autoLockCount) }}</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">自动解锁</div>
        <div class="kpi-value">{{ countText(row?.autoUnlockCount) }}</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">人工解锁</div>
        <div class="kpi-value">{{ countText(row?.manualUnlockCount) }}</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">平均锁定时长</div>
        <div class="kpi-value">{{ hoursText(row?.avgLockHours, '暂无样本') }}</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">平均恢复时长</div>
        <div class="kpi-value">{{ hoursText(row?.avgRecoverHours, '暂无样本') }}</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">人工介入率</div>
        <div class="kpi-value">{{ interventionRateText }}</div>
      </div>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';
import { countText, hoursText, rateText } from '@/utils/display';

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

/** 无解锁样本时展示「无解锁」；有样本则百分比；接口缺省则按计数推算 */
const interventionRateText = computed(() => {
  const r = row.value;
  if (!r) return '…';
  const auto = Number(r.autoUnlockCount) || 0;
  const manual = Number(r.manualUnlockCount) || 0;
  if (auto + manual <= 0) return '无解锁';
  if (r.manualInterventionRate != null) {
    return rateText(r.manualInterventionRate, { unit: 'ratio', digits: 1 });
  }
  return rateText(manual / (auto + manual), { unit: 'ratio', digits: 1 });
});

async function load() {
  loading.value = true;
  try {
    const q = date.value ? `?date=${encodeURIComponent(date.value)}` : '';
    row.value = await api.request<DeviceKpiRow>(
      `/api/v2/ops/admin/device-availability-kpi${q}`,
      'GET'
    );
  } catch (e: unknown) {
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
  text-align: center;
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
