<template>
  <div v-loading="loading" class="finance-page">
    <el-result
      v-if="loadError"
      icon="warning"
      :title="loadError"
      sub-title="如需查看财务数据，请联系管理员开通权限后重试"
    >
      <template #extra>
        <el-button type="primary" :loading="loading" @click="load">重试</el-button>
        <el-button @click="goPath('/')">返回工作台</el-button>
      </template>
    </el-result>

    <template v-else>
      <el-card class="page-card" shadow="never">
        <template #header>
          <div class="page-card-head">
            <div class="page-card-head__meta">
              <div class="page-card-head__title">
                <span class="title">财务毛利</span>
                <span class="hint">上方为今日快照；趋势与商品 TOP 受下方天数范围影响</span>
              </div>
            </div>
            <div class="page-card-head__actions">
              <el-button type="primary" plain :loading="solidifying" @click="solidifyYesterday"
                >固化昨日毛利</el-button
              >
              <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
            </div>
          </div>
        </template>

        <el-alert
          type="info"
          :closable="false"
          show-icon
          class="margin-lock-alert"
          title="毛利固化规则"
          description="今日数据实时计算；历史日在日切后固化，固化后即使修改采购成本也不回溯。可手动「固化昨日毛利」。日资金账单见「资金账单」菜单。"
        />

        <el-row :gutter="12" class="kpi-row">
          <el-col v-for="item in kpiTiles" :key="item.label" :xs="12" :sm="8" :md="4">
            <div
              class="kpi-tile"
              :class="[item.accent, { 'is-clickable': !!item.path && canAccessPath(item.path) }]"
              :role="item.path && canAccessPath(item.path) ? 'button' : undefined"
              :tabindex="item.path && canAccessPath(item.path) ? 0 : undefined"
              @click="item.path && goPath(item.path)"
              @keydown.enter="item.path && goPath(item.path)"
            >
              <div class="kpi-label">{{ item.label }}</div>
              <div class="kpi-value" :class="{ warn: listHydrated && item.warn }">
                {{ item.value }}
              </div>
              <div v-if="item.hint" class="kpi-hint">{{ item.hint }}</div>
            </div>
          </el-col>
        </el-row>
      </el-card>

      <div class="trend-toolbar">
        <div>
          <span class="trend-title">趋势与商品</span>
          <span class="header-hint">当前范围：近 {{ days }} 天</span>
        </div>
        <el-radio-group v-model="days" size="default" @change="onDaysChange">
          <el-radio-button :value="1">今天</el-radio-button>
          <el-radio-button :value="7">近 7 天</el-radio-button>
          <el-radio-button :value="30">近 30 天</el-radio-button>
          <el-radio-button :value="90">近 90 天</el-radio-button>
        </el-radio-group>
      </div>

      <div class="chart-grid chart-grid--split">
        <ChartPanel title="毛利趋势" :hint="`近 ${days} 天 · 营收 / 成本 / 毛利`">
          <template #actions>
            <div class="chart-type-switch" role="group" aria-label="图表类型">
              <button
                type="button"
                :class="{ active: chartKind === 'line' }"
                @click="chartKind = 'line'"
              >
                折线
              </button>
              <button
                type="button"
                :class="{ active: chartKind === 'area' }"
                @click="chartKind = 'area'"
              >
                面积
              </button>
              <button
                type="button"
                :class="{ active: chartKind === 'bar' }"
                @click="chartKind = 'bar'"
              >
                柱状
              </button>
            </div>
          </template>
          <Transition name="chart-fade" mode="out-in">
            <ChartBox v-if="chartSvg" :key="chartKind" :svg="chartSvg" />
            <el-empty
              v-else-if="listHydrated"
              key="empty"
              description="暂无趋势数据"
              :image-size="64"
            />
          </Transition>
          <template #footer>
            <span class="chart-legend-item"><i style="background: #2dd4bf" />营收</span>
            <span class="chart-legend-item"><i style="background: #60a5fa" />成本</span>
            <span class="chart-legend-item"><i style="background: #fbbf24" />毛利</span>
          </template>
        </ChartPanel>

        <ChartPanel title="累计快照" fill compact>
          <el-descriptions :column="1" border size="small" class="snapshot-desc">
            <el-descriptions-item label="累计营收">{{
              listHydrated ? `¥${((stats.revenueTotalCents || 0) / 100).toFixed(2)}` : '—'
            }}</el-descriptions-item>
            <el-descriptions-item label="累计成本">{{
              listHydrated ? `¥${((stats.cogsTotalCents || 0) / 100).toFixed(2)}` : '—'
            }}</el-descriptions-item>
            <el-descriptions-item label="累计毛利">{{
              listHydrated ? `¥${((stats.grossMarginTotalCents || 0) / 100).toFixed(2)}` : '—'
            }}</el-descriptions-item>
            <el-descriptions-item label="今日报废金额">{{
              listHydrated ? `¥${((stats.writeOffTodayCents || 0) / 100).toFixed(2)}` : '—'
            }}</el-descriptions-item>
            <el-descriptions-item label="今日报废件数">{{
              listHydrated ? stats.writeOffTodayQty || 0 : '—'
            }}</el-descriptions-item>
          </el-descriptions>
        </ChartPanel>
      </div>

      <ChartPanel :title="`商品毛利排行 · 近 ${days} 天`" compact class="sku-panel">
        <template #actions>
          <el-button v-hasPermi="['ops:finance:export']" @click="onExportTopSkus">{{
            topSkusExportLabel
          }}</el-button>
          <el-button v-if="canAccessPath('/skus')" link type="primary" @click="goPath('/skus')"
            >商品管理</el-button
          >
        </template>
        <div class="table-scroll">
          <div class="table-scroll-inner">
            <el-table
              class="report-table sku-table"
              :data="displayTopSkus"
              :default-sort="idDefaultSort"
              @sort-change="onIdSortChange"
              stripe
              border
              row-key="skuId"
              @selection-change="onTopSkusSelectionChange"
              empty-text=" "
            >
              <template #empty
                ><el-empty v-if="listHydrated && !loading" description="暂无商品毛利数据"
              /></template>
              <el-table-column type="selection" width="48" align="center" />
              <el-table-column
                prop="skuId"
                label="商品编号"
                min-width="120"
                align="center"
                class-name="col-text"
                show-overflow-tooltip
                sortable="custom"
              >
                <template #default="{ row }">
                  <span class="cell-id">{{ row.skuId }}</span>
                </template>
              </el-table-column>
              <el-table-column
                label="商品"
                min-width="140"
                align="center"
                class-name="col-text"
                show-overflow-tooltip
              >
                <template #default="{ row }">{{ row.skuName || '无' }}</template>
              </el-table-column>
              <el-table-column prop="qtySold" label="销量" min-width="88" align="center" />
              <el-table-column label="营收" min-width="110" align="center" class-name="col-money">
                <template #default="{ row }">¥{{ (row.revenueCents / 100).toFixed(2) }}</template>
              </el-table-column>
              <el-table-column label="成本" min-width="110" align="center" class-name="col-money">
                <template #default="{ row }">¥{{ (row.cogsCents / 100).toFixed(2) }}</template>
              </el-table-column>
              <el-table-column label="毛利" min-width="110" align="center" class-name="col-money">
                <template #default="{ row }">
                  <span :class="{ warn: row.grossMarginCents < 0 }"
                    >¥{{ (row.grossMarginCents / 100).toFixed(2) }}</span
                  >
                </template>
              </el-table-column>
              <el-table-column label="毛利率" min-width="96" align="center">
                <template #default="{ row }">
                  <span :class="{ warn: Number(skuMarginRate(row)) < 0 }"
                    >{{ skuMarginRate(row) }}%</span
                  >
                </template>
              </el-table-column>
            </el-table>
          </div>
        </div>
      </ChartPanel>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';
import ChartBox from '@/components/ChartBox.vue';
import ChartPanel from '@/components/ChartPanel.vue';
import { useListCsv } from '@/composables/useListCsv';
import { useNavAccess } from '@/composables/useNavAccess';
import { useTableSelection } from '@/composables/useTableSelection';
import { useIdColumnSort } from '@/composables/useIdColumnSort';
import { buildSeriesChart, formatYuan, shortDate, type ChartKind } from '@/utils/charts';

interface FinanceStats {
  revenueTodayCents?: number;
  cogsTodayCents?: number;
  grossMarginTodayCents?: number;
  writeOffTodayCents?: number;
  writeOffTodayQty?: number;
  orderToday?: number;
  averageOrderValueTodayCents?: number;
  grossMarginRateToday?: number;
  revenueTotalCents?: number;
  cogsTotalCents?: number;
  grossMarginTotalCents?: number;
}

interface FinanceDaily {
  date: string;
  revenueCents: number;
  cogsCents: number;
  grossMarginCents: number;
  writeOffCents: number;
}

interface FinanceSku {
  skuId: string;
  skuName: string;
  qtySold: number;
  revenueCents: number;
  cogsCents: number;
  grossMarginCents: number;
}

interface FinanceReport {
  summary: FinanceStats;
  daily: FinanceDaily[];
  topSkus: FinanceSku[];
}

const { router, canAccessPath, goPath } = useNavAccess();
const route = useRoute();
const loading = ref(false);
/** 首屏未拉完前勿展示 ¥0 / 0%，避免与真实快照闪错 */
const listHydrated = ref(false);
const solidifying = ref(false);
const loadError = ref('');
const days = ref(parseDays(route.query.days));
const chartKind = ref<ChartKind>('area');
const stats = ref<FinanceStats>({});
const daily = ref<FinanceDaily[]>([]);
const topSkus = ref<FinanceSku[]>([]);
const {
  defaultSort: idDefaultSort,
  onSortChange: onIdSortChange,
  sortById
} = useIdColumnSort<FinanceSku>('skuId');
const displayTopSkus = computed(() => sortById(topSkus.value));

function parseDays(raw: unknown): number {
  const n = Number(raw);
  return n === 1 || n === 7 || n === 30 || n === 90 ? n : 1;
}

function onDaysChange() {
  router.replace({ query: { ...route.query, days: String(days.value) } });
  load({ resetSeries: true });
}

function skuMarginRate(row: FinanceSku) {
  return row.revenueCents ? ((row.grossMarginCents / row.revenueCents) * 100).toFixed(1) : '0.0';
}

const {
  onSelectionChange: onTopSkusSelectionChange,
  pickSelected: pickTopSkusSelected,
  exportButtonLabel: topSkusExportLabel,
  clearSelection: clearTopSkusSelection
} = useTableSelection<FinanceSku>((row) => row.skuId);

const { onExport: onExportTopSkus } = useListCsv({
  filePrefix: '商品毛利TOP',
  headers: ['商品编号', '商品', '销量', '营收', '成本', '毛利', '毛利率'],
  toRows: () =>
    pickTopSkusSelected(topSkus.value).map((row) => [
      row.skuId,
      row.skuName,
      row.qtySold,
      (row.revenueCents / 100).toFixed(2),
      (row.cogsCents / 100).toFixed(2),
      (row.grossMarginCents / 100).toFixed(2),
      `${skuMarginRate(row)}%`
    ])
});

const kpiTiles = computed(() => {
  const marginRate = (stats.value.grossMarginRateToday || 0) * 100;
  const marginCents = stats.value.grossMarginTodayCents || 0;
  const canAnalytics = canAccessPath('/analytics');
  const canOrders = canAccessPath('/orders');
  const ready = listHydrated.value;
  return [
    {
      label: '今日营收',
      value: ready ? `¥${((stats.value.revenueTodayCents || 0) / 100).toFixed(2)}` : '—',
      accent: 'accent-teal',
      path: canAnalytics ? '/analytics' : undefined,
      hint: ready ? (canAnalytics ? '查看数据分析' : '今日快照') : '加载中…'
    },
    {
      label: '今日成本',
      value: ready ? `¥${((stats.value.cogsTodayCents || 0) / 100).toFixed(2)}` : '—',
      accent: 'accent-blue',
      hint: ready ? undefined : '加载中…'
    },
    {
      label: '今日毛利',
      value: ready ? `¥${(marginCents / 100).toFixed(2)}` : '—',
      accent: 'accent-amber',
      warn: ready && marginCents < 0,
      hint: ready ? undefined : '加载中…'
    },
    {
      label: '今日毛利率',
      value: ready ? `${marginRate.toFixed(1)}%` : '—',
      accent: 'accent-violet',
      warn: ready && marginRate < 0,
      hint: ready ? undefined : '加载中…'
    },
    {
      label: '今日订单',
      value: ready ? String(stats.value.orderToday || 0) : '—',
      accent: 'accent-teal',
      path: canOrders ? '/orders' : undefined,
      hint: ready ? (canOrders ? '查看订单' : '今日快照') : '加载中…'
    },
    {
      label: '今日客单',
      value: ready ? `¥${((stats.value.averageOrderValueTodayCents || 0) / 100).toFixed(2)}` : '—',
      accent: 'accent-blue',
      hint: ready ? undefined : '加载中…'
    }
  ];
});

const chartSvg = computed(() => {
  if (!daily.value.length) return '';
  return buildSeriesChart({
    labels: daily.value.map((d) => shortDate(d.date)),
    series: [
      { name: '营收', values: daily.value.map((d) => d.revenueCents / 100), color: '#2dd4bf' },
      { name: '成本', values: daily.value.map((d) => d.cogsCents / 100), color: '#60a5fa' },
      { name: '毛利', values: daily.value.map((d) => d.grossMarginCents / 100), color: '#fbbf24' }
    ],
    kind: chartKind.value,
    formatY: (v) => formatYuan(v * 100)
  });
});

async function load(opts?: { resetSeries?: boolean }) {
  loading.value = true;
  loadError.value = '';
  // 切天数时清空系列，避免旧区间叠新图；软刷新保留
  if (opts?.resetSeries) {
    daily.value = [];
    topSkus.value = [];
  }
  try {
    const data = await api.request<FinanceReport>(
      `/api/v2/ops/admin/finance/report?days=${days.value}`,
      'GET'
    );
    stats.value = data.summary || {};
    daily.value = data.daily || [];
    topSkus.value = data.topSkus || [];
    clearTopSkusSelection();
  } catch (e) {
    const message = e instanceof Error ? e.message : '加载失败';
    loadError.value = message;
    ElMessage.error(message);
  } finally {
    listHydrated.value = true;
    loading.value = false;
  }
}

async function solidifyYesterday() {
  solidifying.value = true;
  try {
    await api.request('/api/v2/ops/admin/finance/margin-locks/solidify', 'POST');
    ElMessage.success('昨日毛利已固化');
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '固化失败');
  } finally {
    solidifying.value = false;
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
.kpi-row {
  margin-bottom: 4px;
}
.margin-lock-alert {
  margin-bottom: 12px;
}
.kpi-tile {
  background: var(--el-fill-color-light);
  border: 1px solid var(--layout-border);
  border-radius: 10px;
  padding: 12px 14px;
  margin-bottom: 8px;
  height: 100%;
  box-sizing: border-box;
  position: relative;
  overflow: hidden;
}
.kpi-tile::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 3px;
}
.kpi-tile.accent-teal::before {
  background: #2dd4bf;
}
.kpi-tile.accent-blue::before {
  background: #60a5fa;
}
.kpi-tile.accent-violet::before {
  background: #a78bfa;
}
.kpi-tile.accent-amber::before {
  background: #fbbf24;
}
.kpi-tile.is-clickable {
  cursor: pointer;
  transition:
    border-color 0.15s ease,
    box-shadow 0.15s ease,
    transform 0.15s ease;
}
.kpi-tile.is-clickable:hover,
.kpi-tile.is-clickable:focus-visible {
  transform: translateY(-1px);
  border-color: color-mix(in srgb, var(--app-primary, #0f766e) 40%, var(--layout-border));
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--app-primary, #0f766e) 16%, transparent);
  outline: none;
}
.kpi-label {
  font-size: 13px;
  color: var(--layout-muted);
}
.kpi-value {
  font-size: 20px;
  font-weight: 700;
  margin-top: 4px;
  color: var(--layout-text);
}
.kpi-hint {
  font-size: 12px;
  color: var(--layout-muted);
  margin-top: 6px;
}
.warn {
  color: #dc2626;
}
.sku-panel {
  margin-top: 16px;
}
.sku-table :deep(th.col-text > .cell),
.sku-table :deep(td.col-text > .cell) {
  text-align: center;
}
.snapshot-desc :deep(.el-descriptions__table) {
  height: 100%;
}
.snapshot-desc :deep(.el-descriptions__cell) {
  vertical-align: middle;
}
.snapshot-desc :deep(.el-descriptions__label) {
  width: 110px;
}
</style>
