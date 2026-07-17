<template>
  <div v-loading="loading" class="analytics-page">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-head">
          <span class="title">数据分析</span>
          <div class="heading-actions">
            <el-radio-group v-model="days" size="default" @change="load">
              <el-radio-button :label="7">近 7 天</el-radio-button>
              <el-radio-button :label="30">近 30 天</el-radio-button>
              <el-radio-button :label="90">近 90 天</el-radio-button>
            </el-radio-group>
            <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
          </div>
        </div>
      </template>

      <el-row :gutter="12" class="stats-row">
        <el-col :xs="12" :sm="6">
          <div class="stat-tile accent-teal">
            <div class="stat-label">今日营收</div>
            <div class="stat-value">¥{{ ((stats.revenueTodayCents || 0) / 100).toFixed(2) }}</div>
          </div>
        </el-col>
        <el-col :xs="12" :sm="6">
          <div class="stat-tile accent-blue">
            <div class="stat-label">今日订单</div>
            <div class="stat-value">{{ stats.orderToday || 0 }}</div>
          </div>
        </el-col>
        <el-col :xs="12" :sm="6">
          <div class="stat-tile accent-violet">
            <div class="stat-label">24h 开门成功率</div>
            <div class="stat-value">{{ ((stats.doorSuccessRate24h || 0) * 100).toFixed(1) }}%</div>
          </div>
        </el-col>
        <el-col :xs="12" :sm="6">
          <div class="stat-tile accent-amber">
            <div class="stat-label">24h 自动识别率</div>
            <div class="stat-value">{{ ((stats.recognitionAutoRate24h || 0) * 100).toFixed(1) }}%</div>
          </div>
        </el-col>
      </el-row>
    </el-card>

    <div class="chart-grid chart-grid--2">
      <ChartPanel title="营收趋势" hint="单位：元">
        <template #actions>
          <div class="chart-type-switch" role="group" aria-label="图表类型">
            <button type="button" :class="{ active: revenueKind === 'line' }" @click="revenueKind = 'line'">折线</button>
            <button type="button" :class="{ active: revenueKind === 'area' }" @click="revenueKind = 'area'">面积</button>
            <button type="button" :class="{ active: revenueKind === 'bar' }" @click="revenueKind = 'bar'">柱状</button>
          </div>
        </template>
        <Transition name="chart-fade" mode="out-in">
          <ChartBox v-if="revenueSvg" :key="revenueKind" :svg="revenueSvg" />
          <el-empty v-else key="empty" description="暂无营收趋势" :image-size="64" />
        </Transition>
        <template #footer>
          <span class="chart-legend-item"><i style="background:#2dd4bf" />营收（元）</span>
        </template>
      </ChartPanel>

      <ChartPanel title="订单量" hint="单位：单">
        <template #actions>
          <div class="chart-type-switch" role="group" aria-label="图表类型">
            <button type="button" :class="{ active: orderKind === 'line' }" @click="orderKind = 'line'">折线</button>
            <button type="button" :class="{ active: orderKind === 'area' }" @click="orderKind = 'area'">面积</button>
            <button type="button" :class="{ active: orderKind === 'bar' }" @click="orderKind = 'bar'">柱状</button>
          </div>
        </template>
        <Transition name="chart-fade" mode="out-in">
          <ChartBox v-if="orderSvg" :key="orderKind" :svg="orderSvg" />
          <el-empty v-else key="empty" description="暂无订单趋势" :image-size="64" />
        </Transition>
        <template #footer>
          <span class="chart-legend-item"><i style="background:#60a5fa" />订单量</span>
        </template>
      </ChartPanel>
    </div>

    <div class="chart-grid chart-grid--2">
      <ChartPanel title="订单支付渠道" hint="按订单金额" donut>
        <div class="donut-layout">
          <ChartBox v-if="orderChannelSvg" :svg="orderChannelSvg" donut />
          <el-empty v-else description="暂无订单支付数据" :image-size="64" />
          <ul v-if="orderChannelSvg" class="donut-legend-list">
            <li v-for="p in orderChannelParts" :key="p.label">
              <i :style="{ background: p.color }" />
              {{ p.label }} ¥{{ ((p.value || 0) / 100).toFixed(p.value >= 10000 ? 0 : 2) }}
              <span class="muted">（{{ p.count }} 单）</span>
            </li>
          </ul>
        </div>
      </ChartPanel>

      <ChartPanel title="充值渠道" hint="已到账金额" donut>
        <div class="donut-layout">
          <ChartBox v-if="rechargeChannelSvg" :svg="rechargeChannelSvg" donut />
          <el-empty v-else description="暂无充值数据" :image-size="64" />
          <ul v-if="rechargeChannelSvg" class="donut-legend-list">
            <li v-for="p in rechargeChannelParts" :key="p.label">
              <i :style="{ background: p.color }" />
              {{ p.label }} ¥{{ ((p.value || 0) / 100).toFixed(p.value >= 10000 ? 0 : 2) }}
              <span class="muted">（{{ p.count }} 笔）</span>
            </li>
          </ul>
        </div>
      </ChartPanel>
    </div>

    <div class="chart-grid chart-grid--split">
      <ChartPanel title="识别质量" hint="自动识别率 vs 争议率">
        <template #actions>
          <div class="chart-type-switch" role="group" aria-label="图表类型">
            <button type="button" :class="{ active: opsKind === 'line' }" @click="opsKind = 'line'">折线</button>
            <button type="button" :class="{ active: opsKind === 'area' }" @click="opsKind = 'area'">面积</button>
            <button type="button" :class="{ active: opsKind === 'bar' }" @click="opsKind = 'bar'">柱状</button>
          </div>
        </template>
        <Transition name="chart-fade" mode="out-in">
          <ChartBox v-if="opsSvg" :key="opsKind" :svg="opsSvg" />
          <el-empty v-else key="empty" description="暂无识别质量数据" :image-size="64" />
        </Transition>
        <template #footer>
          <span class="chart-legend-item"><i style="background:#2dd4bf" />自动识别率</span>
          <span class="chart-legend-item"><i style="background:#fbbf24" />争议率</span>
        </template>
      </ChartPanel>

      <div class="chart-aside">
        <ChartPanel title="设备在线" fill donut>
          <template #actions>
            <el-button
              v-if="offlineDevices > 0"
              link
              type="primary"
              @click="router.push({ path: '/devices', query: { online: 'OFFLINE' } })"
            >
              查看离线 {{ offlineDevices }}
            </el-button>
          </template>
          <div class="donut-layout">
            <ChartBox v-if="deviceSvg" :svg="deviceSvg" donut />
            <el-empty v-else description="暂无设备数据" :image-size="64" />
            <ul v-if="deviceSvg" class="donut-legend-list">
              <li><i style="background:#2dd4bf" />在线 {{ stats.deviceOnline || 0 }}</li>
              <li><i style="background:#64748b" />离线 {{ offlineDevices }}</li>
            </ul>
          </div>
        </ChartPanel>

        <ChartPanel title="经营快照" compact>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="累计营收">¥{{ ((stats.revenueTotalCents || 0) / 100).toFixed(2) }}</el-descriptions-item>
            <el-descriptions-item label="累计订单">{{ stats.orderTotal || 0 }}</el-descriptions-item>
            <el-descriptions-item label="待审争议">
              <el-button
                v-if="(stats.disputeOpen || 0) > 0"
                link
                type="primary"
                @click="router.push({ path: '/disputes', query: { status: 'OPEN' } })"
              >
                {{ stats.disputeOpen }}
              </el-button>
              <span v-else>0</span>
            </el-descriptions-item>
            <el-descriptions-item label="24h 争议率">{{ ((stats.disputeRate24h || 0) * 100).toFixed(1) }}%</el-descriptions-item>
            <el-descriptions-item label="毛利率（今日）">
              <el-button link type="primary" @click="router.push('/finance')">
                {{ ((finance?.grossMarginRateToday || 0) * 100).toFixed(1) }}%
              </el-button>
            </el-descriptions-item>
          </el-descriptions>
        </ChartPanel>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { dictLabel } from '@aicabinet/shared-dict';
import { api } from '@/api/client';
import ChartBox from '@/components/ChartBox.vue';
import ChartPanel from '@/components/ChartPanel.vue';
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
interface ChannelStat { channel: string; count: number; amountCents: number }
interface ChannelBreakdown {
  orderPayChannels?: ChannelStat[];
  rechargeChannels?: ChannelStat[];
}

const CHANNEL_COLORS: Record<string, string> = {
  WECHAT: '#07c160',
  ALIPAY: '#1677ff',
  BALANCE: '#f59e0b',
  MOCK: '#94a3b8',
  UNKNOWN: '#64748b'
};

const router = useRouter();
const loading = ref(false);
const days = ref(7);
const stats = ref<AdminStats>({});
const trend = ref<DailyStat[]>([]);
const opsTrend = ref<OpsDaily[]>([]);
const finance = ref<FinanceStats | null>(null);
const channels = ref<ChannelBreakdown>({});

const revenueKind = ref<ChartKind>('area');
const orderKind = ref<ChartKind>('bar');
const opsKind = ref<ChartKind>('line');

const offlineDevices = computed(() => Math.max((stats.value.deviceTotal || 0) - (stats.value.deviceOnline || 0), 0));
const labels = computed(() => trend.value.map((d) => shortDate(d.date)));

function channelParts(statsList?: ChannelStat[]) {
  return (statsList || []).map((s, i) => {
    const code = String(s.channel || 'UNKNOWN').toUpperCase();
    return {
      label: dictLabel('pay_channel', code) || code,
      value: s.amountCents || 0,
      count: s.count || 0,
      color: CHANNEL_COLORS[code] || ['#2dd4bf', '#60a5fa', '#a78bfa', '#fbbf24', '#f472b6'][i % 5]
    };
  });
}

const orderChannelParts = computed(() => channelParts(channels.value.orderPayChannels));
const rechargeChannelParts = computed(() => channelParts(channels.value.rechargeChannels));

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

const orderChannelSvg = computed(() =>
  buildDonutChart({
    parts: orderChannelParts.value.map((p) => ({ label: p.label, value: p.value, color: p.color }))
  })
);

const rechargeChannelSvg = computed(() =>
  buildDonutChart({
    parts: rechargeChannelParts.value.map((p) => ({ label: p.label, value: p.value, color: p.color }))
  })
);

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
    const [s, t, o, f, c] = await Promise.all([
      api.request<AdminStats>('/api/v2/ops/admin/stats', 'GET'),
      api.request<{ last7Days: DailyStat[] }>(`/api/v2/ops/admin/trend?days=${d}`, 'GET'),
      api.request<{ last7Days: OpsDaily[] }>(`/api/v2/ops/admin/trend/ops?days=${d}`, 'GET'),
      api.request<FinanceStats>('/api/v2/ops/admin/finance/stats', 'GET').catch(() => null),
      api.request<ChannelBreakdown>(`/api/v2/ops/admin/trend/channels?days=${d}`, 'GET').catch(() => ({}))
    ]);
    stats.value = s || {};
    trend.value = t?.last7Days || [];
    opsTrend.value = o?.last7Days || [];
    finance.value = f;
    channels.value = c || {};
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<style scoped>
.card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.title { font-weight: 600; }
.heading-actions { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; }

.stats-row .stat-tile {
  border-radius: 10px;
  padding: 14px 16px;
  margin-bottom: 4px;
  border: 1px solid var(--layout-border);
  background: var(--layout-bg, #f8fafc);
  position: relative;
  overflow: hidden;
}
.stats-row .stat-tile::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 3px;
}
.stat-tile.accent-teal::before { background: #2dd4bf; }
.stat-tile.accent-blue::before { background: #60a5fa; }
.stat-tile.accent-violet::before { background: #a78bfa; }
.stat-tile.accent-amber::before { background: #fbbf24; }
.stat-label { font-size: 13px; color: var(--layout-muted); }
.stat-value { font-size: 24px; font-weight: 700; margin-top: 6px; }
.muted { color: var(--layout-muted); font-size: 12px; margin-left: 4px; }
</style>
