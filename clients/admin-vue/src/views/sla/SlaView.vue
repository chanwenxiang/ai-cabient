<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">服务时限监控</span>
            <span class="hint">开门成功率、识别耗时与设备在线率</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-empty v-if="listHydrated && !data" description="暂无服务时限数据" />
    <template v-else>
      <el-row :gutter="12" class="stat-row">
        <el-col v-for="tile in statTiles" :key="tile.label" :xs="12" :sm="8" :md="6">
          <button
            type="button"
            class="stat-tile"
            :aria-label="listHydrated ? `${tile.label} ${tile.value}` : `${tile.label} — 加载中…`"
            tabindex="-1"
          >
            <div class="stat-label">{{ tile.label }}</div>
            <div class="stat-value">{{ listHydrated ? tile.value : '—' }}</div>
            <div v-if="!listHydrated" class="stat-hint">加载中…</div>
          </button>
        </el-col>
      </el-row>

      <el-descriptions
        v-if="listHydrated && data?.realtime"
        title="实时指标"
        :column="2"
        border
        class="rt-block"
      >
        <el-descriptions-item label="24h 开门成功率">
          {{ pct(data.realtime.doorSuccessRate24h) }}
        </el-descriptions-item>
        <el-descriptions-item label="当前在线率">
          {{ pct(data.realtime.deviceOnlineRateNow) }}
        </el-descriptions-item>
        <el-descriptions-item label="24h 识别均耗时">
          {{ data.realtime.avgRecognizeMs24h ?? 0 }} ms
        </el-descriptions-item>
        <el-descriptions-item label="争议时限达标率">
          {{ pct(data.realtime.disputeSlaCompliance24h) }}
        </el-descriptions-item>
        <el-descriptions-item label="开放争议">
          {{ data.realtime.disputeOpen ?? 0 }}
        </el-descriptions-item>
        <el-descriptions-item label="逾期争议">
          {{ data.realtime.disputeOverdue ?? 0 }}
        </el-descriptions-item>
      </el-descriptions>
    </template>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';

interface SlaRealtime {
  doorSuccessRate24h?: number;
  avgRecognizeMs24h?: number;
  deviceOnlineRateNow?: number;
  disputeOpen?: number;
  disputeOverdue?: number;
  disputeResolved24h?: number;
  disputeSlaCompliance24h?: number;
}

interface SlaMetrics {
  snapshotDate?: string;
  doorOpenAttempts?: number;
  doorOpenSuccess?: number;
  doorSuccessRate?: number;
  avgRecognizeMs?: number;
  p95RecognizeMs?: number;
  deviceTotal?: number;
  deviceOnlinePeak?: number;
  deviceOnlineRate?: number;
  realtime?: SlaRealtime;
}

const loading = ref(false);
const listHydrated = ref(false);
const data = ref<SlaMetrics | null>(null);

function pct(v?: number | null) {
  if (v == null || Number.isNaN(v)) return '无';
  return `${(v * 100).toFixed(1)}%`;
}

const statTiles = computed(() => {
  const d = data.value;
  return [
    { label: '快照日期', value: d?.snapshotDate || '无' },
    { label: '开门成功率', value: pct(d?.doorSuccessRate) },
    {
      label: '开门成功/尝试',
      value: `${d?.doorOpenSuccess ?? 0}/${d?.doorOpenAttempts ?? 0}`
    },
    { label: '设备在线率', value: pct(d?.deviceOnlineRate) },
    { label: '识别均耗时', value: `${d?.avgRecognizeMs ?? 0} ms` },
    { label: '识别 P95', value: `${d?.p95RecognizeMs ?? 0} ms` },
    { label: '设备总数', value: String(d?.deviceTotal ?? 0) },
    { label: '在线峰值', value: String(d?.deviceOnlinePeak ?? 0) }
  ];
});

async function load() {
  loading.value = true;
  try {
    data.value = await api.request<SlaMetrics>('/api/v2/ops/admin/sla', 'GET');
  } catch (e) {
    // 首屏失败才清空；软刷新失败保留上次 KPI，避免闪「暂无」
    if (!listHydrated.value) data.value = null;
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    listHydrated.value = true;
    loading.value = false;
  }
}

onMounted(load);
</script>

<style scoped>
.stat-row { margin-bottom: 16px; }
.stat-tile {
  display: block;
  width: 100%;
  text-align: left;
  background: var(--el-fill-color-blank);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 14px 16px;
  margin-bottom: 12px;
  cursor: default;
  color: inherit;
  font: inherit;
}
.stat-label { color: var(--el-text-color-secondary); font-size: 13px; margin-bottom: 6px; }
.stat-value { font-size: 20px; font-weight: 600; }
.stat-hint { margin-top: 4px; font-size: 12px; color: var(--el-text-color-secondary); }
.rt-block { margin-top: 8px; }
</style>
