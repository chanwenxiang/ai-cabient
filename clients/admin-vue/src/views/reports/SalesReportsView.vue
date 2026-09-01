<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">销售报表</span>
            <span class="hint"
              >商品 / 货柜 / 商户 / 渠道 / 毛利 · 含退款与净营收 · 可按柜机筛选</span
            >
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button @click="onExport">{{ exportButtonLabel }}</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="search">
      <el-form-item label="维度">
        <el-radio-group v-model="dim" @change="search">
          <el-radio-button value="PRODUCT">商品</el-radio-button>
          <el-radio-button value="CABINET">货柜</el-radio-button>
          <el-radio-button value="MERCHANT">商户</el-radio-button>
          <el-radio-button value="CHANNEL">渠道</el-radio-button>
          <el-radio-button value="MARGIN">毛利</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="柜机">
        <el-select
          v-model="deviceId"
          clearable
          filterable
          placeholder="全部柜机"
          style="width: 200px"
          @change="search"
        >
          <el-option
            v-for="d in deviceOptions"
            :key="d.deviceId"
            :label="`${d.deviceName || d.deviceId}（${d.deviceId}）`"
            :value="d.deviceId"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="快捷">
        <el-radio-group v-model="rangePreset" size="small" @change="onPresetChange">
          <el-radio-button value="today">今日</el-radio-button>
          <el-radio-button value="7d">近7天</el-radio-button>
          <el-radio-button value="30d">近30天</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="区间">
        <el-date-picker
          v-model="range"
          type="daterange"
          value-format="YYYY-MM-DD"
          @change="onRangePicked"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </el-form-item>
    </el-form>

    <div class="kpi-grid">
      <div
        v-for="tile in kpiTiles"
        :key="tile.label"
        class="kpi-tile"
        :class="tile.accent"
        :aria-label="`${tile.label} ${tile.value}`"
      >
        <div class="kpi-label">{{ tile.label }}</div>
        <div class="kpi-value">{{ listHydrated ? tile.value : '…' }}</div>
        <div v-if="tile.hint" class="kpi-hint">{{ tile.hint }}</div>
      </div>
    </div>

    <div class="table-scroll">
      <div class="table-scroll-inner">
        <el-table
          ref="tableRef"
          v-loading="loading"
          :data="rows"
          stripe
          border
          class="report-table"
          row-key="dimKey"
          empty-text=" "
          :default-sort="tableDefaultSort"
          @selection-change="onSelectionChange"
          @sort-change="onSortChange"
          @row-click="onRowClick"
        >
          <template #empty
            ><el-empty v-if="listHydrated && !loading" description="暂无数据"
          /></template>
          <el-table-column type="selection" width="48" align="center" />
          <el-table-column prop="dimKey" label="编码" min-width="140" align="center">
            <template #default="{ row }">
              <button
                v-if="canNavigateRow(row)"
                type="button"
                class="dim-link"
                @click.stop="navigateRow(row)"
              >
                {{ row.dimKey || '—' }}
              </button>
              <span v-else>{{ row.dimKey || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="dimLabel" label="名称" min-width="160" align="center">
            <template #default="{ row }">
              <button
                v-if="canNavigateRow(row)"
                type="button"
                class="dim-link"
                @click.stop="navigateRow(row)"
              >
                {{ row.dimLabel || '—' }}
              </button>
              <span v-else>{{ row.dimLabel || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="orderCount" label="订单数" width="90" align="center" />
          <el-table-column prop="qty" label="销量" width="80" align="center" />
          <el-table-column
            prop="revenueCents"
            label="营收"
            width="110"
            align="center"
            sortable="custom"
          >
            <template #default="{ row }">{{ yuan(row.revenueCents) }}</template>
          </el-table-column>
          <el-table-column
            prop="refundedCents"
            label="退款"
            width="110"
            align="center"
            sortable="custom"
          >
            <template #default="{ row }">
              <span :class="{ 'is-refund': Number(row.refundedCents || 0) > 0 }">{{
                yuan(row.refundedCents)
              }}</span>
            </template>
          </el-table-column>
          <el-table-column label="退款单" width="80" align="center">
            <template #default="{ row }">{{ Number(row.refundOrderCount || 0) }}</template>
          </el-table-column>
          <el-table-column label="退款率" width="88" align="center">
            <template #default="{ row }">{{ refundRateText(row) }}</template>
          </el-table-column>
          <el-table-column label="净营收" width="110" align="center">
            <template #default="{ row }">{{ yuan(netRevenue(row)) }}</template>
          </el-table-column>
          <el-table-column label="成本" width="110" align="center">
            <template #default="{ row }">{{ yuan(row.cogsCents) }}</template>
          </el-table-column>
          <el-table-column
            prop="marginCents"
            label="毛利"
            width="110"
            align="center"
            sortable="custom"
          >
            <template #default="{ row }">{{ yuan(row.marginCents) }}</template>
          </el-table-column>
          <el-table-column label="毛利率" width="90" align="center">
            <template #default="{ row }">
              {{
                Number(row.revenueCents) > 0
                  ? `${((Number(row.marginCents || 0) / Number(row.revenueCents)) * 100).toFixed(1)}%`
                  : '暂无'
              }}
            </template>
          </el-table-column>
          <el-table-column label="客单价" width="100" align="center">
            <template #default="{ row }">
              {{
                Number(row.orderCount) > 0
                  ? yuan(Number(row.revenueCents || 0) / Number(row.orderCount))
                  : '暂无'
              }}
            </template>
          </el-table-column>
          <el-table-column label="件均价" width="100" align="center">
            <template #default="{ row }">
              {{
                Number(row.qty) > 0 ? yuan(Number(row.revenueCents || 0) / Number(row.qty)) : '暂无'
              }}
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <PagePager
      :hydrated="listHydrated"
      v-model:current-page="page"
      v-model:page-size="size"
      :total="total"
      :page-sizes="[10, 20, 50, 100]"
      layout="total, sizes, prev, pager, next, jumper"
      background
      @current-change="load"
      @size-change="onSizeChange"
    />
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import type { TableColumnCtx } from 'element-plus';
import { api, downloadAuthFile } from '@/api/client';
import PagePager from '@/components/PagePager.vue';
import { useAdminListTable } from '@/composables/useAdminListTable';
import { useDeviceOptions } from '@/composables/useDeviceOptions';
import { useListCsv } from '@/composables/useListCsv';
import { useNavAccess } from '@/composables/useNavAccess';
import { csvFileName } from '@/utils/csv';

interface SalesRow {
  dimKey?: string;
  dimLabel?: string;
  orderCount?: number;
  qty?: number;
  revenueCents?: number;
  cogsCents?: number;
  marginCents?: number;
  refundedCents?: number;
  refundOrderCount?: number;
  netRevenueCents?: number;
}

interface SalesSummary {
  rowCount?: number;
  orderCount?: number;
  qty?: number;
  revenueCents?: number;
  refundedCents?: number;
  refundOrderCount?: number;
  netRevenueCents?: number;
  cogsCents?: number;
  marginCents?: number;
}

type RangePreset = 'today' | '7d' | '30d' | 'custom';
type SortProp = 'revenueCents' | 'refundedCents' | 'marginCents' | '';

const { deviceOptions, loadDeviceOptions } = useDeviceOptions();
const { canAccessPath, goPath } = useNavAccess();

const loading = ref(false);
const listHydrated = ref(false);
const page = ref(1);
const size = ref(20);
const total = ref(0);
const dim = ref('PRODUCT');
const deviceId = ref('');
const rangePreset = ref<RangePreset>('today');
const summary = ref<SalesSummary | null>(null);
const sortBy = ref<SortProp>('');
const sortDir = ref<'asc' | 'desc' | ''>('');

function todayStr() {
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

function shiftDateStr(days: number) {
  const d = new Date();
  d.setDate(d.getDate() + days);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

function rangeForPreset(preset: RangePreset): [string, string] {
  const today = todayStr();
  if (preset === '7d') return [shiftDateStr(-6), today];
  if (preset === '30d') return [shiftDateStr(-29), today];
  return [today, today];
}

const range = ref<[string, string] | null>(rangeForPreset('today'));
const rows = ref<SalesRow[]>([]);

const {
  tableRef,
  selectedKeys,
  onSelectionChange,
  pickSelected,
  exportButtonLabel,
  clearSelection
} = useAdminListTable<SalesRow>((r) => r.dimKey || r.dimLabel || '');

const tableDefaultSort = computed(() => {
  if (sortBy.value && sortDir.value) {
    return { prop: sortBy.value, order: sortDir.value === 'asc' ? 'ascending' : 'descending' };
  }
  return undefined;
});

function yuan(cents?: number | null) {
  if (cents == null || Number.isNaN(Number(cents))) return '¥0.00';
  return `¥${(Number(cents) / 100).toFixed(2)}`;
}

/** 净营收：优先用后端字段，否则营收 - 退款。 */
function netRevenue(row: SalesRow) {
  if (row.netRevenueCents != null) return Number(row.netRevenueCents);
  return Math.max(0, Number(row.revenueCents || 0) - Number(row.refundedCents || 0));
}

function refundRateText(row: SalesRow) {
  const revenue = Number(row.revenueCents || 0);
  const refunded = Number(row.refundedCents || 0);
  if (revenue <= 0) return refunded > 0 ? '—' : '0.0%';
  return `${((refunded / revenue) * 100).toFixed(1)}%`;
}

function canNavigateRow(row: SalesRow) {
  const key = (row.dimKey || '').trim();
  if (!key) return false;
  if (dim.value === 'PRODUCT' || dim.value === 'MARGIN') return canAccessPath('/skus');
  if (dim.value === 'CABINET') return canAccessPath(`/devices/${encodeURIComponent(key)}`);
  if (dim.value === 'MERCHANT') return canAccessPath('/merchants');
  return false;
}

function navigateRow(row: SalesRow) {
  const key = (row.dimKey || '').trim();
  if (!key) return;
  if (dim.value === 'PRODUCT' || dim.value === 'MARGIN') {
    goPath('/skus', { keyword: key });
    return;
  }
  if (dim.value === 'CABINET') {
    goPath(`/devices/${encodeURIComponent(key)}`);
    return;
  }
  if (dim.value === 'MERCHANT') {
    goPath('/merchants', { tab: 'splits', merchantId: key });
  }
}

function onRowClick(row: SalesRow) {
  if (canNavigateRow(row)) navigateRow(row);
}

function onSortChange(payload: { prop: string; order: string | null; column: TableColumnCtx<SalesRow> }) {
  const prop = payload.prop as SortProp;
  if (!payload.order || (prop !== 'revenueCents' && prop !== 'refundedCents' && prop !== 'marginCents')) {
    sortBy.value = '';
    sortDir.value = '';
  } else {
    sortBy.value = prop;
    sortDir.value = payload.order === 'ascending' ? 'asc' : 'desc';
  }
  page.value = 1;
  load();
}

const kpiTiles = computed(() => {
  const s = summary.value;
  const revenue = Number(s?.revenueCents || 0);
  const refunded = Number(s?.refundedCents || 0);
  const margin = Number(s?.marginCents || 0);
  const refundRate = revenue > 0 ? `${((refunded / revenue) * 100).toFixed(1)}%` : '0.0%';
  const marginRate = revenue > 0 ? `${((margin / revenue) * 100).toFixed(1)}%` : '暂无';
  return [
    {
      label: '订单数',
      value: String(s?.orderCount ?? 0),
      hint: `共 ${s?.rowCount ?? 0} 行`,
      accent: ''
    },
    {
      label: '营收',
      value: yuan(s?.revenueCents),
      hint: `销量 ${s?.qty ?? 0}`,
      accent: ''
    },
    {
      label: '退款',
      value: yuan(s?.refundedCents),
      hint: `${s?.refundOrderCount ?? 0} 单 · ${refundRate}`,
      accent: refunded > 0 ? 'warn' : ''
    },
    {
      label: '净营收',
      value: yuan(s?.netRevenueCents ?? Math.max(0, revenue - refunded)),
      hint: '',
      accent: ''
    },
    {
      label: '毛利',
      value: yuan(s?.marginCents),
      hint: `毛利率 ${marginRate}`,
      accent: ''
    }
  ];
});

const { onExport: exportSelectedCsv } = useListCsv({
  filePrefix: '销售报表',
  headers: [
    '编码',
    '名称',
    '订单数',
    '销量',
    '营收',
    '退款',
    '退款单',
    '退款率%',
    '净营收',
    '成本',
    '毛利',
    '毛利率%',
    '客单价',
    '件均价'
  ],
  toRows: () =>
    pickSelected(rows.value).map((r) => {
      const revenue = Number(r.revenueCents || 0);
      const margin = Number(r.marginCents || 0);
      const refunded = Number(r.refundedCents || 0);
      const orders = Number(r.orderCount || 0);
      const qty = Number(r.qty || 0);
      const net = netRevenue(r);
      return [
        r.dimKey || '',
        r.dimLabel || '',
        orders,
        qty,
        (revenue / 100).toFixed(2),
        (refunded / 100).toFixed(2),
        Number(r.refundOrderCount || 0),
        revenue > 0 ? ((refunded / revenue) * 100).toFixed(1) : '',
        (net / 100).toFixed(2),
        ((r.cogsCents || 0) / 100).toFixed(2),
        (margin / 100).toFixed(2),
        revenue > 0 ? ((margin / revenue) * 100).toFixed(1) : '',
        orders > 0 ? (revenue / orders / 100).toFixed(2) : '',
        qty > 0 ? (revenue / qty / 100).toFixed(2) : ''
      ];
    })
});

function queryParams(includePage = true) {
  const q = new URLSearchParams({ dim: dim.value });
  if (range.value?.[0]) q.set('fromDate', range.value[0]);
  if (range.value?.[1]) q.set('toDate', range.value[1]);
  if (deviceId.value) q.set('deviceId', deviceId.value);
  if (sortBy.value) {
    q.set('sortBy', sortBy.value);
    if (sortDir.value) q.set('sortDir', sortDir.value);
  }
  if (includePage) {
    q.set('page', String(page.value - 1));
    q.set('size', String(size.value));
  }
  return q;
}

async function load() {
  loading.value = true;
  try {
    const data = await api.request<{
      items: SalesRow[];
      total: number;
      summary?: SalesSummary;
    }>(`/api/v2/ops/admin/sales-reports?${queryParams()}`, 'GET');
    rows.value = data.items || [];
    total.value = Number(data.total) || 0;
    summary.value = data.summary || null;
    clearSelection();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
    if (!listHydrated.value) {
      rows.value = [];
      summary.value = null;
    }
  } finally {
    listHydrated.value = true;
    loading.value = false;
  }
}

function onSizeChange() {
  page.value = 1;
  load();
}

function search() {
  page.value = 1;
  load();
}

function onPresetChange(preset: RangePreset | string) {
  const p = (preset || 'today') as RangePreset;
  if (p === 'custom') return;
  rangePreset.value = p;
  range.value = rangeForPreset(p);
  search();
}

function onRangePicked() {
  rangePreset.value = 'custom';
}

function reset() {
  dim.value = 'PRODUCT';
  deviceId.value = '';
  rangePreset.value = 'today';
  range.value = rangeForPreset('today');
  sortBy.value = '';
  sortDir.value = '';
  page.value = 1;
  load();
}

async function onExport() {
  if (selectedKeys.value.length) {
    exportSelectedCsv();
    return;
  }
  try {
    await downloadAuthFile(
      `/api/v2/ops/admin/sales-reports/export?${queryParams(false)}`,
      csvFileName(`销售报表-${dim.value}`)
    );
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '导出失败');
  }
}

onMounted(async () => {
  await loadDeviceOptions();
  await load();
});
</script>

<style scoped>
.is-refund {
  color: var(--el-color-danger);
  font-variant-numeric: tabular-nums;
}
.dim-link {
  appearance: none;
  border: 0;
  background: transparent;
  padding: 0;
  margin: 0;
  color: var(--el-color-primary);
  cursor: pointer;
  font: inherit;
  text-decoration: underline;
  text-underline-offset: 2px;
}
.dim-link:hover {
  opacity: 0.85;
}
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 12px;
}
.kpi-tile {
  background: var(--layout-card, #fff);
  border: 1px solid var(--layout-border, #ebeef5);
  border-radius: 8px;
  padding: 12px 14px;
  text-align: left;
}
.kpi-tile.warn .kpi-value {
  color: var(--el-color-danger);
}
.kpi-label {
  font-size: var(--admin-font-size-hint, 12px);
  color: var(--layout-muted, #64748b);
}
.kpi-value {
  margin-top: 4px;
  font-size: 20px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  color: var(--layout-text, #1e293b);
}
.kpi-hint {
  margin-top: 2px;
  font-size: 12px;
  color: var(--layout-muted, #64748b);
}
@media (max-width: 1100px) {
  .kpi-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
