<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">SLA 监控</span>
            <span class="hint">开门成功率、识别耗时与设备在线率</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-empty v-if="!loading && !data" description="暂无 SLA 数据" />
    <template v-else>
      <el-row :gutter="12" class="stat-row">
        <el-col :xs="12" :sm="8" :md="6">
          <div class="stat-tile">
            <div class="stat-label">快照日期</div>
            <div class="stat-value">{{ data?.snapshotDate || '-' }}</div>
          </div>
        </el-col>
        <el-col :xs="12" :sm="8" :md="6">
          <div class="stat-tile">
            <div class="stat-label">开门成功率</div>
            <div class="stat-value">{{ pct(data?.doorSuccessRate) }}</div>
          </div>
        </el-col>
        <el-col :xs="12" :sm="8" :md="6">
          <div class="stat-tile">
            <div class="stat-label">开门成功/尝试</div>
            <div class="stat-value">{{ data?.doorOpenSuccess ?? 0 }}/{{ data?.doorOpenAttempts ?? 0 }}</div>
          </div>
        </el-col>
        <el-col :xs="12" :sm="8" :md="6">
          <div class="stat-tile">
            <div class="stat-label">设备在线率</div>
            <div class="stat-value">{{ pct(data?.deviceOnlineRate) }}</div>
          </div>
        </el-col>
        <el-col :xs="12" :sm="8" :md="6">
          <div class="stat-tile">
            <div class="stat-label">识别均耗时</div>
            <div class="stat-value">{{ data?.avgRecognizeMs ?? 0 }} ms</div>
          </div>
        </el-col>
        <el-col :xs="12" :sm="8" :md="6">
          <div class="stat-tile">
            <div class="stat-label">识别 P95</div>
            <div class="stat-value">{{ data?.p95RecognizeMs ?? 0 }} ms</div>
          </div>
        </el-col>
        <el-col :xs="12" :sm="8" :md="6">
          <div class="stat-tile">
            <div class="stat-label">设备总数</div>
            <div class="stat-value">{{ data?.deviceTotal ?? 0 }}</div>
          </div>
        </el-col>
        <el-col :xs="12" :sm="8" :md="6">
          <div class="stat-tile">
            <div class="stat-label">在线峰值</div>
            <div class="stat-value">{{ data?.deviceOnlinePeak ?? 0 }}</div>
          </div>
        </el-col>
      </el-row>

      <el-descriptions v-if="data?.realtime" title="实时指标" :column="2" border class="rt-block">
        <el-descriptions-item label="24h 开门成功率">
          {{ pct(data.realtime.doorSuccessRate24h) }}
        </el-descriptions-item>
        <el-descriptions-item label="当前在线率">
          {{ pct(data.realtime.deviceOnlineRateNow) }}
        </el-descriptions-item>
        <el-descriptions-item label="24h 识别均耗时">
          {{ data.realtime.avgRecognizeMs24h ?? 0 }} ms
        </el-descriptions-item>
        <el-descriptions-item label="争议 SLA 达标率">
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
import { onMounted, ref } from 'vue';
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
const data = ref<SlaMetrics | null>(null);

function pct(v?: number | null) {
  if (v == null || Number.isNaN(v)) return '-';
  return `${(v * 100).toFixed(1)}%`;
}

async function load() {
  loading.value = true;
  try {
    data.value = await api.request<SlaMetrics>('/api/v2/ops/admin/sla', 'GET');
  } catch (e) {
    data.value = null;
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<style scoped>
.stat-row { margin-bottom: 16px; }
.stat-tile {
  background: var(--el-fill-color-blank);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 14px 16px;
  margin-bottom: 12px;
}
.stat-label { color: var(--el-text-color-secondary); font-size: 13px; margin-bottom: 6px; }
.stat-value { font-size: 20px; font-weight: 600; }
.rt-block { margin-top: 8px; }
</style>
