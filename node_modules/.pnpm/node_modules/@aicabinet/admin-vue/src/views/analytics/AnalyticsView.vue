<template>
  <div v-loading="loading" class="analytics-page">
    <div class="page-heading">
      <div>
        <h1>数据分析</h1>
        <p>营收、订单与识别质量趋势，支持 7 / 30 / 90 天；可切换折线 / 面积 / 柱状图。</p>
      </div>
      <div class="heading-actions">
        <el-radio-group v-model="days" size="default" @change="load">
          <el-radio-button :label="7">近 7 天</el-radio-button>
          <el-radio-button :label="30">近 30 天</el-radio-button>
          <el-radio-button :label="90">近 90 天</el-radio-button>
        </el-radio-group>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <el-row :gutter="16" class="stats-row">
      <el-col :xs="12" :sm="6">
        <el-card shadow="never" class="stat-card accent-teal">
          <el-statistic title="今日营收" :value="(stats.revenueTodayCents || 0) / 100" prefix="¥" :precision="2" />
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="never" class="stat-card accent-blue">
          <el-statistic title="今日订单" :value="stats.orderToday || 0" />
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="never" class="stat-card accent-violet">
          <el-statistic title="24h 开门成功率" :value="(stats.doorSuccessRate24h || 0) * 100" suffix="%" :precision="1" />
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="never" class="stat-card accent-amber">
          <el-statistic title="24h 自动识别率" :value="(stats.recognitionAutoRate24h || 0) * 100" suffix="%" :precision="1" />
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" style="margin-top:16px">
      <el-col :xs="24" :md="12">
        <el-card shadow="never" class="chart-card">
          <template #header>
            <div class="card-head">
              <div>
                <strong>营收趋势</strong>
                <span class="header-hint">单位：元</span>
              </div>
              <div class="chart-type" role="group" aria-label="图表类型">
                <button type="button" :class="{ active: revenueKind === 'line' }" @click="revenueKind = 'line'">折线</button>
                <button type="button" :class="{ active: revenueKind === 'area' }" @click="revenueKind = 'area'">面积</button>
                <button type="button" :class="{ active: revenueKind === 'bar' }" @click="revenueKind = 'bar'">柱状</button>
              </div>
            </div>
          </template>
          <Transition name="chart-fade" mode="out-in">
            <ChartBox v-if="revenueSvg" :key="revenueKind" :svg="revenueSvg" />
            <el-empty v-else key="empty" description="暂无营收趋势" :image-size="64" />
          </Transition>
          <div class="chart-legend">
            <span><i style="background:#2dd4bf" />营收（元）</span>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="24" :md="12">
        <el-card shadow="never" class="chart-card">
          <template #header>
            <div class="card-head">
              <div>
                <strong>订单量</strong>
                <span class="header-hint">单位：单</span>
              </div>
              <div class="chart-type" role="group" aria-label="图表类型">
                <button type="button" :class="{ active: orderKind === 'line' }" @click="orderKind = 'line'">折线</button>
                <button type="button" :class="{ active: orderKind === 'area' }" @click="orderKind = 'area'">面积</button>
                <button type="button" :class="{ active: orderKind === 'bar' }" @click="orderKind = 'bar'">柱状</button>
              </div>
            </div>
          </template>
          <Transition name="chart-fade" mode="out-in">
            <ChartBox v-if="orderSvg" :key="orderKind" :svg="orderSvg" />
            <el-empty v-else key="empty" description="暂无订单趋势" :image-size="64" />
          </Transition>
          <div class="chart-legend">
            <span><i style="background:#60a5fa" />订单量</span>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" style="margin-top:16px">
      <el-col :xs="24" :md="14">
        <el-card shadow="never" class="chart-card">
          <template #header>
            <div class="card-head">
              <div>
                <strong>识别质量</strong>
                <span class="header-hint">自动识别率 vs 争议率</span>
              </div>
              <div class="chart-type" role="group" aria-label="图表类型">
                <button type="button" :class="{ active: opsKind === 'line' }" @click="opsKind = 'line'">折线</button>
                <button type="button" :class="{ active: opsKind === 'area' }" @click="opsKind = 'area'">面积</button>
                <button type="button" :class="{ active: opsKind === 'bar' }" @click="opsKind = 'bar'">柱状</button>
              </div>
            </div>
          </template>
          <Transition name="chart-fade" mode="out-in">
            <ChartBox v-if="opsSvg" :key="opsKind" :svg="opsSvg" />
            <el-empty v-else key="empty" description="暂无识别质量数据" :image-size="64" />
          </Transition>
          <div class="chart-legend">
            <span><i style="background:#2dd4bf" />自动识别率</span>
            <span><i style="background:#fbbf24" />争议率</span>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="24" :md="10">
        <el-card shadow="never" class="chart-card">
          <template #header><strong>设备在线</strong></template>
          <div class="donut-wrap">
            <ChartBox v-if="deviceSvg" :svg="deviceSvg" donut />
            <el-empty v-else description="暂无设备数据" :image-size="64" />
            <ul class="donut-legend">
              <li><i style="background:#2dd4bf" />在线 {{ stats.deviceOnline || 0 }}</li>
              <li><i style="background:#64748b" />离线 {{ offlineDevices }}</li>
            </ul>
          </div>
        </el-card>
        <el-card shadow="never" class="chart-card" style="margin-top:16px">
          <template #header><strong>经营快照</strong></template>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="累计营收">¥{{ ((stats.revenueTotalCents || 0) / 100).toFixed(2) }}</el-descriptions-item>
            <el-descriptions-item label="累计订单">{{ stats.orderTotal || 0 }}</el-descriptions-item>
            <el-descriptions-item label="待审争议">{{ stats.disputeOpen || 0 }}</el-descriptions-item>
            <el-descriptions-item label="24h 争议率">{{ ((stats.disputeRate24h || 0) * 100).toFixed(1) }}%</el-descriptions-item>
            <el-descriptions-item label="毛利率（今日）">{{ ((finance?.grossMarginRateToday || 0) * 100).toFixed(1) }}%</el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';
import ChartBox from '@/components/ChartBox.vue';
import {
  buildDonutChart,
  buildSeriesChart,
  formatPct,
  formatYuan,
  shortDate,
  type ChartKind
} from '@/utils/charts';

interface AdminStats {
  revenueTodayCents?: number;
  orderToday?: number;
  revenueTotalCents?: number;
  orderTotal?: number;
  doorSuccessRate24h?: number;
  recognitionAutoRate24h?: number;
  disputeRate24h?: number;
  disputeOpen?: number;
  deviceOnline?: number;
  deviceTotal?: number;
}

interface DailyStat { date: string; orderCount: number; revenueCents: number }
interface OpsDaily { date: string; recognitionRate: number; disputeRate: number }
interface FinanceStats { grossMarginRateToday?: number }

const loading = ref(false);
const days = ref(7);
const stats = ref<AdminStats>({});
const trend = ref<DailyStat[]>([]);
const opsTrend = ref<OpsDaily[]>([]);
const finance = ref<FinanceStats | null>(null);

const revenueKind = ref<ChartKind>('area');
const orderKind = ref<ChartKind>('bar');
const opsKind = ref<ChartKind>('line');

const offlineDevices = computed(() => Math.max((stats.value.deviceTotal || 0) - (stats.value.deviceOnline || 0), 0));
const labels = computed(() => trend.value.map((d) => shortDate(d.date)));

const revenueSvg = computed(() => {
  if (!trend.value.length) return '';
  return buildSeriesChart({
    labels: labels.value,
    series: [{ name: '营收', values: trend.value.map((d) => d.revenueCents / 100), color: '#2dd4bf' }],
    kind: revenueKind.value,
    formatY: (v) => formatYuan(v * 100)
  });
});

const orderSvg = computed(() => {
  if (!trend.value.length) return '';
  return buildSeriesChart({
    labels: labels.value,
    series: [{ name: '订单', values: trend.value.map((d) => d.orderCount), color: '#60a5fa' }],
    kind: orderKind.value,
    formatY: (v) => String(Math.round(v))
  });
});

const opsSvg = computed(() => {
  if (!opsTrend.value.length) return '';
  return buildSeriesChart({
    labels: opsTrend.value.map((d) => shortDate(d.date)),
    series: [
      { name: '自动识别率', values: opsTrend.value.map((d) => d.recognitionRate * 100), color: '#2dd4bf' },
      { name: '争议率', values: opsTrend.value.map((d) => d.disputeRate * 100), color: '#fbbf24' }
    ],
    kind: opsKind.value,
    formatY: (v) => formatPct(v / 100)
  });
});

const deviceSvg = computed(() =>
  buildDonutChart({
    parts: [
      { label: '在线', value: stats.value.deviceOnline || 0, color: '#2dd4bf' },
      { label: '离线', value: offlineDevices.value, color: '#64748b' }
    ]
  })
);

async function load() {
  loading.value = true;
  try {
    const d = days.value;
    const [s, t, o, f] = await Promise.all([
      api.request<AdminStats>('/api/v2/ops/admin/stats', 'GET'),
      api.request<{ last7Days: DailyStat[] }>(`/api/v2/ops/admin/trend?days=${d}`, 'GET'),
      api.request<{ last7Days: OpsDaily[] }>(`/api/v2/ops/admin/trend/ops?days=${d}`, 'GET'),
      api.request<FinanceStats>('/api/v2/ops/admin/finance/stats', 'GET').catch(() => null)
    ]);
    stats.value = s || {};
    trend.value = t?.last7Days || [];
    opsTrend.value = o?.last7Days || [];
    finance.value = f;
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<style scoped>
.page-heading {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: flex-start;
  margin-bottom: 16px;
  flex-wrap: wrap;
}
.page-heading h1 { margin: 0; font-size: 22px; }
.page-heading p { margin: 6px 0 0; color: var(--layout-muted); }
.heading-actions { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; }

.stats-row .stat-card {
  margin-bottom: 0;
  position: relative;
  overflow: hidden;
  border: 1px solid var(--layout-border);
  background:
    linear-gradient(145deg, rgba(255, 255, 255, 0.03), transparent 55%),
    var(--layout-card-bg, var(--el-bg-color));
}
.stats-row .stat-card::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 3px;
}
.stat-card.accent-teal::before { background: #2dd4bf; }
.stat-card.accent-blue::before { background: #60a5fa; }
.stat-card.accent-violet::before { background: #a78bfa; }
.stat-card.accent-amber::before { background: #fbbf24; }

.chart-card {
  min-height: 300px;
  border: 1px solid var(--layout-border);
  background:
    radial-gradient(120% 80% at 100% 0%, rgba(45, 212, 191, 0.06), transparent 50%),
    var(--layout-card-bg, var(--el-bg-color));
}
.card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.header-hint {
  margin-left: 10px;
  color: var(--layout-muted);
  font-size: 12px;
  font-weight: 400;
}
.chart-type {
  display: inline-flex;
  padding: 3px;
  gap: 2px;
  border-radius: 10px;
  background: rgba(148, 163, 184, 0.12);
  border: 1px solid var(--layout-border);
}
.chart-type button {
  appearance: none;
  border: 0;
  background: transparent;
  color: var(--layout-muted);
  font-size: 12px;
  line-height: 1;
  padding: 7px 12px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease, box-shadow 0.15s ease;
}
.chart-type button:hover {
  color: var(--layout-text);
}
.chart-type button.active {
  color: #0f172a;
  background: linear-gradient(180deg, #5eead4, #2dd4bf);
  box-shadow: 0 4px 12px rgba(45, 212, 191, 0.28);
  font-weight: 600;
}
.chart-fade-enter-active,
.chart-fade-leave-active {
  transition: opacity 0.18s ease, transform 0.18s ease;
}
.chart-fade-enter-from,
.chart-fade-leave-to {
  opacity: 0;
  transform: translateY(4px);
}
.chart-legend {
  display: flex;
  gap: 16px;
  margin-top: 4px;
  color: var(--layout-muted);
  font-size: 13px;
}
.chart-legend i,
.donut-legend i {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 999px;
  margin-right: 6px;
  box-shadow: 0 0 0 2px rgba(255, 255, 255, 0.06);
}
.donut-wrap {
  display: flex;
  align-items: center;
  gap: 20px;
  min-height: 200px;
}
.donut-legend {
  list-style: none;
  margin: 0;
  padding: 0;
  color: var(--layout-muted);
}
.donut-legend li { margin: 10px 0; font-size: 14px; }
</style>
