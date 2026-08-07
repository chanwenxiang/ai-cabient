<template>
  <div class="bigscreen">
    <header class="bs-header">
      <div class="bs-title">
        <span class="bs-logo">AI 开门柜</span>
        <span class="bs-sub">运营大屏 · 自动刷新 30s</span>
      </div>
      <div class="bs-actions">
        <span class="bs-clock">{{ clock }}</span>
        <el-button size="small" :icon="FullScreen" @click="toggleFullscreen">全屏</el-button>
        <el-button size="small" :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </header>

    <section class="bs-kpis">
      <div class="bs-kpi">
        <div class="bs-kpi-label">在售柜</div>
        <div class="bs-kpi-value">{{ workbench?.devicesOnSale ?? '—' }}</div>
        <div class="bs-kpi-hint">锁机 {{ workbench?.devicesSalesLocked ?? 0 }}</div>
      </div>
      <div class="bs-kpi">
        <div class="bs-kpi-label">离线柜</div>
        <div class="bs-kpi-value warn">{{ workbench?.offlineDevices ?? '—' }}</div>
        <div class="bs-kpi-hint">在线率 {{ pct(sla?.deviceOnlineRate) }}</div>
      </div>
      <div class="bs-kpi">
        <div class="bs-kpi-label">今日订单</div>
        <div class="bs-kpi-value">{{ stats?.orderToday ?? '—' }}</div>
        <div class="bs-kpi-hint">累计 {{ stats?.orderTotal ?? 0 }}</div>
      </div>
      <div class="bs-kpi">
        <div class="bs-kpi-label">今日营收</div>
        <div class="bs-kpi-value money">{{ yuan(stats?.revenueTodayCents) }}</div>
        <div class="bs-kpi-hint">累计 {{ yuan(stats?.revenueTotalCents) }}</div>
      </div>
      <div class="bs-kpi">
        <div class="bs-kpi-label">今日毛利</div>
        <div class="bs-kpi-value money">{{ yuan(finance?.grossMarginTodayCents) }}</div>
        <div class="bs-kpi-hint">毛利率 {{ pct(finance?.grossMarginRateToday) }}</div>
      </div>
      <div class="bs-kpi">
        <div class="bs-kpi-label">开门成功率</div>
        <div class="bs-kpi-value">{{ pct(sla?.realtime?.doorSuccessRate24h) }}</div>
        <div class="bs-kpi-hint">24h</div>
      </div>
      <div class="bs-kpi">
        <div class="bs-kpi-label">识别自动结算</div>
        <div class="bs-kpi-value">{{ pct(stats?.recognitionAutoRate24h) }}</div>
        <div class="bs-kpi-hint">平均 {{ stats?.doorSuccessRate24h != null ? '—' : '' }}{{ ms(sla?.realtime?.avgRecognizeMs24h) }}</div>
      </div>
      <div class="bs-kpi">
        <div class="bs-kpi-label">待处理争议</div>
        <div class="bs-kpi-value warn">{{ workbench?.openDisputes ?? '—' }}</div>
        <div class="bs-kpi-hint">逾期 {{ workbench?.overdueDisputes ?? 0 }}</div>
      </div>
    </section>

    <section class="bs-panels">
      <div class="bs-panel">
        <div class="bs-panel-title">设备可用性 KPI（今天）</div>
        <div class="bs-panel-body">
          <div class="bs-row"><span>离线事件</span><b>{{ kpi?.offlineEvents ?? 0 }}</b></div>
          <div class="bs-row"><span>自动锁机</span><b>{{ kpi?.autoLockCount ?? 0 }}</b></div>
          <div class="bs-row"><span>自动解锁</span><b class="good">{{ kpi?.autoUnlockCount ?? 0 }}</b></div>
          <div class="bs-row"><span>人工解锁</span><b>{{ kpi?.manualUnlockCount ?? 0 }}</b></div>
          <div class="bs-row"><span>人工介入率</span><b>{{ pct(kpi?.manualInterventionRate) }}</b></div>
          <div class="bs-row"><span>平均恢复时长</span><b>{{ hours(kpi?.avgRecoverHours) }}</b></div>
        </div>
      </div>

      <div class="bs-panel">
        <div class="bs-panel-title">识别与 SLA</div>
        <div class="bs-panel-body">
          <div class="bs-row"><span>平均识别耗时</span><b>{{ ms(sla?.avgRecognizeMs) }}</b></div>
          <div class="bs-row"><span>95 分位耗时</span><b>{{ ms(sla?.p95RecognizeMs) }}</b></div>
          <div class="bs-row"><span>争议 SLA 合规率</span><b>{{ pct(sla?.realtime?.disputeSlaCompliance24h) }}</b></div>
          <div class="bs-row"><span>24h 解决争议</span><b>{{ sla?.realtime?.disputeResolved24h ?? 0 }}</b></div>
          <div class="bs-row"><span>低库存 SKU</span><b class="warn">{{ stats?.lowStockSkuCount ?? 0 }}</b></div>
          <div class="bs-row"><span>待分账异常</span><b>{{ stats?.pendingSplitCount ?? 0 }}</b></div>
        </div>
      </div>

      <div class="bs-panel">
        <div class="bs-panel-title">支付渠道（近 7 天）</div>
        <div class="bs-panel-body">
          <div v-for="ch in channels" :key="ch.channel" class="bs-bar-row">
            <span class="bs-bar-label">{{ ch.channel }}</span>
            <div class="bs-bar">
              <div class="bs-bar-fill" :style="{ width: barWidth(ch) }" />
            </div>
            <span class="bs-bar-num">{{ ch.count }} 笔 · {{ yuan(ch.amountCents) }}</span>
          </div>
          <div v-if="!channels.length" class="bs-empty">暂无渠道数据</div>
        </div>
      </div>
    </section>

    <section class="bs-risks">
      <span v-for="r in risks" :key="r.label" class="bs-risk">
        {{ r.label }} <b :class="{ warn: r.value > 0 }">{{ r.value }}</b>
      </span>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { FullScreen, Refresh } from '@element-plus/icons-vue';
import { api } from '@/api/client';

interface AdminStats {
  deviceTotal: number; deviceOnline: number; sessionToday: number; orderToday: number;
  revenueTodayCents: number; orderTotal: number; revenueTotalCents: number;
  recognitionAutoRate24h: number; doorSuccessRate24h: number; lowStockSkuCount: number;
  pendingSplitCount: number; nearExpiryLotCount: number; expiredLotCount: number;
  pullOffOpenCount: number; slotDiscrepancyCount: number;
}
interface Workbench {
  devicesOnSale: number; devicesSalesLocked: number; offlineDevices: number;
  openDisputes: number; overdueDisputes: number; waitingUploads: number;
  lowStockItems: number; pendingReplenishments: number; staleSessions: number;
  reconciliationMismatches: number; splitExceptions: number; inTransitOverdue: number;
  pendingUnpaidOrders: number;
}
interface SlaRealtime {
  doorSuccessRate24h: number; avgRecognizeMs24h: number; disputeResolved24h: number;
  disputeSlaCompliance24h: number; disputeOpen: number; disputeOverdue: number;
}
interface SlaMetrics {
  doorSuccessRate: number; avgRecognizeMs: number; p95RecognizeMs: number;
  deviceOnlineRate: number; realtime?: SlaRealtime;
}
interface FinanceStats {
  revenueTodayCents: number; grossMarginTodayCents: number; grossMarginRateToday: number;
  orderToday: number;
}
interface Kpi {
  offlineEvents: number; autoLockCount: number; autoUnlockCount: number;
  manualUnlockCount: number; manualInterventionRate?: number | null; avgRecoverHours?: number | null;
}
interface ChannelStat { channel: string; count: number; amountCents: number }

const loading = ref(false);
const clock = ref('');
const stats = ref<AdminStats | null>(null);
const workbench = ref<Workbench | null>(null);
const sla = ref<SlaMetrics | null>(null);
const finance = ref<FinanceStats | null>(null);
const kpi = ref<Kpi | null>(null);
const channels = ref<ChannelStat[]>([]);

const risks = computed(() => [
  { label: '待上传', value: workbench.value?.waitingUploads ?? 0 },
  { label: '卡点会话', value: workbench.value?.staleSessions ?? 0 },
  { label: '补货待办', value: workbench.value?.pendingReplenishments ?? 0 },
  { label: '临期批次', value: stats.value?.nearExpiryLotCount ?? 0 },
  { label: '过期批次', value: stats.value?.expiredLotCount ?? 0 },
  { label: '对账差异', value: workbench.value?.reconciliationMismatches ?? 0 },
  { label: '分账异常', value: workbench.value?.splitExceptions ?? 0 },
  { label: '未付订单', value: workbench.value?.pendingUnpaidOrders ?? 0 },
  { label: '待拉下架', value: stats.value?.pullOffOpenCount ?? 0 },
  { label: '槽位差异', value: stats.value?.slotDiscrepancyCount ?? 0 }
]);

function yuan(cents?: number | null) {
  if (cents == null) return '—';
  return `¥${(cents / 100).toFixed(0)}`;
}
function pct(v?: number | null) {
  if (v == null) return '—';
  return `${(v * 100).toFixed(1)}%`;
}
function ms(v?: number | null) {
  if (v == null) return '—';
  return `${v}ms`;
}
function hours(v?: number | null) {
  if (v == null) return '—';
  return `${v.toFixed(1)}h`;
}
function barWidth(ch: ChannelStat) {
  const max = Math.max(1, ...channels.value.map((c) => c.count));
  return `${Math.max(6, Math.round((ch.count / max) * 100))}%`;
}

async function load() {
  loading.value = true;
  const [s, w, sl, f, k, ch] = await Promise.all([
    api.request<AdminStats>('/api/v2/ops/admin/stats', 'GET').catch(() => null),
    api.request<Workbench>('/api/v2/ops/admin/workbench', 'GET').catch(() => null),
    api.request<SlaMetrics>('/api/v2/ops/admin/sla', 'GET').catch(() => null),
    api.request<FinanceStats>('/api/v2/ops/admin/finance/stats', 'GET').catch(() => null),
    api.request<Kpi>('/api/v2/ops/admin/device-availability-kpi', 'GET').catch(() => null),
    api.request<{ orderPayChannels: ChannelStat[] }>('/api/v2/ops/admin/trend/channels?days=7', 'GET').catch(() => null)
  ]);
  stats.value = s;
  workbench.value = w;
  sla.value = sl;
  finance.value = f;
  kpi.value = k;
  channels.value = ch?.orderPayChannels ?? [];
  loading.value = false;
}

function tick() {
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  clock.value = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

function toggleFullscreen() {
  if (document.fullscreenElement) {
    document.exitFullscreen().catch(() => {});
  } else {
    document.documentElement.requestFullscreen().catch(() => {});
  }
}

let clockTimer = 0;
let refreshTimer = 0;
onMounted(() => {
  tick();
  load();
  clockTimer = window.setInterval(tick, 1000);
  refreshTimer = window.setInterval(load, 30_000);
});
onBeforeUnmount(() => {
  window.clearInterval(clockTimer);
  window.clearInterval(refreshTimer);
});
</script>

<style scoped>
.bigscreen {
  min-height: calc(100vh - 48px);
  padding: 18px;
  background: radial-gradient(1200px 500px at 20% -10%, rgba(45, 212, 191, 0.10), transparent 60%),
    #0b1220;
  color: #e6edf7;
}
.bs-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.bs-title { display: flex; align-items: baseline; gap: 12px; }
.bs-logo { font-size: 24px; font-weight: 700; letter-spacing: 2px; }
.bs-sub { font-size: 13px; color: #7c8ba1; }
.bs-actions { display: flex; align-items: center; gap: 8px; }
.bs-clock { font-size: 15px; color: #7c8ba1; margin-right: 8px; }
.bs-kpis {
  display: grid;
  grid-template-columns: repeat(8, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}
.bs-kpi {
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 10px;
  padding: 14px 12px;
}
.bs-kpi-label { font-size: 13px; color: #7c8ba1; }
.bs-kpi-value { font-size: 28px; font-weight: 700; margin: 6px 0 4px; }
.bs-kpi-value.money { color: #2dd4bf; }
.bs-kpi-value.warn { color: #f87171; }
.bs-kpi-hint { font-size: 12px; color: #7c8ba1; }
.bs-panels {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}
.bs-panel {
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 10px;
  padding: 14px;
}
.bs-panel-title { font-size: 14px; font-weight: 600; margin-bottom: 10px; color: #a5b4c8; }
.bs-panel-body { display: flex; flex-direction: column; gap: 8px; }
.bs-row { display: flex; justify-content: space-between; font-size: 14px; }
.bs-row span { color: #a5b4c8; }
.bs-row b { color: #e6edf7; }
.bs-row b.good { color: #34d399; }
.bs-row b.warn { color: #f87171; }
.bs-bar-row { display: flex; align-items: center; gap: 8px; font-size: 13px; }
.bs-bar-label { width: 54px; color: #a5b4c8; }
.bs-bar { flex: 1; height: 10px; background: rgba(255, 255, 255, 0.08); border-radius: 5px; overflow: hidden; }
.bs-bar-fill { height: 100%; background: linear-gradient(90deg, #2dd4bf, #38bdf8); border-radius: 5px; }
.bs-bar-num { width: 150px; text-align: right; color: #a5b4c8; }
.bs-empty { color: #7c8ba1; font-size: 13px; }
.bs-risks { display: flex; flex-wrap: wrap; gap: 10px; }
.bs-risk {
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 8px;
  padding: 8px 12px;
  font-size: 13px;
  color: #a5b4c8;
}
.bs-risk b { margin-left: 6px; color: #e6edf7; }
.bs-risk b.warn { color: #f87171; }
@media (max-width: 1100px) {
  .bs-kpis { grid-template-columns: repeat(4, minmax(0, 1fr)); }
  .bs-panels { grid-template-columns: 1fr; }
}
</style>
