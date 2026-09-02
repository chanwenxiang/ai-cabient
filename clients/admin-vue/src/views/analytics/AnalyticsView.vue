<template>
  <div v-loading="loading" class="analytics-page">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="page-card-head">
          <div class="page-card-head__meta">
            <div class="page-card-head__title">
              <span class="title">数据分析</span>
              <span class="hint">上方为今日 / 近 24 小时快照，不受下方趋势范围影响</span>
            </div>
          </div>
          <div class="page-card-head__actions">
            <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
          </div>
        </div>
      </template>

      <el-row :gutter="12" class="stats-row">
        <el-col :xs="12" :sm="6">
          <div
            class="stat-tile accent-teal"
            :class="{ 'is-clickable': canAccessPath('/finance') }"
            :role="canAccessPath('/finance') ? 'button' : undefined"
            :tabindex="canAccessPath('/finance') ? 0 : undefined"
            @click="goPath('/finance')"
            @keydown.enter="goPath('/finance')"
          >
            <div class="stat-label">今日营收</div>
            <div class="stat-value">
              {{ listHydrated ? `¥${((stats.revenueTodayCents || 0) / 100).toFixed(2)}` : '…' }}
            </div>
            <div class="stat-hint">
              <template v-if="!listHydrated">加载中…</template>
              <template v-else>{{
                canAccessPath('/finance') ? '查看财务毛利' : '今日快照'
              }}</template>
            </div>
          </div>
        </el-col>
        <el-col :xs="12" :sm="6">
          <div
            class="stat-tile accent-blue"
            :class="{ 'is-clickable': canAccessPath('/orders') }"
            :role="canAccessPath('/orders') ? 'button' : undefined"
            :tabindex="canAccessPath('/orders') ? 0 : undefined"
            @click="goPath('/orders')"
            @keydown.enter="goPath('/orders')"
          >
            <div class="stat-label">今日订单</div>
            <div class="stat-value">{{ listHydrated ? stats.orderToday || 0 : '…' }}</div>
            <div class="stat-hint">
              <template v-if="!listHydrated">加载中…</template>
              <template v-else>{{
                canAccessPath('/orders') ? '查看订单列表' : '今日快照'
              }}</template>
            </div>
          </div>
        </el-col>
        <el-col :xs="12" :sm="6">
          <div
            class="stat-tile accent-violet"
            :class="{ 'is-clickable': canAccessPath('/sessions') }"
            :role="canAccessPath('/sessions') ? 'button' : undefined"
            :tabindex="canAccessPath('/sessions') ? 0 : undefined"
            @click="goPath('/sessions')"
            @keydown.enter="goPath('/sessions')"
          >
            <div class="stat-label">24h 开门成功率</div>
            <div class="stat-value">
              {{ listHydrated ? `${((stats.doorSuccessRate24h || 0) * 100).toFixed(1)}%` : '…' }}
            </div>
            <div class="stat-hint">
              <template v-if="!listHydrated">加载中…</template>
              <template v-else>{{
                canAccessPath('/sessions') ? '查看开门记录' : '近 24 小时'
              }}</template>
            </div>
          </div>
        </el-col>
        <el-col :xs="12" :sm="6">
          <div
            class="stat-tile accent-amber"
            :class="{ 'is-clickable': canAccessPath('/disputes') }"
            :role="canAccessPath('/disputes') ? 'button' : undefined"
            :tabindex="canAccessPath('/disputes') ? 0 : undefined"
            @click="goPath('/disputes')"
            @keydown.enter="goPath('/disputes')"
          >
            <div class="stat-label">24h 自动识别率</div>
            <div class="stat-value">
              {{
                listHydrated ? `${((stats.recognitionAutoRate24h || 0) * 100).toFixed(1)}%` : '…'
              }}
            </div>
            <div class="stat-hint">
              <template v-if="!listHydrated">加载中…</template>
              <template v-else>{{
                canAccessPath('/disputes') ? '查看争议审核' : '近 24 小时'
              }}</template>
            </div>
          </div>
        </el-col>
      </el-row>
    </el-card>

    <div class="trend-toolbar">
      <div>
        <span class="trend-title">趋势与渠道</span>
        <span class="header-hint">当前范围：近 {{ days }} 天</span>
      </div>
      <el-radio-group v-model="days" size="default" @change="onDaysChange">
        <el-radio-button :value="1">今天</el-radio-button>
        <el-radio-button :value="7">近 7 天</el-radio-button>
        <el-radio-button :value="30">近 30 天</el-radio-button>
        <el-radio-button :value="90">近 90 天</el-radio-button>
      </el-radio-group>
    </div>

    <div class="chart-grid chart-grid--2">
      <ChartPanel title="营收趋势" :hint="`近 ${days} 天 · 元`">
        <template #actions>
          <fieldset class="chart-type-switch" aria-label="图表类型">
            <button
              type="button"
              :class="{ active: revenueKind === 'line' }"
              @click="revenueKind = 'line'"
            >
              折线
            </button>
            <button
              type="button"
              :class="{ active: revenueKind === 'area' }"
              @click="revenueKind = 'area'"
            >
              面积
            </button>
            <button
              type="button"
              :class="{ active: revenueKind === 'bar' }"
              @click="revenueKind = 'bar'"
            >
              柱状
            </button>
          </fieldset>
        </template>
        <Transition name="chart-fade" mode="out-in">
          <ChartBox v-if="revenueSvg" :key="revenueKind" :svg="revenueSvg" />
          <el-empty
            v-else-if="listHydrated"
            key="empty"
            description="暂无营收趋势"
            :image-size="64"
          />
        </Transition>
        <template #footer>
          <span class="chart-legend-item"><i style="background: #2dd4bf" />营收（元）</span>
        </template>
      </ChartPanel>

      <ChartPanel title="订单量" :hint="`近 ${days} 天 · 单`">
        <template #actions>
          <fieldset class="chart-type-switch" aria-label="图表类型">
            <button
              type="button"
              :class="{ active: orderKind === 'line' }"
              @click="orderKind = 'line'"
            >
              折线
            </button>
            <button
              type="button"
              :class="{ active: orderKind === 'area' }"
              @click="orderKind = 'area'"
            >
              面积
            </button>
            <button
              type="button"
              :class="{ active: orderKind === 'bar' }"
              @click="orderKind = 'bar'"
            >
              柱状
            </button>
          </fieldset>
        </template>
        <Transition name="chart-fade" mode="out-in">
          <ChartBox v-if="orderSvg" :key="orderKind" :svg="orderSvg" />
          <el-empty
            v-else-if="listHydrated"
            key="empty"
            description="暂无订单趋势"
            :image-size="64"
          />
        </Transition>
        <template #footer>
          <span class="chart-legend-item"><i style="background: #60a5fa" />订单量</span>
        </template>
      </ChartPanel>
    </div>

    <div class="chart-grid chart-grid--2">
      <ChartPanel title="订单支付渠道" :hint="`近 ${days} 天 · 按金额`" donut>
        <div class="donut-layout">
          <ChartBox v-if="orderChannelSvg" :svg="orderChannelSvg" donut />
          <el-empty v-else-if="listHydrated" description="暂无订单支付数据" :image-size="64" />
          <ul v-if="orderChannelSvg" class="donut-legend-list">
            <li v-for="p in orderChannelParts" :key="p.label">
              <i :style="{ background: p.color }" />
              {{ p.label }} ¥{{ ((p.value || 0) / 100).toFixed(2) }}
              <span class="muted">（{{ p.count }} 单）</span>
            </li>
          </ul>
        </div>
      </ChartPanel>

      <ChartPanel title="充值渠道" :hint="`近 ${days} 天 · 已到账`" donut>
        <div class="donut-layout">
          <ChartBox v-if="rechargeChannelSvg" :svg="rechargeChannelSvg" donut />
          <el-empty v-else-if="listHydrated" description="暂无充值数据" :image-size="64" />
          <ul v-if="rechargeChannelSvg" class="donut-legend-list">
            <li v-for="p in rechargeChannelParts" :key="p.label">
              <i :style="{ background: p.color }" />
              {{ p.label }} ¥{{ ((p.value || 0) / 100).toFixed(2) }}
              <span class="muted">（{{ p.count }} 笔）</span>
            </li>
          </ul>
        </div>
      </ChartPanel>
    </div>

    <div class="chart-grid chart-grid--split">
      <ChartPanel title="识别质量" :hint="`近 ${days} 天 · 识别率 vs 争议率`">
        <template #actions>
          <fieldset class="chart-type-switch" aria-label="图表类型">
            <button type="button" :class="{ active: opsKind === 'line' }" @click="opsKind = 'line'">
              折线
            </button>
            <button type="button" :class="{ active: opsKind === 'area' }" @click="opsKind = 'area'">
              面积
            </button>
            <button type="button" :class="{ active: opsKind === 'bar' }" @click="opsKind = 'bar'">
              柱状
            </button>
          </fieldset>
        </template>
        <Transition name="chart-fade" mode="out-in">
          <ChartBox v-if="opsSvg" :key="opsKind" :svg="opsSvg" />
          <el-empty
            v-else-if="listHydrated"
            key="empty"
            description="暂无识别质量数据"
            :image-size="64"
          />
        </Transition>
        <template #footer>
          <span class="chart-legend-item"><i style="background: #2dd4bf" />自动识别率</span>
          <span class="chart-legend-item"><i style="background: #fbbf24" />争议率</span>
        </template>
      </ChartPanel>

      <div class="chart-aside">
        <ChartPanel title="设备在线" fill donut>
          <template #actions>
            <el-button
              v-if="listHydrated && offlineDevices > 0 && canAccessPath('/devices')"
              link
              type="primary"
              @click="goPath('/devices', { online: 'OFFLINE' })"
            >
              查看离线 {{ offlineDevices }}
            </el-button>
          </template>
          <div class="donut-layout">
            <ChartBox v-if="deviceSvg" :svg="deviceSvg" donut />
            <el-empty v-else-if="listHydrated" description="暂无设备数据" :image-size="64" />
            <ul v-if="deviceSvg" class="donut-legend-list">
              <li>
                <i style="background: #2dd4bf" />在线
                {{ listHydrated ? stats.deviceOnline || 0 : '…' }}
              </li>
              <li>
                <i style="background: #64748b" />离线 {{ listHydrated ? offlineDevices : '…' }}
              </li>
            </ul>
          </div>
        </ChartPanel>

        <ChartPanel title="经营快照" compact>
          <el-descriptions :column="1" border size="small" class="snapshot-desc">
            <el-descriptions-item label="累计营收">
              {{ listHydrated ? `¥${((stats.revenueTotalCents || 0) / 100).toFixed(2)}` : '…' }}
            </el-descriptions-item>
            <el-descriptions-item label="累计订单">
              {{ listHydrated ? stats.orderTotal || 0 : '…' }}
            </el-descriptions-item>
            <el-descriptions-item label="待审争议">
              <template v-if="!listHydrated">暂无</template>
              <el-button
                v-else-if="(stats.disputeOpen || 0) > 0 && canAccessPath('/disputes')"
                link
                type="danger"
                @click="goPath('/disputes', { status: 'OPEN' })"
              >
                {{ stats.disputeOpen }} 条待审
              </el-button>
              <span v-else class="muted">{{ stats.disputeOpen || 0 }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="24h 争议率">
              {{ listHydrated ? `${((stats.disputeRate24h || 0) * 100).toFixed(1)}%` : '…' }}
            </el-descriptions-item>
            <el-descriptions-item label="今日毛利率">
              <template v-if="!listHydrated">暂无</template>
              <el-button
                v-else-if="canAccessPath('/finance')"
                link
                type="primary"
                @click="goPath('/finance')"
              >
                查看 {{ ((finance?.grossMarginRateToday || 0) * 100).toFixed(1) }}%
              </el-button>
              <span v-else>{{ ((finance?.grossMarginRateToday || 0) * 100).toFixed(1) }}%</span>
            </el-descriptions-item>
          </el-descriptions>
        </ChartPanel>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { displayLabel } from '@aicabinet/shared-dict';
import { api } from '@/api/client';
import ChartBox from '@/components/ChartBox.vue';
import ChartPanel from '@/components/ChartPanel.vue';
import { useNavAccess } from '@/composables/useNavAccess';
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

interface DailyStat {
  date: string;
  orderCount: number;
  revenueCents: number;
}
interface OpsDaily {
  date: string;
  recognitionRate: number;
  disputeRate: number;
}
interface FinanceStats {
  grossMarginRateToday?: number;
}
interface ChannelStat {
  channel: string;
  count: number;
  amountCents: number;
}
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
/** 渠道代码兜底中文名（字典未加载时避免界面出现英文） */
const CHANNEL_LABELS: Record<string, string> = {
  WECHAT: '微信',
  ALIPAY: '支付宝',
  BALANCE: '余额',
  MOCK: '其他',
  UNKNOWN: '未知'
};

const route = useRoute();
const { router, canAccessPath, goPath } = useNavAccess();
const loading = ref(false);
/** 首屏未拉完前勿展示 ¥0 / 0% /「暂无」，避免与真实快照闪错 */
const listHydrated = ref(false);
const days = ref(parseDays(route.query.days));
const stats = ref<AdminStats>({});
const trend = ref<DailyStat[]>([]);
const opsTrend = ref<OpsDaily[]>([]);
const finance = ref<FinanceStats | null>(null);
const channels = ref<ChannelBreakdown>({});

const revenueKind = ref<ChartKind>('area');
const orderKind = ref<ChartKind>('bar');
const opsKind = ref<ChartKind>('line');

function parseDays(raw: unknown): number {
  const n = Number(raw);
  return n === 1 || n === 7 || n === 30 || n === 90 ? n : 1;
}

function onDaysChange() {
  router.replace({ query: { ...route.query, days: String(days.value) } });
  load({ resetSeries: true });
}

const offlineDevices = computed(() =>
  Math.max((stats.value.deviceTotal || 0) - (stats.value.deviceOnline || 0), 0)
);
const labels = computed(() => trend.value.map((d) => shortDate(d.date)));

function channelParts(statsList?: ChannelStat[]) {
  return (statsList || []).map((s, i) => {
    const code = String(s.channel || 'UNKNOWN').toUpperCase();
    return {
      label: displayLabel('pay_channel', code, CHANNEL_LABELS[code] || '未知'),
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
    series: [
      { name: '营收', values: trend.value.map((d) => d.revenueCents / 100), color: '#2dd4bf' }
    ],
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
      {
        name: '自动识别率',
        values: opsTrend.value.map((d) => d.recognitionRate * 100),
        color: '#2dd4bf'
      },
      { name: '争议率', values: opsTrend.value.map((d) => d.disputeRate * 100), color: '#fbbf24' }
    ],
    kind: opsKind.value,
    formatY: (v) => formatPct(v / 100)
  });
});

const orderChannelSvg = computed(() =>
  buildDonutChart({
    parts: orderChannelParts.value.map((p) => ({ label: p.label, value: p.value, color: p.color })),
    formatCenter: (cents) => (cents / 100).toFixed(2),
    formatValue: (cents) => `¥${(cents / 100).toFixed(2)}`,
    valueLabel: '金额'
  })
);

const rechargeChannelSvg = computed(() =>
  buildDonutChart({
    parts: rechargeChannelParts.value.map((p) => ({
      label: p.label,
      value: p.value,
      color: p.color
    })),
    formatCenter: (cents) => (cents / 100).toFixed(2),
    formatValue: (cents) => `¥${(cents / 100).toFixed(2)}`,
    valueLabel: '金额'
  })
);

const deviceSvg = computed(() =>
  buildDonutChart({
    parts: [
      { label: '在线', value: stats.value.deviceOnline || 0, color: '#2dd4bf' },
      { label: '离线', value: offlineDevices.value, color: '#64748b' }
    ],
    formatValue: (n) => `${n} 台`,
    valueLabel: '数量'
  })
);

async function load(opts?: { resetSeries?: boolean }) {
  loading.value = true;
  // 切天数时清空系列，避免旧区间叠新图；软刷新保留
  if (opts?.resetSeries) {
    trend.value = [];
    opsTrend.value = [];
    channels.value = {};
  }
  try {
    const d = days.value;
    // 财务等角色可能缺 ops 趋势权限；各块独立降级，避免整页空白
    const [s, t, o, f, c] = await Promise.all([
      api.request<AdminStats>('/api/v2/ops/admin/stats', 'GET').catch(() => null),
      api
        .request<{ last7Days: DailyStat[] }>(`/api/v2/ops/admin/trend?days=${d}`, 'GET')
        .catch(() => null),
      api
        .request<{ last7Days: OpsDaily[] }>(`/api/v2/ops/admin/trend/ops?days=${d}`, 'GET')
        .catch(() => null),
      api.request<FinanceStats>('/api/v2/ops/admin/finance/stats', 'GET').catch(() => null),
      api
        .request<ChannelBreakdown>(`/api/v2/ops/admin/trend/channels?days=${d}`, 'GET')
        .catch(() => ({}))
    ]);
    if (!s && !t && !o && !f) {
      ElMessage.error('经营分析数据加载失败');
      return;
    }
    stats.value = s || {};
    trend.value = t?.last7Days || [];
    opsTrend.value = o?.last7Days || [];
    finance.value = f;
    channels.value = c || {};
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    listHydrated.value = true;
    loading.value = false;
  }
}

watch(
  () => route.query.days,
  (raw) => {
    const next = parseDays(raw);
    if (next !== days.value) {
      days.value = next;
      load({ resetSeries: true });
    }
  }
);

onMounted(load);
</script>

<style scoped>
.page-card-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  flex-wrap: wrap;
}
.page-card-head__meta {
  min-width: 0;
}
.page-card-head__title {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.page-card-head__actions {
  display: flex;
  gap: 8px;
  align-items: center;
}
.title {
  font-weight: 600;
  font-size: 15px;
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.4;
}
.header-hint {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  color: var(--layout-muted);
}
.trend-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin-top: 16px;
  padding: 12px 16px;
  border: 1px solid var(--layout-border);
  border-radius: 12px;
  background: var(--layout-card);
}
.trend-title {
  font-weight: 600;
  margin-right: 8px;
}
.stats-row .stat-tile {
  border-radius: 10px;
  padding: 14px 16px;
  margin-bottom: 4px;
  border: 1px solid var(--layout-border);
  background: var(--el-fill-color-light);
  position: relative;
  overflow: hidden;
  height: 100%;
  box-sizing: border-box;
  text-align: center;
}
.stats-row .stat-tile::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 3px;
}
.stat-tile.is-clickable {
  cursor: pointer;
  transition:
    border-color 0.15s ease,
    box-shadow 0.15s ease,
    transform 0.15s ease;
}
.stat-tile.is-clickable:hover,
.stat-tile.is-clickable:focus-visible {
  transform: translateY(-1px);
  border-color: color-mix(in srgb, var(--app-primary, #0f766e) 40%, var(--layout-border));
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--app-primary, #0f766e) 16%, transparent);
  outline: none;
}
.stat-tile.accent-teal::before {
  background: #2dd4bf;
}
.stat-tile.accent-blue::before {
  background: #60a5fa;
}
.stat-tile.accent-violet::before {
  background: #a78bfa;
}
.stat-tile.accent-amber::before {
  background: #fbbf24;
}
.stat-label {
  font-size: 13px;
  color: var(--layout-muted);
}
.stat-value {
  font-size: 24px;
  font-weight: 700;
  margin-top: 6px;
  color: var(--layout-text);
}
.stat-hint {
  font-size: 12px;
  color: var(--layout-muted);
  margin-top: 8px;
}
.muted {
  color: var(--layout-muted);
  font-size: 12px;
}
.donut-legend-list .muted {
  margin-left: 4px;
}
.snapshot-desc :deep(.el-descriptions__label) {
  width: 110px;
}
</style>
