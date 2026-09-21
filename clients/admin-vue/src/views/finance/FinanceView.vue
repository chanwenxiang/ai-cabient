<template>
  <div v-loading="crud.loading" class="finance-page">
    <el-result
      v-if="loadError"
      icon="warning"
      :title="loadError"
      sub-title="如需查看财务数据，请联系管理员开通权限后重试"
    >
      <template #extra>
        <el-button type="primary" :loading="crud.loading" @click="crud.refresh()">重试</el-button>
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
              <!-- 页头刷新是整页刷新（KPI/图表/榜单共用一次报告请求），故 CrudTable 关闭壳内刷新 -->
              <el-button :icon="Refresh" :loading="crud.loading" @click="crud.refresh()"
                >刷新</el-button
              >
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
              <div class="kpi-value" :class="{ warn: crud.hydrated && item.warn }">
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
            <fieldset class="chart-type-switch" aria-label="图表类型">
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
            </fieldset>
          </template>
          <Transition name="chart-fade" mode="out-in">
            <ChartBox
              :key="chartKind"
              :svg="chartSvg"
              :loading="crud.loading && !crud.hydrated"
              :error="loadError ? '毛利趋势加载失败' : ''"
              empty-text="暂无趋势数据"
              @retry="crud.refresh()"
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
              crud.hydrated ? `¥${((stats.revenueTotalCents || 0) / 100).toFixed(2)}` : '…'
            }}</el-descriptions-item>
            <el-descriptions-item label="累计成本">{{
              crud.hydrated ? `¥${((stats.cogsTotalCents || 0) / 100).toFixed(2)}` : '…'
            }}</el-descriptions-item>
            <el-descriptions-item label="累计毛利">{{
              crud.hydrated ? `¥${((stats.grossMarginTotalCents || 0) / 100).toFixed(2)}` : '…'
            }}</el-descriptions-item>
            <el-descriptions-item label="今日报废金额">{{
              crud.hydrated ? `¥${((stats.writeOffTodayCents || 0) / 100).toFixed(2)}` : '…'
            }}</el-descriptions-item>
            <el-descriptions-item label="今日报废件数">{{
              crud.hydrated ? stats.writeOffTodayQty || 0 : '…'
            }}</el-descriptions-item>
          </el-descriptions>
        </ChartPanel>
      </div>

      <ChartPanel :title="`商品毛利排行 · 近 ${days} 天`" compact class="sku-panel">
        <template #actions>
          <el-button v-if="canAccessPath('/skus')" link type="primary" @click="goPath('/skus')"
            >商品管理</el-button
          >
        </template>
        <div class="table-scroll">
          <div class="table-scroll-inner">
            <!-- 商品毛利榜单接入统一表格壳；报告接口无服务端分页（后端榜单上限 20 条），整表一页 -->
            <CrudTable
              :table="crud"
              row-key="skuId"
              selectable
              :show-refresh="false"
              empty-text="暂无商品毛利数据"
              sort-field-label="商品编号"
              :csv="csvOptions"
            >
              <el-table-column prop="skuId" label="商品编号" min-width="120" class-name="col-text">
                <template #default="{ row }">
                  <span class="cell-id">{{ row.skuId }}</span>
                </template>
              </el-table-column>
              <el-table-column label="商品" min-width="140" class-name="col-text">
                <template #default="{ row }">{{ row.skuName || '无' }}</template>
              </el-table-column>
              <el-table-column
                prop="qtySold"
                label="销量"
                min-width="88"
                align="center"
                class-name="col-status"
                label-class-name="col-status"
              />
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
              <el-table-column
                label="毛利率"
                min-width="96"
                align="center"
                class-name="col-money"
                label-class-name="col-money"
              >
                <template #default="{ row }">
                  <span
                    :class="{
                      warn:
                        row.revenueCents > 0 &&
                        Number(((row.grossMarginCents / row.revenueCents) * 100).toFixed(1)) < 0
                    }"
                    >{{ skuMarginRateDisplay(row) }}</span
                  >
                </template>
              </el-table-column>
              <el-table-column
                label="件均价"
                min-width="100"
                align="center"
                class-name="col-money"
                label-class-name="col-money"
              >
                <template #default="{ row }">
                  {{
                    Number(row.qtySold) > 0
                      ? `¥${(Number(row.revenueCents || 0) / Number(row.qtySold) / 100).toFixed(2)}`
                      : '暂无'
                  }}
                </template>
              </el-table-column>
              <el-table-column
                label="件均成本"
                min-width="100"
                align="center"
                class-name="col-money"
                label-class-name="col-money"
              >
                <template #default="{ row }">
                  {{
                    Number(row.qtySold) > 0
                      ? `¥${(Number(row.cogsCents || 0) / Number(row.qtySold) / 100).toFixed(2)}`
                      : '暂无'
                  }}
                </template>
              </el-table-column>
            </CrudTable>
          </div>
        </div>
      </ChartPanel>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import ChartBox from '@/components/ChartBox.vue';
import ChartPanel from '@/components/ChartPanel.vue';
import CrudTable, { type CrudCsvOptions } from '@/components/CrudTable.vue';
import { useCrudTable } from '@/composables/useCrudTable';
import { useNavAccess } from '@/composables/useNavAccess';
import { buildSeriesChart, formatYuan, shortDate, type ChartKind } from '@/utils/charts';
import { UI_COPY } from '@aicabinet/shared-uni/ui-copy';

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
const solidifying = ref(false);
const loadError = ref('');
const days = ref(parseDays(route.query.days));
const chartKind = ref<ChartKind>('area');
const stats = ref<FinanceStats>({});
const daily = ref<FinanceDaily[]>([]);

// 列表状态机统一交给 CrudTable：分页/排序/多选/竞态/空态全部内建。
// 报告接口一次带回 KPI/趋势/榜单：fetchPage 内顺带分发 stats/daily；
// crud.hydrated 首查完成后置真（首屏未拉完前勿展示 ¥0 / 0%，避免与真实快照闪错）。
const crud = useCrudTable<FinanceSku>({
  rowKey: (r) => r.skuId,
  fetchPage: async () => {
    loadError.value = '';
    try {
      const data = await api.request<FinanceReport>(
        AdminEndpoints.financeReport(days.value),
        'GET'
      );
      stats.value = data.summary || {};
      daily.value = data.daily || [];
      // 榜单无服务端分页（后端上限 20 条），整表一页返回
      return data.topSkus || [];
    } catch (e) {
      // 整页错误态（el-result）由页面维护；错误提示交给 useCrudTable 统一弹出
      loadError.value = e instanceof Error ? e.message : '加载失败';
      throw e;
    }
  },
  sort: { prop: 'skuId', mode: 'local' }
});

const csvOptions: CrudCsvOptions = {
  filePrefix: '商品毛利TOP',
  exportPerm: 'ops:finance:export',
  headers: ['商品编号', '商品', '销量', '营收', '成本', '毛利', '毛利率'],
  toRows: (rows) =>
    rows.map((row) => [
      row.skuId,
      row.skuName,
      row.qtySold,
      (row.revenueCents / 100).toFixed(2),
      (row.cogsCents / 100).toFixed(2),
      (row.grossMarginCents / 100).toFixed(2),
      `${skuMarginRateDisplay(row)}`
    ])
};

function parseDays(raw: unknown): number {
  const n = Number(raw);
  if (n === 1 || n === 7 || n === 30 || n === 90) return n;
  if (raw != null && String(raw).trim() !== '' && Number.isFinite(n)) {
    ElMessage.warning('天数仅支持 1 / 7 / 30 / 90，已回退为近 1 天');
  }
  return 1;
}

function onDaysChange() {
  router.replace({ query: { ...route.query, days: String(days.value) } });
  // 切天数时清空趋势系列，避免旧区间叠新图；榜单由 crud.search() 回第一页重查
  daily.value = [];
  void crud.search();
}

/** 无营收时不展示假 0% 毛利率 */
function skuMarginRate(row: FinanceSku): string {
  if (!row.revenueCents) return '暂无';
  return ((row.grossMarginCents / row.revenueCents) * 100).toFixed(1);
}

function skuMarginRateDisplay(row: FinanceSku): string {
  const rate = skuMarginRate(row);
  return rate === '暂无' ? '暂无' : `${rate}%`;
}

function kpiNavHint(ready: boolean, canNavigate: boolean, navigateLabel: string) {
  if (!ready) return UI_COPY.loading;
  return canNavigate ? navigateLabel : '今日快照';
}

function financeKpiMoney(ready: boolean, cents: number) {
  return ready ? `¥${(cents / 100).toFixed(2)}` : '…';
}

type FinanceKpiTile = {
  label: string;
  value: string;
  accent: string;
  path?: string;
  hint?: string;
  warn?: boolean;
};

function buildFinanceKpiTiles(
  ready: boolean,
  canAnalytics: boolean,
  canOrders: boolean,
  marginRate: number,
  marginCents: number
): FinanceKpiTile[] {
  const loadingHint = ready ? undefined : UI_COPY.loading;
  const revenueHint = kpiNavHint(ready, canAnalytics, '查看数据分析');
  const ordersHint = kpiNavHint(ready, canOrders, '查看订单');
  return [
    {
      label: '今日营收',
      value: financeKpiMoney(ready, stats.value.revenueTodayCents || 0),
      accent: 'accent-teal',
      path: canAnalytics ? '/analytics' : undefined,
      hint: revenueHint
    },
    {
      label: '今日成本',
      value: financeKpiMoney(ready, stats.value.cogsTodayCents || 0),
      accent: 'accent-blue',
      hint: loadingHint
    },
    {
      label: '今日毛利',
      value: financeKpiMoney(ready, marginCents),
      accent: 'accent-amber',
      warn: ready && marginCents < 0,
      hint: loadingHint
    },
    {
      label: '今日毛利率',
      value: ready ? `${marginRate.toFixed(1)}%` : '…',
      accent: 'accent-violet',
      warn: ready && marginRate < 0,
      hint: loadingHint
    },
    {
      label: '今日订单',
      value: ready ? String(stats.value.orderToday || 0) : '…',
      accent: 'accent-teal',
      path: canOrders ? '/orders' : undefined,
      hint: ordersHint
    },
    {
      label: '今日客单',
      value: financeKpiMoney(ready, stats.value.averageOrderValueTodayCents || 0),
      accent: 'accent-blue',
      hint: loadingHint
    }
  ];
}

const kpiTiles = computed(() => {
  const marginRate = (stats.value.grossMarginRateToday || 0) * 100;
  const marginCents = stats.value.grossMarginTodayCents || 0;
  return buildFinanceKpiTiles(
    crud.hydrated,
    canAccessPath('/analytics'),
    canAccessPath('/orders'),
    marginRate,
    marginCents
  );
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

async function solidifyYesterday() {
  try {
    // H06：固化会按昨日快照落库、不可回溯重算，必须二次确认
    await ElMessageBox.confirm(
      '将把昨日毛利按快照固化入账，固化后不可回溯重算，确认执行？',
      '固化昨日毛利',
      {
        type: 'warning',
        confirmButtonText: '确认固化',
        cancelButtonText: '取消'
      }
    );
  } catch {
    return;
  }
  solidifying.value = true;
  try {
    await api.request(AdminEndpoints.financeMarginLocksSolidify, 'POST');
    ElMessage.success('昨日毛利已固化');
    await crud.load();
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
      daily.value = [];
      void crud.search();
    }
  }
);
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
  font-size: var(--admin-font-size-title);
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: var(--admin-font-size-sm);
  line-height: 1.4;
}
.header-hint {
  display: block;
  margin-top: 4px;
  font-size: var(--admin-font-size-sm);
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
  text-align: center;
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
  font-size: var(--admin-font-size-table);
  color: var(--layout-muted);
}
.kpi-value {
  font-size: var(--admin-font-size-display);
  font-weight: 700;
  margin-top: 4px;
  color: var(--layout-text);
}
.kpi-hint {
  font-size: var(--admin-font-size-sm);
  color: var(--layout-muted);
  margin-top: 6px;
}
.warn {
  color: #dc2626;
}
.sku-panel {
  margin-top: 16px;
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
