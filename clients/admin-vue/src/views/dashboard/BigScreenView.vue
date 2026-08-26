<template>
  <div class="bigscreen" :class="{ 'is-fullscreen': isFullscreen }">
    <header class="bs-header">
      <div class="bs-title">
        <span class="bs-logo">AI 开门柜</span>
        <span class="bs-sub">运营大屏 · 自动刷新 30s</span>
        <span v-if="demoBanner" class="bs-demo-tag">{{ demoBanner }}</span>
      </div>
      <div class="bs-actions">
        <span class="bs-clock">{{ clock }}</span>
        <el-button size="small" :icon="FullScreen" @click="toggleFullscreen">
          {{ isFullscreen ? '退出全屏' : '全屏' }}
        </el-button>
        <el-button size="small" :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        <el-button size="small" plain @click="goBack">返回后台</el-button>
      </div>
    </header>

    <section class="bs-kpis">
      <div class="bs-kpi">
        <div class="bs-kpi-label">在售柜</div>
        <div class="bs-kpi-value">{{ workbench?.devicesOnSale ?? '暂无' }}</div>
        <div class="bs-kpi-hint">锁机 {{ workbench?.devicesSalesLocked ?? 0 }}</div>
      </div>
      <div class="bs-kpi">
        <div class="bs-kpi-label">离线柜</div>
        <div class="bs-kpi-value warn">{{ workbench?.offlineDevices ?? '暂无' }}</div>
        <div class="bs-kpi-hint">在线率 {{ pct(sla?.deviceOnlineRate) }}</div>
      </div>
      <div class="bs-kpi">
        <div class="bs-kpi-label">今日订单</div>
        <div class="bs-kpi-value">{{ stats?.orderToday ?? '暂无' }}</div>
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
        <div class="bs-kpi-hint">开门时长 {{ ms(sla?.realtime?.avgRecognizeMs24h) }}</div>
      </div>
      <div class="bs-kpi">
        <div class="bs-kpi-label">待处理争议</div>
        <div class="bs-kpi-value warn">{{ workbench?.openDisputes ?? '暂无' }}</div>
        <div class="bs-kpi-hint">逾期 {{ workbench?.overdueDisputes ?? 0 }}</div>
      </div>
    </section>

    <section class="bs-panels">
      <div class="bs-panel bs-panel--wide">
        <div class="bs-panel-title">营收趋势（近 7 天）</div>
        <div class="bs-panel-body">
          <svg
            v-if="trendPoints.length"
            class="bs-line"
            viewBox="0 0 300 110"
            preserveAspectRatio="none"
          >
            <defs>
              <linearGradient id="bsArea" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stop-color="var(--app-primary)" stop-opacity="0.30" />
                <stop offset="100%" stop-color="var(--app-primary)" stop-opacity="0" />
              </linearGradient>
            </defs>
            <polygon :points="areaPoints" fill="url(#bsArea)" />
            <polyline
              :points="trendPoints.join(' ')"
              fill="none"
              stroke="var(--app-primary)"
              stroke-width="2.5"
              stroke-linejoin="round"
              stroke-linecap="round"
            />
            <circle
              v-for="p in dots"
              :key="p.x"
              :cx="p.x"
              :cy="p.y"
              r="3"
              fill="var(--app-primary)"
            />
          </svg>
          <div v-else class="bs-empty">暂无趋势数据</div>
          <div class="bs-line-labels">
            <span>{{ trendDates[0] || '暂无' }}</span>
            <span>{{ trendDates[Math.floor(trendDates.length / 2)] || '' }}</span>
            <span>{{ trendDates[trendDates.length - 1] || '暂无' }}</span>
          </div>
        </div>
      </div>

      <div class="bs-panel">
        <div class="bs-panel-title">待办 / 告警</div>
        <div class="bs-panel-body">
          <div v-for="(item, idx) in actionItems" :key="idx" class="bs-action">
            <span class="bs-action-dot" :class="severityClass(item.severity)" />
            <div class="bs-action-main">
              <div class="bs-action-title">{{ item.title }}</div>
              <div class="bs-action-sub">
                {{ item.deviceId || displayBizNo(item.sessionId, '') || item.detail || '暂无' }}
              </div>
            </div>
          </div>
          <div v-if="!actionItems.length" class="bs-empty">暂无待办</div>
        </div>
      </div>

      <div class="bs-panel">
        <div class="bs-panel-title">货柜统计</div>
        <div class="bs-panel-body">
          <div class="bs-row">
            <span>货柜总数</span><b>{{ stats?.deviceTotal ?? 0 }}</b>
          </div>
          <div class="bs-row">
            <span>在售货柜</span><b class="good">{{ workbench?.devicesOnSale ?? 0 }}</b>
          </div>
          <div class="bs-row">
            <span>停售货柜</span><b>{{ workbench?.devicesSalesLocked ?? 0 }}</b>
          </div>
          <div class="bs-row">
            <span>离线货柜</span><b class="warn">{{ workbench?.offlineDevices ?? 0 }}</b>
          </div>
          <div class="bs-row">
            <span>设备在线率</span><b>{{ pct(sla?.deviceOnlineRate) }}</b>
          </div>
          <div class="bs-row">
            <span>当前会话</span><b>{{ stats?.sessionActive ?? 0 }}</b>
          </div>
        </div>
      </div>

      <div class="bs-panel">
        <div class="bs-panel-title">销售统计（今日）</div>
        <div class="bs-panel-body">
          <div class="bs-row">
            <span>销售金额</span><b class="money">{{ yuan(finance?.revenueTodayCents) }}</b>
          </div>
          <div class="bs-row">
            <span>订单数量</span><b>{{ finance?.orderToday ?? stats?.orderToday ?? 0 }}</b>
          </div>
          <div class="bs-row">
            <span>客单价</span><b>{{ yuan(finance?.averageOrderValueTodayCents) }}</b>
          </div>
          <div class="bs-row">
            <span>待处理争议</span><b class="warn">{{ workbench?.openDisputes ?? 0 }}</b>
          </div>
          <div class="bs-row">
            <span>未支付订单</span><b>{{ workbench?.pendingUnpaidOrders ?? 0 }}</b>
          </div>
          <div class="bs-row">
            <span>低库存 SKU</span><b class="warn">{{ stats?.lowStockSkuCount ?? 0 }}</b>
          </div>
        </div>
      </div>

      <div class="bs-panel">
        <div class="bs-panel-title">商品排行（今日）</div>
        <div class="bs-panel-body">
          <div v-for="(p, i) in topProducts" :key="p.dimLabel" class="bs-rank">
            <span class="bs-rank-idx">{{ i + 1 }}</span>
            <span class="bs-rank-name">{{ p.dimLabel }}</span>
            <span class="bs-rank-num">{{ yuan(p.revenueCents) }}</span>
          </div>
          <div v-if="!topProducts.length" class="bs-empty">暂无商品数据</div>
        </div>
      </div>

      <div class="bs-panel">
        <div class="bs-panel-title">设备可用性 KPI（今天）</div>
        <div class="bs-panel-body">
          <div class="bs-row">
            <span>离线事件</span><b>{{ kpi?.offlineEvents ?? 0 }}</b>
          </div>
          <div class="bs-row">
            <span>自动锁机</span><b>{{ kpi?.autoLockCount ?? 0 }}</b>
          </div>
          <div class="bs-row">
            <span>自动解锁</span><b class="good">{{ kpi?.autoUnlockCount ?? 0 }}</b>
          </div>
          <div class="bs-row">
            <span>人工解锁</span><b>{{ kpi?.manualUnlockCount ?? 0 }}</b>
          </div>
          <div class="bs-row">
            <span>人工介入率</span>
            <b>{{
              (kpi?.autoUnlockCount ?? 0) + (kpi?.manualUnlockCount ?? 0) > 0
                ? pct(kpi?.manualInterventionRate ?? 0)
                : '无解锁'
            }}</b>
          </div>
          <div class="bs-row">
            <span>平均恢复时长</span><b>{{ hours(kpi?.avgRecoverHours) }}</b>
          </div>
        </div>
      </div>

      <div class="bs-panel">
        <div class="bs-panel-title">识别与 SLA</div>
        <div class="bs-panel-body">
          <div class="bs-row">
            <span>平均开门时长</span><b>{{ ms(sla?.avgRecognizeMs) }}</b>
          </div>
          <div class="bs-row">
            <span>开门时长 P95</span><b>{{ ms(sla?.p95RecognizeMs) }}</b>
          </div>
          <div class="bs-row">
            <span>争议 SLA 合规率</span><b>{{ pct(sla?.realtime?.disputeSlaCompliance24h) }}</b>
          </div>
          <div class="bs-row">
            <span>24h 解决争议</span><b>{{ sla?.realtime?.disputeResolved24h ?? 0 }}</b>
          </div>
          <div class="bs-row">
            <span>低库存 SKU</span><b class="warn">{{ stats?.lowStockSkuCount ?? 0 }}</b>
          </div>
          <div class="bs-row">
            <span>待分账异常</span><b>{{ stats?.pendingSplitCount ?? 0 }}</b>
          </div>
        </div>
      </div>

      <div class="bs-panel bs-panel--wide">
        <div class="bs-panel-title">货柜排行（今日营收）</div>
        <div class="bs-panel-body bs-rank-grid">
          <div v-for="(d, i) in topDevices" :key="d.deviceId" class="bs-rank">
            <span class="bs-rank-idx">{{ i + 1 }}</span>
            <span class="bs-rank-name">{{ d.deviceName || d.deviceId }}</span>
            <span class="bs-rank-num">{{ yuan(d.revenueTodayCents) }}</span>
          </div>
          <div v-if="!topDevices.length" class="bs-empty">暂无货柜数据</div>
        </div>
      </div>

      <div class="bs-panel bs-panel--full">
        <div class="bs-panel-title">支付渠道（近 7 天）</div>
        <div class="bs-panel-body">
          <div v-for="ch in channels" :key="ch.channel" class="bs-bar-row">
            <span class="bs-bar-label">{{ dictLabel('pay_channel', ch.channel) }}</span>
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
import { dictLabel } from '@aicabinet/shared-dict';
import { displayBizNo } from '@aicabinet/shared-uni/format';

interface AdminStats {
  deviceTotal: number;
  deviceOnline: number;
  sessionToday: number;
  orderToday: number;
  revenueTodayCents: number;
  orderTotal: number;
  revenueTotalCents: number;
  recognitionAutoRate24h: number;
  doorSuccessRate24h: number;
  lowStockSkuCount: number;
  pendingSplitCount: number;
  nearExpiryLotCount: number;
  expiredLotCount: number;
  pullOffOpenCount: number;
  slotDiscrepancyCount: number;
  sessionActive: number;
}
interface Workbench {
  devicesOnSale: number;
  devicesSalesLocked: number;
  offlineDevices: number;
  openDisputes: number;
  overdueDisputes: number;
  waitingUploads: number;
  lowStockItems: number;
  pendingReplenishments: number;
  staleSessions: number;
  reconciliationMismatches: number;
  splitExceptions: number;
  inTransitOverdue: number;
  pendingUnpaidOrders: number;
  actionItems: OpsActionItem[];
}
interface OpsActionItem {
  type?: string;
  severity?: string;
  title: string;
  detail?: string;
  deviceId?: string;
  sessionId?: string;
}
interface SlaRealtime {
  doorSuccessRate24h: number;
  avgRecognizeMs24h: number;
  disputeResolved24h: number;
  disputeSlaCompliance24h: number;
  disputeOpen: number;
  disputeOverdue: number;
}
interface SlaMetrics {
  doorSuccessRate: number;
  avgRecognizeMs: number;
  p95RecognizeMs: number;
  deviceOnlineRate: number;
  realtime?: SlaRealtime;
}
interface FinanceStats {
  revenueTodayCents: number;
  grossMarginTodayCents: number;
  grossMarginRateToday: number;
  orderToday: number;
  averageOrderValueTodayCents: number;
}
interface Kpi {
  offlineEvents: number;
  autoLockCount: number;
  autoUnlockCount: number;
  manualUnlockCount: number;
  manualInterventionRate?: number | null;
  avgRecoverHours?: number | null;
}
interface ChannelStat {
  channel: string;
  count: number;
  amountCents: number;
}

const loading = ref(false);
const clock = ref('');
const isFullscreen = ref(false);
const demoBanner = ref('');
const stats = ref<AdminStats | null>(null);
const workbench = ref<Workbench | null>(null);
const sla = ref<SlaMetrics | null>(null);
const finance = ref<FinanceStats | null>(null);
const kpi = ref<Kpi | null>(null);
const channels = ref<ChannelStat[]>([]);
const trend = ref<DailyStat[]>([]);
const deviceRanks = ref<DeviceRank[]>([]);
const productRanks = ref<ProductRank[]>([]);

interface DailyStat {
  date: string;
  orderCount: number;
  revenueCents: number;
}
interface DeviceRank {
  deviceId: string;
  deviceName?: string;
  orderToday: number;
  revenueTodayCents: number;
}
interface ProductRank {
  dimLabel: string;
  orderCount: number;
  revenueCents: number;
}

const actionItems = computed(() => (workbench.value?.actionItems ?? []).slice(0, 8));
const topDevices = computed(() =>
  [...deviceRanks.value].sort((a, b) => b.revenueTodayCents - a.revenueTodayCents).slice(0, 5)
);
const topProducts = computed(() =>
  [...productRanks.value].sort((a, b) => b.revenueCents - a.revenueCents).slice(0, 5)
);

const trendPoints = computed(() => {
  const data = trend.value;
  if (!data || data.length < 2) return [];
  const w = 300;
  const h = 100;
  const pad = 6;
  const max = Math.max(1, ...data.map((d) => d.revenueCents));
  return data.map((d, i) => {
    const x = pad + (i * (w - pad * 2)) / (data.length - 1);
    const y = h - pad - (d.revenueCents / max) * (h - pad * 2);
    return `${x.toFixed(1)},${y.toFixed(1)}`;
  });
});
const areaPoints = computed(() => {
  const pts = trendPoints.value;
  if (!pts.length) return '';
  const first = Number(pts[0].split(',')[0]);
  const last = Number(pts[pts.length - 1].split(',')[0]);
  return `${first},106 ${pts.join(' ')} ${last},106`;
});
const dots = computed(() =>
  trendPoints.value.map((p) => {
    const [x, y] = p.split(',').map(Number);
    return { x, y };
  })
);
const trendDates = computed(() => trend.value.map((d) => (d.date ? d.date.slice(5) : '')));

function severityClass(severity?: string) {
  const s = (severity || '').toUpperCase();
  if (s === 'CRITICAL' || s === 'HIGH') return 'is-danger';
  if (s === 'MEDIUM') return 'is-warn';
  return 'is-muted';
}

function goBack() {
  if (window.history.length > 1) {
    window.history.back();
  } else {
    window.location.href = '/admin/#/dashboard';
  }
}

function todayStr() {
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

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
  if (cents == null) return '暂无';
  return `¥${(cents / 100).toFixed(2)}`;
}
function pct(v?: number | null) {
  if (v == null) return '未统计';
  return `${(v * 100).toFixed(1)}%`;
}
function ms(v?: number | null) {
  if (v == null) return '未统计';
  return `${v}ms`;
}
function hours(v?: number | null) {
  if (v == null) return '未统计';
  return `${v.toFixed(1)}h`;
}
function barWidth(ch: ChannelStat) {
  const max = Math.max(1, ...channels.value.map((c) => c.count));
  return `${Math.max(6, Math.round((ch.count / max) * 100))}%`;
}

async function load() {
  loading.value = true;
  const today = todayStr();
  const [s, w, sl, f, k, ch, t, dr, pr, scope] = await Promise.all([
    api.request<AdminStats>('/api/v2/ops/admin/stats', 'GET').catch(() => null),
    api.request<Workbench>('/api/v2/ops/admin/workbench', 'GET').catch(() => null),
    api.request<SlaMetrics>('/api/v2/ops/admin/sla', 'GET').catch(() => null),
    api.request<FinanceStats>('/api/v2/ops/admin/finance/stats', 'GET').catch(() => null),
    api.request<Kpi>('/api/v2/ops/admin/device-availability-kpi', 'GET').catch(() => null),
    api
      .request<{ orderPayChannels: ChannelStat[] }>(
        '/api/v2/ops/admin/trend/channels?days=7',
        'GET'
      )
      .catch(() => null),
    api
      .request<{ last7Days: DailyStat[] }>('/api/v2/ops/admin/trend?days=7', 'GET')
      .catch(() => null),
    api
      .request<{ items: DeviceRank[]; total: number }>(
        '/api/v2/ops/admin/reports/devices?page=0&size=20',
        'GET'
      )
      .then((r) => r?.items ?? [])
      .catch(() => null),
    api
      .request<{ items: ProductRank[]; total: number }>(
        `/api/v2/ops/admin/sales-reports?dim=PRODUCT&fromDate=${today}&toDate=${today}&page=0&size=20`,
        'GET'
      )
      .then((r) => r?.items ?? [])
      .catch(() => null),
    api
      .request<{ demoData?: boolean; label?: string }>('/api/v2/ops/admin/data-scope', 'GET')
      .catch(() => null)
  ]);
  stats.value = s;
  workbench.value = w;
  sla.value = sl;
  finance.value = f;
  kpi.value = k;
  channels.value = ch?.orderPayChannels ?? [];
  trend.value = t?.last7Days ?? [];
  deviceRanks.value = dr ?? [];
  productRanks.value = pr ?? [];
  demoBanner.value = scope?.demoData ? scope.label || '演示数据' : '';
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
    document.documentElement
      .requestFullscreen()
      .then(() => {
        isFullscreen.value = true;
      })
      .catch(() => {});
  }
}

function onFullscreenChange() {
  isFullscreen.value = !!document.fullscreenElement;
}

let clockTimer = 0;
let refreshTimer = 0;
onMounted(() => {
  tick();
  load();
  clockTimer = window.setInterval(tick, 1000);
  refreshTimer = window.setInterval(load, 30_000);
  document.addEventListener('fullscreenchange', onFullscreenChange);
});
onBeforeUnmount(() => {
  window.clearInterval(clockTimer);
  window.clearInterval(refreshTimer);
  document.removeEventListener('fullscreenchange', onFullscreenChange);
});
</script>

<style scoped>
.bigscreen {
  min-height: calc(100vh - 48px);
  padding: 18px;
  background: var(--layout-bg);
  color: var(--layout-text);
}
.bigscreen.is-fullscreen {
  position: fixed;
  inset: 0;
  z-index: 9999;
  overflow: auto;
}
.bs-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.bs-title {
  display: flex;
  align-items: baseline;
  gap: 12px;
}
.bs-logo {
  font-size: 24px;
  font-weight: 700;
  letter-spacing: 2px;
  color: var(--app-primary, #0f766e);
}
.bs-sub {
  font-size: 13px;
  color: var(--layout-muted);
}
.bs-demo-tag {
  font-size: 12px;
  font-weight: 600;
  color: #92400e;
  background: #fef3c7;
  border: 1px solid #f59e0b;
  border-radius: 4px;
  padding: 2px 8px;
}
.bs-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.bs-clock {
  font-size: 15px;
  color: var(--layout-muted);
  margin-right: 8px;
}
.bs-kpis {
  display: grid;
  grid-template-columns: repeat(8, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}
.bs-kpi {
  background: var(--layout-card);
  border: 1px solid var(--layout-border);
  border-radius: 10px;
  padding: 14px 12px;
}
.bs-kpi-label {
  font-size: 13px;
  color: var(--layout-muted);
}
.bs-kpi-value {
  font-size: 28px;
  font-weight: 700;
  margin: 6px 0 4px;
  color: var(--layout-text);
}
.bs-kpi-value.money {
  color: var(--app-primary, #0f766e);
}
.bs-kpi-value.warn {
  color: var(--el-color-danger, #ef4444);
}
.bs-kpi-hint {
  font-size: 12px;
  color: var(--layout-muted);
}
.bs-panels {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}
.bs-panel {
  background: var(--layout-card);
  border: 1px solid var(--layout-border);
  border-radius: 10px;
  padding: 14px;
}
.bs-panel--wide {
  grid-column: span 2;
}
.bs-panel--full {
  grid-column: 1 / -1;
}
.bs-panel-title {
  font-size: 14px;
  font-weight: 600;
  margin-bottom: 10px;
  color: var(--layout-muted);
}
.bs-panel-body {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.bs-row {
  display: flex;
  justify-content: space-between;
  font-size: 14px;
}
.bs-row span {
  color: var(--layout-muted);
}
.bs-row b {
  color: var(--layout-text);
  font-variant-numeric: tabular-nums;
}
.bs-row b.good {
  color: var(--app-primary, #0f766e);
}
.bs-row b.warn {
  color: var(--el-color-danger, #ef4444);
}
.bs-line {
  width: 100%;
  height: 150px;
  display: block;
}
.bs-line-labels {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: var(--layout-muted);
}
.bs-rank-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 10px;
}
.bs-rank {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  min-width: 0;
}
.bs-rank-idx {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  flex-shrink: 0;
  background: var(--app-primary, #0f766e);
  color: #fff;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
}
.bs-rank-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--layout-text);
}
.bs-rank-num {
  color: var(--layout-muted);
}
.bs-action {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: 13px;
}
.bs-action-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-top: 5px;
  flex-shrink: 0;
}
.bs-action-dot.is-danger {
  background: var(--el-color-danger, #ef4444);
}
.bs-action-dot.is-warn {
  background: #f59e0b;
}
.bs-action-dot.is-muted {
  background: var(--layout-muted);
}
.bs-action-main {
  min-width: 0;
}
.bs-action-title {
  color: var(--layout-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.bs-action-sub {
  color: var(--layout-muted);
  font-size: 12px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.bs-bar-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}
.bs-bar-label {
  width: 54px;
  color: var(--layout-muted);
}
.bs-bar {
  flex: 1;
  height: 10px;
  background: var(--layout-border);
  border-radius: 5px;
  overflow: hidden;
}
.bs-bar-fill {
  height: 100%;
  background: linear-gradient(
    90deg,
    var(--app-primary, #0f766e),
    color-mix(in srgb, var(--app-primary, #0f766e) 55%, #38bdf8)
  );
  border-radius: 5px;
}
.bs-bar-num {
  width: 150px;
  text-align: right;
  color: var(--layout-muted);
}
.bs-empty {
  color: var(--layout-muted);
  font-size: 13px;
}
.bs-risks {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
.bs-risk {
  background: var(--layout-card);
  border: 1px solid var(--layout-border);
  border-radius: 8px;
  padding: 8px 12px;
  font-size: 13px;
  color: var(--layout-muted);
}
.bs-risk b {
  margin-left: 6px;
  color: var(--layout-text);
}
.bs-risk b.warn {
  color: var(--el-color-danger, #ef4444);
}
@media (max-width: 1100px) {
  .bs-kpis {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }
  .bs-panels {
    grid-template-columns: 1fr;
  }
}
</style>
