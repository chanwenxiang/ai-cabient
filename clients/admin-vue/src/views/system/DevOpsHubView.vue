<template>
  <div class="devops-hub">
    <div class="devops-header">
      <div>
        <h2>DevOps 中心</h2>
        <p class="hint">监控、CI/CD 与代码质量工具统一入口（集成在运营后台内）</p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新状态</el-button>
    </div>

    <el-row :gutter="16" class="tool-grid">
      <el-col v-for="tool in tools" :key="tool.id" :xs="24" :sm="12" :md="8" :lg="6">
        <el-card shadow="hover" class="tool-card">
          <div class="tool-card-head">
            <span class="tool-name">{{ displayName(tool) }}</span>
            <el-tag :type="tool.online ? 'success' : 'info'" size="small">{{ tool.statusHint }}</el-tag>
          </div>
          <p class="tool-desc">{{ tool.description }}</p>
          <div class="tool-actions">
            <el-button
              v-if="tool.url"
              type="primary"
              link
              @click="openExternal(tool.url)"
            >
              新窗口打开
            </el-button>
            <el-button
              v-if="tool.id === 'sonarqube'"
              type="warning"
              link
              :loading="sonarScanning"
              :disabled="!tool.online || sonarScanning"
              @click="triggerSonarScan"
            >
              重跑 Sonar
            </el-button>
            <el-button
              v-if="tool.id === 'grafana' && grafanaEmbedPath"
              type="primary"
              link
              @click="scrollToGrafana"
            >
              下方嵌入看板
            </el-button>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-card class="prom-helper" shadow="never">
      <template #header>
        <div class="grafana-panel-head">
          <span>Prometheus 常用查询（中文）</span>
          <span class="hint">官方 UI 仅英文；点下方按钮会打开 Prometheus 并自动填入查询</span>
        </div>
      </template>
      <div class="prom-query-grid">
        <el-button
          v-for="q in promQueries"
          :key="q.label"
          @click="openPromQuery(q.expr)"
        >
          {{ q.label }}
        </el-button>
      </div>
      <p class="hint prom-note">
        日常看图请用上方「Grafana」（已默认中文界面）。Prometheus 适合临时 PromQL 调试。
      </p>
    </el-card>

    <el-card v-if="grafanaEmbedPath" ref="grafanaSection" class="grafana-panel" shadow="never">
      <template #header>
        <div class="grafana-panel-head">
          <span>Grafana · AI Cabinet 运营概览</span>
          <span class="hint">同源嵌入，无需单独登录 Grafana（本地 dev）</span>
        </div>
      </template>
      <div class="grafana-frame-wrap">
        <iframe
          v-if="grafanaOnline"
          :src="grafanaEmbedPath"
          title="Grafana Dashboard"
          class="grafana-frame"
          referrerpolicy="no-referrer"
        />
        <el-empty v-else description="Grafana 未启动。请先运行 .\docker-up.ps1（Grafana 在全栈中）" />
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';

interface DevOpsTool {
  id: string;
  name: string;
  description: string;
  url: string;
  embedUrl?: string | null;
  online: boolean;
  statusHint: string;
}

interface DevOpsHub {
  tools: DevOpsTool[];
  githubUrl: string;
  grafanaEmbedPath?: string | null;
}

interface SonarScanResult {
  accepted: boolean;
  jobName: string;
  queueUrl?: string | null;
  actionsUrl?: string | null;
  sonarDashboardUrl?: string | null;
  message: string;
}

const NAME_ZH: Record<string, string> = {
  grafana: 'Grafana 看板',
  prometheus: 'Prometheus 指标',
  sonarqube: 'SonarQube 代码质量',
  github: 'GitHub Actions',
};

const promQueries = [
  { label: '服务是否在线', expr: 'up' },
  { label: '在线设备数', expr: 'cabinet_devices_online' },
  { label: '设备总数', expr: 'cabinet_devices_total' },
  { label: '设备离线率', expr: '(cabinet_devices_total - cabinet_devices_online) / cabinet_devices_total' },
  { label: '开门成功速率', expr: 'rate(cabinet_door_open_total{result="success"}[5m])' },
  { label: '开门失败速率', expr: 'rate(cabinet_door_open_total{result="failure"}[5m])' },
  { label: '结算失败率', expr: 'sum(rate(cabinet_settlement_total{result="failure"}[5m])) / sum(rate(cabinet_settlement_total[5m]))' },
  { label: 'MQTT 转发失败', expr: 'sum(rate(device_trade_forward_total{result="failure"}[5m]))' },
];

const loading = ref(false);
const sonarScanning = ref(false);
const hub = ref<DevOpsHub | null>(null);
const grafanaSection = ref<{ $el?: HTMLElement } | null>(null);

const tools = computed(() => hub.value?.tools ?? []);
const grafanaEmbedPath = computed(() => hub.value?.grafanaEmbedPath || '');
const grafanaOnline = computed(() => tools.value.find((t) => t.id === 'grafana')?.online ?? false);
const prometheusUrl = computed(() => tools.value.find((t) => t.id === 'prometheus')?.url || 'http://localhost:9090');

function displayName(tool: DevOpsTool) {
  return NAME_ZH[tool.id] || tool.name;
}

async function load() {
  loading.value = true;
  try {
    hub.value = await api.request<DevOpsHub>('/api/v2/ops/admin/devops/hub', 'GET');
  } finally {
    loading.value = false;
  }
}

async function triggerSonarScan() {
  try {
    await ElMessageBox.confirm(
      '将通过 GitHub Actions 排队一次 Sonar 全量扫描（ai-cabinet-dev）。扫描需数分钟，期间可继续使用后台。',
      '重跑 Sonar 扫描',
      { type: 'warning', confirmButtonText: '开始扫描', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  sonarScanning.value = true;
  try {
    const result = await api.request<SonarScanResult>('/api/v2/ops/admin/devops/sonar/scan', 'POST');
    ElMessage.success(
      result.accepted
        ? `已提交 GitHub Actions「${result.jobName || 'sonar.yml'}」，完成后可在 SonarQube 查看`
        : result.message || '已提交扫描任务'
    );
    const actionsUrl = result.actionsUrl || result.queueUrl;
    if (actionsUrl) {
      await ElMessageBox.confirm('是否打开 GitHub Actions 查看进度？', '已提交', {
        confirmButtonText: '打开 Actions',
        cancelButtonText: '稍后再看',
        type: 'success'
      })
        .then(() => openExternal(actionsUrl))
        .catch(() => undefined);
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '触发扫描失败');
  } finally {
    sonarScanning.value = false;
  }
}

function openExternal(url: string) {
  window.open(url, '_blank', 'noopener,noreferrer');
}

function openPromQuery(expr: string) {
  const base = prometheusUrl.value.replace(/\/$/, '');
  const url = `${base}/graph?g0.expr=${encodeURIComponent(expr)}&g0.tab=0&g0.range_input=1h`;
  openExternal(url);
}

function scrollToGrafana() {
  grafanaSection.value?.$el?.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

onMounted(load);
</script>

<style scoped>
.devops-hub {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.devops-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.devops-header h2 {
  margin: 0 0 4px;
  font-size: 20px;
}

.hint {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.tool-grid {
  margin-top: 4px;
}

.tool-card {
  min-height: 140px;
}

.tool-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}

.tool-name {
  font-weight: 600;
}

.tool-desc {
  margin: 0 0 12px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  min-height: 36px;
}

.tool-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.grafana-panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.prom-query-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.prom-note {
  margin-top: 12px;
}

.grafana-frame-wrap {
  min-height: 480px;
  background: var(--el-fill-color-light);
  border-radius: 8px;
  overflow: hidden;
}

.grafana-frame {
  width: 100%;
  height: 520px;
  border: 0;
  display: block;
}
</style>
