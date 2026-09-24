<template>
  <div ref="rootRef" class="bs-root">
    <header class="bs-header">
      <div class="bs-side">
        <span class="bs-brand">AI 开门柜</span>
        <span class="bs-sub">售货机运营网络</span>
      </div>
      <div class="bs-title-wrap">
        <i class="bs-title-wing bs-title-wing--l" />
        <h1 class="bs-title">售货机运营态势大屏</h1>
        <i class="bs-title-wing bs-title-wing--r" />
      </div>
      <div class="bs-side bs-side--right">
        <span v-if="demoBanner" class="bs-demo-tag">{{ demoBanner }}</span>
        <span class="bs-clock"
          ><b>{{ clockTime }}</b
          ><i>{{ clockDate }}</i></span
        >
        <el-button class="bs-btn" :disabled="loading" @click="load">刷新</el-button>
        <el-button class="bs-btn" @click="toggleFullscreen">
          {{ isFullscreen ? '退出全屏' : '全屏' }}
        </el-button>
        <RouterLink class="bs-btn bs-btn--ghost" to="/dashboard">返回后台</RouterLink>
      </div>
    </header>

    <section class="bs-kpis">
      <div v-for="k in kpis" :key="k.label" class="bs-kpi">
        <span class="bs-kpi-icon" :style="{ '--tone': k.tone }">
          <el-icon :size="22"><component :is="k.icon" /></el-icon>
        </span>
        <div class="bs-kpi-meta">
          <b class="bs-kpi-value">{{ k.value }}</b>
          <span class="bs-kpi-label">{{ k.label }}</span>
          <span class="bs-kpi-hint">{{ k.hint }}</span>
        </div>
      </div>
    </section>

    <main class="bs-main">
      <div class="bs-col">
        <div class="bs-panel" style="flex: 1.15">
          <div class="bs-panel-title">
            <i class="bs-arrow" />销售趋势（近 10 日）<i class="bs-title-line" />
          </div>
          <div class="bs-panel-body">
            <EChart
              :option="trendOption"
              :loading="loading && !hydrated"
              empty-text="暂无趋势数据"
            />
          </div>
        </div>
        <div class="bs-panel" style="flex: 1">
          <div class="bs-panel-title">
            <i class="bs-arrow" />品类销售构成（近 30 天）<i class="bs-title-line" />
          </div>
          <div class="bs-panel-body bs-donut-layout">
            <EChart
              class="bs-donut-chart"
              :option="categoryOption"
              :loading="loading && !hydrated"
              empty-text="暂无品类数据"
            />
            <ul v-if="categoryParts.some((p) => p.value > 0)" class="bs-legend">
              <li v-for="p in categoryParts" :key="p.label">
                <i :style="{ background: p.color }" />
                <span class="bs-legend-name">{{ p.label }}</span>
                <span class="bs-legend-pct">{{ p.pct }}</span>
              </li>
            </ul>
          </div>
        </div>
        <div class="bs-panel" style="flex: 1">
          <div class="bs-panel-title">
            <i class="bs-arrow" />区域销售对比（今日）<i class="bs-title-line" />
          </div>
          <div class="bs-panel-body">
            <EChart
              :option="regionOption"
              :loading="loading && !hydrated"
              empty-text="暂无区域数据"
            />
          </div>
        </div>
      </div>

      <div class="bs-map">
        <i class="bs-corner tl" /><i class="bs-corner tr" /><i class="bs-corner bl" /><i
          class="bs-corner br"
        />
        <div ref="mapRef" class="bs-map-canvas" />
        <div v-if="!hydrated" class="bs-map-loading">地图数据加载中…</div>
        <div v-else-if="!mapPoints.length" class="bs-map-empty">暂无设备点位（含坐标）</div>
        <div class="bs-map-legend">
          <span><i class="lg-dot lg-online" />{{ onlineLabel('ONLINE') }} {{ onlineCount }}</span>
          <span
            ><i class="lg-dot lg-offline" />{{ onlineLabel('OFFLINE') }} {{ offlineCount }}</span
          >
        </div>
      </div>

      <div class="bs-col">
        <div class="bs-panel" style="flex: 1.1">
          <div class="bs-panel-title">
            <i class="bs-arrow" />点位销售排行（今日）<i class="bs-title-line" />
          </div>
          <div class="bs-panel-body bs-panel-body--list">
            <div
              v-for="(d, i) in topDevices"
              :key="d.deviceId"
              class="bs-rank"
              :class="{ top: i < 3 }"
            >
              <span class="bs-rank-idx" :class="`no${i + 1}`">{{ i + 1 }}</span>
              <div class="bs-rank-main">
                <div class="bs-rank-row">
                  <span class="bs-rank-name">{{ d.deviceName || d.deviceId }}</span>
                  <span class="bs-rank-num">{{ yuanShort(d.revenueTodayCents) }}</span>
                </div>
                <div class="bs-rank-bar">
                  <i :style="{ width: rankWidth(d.revenueTodayCents) }" />
                </div>
              </div>
            </div>
            <div v-if="hydrated && !topDevices.length" class="bs-empty">暂无货柜数据</div>
          </div>
        </div>

        <div class="bs-panel" style="flex: 1.15">
          <div class="bs-panel-title">
            <i class="bs-arrow" />点位运营明细<i class="bs-title-line" />
          </div>
          <div class="bs-panel-body bs-panel-body--list">
            <div class="bs-table-head">
              <span>点位</span><span>营收</span><span>订单</span><span>状态</span>
            </div>
            <div v-for="row in detailRows" :key="row.deviceId" class="bs-table-row">
              <span class="bs-cell-name">{{ row.name }}</span>
              <span class="bs-cell-num">{{ yuanShort(row.revenue) }}</span>
              <span class="bs-cell-num">{{ row.orders }}</span>
              <span class="bs-cell-status" :class="row.online ? 'is-ok' : 'is-bad'">
                <i />{{ row.online ? onlineLabel('ONLINE') : onlineLabel('OFFLINE') }}
              </span>
            </div>
            <div v-if="hydrated && !detailRows.length" class="bs-empty">暂无明细数据</div>
          </div>
        </div>

        <div class="bs-panel" style="flex: 1">
          <div class="bs-panel-title">
            <i class="bs-arrow" />待办 / 告警<i class="bs-title-line" />
          </div>
          <div class="bs-panel-body bs-panel-body--list">
            <div
              v-for="(item, idx) in actionItems"
              :key="idx"
              class="bs-action"
              :class="severityClass(item.severity)"
            >
              <i class="bs-action-bar" />
              <div class="bs-action-main">
                <div class="bs-action-title">{{ item.title }}</div>
                <div class="bs-action-sub">
                  {{ item.deviceId || displayBizNo(item.sessionId, '') || item.detail || '暂无' }}
                </div>
              </div>
            </div>
            <div v-if="hydrated && !actionItems.length" class="bs-empty">暂无待办</div>
          </div>
        </div>
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { RouterLink } from 'vue-router';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import { createSoftFailCollector } from '@/utils/soft-fallback';
import { displayLabel } from '@aicabinet/shared-dict';
import { displayBizNo } from '@aicabinet/shared-uni/format';
import {
  Monitor,
  Wallet,
  Tickets,
  TrendCharts,
  Connection,
  Warning
} from '@element-plus/icons-vue';
import EChart from '@/components/EChart.vue';
import {
  loadAmap,
  type AmapInfoWindow,
  type AmapMap,
  type AmapMarkerLike,
  type AmapNS
} from '@/utils/amap';
import {
  CHART_FONT_FAMILY,
  donutColor,
  donutOption,
  seriesOption,
  type EChartsOption
} from '@/utils/echarts';

/** KPI 卡配置：竞品风格（圆底图标 + 大数字 + 副标）。 */
const KPI_ICONS = { Monitor, Wallet, Tickets, TrendCharts, Connection, Warning } as const;

/* ---------- 数据形状（与后端 DTO 对应） ---------- */
interface AdminStats {
  deviceTotal: number;
  deviceOnline: number;
  orderToday: number;
  revenueTodayCents: number;
  orderTotal: number;
  revenueTotalCents: number;
  sessionActive: number;
}
interface Workbench {
  devicesOnSale: number;
  devicesSalesLocked: number;
  offlineDevices: number;
  openDisputes: number;
  overdueDisputes: number;
  actionItems: OpsActionItem[];
}
interface OpsActionItem {
  severity?: string;
  title: string;
  detail?: string;
  deviceId?: string;
  sessionId?: string;
}
interface SlaMetrics {
  deviceOnlineRate: number;
}
interface FinanceStats {
  grossMarginTodayCents: number;
  grossMarginRateToday: number;
}
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
interface MapPoint {
  deviceId: string;
  deviceName: string;
  onlineStatus: string;
  lifecycleStatus: string;
  routeCode: string;
  latitude: number | null;
  longitude: number | null;
  address: string;
}
interface CategoryRow {
  dimLabel: string;
  revenueCents: number;
}

/* ---------- 状态 ---------- */
const loading = ref(false);
const hydrated = ref(false);
const isFullscreen = ref(false);
const demoBanner = ref('');
const stats = ref<AdminStats | null>(null);
const workbench = ref<Workbench | null>(null);
const sla = ref<SlaMetrics | null>(null);
const finance = ref<FinanceStats | null>(null);
const trend = ref<DailyStat[]>([]);
const deviceRanks = ref<DeviceRank[]>([]);
const categoryRows = ref<CategoryRow[]>([]);
const mapPoints = ref<MapPoint[]>([]);

const rootRef = ref<HTMLDivElement | null>(null);
const mapRef = ref<HTMLDivElement | null>(null);
/**
 * 地图引擎：key 可用时用高德官方暗色底图，否则降级 Leaflet 免 key 瓦片。
 * key 来源优先级见 `@/utils/amap`：运行时 `runtime-config.json` → 构建时 `VITE_AMAP_JS_KEY`。
 */
type MapEngine = 'amap' | 'leaflet';
let mapEngine: MapEngine = 'leaflet';
let map: L.Map | null = null;
let markerLayer: L.LayerGroup | null = null;
let amapNS: AmapNS | null = null;
let amapMap: AmapMap | null = null;
let amapInfoWindow: AmapInfoWindow | null = null;
const amapMarkers: AmapMarkerLike[] = [];

/* ---------- KPI ---------- */
const onlineCount = computed(
  () => mapPoints.value.filter((p) => p.onlineStatus === 'ONLINE').length
);
const offlineCount = computed(
  () => mapPoints.value.filter((p) => p.onlineStatus !== 'ONLINE').length
);
const kpis = computed(() => [
  {
    icon: KPI_ICONS.Monitor,
    tone: '#2dd4bf',
    label: '售货机总数',
    value: String(stats.value?.deviceTotal ?? 0),
    hint: `在售 ${workbench.value?.devicesOnSale ?? 0}`
  },
  {
    icon: KPI_ICONS.Wallet,
    tone: '#38bdf8',
    label: '今日营收',
    value: yuan(stats.value?.revenueTodayCents),
    hint: `累计 ${yuan(stats.value?.revenueTotalCents)}`
  },
  {
    icon: KPI_ICONS.Tickets,
    tone: '#a78bfa',
    label: '今日订单',
    value: String(stats.value?.orderToday ?? 0),
    hint: `累计 ${stats.value?.orderTotal ?? 0}`
  },
  {
    icon: KPI_ICONS.TrendCharts,
    tone: '#4ade80',
    label: '今日毛利',
    value: yuan(finance.value?.grossMarginTodayCents),
    hint: `毛利率 ${pct(finance.value?.grossMarginRateToday)}`
  },
  {
    icon: KPI_ICONS.Connection,
    tone: '#fbbf24',
    label: '设备在线率',
    value: pct(sla.value?.deviceOnlineRate),
    hint: `离线 ${workbench.value?.offlineDevices ?? 0}`
  },
  {
    icon: KPI_ICONS.Warning,
    tone: '#f87171',
    label: '待处理争议',
    value: String(workbench.value?.openDisputes ?? 0),
    hint: `逾期 ${workbench.value?.overdueDisputes ?? 0}`
  }
]);

/* ---------- 图表 ---------- */
const trendOption = computed<EChartsOption | null>(() => {
  const days = trend.value;
  if (!hydrated.value || !days.length) return null;
  const axisColor = '#8fa8c7';
  const grid = '#4090c733';
  return {
    grid: { left: 6, right: 6, top: 30, bottom: 2, containLabel: true },
    legend: {
      top: 0,
      right: 0,
      textStyle: { color: axisColor, fontSize: 11, fontFamily: CHART_FONT_FAMILY },
      itemWidth: 12,
      itemHeight: 8
    },
    tooltip: {
      trigger: 'axis',
      backgroundColor: 'rgba(8, 20, 38, 0.88)',
      borderWidth: 0,
      textStyle: { color: '#d7e7ff', fontSize: 12, fontFamily: CHART_FONT_FAMILY }
    },
    xAxis: {
      type: 'category',
      data: days.map((d) => (d.date ? d.date.slice(5) : '')),
      axisLabel: { color: axisColor, fontSize: 10, fontFamily: CHART_FONT_FAMILY },
      axisLine: { lineStyle: { color: grid } },
      axisTick: { show: false }
    },
    yAxis: [
      {
        type: 'value',
        name: '单',
        nameTextStyle: { color: axisColor },
        axisLabel: { color: axisColor, fontSize: 10, fontFamily: CHART_FONT_FAMILY },
        splitLine: { lineStyle: { color: 'rgba(148, 197, 255, 0.14)', type: 'dashed' } }
      },
      {
        type: 'value',
        name: '元',
        nameTextStyle: { color: axisColor },
        axisLabel: {
          color: axisColor,
          fontSize: 10,
          fontFamily: CHART_FONT_FAMILY,
          formatter: (v: number) => (v >= 1000 ? `${(v / 1000).toFixed(1)}k` : `${v}`)
        },
        splitLine: { show: false }
      }
    ],
    series: [
      {
        name: '订单量',
        type: 'bar',
        data: days.map((d) => d.orderCount),
        yAxisIndex: 0,
        barMaxWidth: 14,
        itemStyle: {
          borderRadius: [3, 3, 0, 0],
          color: {
            type: 'linear',
            x: 0,
            y: 0,
            x2: 0,
            y2: 1,
            colorStops: [
              { offset: 0, color: '#38bdf8' },
              { offset: 1, color: 'rgba(56, 189, 248, 0.25)' }
            ]
          }
        }
      },
      {
        name: '营收（元）',
        type: 'line',
        data: days.map((d) => Math.round(d.revenueCents) / 100),
        yAxisIndex: 1,
        smooth: true,
        symbol: 'circle',
        symbolSize: 6,
        itemStyle: { color: '#2dd4bf' },
        lineStyle: { width: 2.5, color: '#2dd4bf' },
        areaStyle: {
          color: {
            type: 'linear',
            x: 0,
            y: 0,
            x2: 0,
            y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(45, 212, 191, 0.35)' },
              { offset: 1, color: 'rgba(45, 212, 191, 0.02)' }
            ]
          }
        }
      }
    ]
  };
});

const categoryParts = computed(() => {
  const rows = categoryRows.value.slice(0, 6).map((r, i) => ({
    label: r.dimLabel,
    value: r.revenueCents,
    color: donutColor(i)
  }));
  const total = rows.reduce((s, r) => s + Math.max(r.value, 0), 0);
  return rows
    .filter((r) => r.value > 0)
    .map((r) => ({ ...r, pct: total > 0 ? `${((r.value / total) * 100).toFixed(1)}%` : '0%' }));
});

const categoryOption = computed<EChartsOption | null>(() => {
  const parts = categoryParts.value;
  if (!hydrated.value || !parts.length) return null;
  return donutOption({
    parts: parts.map((p) => ({ label: p.label, value: p.value, color: p.color })),
    dark: true,
    radius: ['58%', '82%'],
    formatCenter: (cents) => (cents / 100).toFixed(0),
    formatValue: (cents) => `¥${(cents / 100).toFixed(2)}`,
    valueLabel: '营收'
  });
});

const regionOption = computed<EChartsOption | null>(() => {
  if (!hydrated.value) return null;
  const revenueByRoute = new Map<string, number>();
  const routeOf = new Map(mapPoints.value.map((p) => [p.deviceId, p.routeCode || '']));
  for (const d of deviceRanks.value) {
    const label = routeOf.get(d.deviceId) || '未分组';
    revenueByRoute.set(label, (revenueByRoute.get(label) ?? 0) + d.revenueTodayCents);
  }
  const entries = [...revenueByRoute.entries()].sort((a, b) => a[1] - b[1]).slice(-8);
  if (!entries.length) return null;
  return seriesOption({
    labels: entries.map(([k]) => k),
    series: [
      {
        name: '今日营收（元）',
        values: entries.map(([, v]) => Math.round(v) / 100),
        color: '#38bdf8',
        kind: 'bar'
      }
    ],
    kind: 'bar',
    horizontal: true,
    dark: true,
    formatY: (v) => `${Math.round(v)}`,
    formatValue: (v) => `¥${v.toFixed(2)}`
  });
});

/* ---------- 排行 / 明细 / 告警 ---------- */
const topDevices = computed(() =>
  [...deviceRanks.value].sort((a, b) => b.revenueTodayCents - a.revenueTodayCents).slice(0, 5)
);
/** 地图上常显标注的点位（营收 TOP3）。 */
const top3DeviceIds = computed(() => new Set(topDevices.value.slice(0, 3).map((d) => d.deviceId)));
const maxRankRevenue = computed(() =>
  Math.max(1, ...topDevices.value.map((d) => d.revenueTodayCents))
);
function rankWidth(cents: number) {
  return `${Math.max(4, Math.round((cents / maxRankRevenue.value) * 100))}%`;
}

const rankByDevice = ref(new Map<string, DeviceRank>());
const detailRows = computed(() =>
  mapPoints.value
    .map((p) => {
      const rank = rankByDevice.value.get(p.deviceId);
      return {
        deviceId: p.deviceId,
        name: p.deviceName || p.deviceId,
        revenue: rank?.revenueTodayCents ?? 0,
        orders: rank?.orderToday ?? 0,
        online: p.onlineStatus === 'ONLINE'
      };
    })
    .sort((a, b) => b.revenue - a.revenue)
    .slice(0, 7)
);

const actionItems = computed(() => (workbench.value?.actionItems ?? []).slice(0, 6));
function severityClass(severity?: string) {
  const s = (severity || '').toUpperCase();
  if (s === 'CRITICAL' || s === 'HIGH') return 'is-danger';
  if (s === 'MEDIUM') return 'is-warn';
  return 'is-muted';
}

/* ---------- 格式化 ---------- */
function yuan(cents?: number | null) {
  if (cents == null) return '暂无';
  return `¥${(cents / 100).toFixed(2)}`;
}
function yuanShort(cents: number) {
  const v = cents / 100;
  if (v >= 10000) return `¥${(v / 10000).toFixed(1)}w`;
  return `¥${v.toFixed(v >= 100 ? 0 : 2)}`;
}
function pct(v?: number | null) {
  if (v == null) return '未统计';
  return `${(v * 100).toFixed(1)}%`;
}

/* ---------- 地图 ---------- */
const STATUS_COLOR: Record<string, string> = {
  ONLINE: '#2dd4bf',
  OFFLINE: '#ef4444'
};
function markerColor(p: MapPoint): string {
  return STATUS_COLOR[p.onlineStatus] ?? '#fbbf24';
}
/** 状态标签统一走字典（ESLint local/no-hardcoded-status-label） */
function onlineLabel(status: string): string {
  return displayLabel('online_status', status, status === 'ONLINE' ? '在线' : '离线');
}
function escapeHtml(v: string): string {
  return String(v ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;');
}

function renderMarkers() {
  const pts = mapPoints.value.filter((p) => p.latitude != null && p.longitude != null);
  const maxRev = Math.max(1, ...deviceRanks.value.map((d) => d.revenueTodayCents));
  const revOf = rankByDevice.value;
  if (mapEngine === 'amap') {
    renderMarkersAmap(pts, maxRev, revOf);
  } else {
    renderMarkersLeaflet(pts, maxRev, revOf);
  }
}

interface MapPointInput {
  p: MapPoint;
  revenue: number;
  size: number;
  color: string;
  online: boolean;
  labeled: boolean;
}

function markerInput(
  pts: MapPoint[],
  maxRev: number,
  revOf: Map<string, DeviceRank>
): MapPointInput[] {
  return pts.map((p) => {
    const revenue = revOf.get(p.deviceId)?.revenueTodayCents ?? 0;
    return {
      p,
      revenue,
      size: 14 + Math.round((revenue / maxRev) * 12), // 14~26px，销售越好点越大
      color: markerColor(p),
      online: p.onlineStatus === 'ONLINE',
      labeled: top3DeviceIds.value.has(p.deviceId)
    };
  });
}

function markerHtml(m: MapPointInput): string {
  return (
    `<div class="bs-dot${m.online ? '' : ' is-offline'}" style="--c:${m.color};width:${m.size}px;height:${m.size}px"></div>` +
    (m.labeled
      ? `<span class="bs-dot-label">${escapeHtml(m.p.deviceName || m.p.deviceId)} · ${yuanShort(m.revenue)}</span>`
      : '')
  );
}

function popupHtml(m: MapPointInput): string {
  const route = m.p.routeCode ? ` · 线路 ${escapeHtml(m.p.routeCode)}` : '';
  const inner = `<div class="bs-popup">
          <b>${escapeHtml(m.p.deviceName || m.p.deviceId)}</b>
          <span>状态：${onlineLabel(m.p.onlineStatus)}${route}</span>
          <span>今日营收：${yuan(m.revenue)}</span>
          ${m.p.address ? `<span class="muted">${escapeHtml(m.p.address)}</span>` : ''}
        </div>`;
  // AMap 自定义 InfoWindow 无外壳，需要自带深色容器；Leaflet 默认白底弹窗直接用 inner
  return mapEngine === 'amap' ? `<div class="bs-iw">${inner}</div>` : inner;
}

function renderMarkersAmap(pts: MapPoint[], maxRev: number, revOf: Map<string, DeviceRank>) {
  if (!amapMap || !amapNS) return;
  amapMap.clearMap();
  amapMarkers.length = 0;
  for (const m of markerInput(pts, maxRev, revOf)) {
    const marker = new amapNS.Marker({
      position: [m.p.longitude as number, m.p.latitude as number],
      content: markerHtml(m),
      anchor: 'center',
      zIndex: m.online ? 120 : 130
    });
    marker.on('click', () => {
      if (!amapInfoWindow || !amapMap) return;
      amapInfoWindow.setContent(popupHtml(m));
      amapInfoWindow.open(amapMap, [m.p.longitude as number, m.p.latitude as number]);
    });
    amapMarkers.push(marker);
  }
  amapMap.add(amapMarkers);
  amapMap.setFitView(amapMarkers.length ? amapMarkers : null, false, [70, 70, 70, 70]);
}

function renderMarkersLeaflet(pts: MapPoint[], maxRev: number, revOf: Map<string, DeviceRank>) {
  if (!map || !markerLayer) return;
  markerLayer.clearLayers();
  for (const m of markerInput(pts, maxRev, revOf)) {
    const icon = L.divIcon({
      className: 'bs-marker',
      html: markerHtml(m),
      iconSize: [m.size, m.size],
      iconAnchor: [m.size / 2, m.size / 2]
    });
    L.marker([m.p.latitude as number, m.p.longitude as number], { icon })
      .bindPopup(popupHtml(m))
      .addTo(markerLayer);
  }
  if (pts.length === 1) {
    map.setView([pts[0].latitude as number, pts[0].longitude as number], 13);
  } else if (pts.length > 1) {
    map.fitBounds(
      L.latLngBounds(pts.map((p) => L.latLng(p.latitude as number, p.longitude as number))).pad(
        0.25
      )
    );
  }
}

/** 引擎选择：高德可用（运行时/构建时配置了 key 且加载成功）则用官方暗色底图，否则 Leaflet。 */
async function ensureMapEngine() {
  const ns = await loadAmap().catch(() => null);
  if (ns && mapRef.value) {
    mapEngine = 'amap';
    amapNS = ns;
    amapMap = new ns.Map(mapRef.value, {
      // 深青蓝（暗色系）：`dark` 偏褐灰、路网对比低，在大屏上观感发闷；
      // `blue` 保留深色底同时路网清晰，与面板青绿发光风格更搭（2026-09-22 实拍对比选定）。
      mapStyle: 'amap://styles/blue',
      viewMode: '2D',
      zoom: 11,
      center: [113.75, 23.02],
      zooms: [3, 18]
    });
    amapInfoWindow = new ns.InfoWindow({
      isCustom: true,
      closeWhenClickMap: true,
      offset: new ns.Pixel(0, -20)
    });
    return;
  }
  mapEngine = 'leaflet';
  ensureLeafletMap();
}

function ensureLeafletMap() {
  if (map || !mapRef.value) return;
  map = L.map(mapRef.value, {
    zoomControl: false,
    attributionControl: false,
    preferCanvas: false
  }).setView([35.86, 104.19], 4);
  // 大屏固定暗色：高德路网 + CSS 反相成深色底图（失败降级 Esri / GeoQ）
  const layers = [
    L.tileLayer(
      'https://webrd0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}',
      {
        maxZoom: 18,
        subdomains: '1234'
      }
    ),
    L.tileLayer(
      'https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/{z}/{y}/{x}',
      {
        maxZoom: 19
      }
    ),
    L.tileLayer(
      'https://map.geoq.cn/ArcGIS/rest/services/ChinaOnlineCommunity/MapServer/tile/{z}/{y}/{x}',
      {
        maxZoom: 16
      }
    )
  ];
  const attach = (i: number) => {
    if (!map || i >= layers.length) return;
    const layer = layers[i];
    let errors = 0;
    layer.on('tileerror', () => {
      errors += 1;
      if (!map || !map.hasLayer(layer) || errors < 6) return;
      map.removeLayer(layer);
      attach(i + 1);
    });
    layer.addTo(map);
  };
  attach(0);
  markerLayer = L.layerGroup().addTo(map);
}

/* ---------- 数据加载 ---------- */
function daysAgoStr(days: number) {
  const d = new Date();
  d.setDate(d.getDate() - days);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

async function load() {
  loading.value = true;
  const today = daysAgoStr(0);
  const { soft, flush } = createSoftFailCollector();
  const [s, w, sl, f, t, dr, cr, mp, scope] = await Promise.all([
    soft(api.request<AdminStats>(AdminEndpoints.stats, 'GET'), null, '运营统计'),
    soft(api.request<Workbench>(AdminEndpoints.workbench, 'GET'), null, '工作台'),
    soft(api.request<SlaMetrics>(AdminEndpoints.sla, 'GET'), null, 'SLA'),
    soft(api.request<FinanceStats>(AdminEndpoints.financeStats, 'GET'), null, '财务统计'),
    soft(
      api.request<{ last7Days: DailyStat[] }>(AdminEndpoints.trend(10), 'GET'),
      null,
      '趋势'
    ),
    soft(
      api
        .request<{ items: DeviceRank[]; total: number }>(
          AdminEndpoints.reportsDevicesList('page=0&size=50'),
          'GET'
        )
        .then((r) => r?.items ?? []),
      [] as DeviceRank[],
      '设备报表'
    ),
    soft(
      api
        .request<{ items: CategoryRow[]; total: number }>(
          AdminEndpoints.salesReportsList(
            `dim=CATEGORY&fromDate=${daysAgoStr(29)}&toDate=${today}&page=0&size=50`
          ),
          'GET'
        )
        .then((r) => r?.items ?? []),
      [] as CategoryRow[],
      '品类销售'
    ),
    soft(api.request<MapPoint[]>(AdminEndpoints.devicesMapPoints(''), 'GET'), [], '地图点位'),
    soft(
      api.request<{ demoData?: boolean; label?: string }>(AdminEndpoints.dataScope, 'GET'),
      null,
      '数据范围'
    )
  ]);
  flush();
  stats.value = s;
  workbench.value = w;
  sla.value = sl;
  finance.value = f;
  trend.value = t?.last7Days ?? [];
  deviceRanks.value = dr;
  categoryRows.value = cr;
  mapPoints.value = Array.isArray(mp) ? mp : [];
  const rankMap = new Map<string, DeviceRank>();
  for (const d of deviceRanks.value) rankMap.set(d.deviceId, d);
  rankByDevice.value = rankMap;
  demoBanner.value = scope?.demoData ? scope.label || '演示数据' : '';
  hydrated.value = true;
  loading.value = false;
  renderMarkers();
}

/* ---------- 时钟 / 全屏 / 生命周期 ---------- */
const clockTime = ref('');
const clockDate = ref('');
function tick() {
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  clockTime.value = `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
  clockDate.value = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} 星期${'日一二三四五六'[d.getDay()]}`;
}

async function toggleFullscreen() {
  try {
    if (document.fullscreenElement) {
      await document.exitFullscreen();
    } else {
      await rootRef.value?.requestFullscreen();
    }
  } catch {
    /* 浏览器拒绝（如非用户手势）时静默 */
  }
}
function onFullscreenChange() {
  isFullscreen.value = !!document.fullscreenElement;
}

let clockTimer: ReturnType<typeof globalThis.setInterval> | 0 = 0;
let refreshTimer: ReturnType<typeof globalThis.setInterval> | 0 = 0;
onMounted(async () => {
  tick();
  await load();
  await ensureMapEngine();
  renderMarkers();
  isFullscreen.value = !!document.fullscreenElement;
  clockTimer = globalThis.setInterval(tick, 1000);
  refreshTimer = globalThis.setInterval(load, 30_000);
  document.addEventListener('fullscreenchange', onFullscreenChange);
});
onBeforeUnmount(() => {
  globalThis.clearInterval(clockTimer);
  globalThis.clearInterval(refreshTimer);
  document.removeEventListener('fullscreenchange', onFullscreenChange);
  if (amapMap) {
    amapMap.destroy();
    amapMap = null;
  }
  map?.remove();
  map = null;
  markerLayer = null;
});
</script>

<style scoped>
.bs-root {
  position: fixed;
  inset: 0;
  z-index: var(--z-fullscreen, 9999);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background:
    radial-gradient(90% 55% at 50% -8%, rgba(45, 212, 191, 0.12), transparent 58%),
    radial-gradient(60% 45% at 100% 100%, rgba(56, 189, 248, 0.07), transparent 60%),
    radial-gradient(60% 45% at 0% 100%, rgba(99, 102, 241, 0.06), transparent 60%),
    linear-gradient(180deg, #071120 0%, #050d1a 100%);
  color: #d7e7ff;
  font-family: 'Segoe UI', 'Microsoft YaHei', 'PingFang SC', sans-serif;
}
.bs-root::before {
  content: '';
  position: absolute;
  inset: 0;
  pointer-events: none;
  background-image:
    linear-gradient(rgba(64, 144, 255, 0.04) 1px, transparent 1px),
    linear-gradient(90deg, rgba(64, 144, 255, 0.04) 1px, transparent 1px);
  background-size: 48px 48px;
}

/* ---------- 头部 ---------- */
.bs-header {
  position: relative;
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  align-items: center;
  gap: 16px;
  height: 68px;
  padding: 0 20px;
  background: linear-gradient(180deg, rgba(12, 32, 60, 0.92), rgba(7, 20, 38, 0.4));
  border-bottom: 1px solid rgba(64, 144, 255, 0.22);
}
.bs-side {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.bs-side--right {
  justify-content: flex-end;
  flex-wrap: wrap;
}
.bs-brand {
  font-size: 20px;
  font-weight: 700;
  letter-spacing: 3px;
  color: #2dd4bf;
  text-shadow: 0 0 18px rgba(45, 212, 191, 0.55);
  white-space: nowrap;
}
.bs-sub {
  font-size: 12px;
  color: #8fa8c7;
  white-space: nowrap;
}
.bs-title-wrap {
  display: flex;
  align-items: center;
  gap: 18px;
  min-width: 0;
}
.bs-title {
  margin: 0;
  font-size: 28px;
  font-weight: 700;
  letter-spacing: 10px;
  text-indent: 10px;
  text-align: center;
  color: #f2f9ff;
  white-space: nowrap;
  text-shadow:
    0 0 26px rgba(56, 189, 248, 0.5),
    0 2px 10px rgba(0, 0, 0, 0.5);
}
/* 竞品式标题两翼：渐变线 + 斜切亮块 */
.bs-title-wing {
  width: clamp(60px, 12vw, 220px);
  height: 12px;
  position: relative;
}
.bs-title-wing::before {
  content: '';
  position: absolute;
  top: 50%;
  left: 0;
  right: 0;
  height: 2px;
  transform: translateY(-50%);
  background: linear-gradient(90deg, transparent, rgba(45, 212, 191, 0.9));
}
.bs-title-wing--r::before {
  background: linear-gradient(90deg, rgba(45, 212, 191, 0.9), transparent);
}
.bs-title-wing::after {
  content: '';
  position: absolute;
  top: 50%;
  width: 34px;
  height: 8px;
  transform: translateY(-50%) skewX(-30deg);
  background: linear-gradient(90deg, rgba(45, 212, 191, 0.15), rgba(45, 212, 191, 0.85));
}
.bs-title-wing--l::after {
  right: 0;
}
.bs-title-wing--r::after {
  left: 0;
  transform: translateY(-50%) skewX(30deg);
  background: linear-gradient(90deg, rgba(45, 212, 191, 0.85), rgba(45, 212, 191, 0.15));
}
.bs-clock {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  line-height: 1.25;
  margin-right: 6px;
}
.bs-clock b {
  font-size: 18px;
  font-weight: 700;
  color: #eaf5ff;
  font-variant-numeric: tabular-nums;
  letter-spacing: 1px;
}
.bs-clock i {
  font-style: normal;
  font-size: 11px;
  color: #8fa8c7;
}
.bs-demo-tag {
  font-size: 12px;
  font-weight: 600;
  color: #fcd34d;
  background: rgba(251, 191, 36, 0.12);
  border: 1px solid rgba(251, 191, 36, 0.45);
  border-radius: 4px;
  padding: 1px 8px;
  white-space: nowrap;
}
/* 大屏极简按钮。动作按钮走 el-button —— admin-table-gate 禁止视图内出现原生 button 元素。
   `.bs-root` 前缀用于提高权重（.bs-root .bs-btn = 2 类，scoped 后为 3），压过
   element-plus 的 `.el-button` / `.el-button.is-disabled` 默认态；
   `.el-button` 后缀块只做「抹平」：height:32px / line-height:1 / font-weight:500 /
   相邻按钮 margin-left，其余视觉与改造前逐条一致。 */
.bs-root .bs-btn {
  font-size: 12px;
  color: #d7e7ff;
  background: rgba(45, 212, 191, 0.08);
  border: 1px solid rgba(45, 212, 191, 0.35);
  border-radius: 4px;
  padding: 4px 10px;
  cursor: pointer;
  transition: background 0.2s ease;
  text-decoration: none;
  white-space: nowrap;
}
.bs-root .bs-btn.el-button {
  height: auto;
  margin-left: 0;
  font-weight: 400;
  line-height: normal;
}
.bs-root .bs-btn:hover,
.bs-root .bs-btn:focus {
  color: #d7e7ff;
  background: rgba(45, 212, 191, 0.2);
}
.bs-root .bs-btn:disabled,
.bs-root .bs-btn.is-disabled {
  color: #d7e7ff;
  background: rgba(45, 212, 191, 0.08);
  border-color: rgba(45, 212, 191, 0.35);
  opacity: 0.55;
  cursor: default;
}
.bs-root .bs-btn--ghost {
  color: #8fa8c7;
  border-color: rgba(143, 168, 199, 0.35);
  background: transparent;
}
.bs-root .bs-btn--ghost:hover,
.bs-root .bs-btn--ghost:focus {
  color: #d7e7ff;
  background: rgba(143, 168, 199, 0.12);
}

/* ---------- KPI：圆底图标 + 大数字 ---------- */
.bs-kpis {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 12px;
  padding: 12px 20px 0;
  position: relative;
}
.bs-kpi {
  /* 全局 .stat-tile/.bs-kpi 枚举规则是纵排；这里显式横排覆盖 */
  display: flex;
  flex-direction: row;
  align-items: center;
  justify-content: flex-start;
  gap: 12px;
  padding: 12px 14px;
  text-align: left;
  background: linear-gradient(135deg, rgba(20, 48, 86, 0.66), rgba(9, 24, 46, 0.55));
  border: 1px solid rgba(64, 144, 255, 0.2);
  border-radius: 8px;
  box-shadow: inset 0 0 26px rgba(45, 212, 191, 0.045);
}
.bs-kpi-icon {
  --tone: #2dd4bf;
  width: 46px;
  height: 46px;
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  color: var(--tone);
  background:
    radial-gradient(circle at 32% 28%, rgba(255, 255, 255, 0.16), transparent 46%),
    color-mix(in srgb, var(--tone) 16%, transparent);
  border: 1px solid color-mix(in srgb, var(--tone) 55%, transparent);
  box-shadow: 0 0 14px color-mix(in srgb, var(--tone) 30%, transparent);
}
.bs-kpi-meta {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.bs-kpi-value {
  font-size: 24px;
  font-weight: 700;
  line-height: 1.2;
  color: #f2f9ff;
  font-variant-numeric: tabular-nums;
  text-shadow: 0 0 16px rgba(56, 189, 248, 0.4);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.bs-kpi-label {
  font-size: 12px;
  color: #cfe3ff;
  margin-top: 1px;
}
.bs-kpi-hint {
  font-size: 11px;
  color: #6c86a8;
}

/* ---------- 主体三栏 ---------- */
.bs-main {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(300px, 360px) minmax(0, 1fr) minmax(300px, 360px);
  gap: 14px;
  padding: 14px 20px 16px;
  position: relative;
}
.bs-col {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-height: 0;
  min-width: 0;
}
.bs-panel {
  --corner: rgba(45, 212, 191, 0.85);
  --cs: 13px;
  display: flex;
  flex-direction: column;
  min-height: 0;
  padding: 0 14px 12px;
  background:
    linear-gradient(var(--corner), var(--corner)) left 0 top 0 / var(--cs) 2px,
    linear-gradient(var(--corner), var(--corner)) left 0 top 0 / 2px var(--cs),
    linear-gradient(var(--corner), var(--corner)) right 0 top 0 / var(--cs) 2px,
    linear-gradient(var(--corner), var(--corner)) right 0 top 0 / 2px var(--cs),
    linear-gradient(var(--corner), var(--corner)) left 0 bottom 0 / var(--cs) 2px,
    linear-gradient(var(--corner), var(--corner)) left 0 bottom 0 / 2px var(--cs),
    linear-gradient(var(--corner), var(--corner)) right 0 bottom 0 / var(--cs) 2px,
    linear-gradient(var(--corner), var(--corner)) right 0 bottom 0 / 2px var(--cs),
    linear-gradient(180deg, rgba(17, 44, 80, 0.6), rgba(8, 22, 42, 0.68));
  background-repeat: no-repeat;
  border: 1px solid rgba(64, 144, 255, 0.18);
  border-radius: 2px;
  backdrop-filter: blur(4px);
}
.bs-panel-title {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 38px;
  margin: 0 -14px 6px;
  padding: 0 12px;
  font-size: 14px;
  font-weight: 600;
  letter-spacing: 1.5px;
  color: #f2f9ff;
  background: linear-gradient(
    90deg,
    rgba(45, 212, 191, 0.14),
    rgba(56, 189, 248, 0.05) 42%,
    transparent 78%
  );
  border-bottom: 1px solid rgba(64, 144, 255, 0.14);
  white-space: nowrap;
}
.bs-arrow {
  width: 0;
  height: 0;
  flex-shrink: 0;
  border-left: 8px solid #2dd4bf;
  border-top: 5px solid transparent;
  border-bottom: 5px solid transparent;
  filter: drop-shadow(0 0 6px rgba(45, 212, 191, 0.8));
}
.bs-title-line {
  flex: 1;
  height: 2px;
  margin-left: 6px;
  background: linear-gradient(
    90deg,
    rgba(45, 212, 191, 0.55),
    rgba(56, 189, 248, 0.2) 55%,
    transparent
  );
  min-width: 30px;
}
.bs-panel-body {
  flex: 1;
  min-height: 110px;
}
.bs-panel-body--list {
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  scrollbar-width: thin;
  scrollbar-color: rgba(64, 144, 255, 0.4) transparent;
}

/* ---------- 品类：图左例右 ---------- */
.bs-donut-layout {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 0;
}
.bs-donut-chart {
  flex: 1.05;
  min-width: 0;
  height: 100%;
}
.bs-legend {
  flex: 1;
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
}
.bs-legend li {
  display: grid;
  grid-template-columns: 10px minmax(0, 1fr) auto;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: #cfe3ff;
}
.bs-legend li i {
  width: 8px;
  height: 8px;
  border-radius: 2px;
}
.bs-legend-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.bs-legend-pct {
  color: #8fa8c7;
  font-variant-numeric: tabular-nums;
}

/* ---------- 地图 ---------- */
.bs-map {
  position: relative;
  min-width: 0;
  border: 1px solid rgba(64, 144, 255, 0.22);
  border-radius: 2px;
  overflow: hidden;
  background: rgba(6, 16, 32, 0.5);
}
.bs-corner {
  position: absolute;
  width: 26px;
  height: 26px;
  z-index: 500;
  pointer-events: none;
  border: 2px solid rgba(45, 212, 191, 0.75);
  filter: drop-shadow(0 0 6px rgba(45, 212, 191, 0.45));
}
.bs-corner.tl {
  top: 0;
  left: 0;
  border-right: 0;
  border-bottom: 0;
}
.bs-corner.tr {
  top: 0;
  right: 0;
  border-left: 0;
  border-bottom: 0;
}
.bs-corner.bl {
  bottom: 0;
  left: 0;
  border-right: 0;
  border-top: 0;
}
.bs-corner.br {
  bottom: 0;
  right: 0;
  border-left: 0;
  border-top: 0;
}
.bs-map-canvas {
  position: absolute;
  inset: 0;
}
.bs-map :deep(.leaflet-tile-pane) {
  /* 亮色瓦片反相为暗色底图 */
  filter: invert(1) hue-rotate(200deg) brightness(0.82) contrast(0.92) saturate(0.6);
}
.bs-map :deep(.leaflet-container) {
  background: #07111f;
  font-family: inherit;
}
.bs-map-legend {
  position: absolute;
  left: 50%;
  bottom: 12px;
  transform: translateX(-50%);
  z-index: 500;
  display: flex;
  gap: 16px;
  padding: 6px 14px;
  font-size: 12px;
  color: #d7e7ff;
  background: rgba(8, 22, 42, 0.8);
  border: 1px solid rgba(64, 144, 255, 0.28);
  border-radius: 999px;
}
.lg-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: 5px;
}
.lg-online {
  background: #2dd4bf;
  box-shadow: 0 0 8px #2dd4bf;
}
.lg-offline {
  background: #ef4444;
  box-shadow: 0 0 8px #ef4444;
}
.bs-map-loading,
.bs-map-empty {
  position: absolute;
  inset: 0;
  z-index: 500;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  color: #8fa8c7;
  background: rgba(6, 16, 32, 0.55);
}

/* ---------- 排行 ---------- */
.bs-rank {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 7px 2px;
}
.bs-rank + .bs-rank {
  border-top: 1px solid rgba(64, 144, 255, 0.1);
}
.bs-rank-idx {
  width: 26px;
  height: 22px;
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 700;
  font-style: italic;
  color: #8fa8c7;
  background: rgba(64, 144, 255, 0.12);
  clip-path: polygon(0 0, 100% 0, 78% 100%, 0 100%);
}
.bs-rank-idx.no1 {
  color: #fff;
  background: linear-gradient(135deg, #f43f5e, #fb7185);
}
.bs-rank-idx.no2 {
  color: #fff;
  background: linear-gradient(135deg, #f59e0b, #fbbf24);
}
.bs-rank-idx.no3 {
  color: #07111f;
  background: linear-gradient(135deg, #eab308, #facc15);
}
.bs-rank.top .bs-rank-num {
  color: #2dd4bf;
}
.bs-rank-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.bs-rank-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
}
.bs-rank-name {
  font-size: 12.5px;
  color: #eaf5ff;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.bs-rank-num {
  font-size: 12.5px;
  color: #8fa8c7;
  font-variant-numeric: tabular-nums;
  flex-shrink: 0;
}
.bs-rank-bar {
  height: 6px;
  border-radius: 3px;
  background: rgba(64, 144, 255, 0.12);
  overflow: hidden;
}
.bs-rank-bar i {
  display: block;
  height: 100%;
  border-radius: 3px;
  background: linear-gradient(90deg, rgba(45, 212, 191, 0.9), rgba(56, 189, 248, 0.9));
  box-shadow: 0 0 8px rgba(45, 212, 191, 0.55);
  transition: width 0.6s ease;
}

/* ---------- 明细表 ---------- */
.bs-table-head,
.bs-table-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 64px 44px 52px;
  gap: 6px;
  align-items: center;
  font-size: 12px;
  padding: 5px 4px;
}
.bs-table-head {
  color: #8fa8c7;
  background: linear-gradient(90deg, rgba(56, 189, 248, 0.14), rgba(64, 144, 255, 0.04));
  border: 1px solid rgba(64, 144, 255, 0.16);
  border-radius: 3px;
  position: sticky;
  top: 0;
  z-index: 1;
}
.bs-table-row {
  color: #d7e7ff;
  border-bottom: 1px solid rgba(64, 144, 255, 0.08);
}
.bs-table-row:hover {
  background: rgba(56, 189, 248, 0.07);
}
.bs-cell-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.bs-cell-num {
  text-align: right;
  font-variant-numeric: tabular-nums;
  color: #8fa8c7;
}
.bs-cell-status {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  font-size: 11px;
  border-radius: 3px;
  padding: 2px 0;
}
.bs-cell-status i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
}
.bs-cell-status.is-ok {
  color: #2dd4bf;
  background: rgba(45, 212, 191, 0.1);
}
.bs-cell-status.is-ok i {
  background: #2dd4bf;
  box-shadow: 0 0 6px #2dd4bf;
}
.bs-cell-status.is-bad {
  color: #f87171;
  background: rgba(239, 68, 68, 0.12);
}
.bs-cell-status.is-bad i {
  background: #f87171;
  box-shadow: 0 0 6px #f87171;
}

/* ---------- 告警 ---------- */
.bs-action {
  display: flex;
  align-items: stretch;
  gap: 10px;
  padding: 7px 4px;
}
.bs-action + .bs-action {
  border-top: 1px solid rgba(64, 144, 255, 0.1);
}
.bs-action-bar {
  width: 3px;
  border-radius: 2px;
  flex-shrink: 0;
}
.bs-action.is-danger .bs-action-bar {
  background: #f87171;
  box-shadow: 0 0 8px rgba(248, 113, 113, 0.8);
}
.bs-action.is-warn .bs-action-bar {
  background: #fbbf24;
  box-shadow: 0 0 8px rgba(251, 191, 36, 0.7);
}
.bs-action.is-muted .bs-action-bar {
  background: #6c86a8;
}
.bs-action-main {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.bs-action-title {
  color: #eaf5ff;
  font-size: 12.5px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.bs-action-sub {
  color: #6c86a8;
  font-size: 11px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.bs-empty {
  color: #6c86a8;
  font-size: 12px;
  text-align: center;
  padding: 12px 0;
}

/* ---------- 窄屏降级：纵向堆叠 + 页面滚动 ---------- */
@media (max-width: 1280px) {
  .bs-root {
    position: relative;
    inset: auto;
    height: auto;
    min-height: 100vh;
    overflow: auto;
  }
  .bs-kpis {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
  .bs-main {
    grid-template-columns: 1fr;
  }
  .bs-map {
    height: 420px;
  }
  .bs-title {
    letter-spacing: 4px;
    font-size: 20px;
  }
}
</style>

<!-- 点位/弹窗由 Leaflet 运行时注入（无 data-v 属性），必须用非 scoped 样式 -->
<style>
.bs-marker {
  background: transparent !important;
  border: none !important;
}
.bs-dot {
  position: relative;
  border-radius: 50%;
  background: var(--c, #2dd4bf);
  border: 2px solid rgba(255, 255, 255, 0.85);
  box-shadow: 0 0 12px var(--c, #2dd4bf);
  opacity: 0.95;
}
.bs-dot.is-offline::after {
  content: '';
  position: absolute;
  inset: -6px;
  border-radius: 50%;
  border: 2px solid var(--c, #ef4444);
  animation: bs-pulse 1.8s ease-out infinite;
}
@keyframes bs-pulse {
  0% {
    transform: scale(0.7);
    opacity: 0.8;
  }
  100% {
    transform: scale(1.9);
    opacity: 0;
  }
}
@media (prefers-reduced-motion: reduce) {
  .bs-dot.is-offline::after {
    animation: none;
    opacity: 0.3;
  }
}
/* 营收 TOP3 点位的常显标注 */
.bs-dot-label {
  position: absolute;
  left: 50%;
  top: -26px;
  transform: translateX(-50%);
  white-space: nowrap;
  font-size: 11px;
  line-height: 1.4;
  color: #eaf5ff;
  background: rgba(8, 22, 42, 0.85);
  border: 1px solid rgba(45, 212, 191, 0.5);
  border-radius: 4px;
  padding: 1px 7px;
  text-shadow: 0 1px 4px rgba(0, 0, 0, 0.6);
}
/* AMap 自定义 InfoWindow 外壳（深色玻璃） */
.bs-iw {
  min-width: 170px;
  padding: 10px 12px;
  background: rgba(8, 22, 42, 0.94);
  border: 1px solid rgba(45, 212, 191, 0.45);
  border-radius: 6px;
  box-shadow: 0 6px 20px rgba(0, 0, 0, 0.5);
  font-size: 12px;
}
.bs-iw .bs-popup {
  color: #eaf5ff;
}
.bs-iw .bs-popup b {
  color: #2dd4bf;
}
.bs-iw .bs-popup .muted {
  color: #8fa8c7;
}
.bs-popup {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 12px;
  color: #1f2937;
}
.bs-popup .muted {
  color: #6b7280;
}
</style>
