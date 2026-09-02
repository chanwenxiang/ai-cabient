<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">资金账单</span>
            <span class="hint">日汇总拆费项 + 账务明细；T+1 入账节奏</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button v-hasPermi="['ops:fund:export']" @click="exportCsv">{{
            exportLabel
          }}</el-button>
          <el-button
            :icon="Refresh"
            :loading="tab === 'ledger' ? ledgerLoading : loading"
            @click="reloadCurrent"
            >刷新</el-button
          >
        </div>
      </div>
    </template>

    <el-alert
      type="info"
      closable
      show-icon
      class="t1-alert"
      title="T+1：当日流水通常次日入账；通道费约 0.6% 估算，平台抽成取自分账记账"
    />

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="onSearch">
      <el-form-item label="账期">
        <el-date-picker
          v-model="range"
          type="daterange"
          value-format="YYYY-MM-DD"
          start-placeholder="开始"
          end-placeholder="结束"
        />
        <span class="range-hint">支持跨月，单次不超过 90 天</span>
      </el-form-item>
      <el-form-item label="关键词">
        <el-input
          v-model="keyword"
          clearable
          data-testid="fund-keyword"
          :placeholder="tab === 'ledger' ? '分录号 / 订单 / 货柜 / 商户' : '商户编号 / 名称'"
          style="width: 200px"
          @keyup.enter="onSearch"
          @clear="onSearch"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" data-testid="fund-search" @click="onSearch">查询</el-button>
      </el-form-item>
    </el-form>

    <el-alert
      v-if="searchResultHint"
      type="info"
      :closable="false"
      show-icon
      class="search-result-hint"
      :title="searchResultHint"
    />

    <el-tabs v-model="tab">
      <el-tab-pane label="日资金账单" name="bills">
        <div class="table-scroll">
          <div class="table-scroll-inner">
            <el-table
              v-loading="loading"
              :data="displayBills"
              :default-sort="billIdDefaultSort"
              @sort-change="onBillIdSortChange"
              stripe
              border
              class="report-table"
              row-key="rowKey"
              @selection-change="onBillSelectionChange"
              empty-text=" "
            >
              <template #empty
                ><el-empty
                  v-if="listHydrated && !loading"
                  :description="billEmptyDescription"
              /></template>
              <el-table-column type="selection" width="48" align="center" />
              <el-table-column prop="bizDate" label="账期" width="120" align="center" />
              <el-table-column
                prop="merchantId"
                label="商户编号"
                min-width="120"
                align="center"
                class-name="col-text"
                show-overflow-tooltip
                sortable="custom"
              >
                <template #default="{ row }">
                  <span class="cell-id">{{ row.merchantId }}</span>
                </template>
              </el-table-column>
              <el-table-column
                label="商户"
                min-width="120"
                align="center"
                class-name="col-text"
                show-overflow-tooltip
              >
                <template #default="{ row }">{{ row.merchantName || '无' }}</template>
              </el-table-column>
              <el-table-column label="订单实付" width="110" align="center">
                <template #default="{ row }">¥{{ yuan(row.orderPaidCents) }}</template>
              </el-table-column>
              <el-table-column label="平台抽成" width="100" align="center">
                <template #default="{ row }">¥{{ yuan(row.platformFeeCents) }}</template>
              </el-table-column>
              <el-table-column label="通道费(估)" width="100" align="center">
                <template #default="{ row }">¥{{ yuan(row.channelFeeCents) }}</template>
              </el-table-column>
              <el-table-column label="已入账" width="100" align="center">
                <template #default="{ row }">¥{{ yuan(row.creditedCents) }}</template>
              </el-table-column>
              <el-table-column label="待入账" width="100" align="center">
                <template #default="{ row }">¥{{ yuan(row.pendingCents) }}</template>
              </el-table-column>
              <el-table-column prop="orderCount" label="笔数" width="80" align="center" />
              <el-table-column label="固化" width="80" align="center">
                <template #default="{ row }">
                  <el-tag :type="row.solidified ? 'success' : 'info'" size="small">
                    {{ row.solidified ? '已固化' : '实时' }}
                  </el-tag>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </div>
        <PagePager
          :hydrated="listHydrated"
          v-model:current-page="billPage"
          v-model:page-size="billSize"
          :total="billTotal"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          background
          @current-change="loadBills"
          @size-change="onBillSizeChange"
        />
      </el-tab-pane>

      <el-tab-pane label="账务明细" name="ledger">
        <el-form inline class="filter-bar filter-bar--compact">
          <el-form-item label="财务类型">
            <el-select
              v-model="financialType"
              clearable
              placeholder="全部"
              style="width: 160px"
              @change="onLedgerFilterChange"
            >
              <el-option
                v-for="item in dictOptions('fund_ledger_type')"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="收支">
            <el-select
              v-model="direction"
              clearable
              placeholder="全部"
              style="width: 120px"
              @change="onLedgerFilterChange"
            >
              <el-option
                v-for="item in dictOptions('fund_direction')"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </el-form-item>
        </el-form>
        <div class="table-scroll">
          <div class="table-scroll-inner">
            <el-table
              v-loading="ledgerLoading"
              :data="displayLedger"
              :default-sort="ledgerIdDefaultSort"
              @sort-change="onLedgerIdSortChange"
              stripe
              border
              class="report-table"
              row-key="entryId"
              @selection-change="onLedgerSelectionChange"
              empty-text=" "
            >
              <template #empty
                ><el-empty
                  v-if="ledgerHydrated && !ledgerLoading"
                  :description="ledgerEmptyDescription"
              /></template>
              <el-table-column type="selection" width="48" align="center" />
              <el-table-column
                prop="entryId"
                label="分录号"
                width="100"
                align="center"
                class-name="col-text"
                sortable="custom"
              >
                <template #default="{ row }">
                  <span class="cell-id">{{ displayBizNo(row.entryId) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="财务类型" width="140" align="center">
                <template #default="{ row }">{{
                  dictLabel('fund_ledger_type', row.financialType)
                }}</template>
              </el-table-column>
              <el-table-column label="收支" width="80" align="center">
                <template #default="{ row }">
                  <el-tag :type="row.direction === 'IN' ? 'success' : 'danger'" size="small">
                    {{ dictLabel('fund_direction', row.direction) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="金额" width="110" align="center">
                <template #default="{ row }">¥{{ yuan(row.amountCents) }}</template>
              </el-table-column>
              <el-table-column
                prop="orderId"
                label="订单"
                min-width="160"
                show-overflow-tooltip
                align="center"
              />
              <el-table-column prop="deviceId" label="货柜" width="120" align="center" />
              <el-table-column
                prop="merchantName"
                label="商户"
                min-width="140"
                show-overflow-tooltip
                align="center"
              />
              <el-table-column label="时间" width="170" align="center">
                <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
              </el-table-column>
            </el-table>
          </div>
        </div>
        <PagePager
          :hydrated="ledgerHydrated"
          v-model:current-page="ledgerPage"
          v-model:page-size="ledgerSize"
          :total="ledgerTotal"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          background
          @current-change="loadLedger"
          @size-change="onLedgerSizeChange"
        />
      </el-tab-pane>
    </el-tabs>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import PagePager from '@/components/PagePager.vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { dictLabel, dictOptions } from '@aicabinet/shared-dict';
import { displayBizNo } from '@aicabinet/shared-uni/format';
import { api, downloadAuthFile } from '@/api/client';
import { useListCsv } from '@/composables/useListCsv';
import { useTableSelection } from '@/composables/useTableSelection';
import { useIdColumnSort } from '@/composables/useIdColumnSort';
import { csvFileName } from '@/utils/csv';

interface BillRow {
  bizDate: string;
  merchantId: string;
  merchantName?: string;
  orderPaidCents: number;
  platformFeeCents: number;
  channelFeeCents: number;
  creditedCents: number;
  pendingCents: number;
  orderCount: number;
  solidified: boolean;
  rowKey?: string;
}

interface LedgerRow {
  entryId: string;
  financialType: string;
  direction: string;
  amountCents: number;
  merchantName?: string;
  deviceId?: string;
  orderId?: string;
  createdAt?: string;
}

const MAX_RANGE_DAYS = 90;

const tab = ref('bills');
const loading = ref(false);
const listHydrated = ref(false);
const ledgerLoading = ref(false);
const ledgerHydrated = ref(false);
const bills = ref<BillRow[]>([]);
const billTotal = ref(0);
const ledger = ref<LedgerRow[]>([]);
const keyword = ref('');
/** 点击「查询」后生效的关键词，用于结果条数/空态提示（IMP-028） */
const appliedKeyword = ref('');
const billPage = ref(1);
const billSize = ref(20);

const {
  defaultSort: billIdDefaultSort,
  onSortChange: onBillIdSortChange,
  sortById: sortBillsById
} = useIdColumnSort<BillRow>('merchantId');
const {
  defaultSort: ledgerIdDefaultSort,
  onSortChange: onLedgerIdSortChange,
  sortById: sortLedgerById
} = useIdColumnSort<LedgerRow>('entryId');
const displayBills = computed(() => sortBillsById(bills.value));
const displayLedger = computed(() => sortLedgerById(ledger.value));

const ledgerTotal = ref(0);
const ledgerPage = ref(1);
const ledgerSize = ref(20);
const financialType = ref('');
const direction = ref('');
const range = ref<[string, string] | null>(null);

function assertRangeOk(): boolean {
  if (!range.value?.[0] || !range.value?.[1]) return true;
  const from = new Date(range.value[0] + 'T00:00:00');
  const to = new Date(range.value[1] + 'T00:00:00');
  const days = Math.floor((to.getTime() - from.getTime()) / 86400000) + 1;
  if (days > MAX_RANGE_DAYS) {
    ElMessage.warning(`账期跨度不能超过 ${MAX_RANGE_DAYS} 天（支持跨月）`);
    return false;
  }
  return true;
}

const {
  onSelectionChange: onBillSelectionChange,
  pickSelected: pickBills,
  exportButtonLabel: billsExportLabel,
  selectedKeys: billSelectedKeys,
  clearSelection: clearBillSelection
} = useTableSelection<BillRow>((r) => r.rowKey || `${r.bizDate}|${r.merchantId}`);

const {
  onSelectionChange: onLedgerSelectionChange,
  pickSelected: pickLedger,
  exportButtonLabel: ledgerExportLabel,
  clearSelection: clearLedgerSelection
} = useTableSelection<LedgerRow>((r) => r.entryId);

const exportLabel = computed(() =>
  tab.value === 'ledger'
    ? ledgerExportLabel.value.replace('导出', '导出明细')
    : billsExportLabel.value.replace('导出', '导出日账单')
);

const activeResultTotal = computed(() =>
  tab.value === 'ledger' ? ledgerTotal.value : billTotal.value
);

const searchResultHint = computed(() => {
  if (!appliedKeyword.value) return '';
  const tabLabel = tab.value === 'ledger' ? '账务明细' : '日资金账单';
  return `关键词「${appliedKeyword.value}」· ${tabLabel}共 ${activeResultTotal.value} 条`;
});

const billEmptyDescription = computed(() =>
  appliedKeyword.value
    ? `未找到匹配「${appliedKeyword.value}」的日账单`
    : '暂无账单'
);

const ledgerEmptyDescription = computed(() =>
  appliedKeyword.value
    ? `未找到匹配「${appliedKeyword.value}」的账务明细`
    : '暂无流水'
);

const { onExport: exportBillsCsv } = useListCsv({
  filePrefix: '资金日账单',
  headers: [
    '账期',
    '商户编号',
    '商户名称',
    '订单实付',
    '平台抽成',
    '通道费',
    '已入账',
    '待入账',
    '笔数',
    '固化'
  ],
  toRows: () =>
    pickBills(displayBills.value).map((row) => [
      row.bizDate,
      row.merchantId,
      row.merchantName,
      yuan(row.orderPaidCents),
      yuan(row.platformFeeCents),
      yuan(row.channelFeeCents),
      yuan(row.creditedCents),
      yuan(row.pendingCents),
      row.orderCount,
      row.solidified ? '已固化' : '实时'
    ])
});

const { onExport: exportLedgerCsv } = useListCsv({
  filePrefix: '资金账务明细',
  headers: ['财务类型', '收支', '金额', '订单', '货柜', '商户', '时间'],
  toRows: () =>
    pickLedger(displayLedger.value).map((row) => [
      dictLabel('fund_ledger_type', row.financialType),
      dictLabel('fund_direction', row.direction),
      yuan(row.amountCents),
      row.orderId,
      row.deviceId,
      row.merchantName,
      formatTime(row.createdAt)
    ])
});

function yuan(cents: number) {
  return ((cents || 0) / 100).toFixed(2);
}

function formatTime(v?: string) {
  if (!v) return '无';
  return String(v).replace('T', ' ').slice(0, 19);
}

function queryDates() {
  const fromDate = range.value?.[0];
  const toDate = range.value?.[1];
  const q = new URLSearchParams();
  if (fromDate) q.set('fromDate', fromDate);
  if (toDate) q.set('toDate', toDate);
  return q;
}

function onSearch() {
  billPage.value = 1;
  ledgerPage.value = 1;
  appliedKeyword.value = keyword.value.trim();
  if (!assertRangeOk()) return;
  reloadCurrent();
}

function onLedgerFilterChange() {
  ledgerPage.value = 1;
  loadLedger();
}

function onLedgerSizeChange() {
  ledgerPage.value = 1;
  loadLedger();
}

function onBillSizeChange() {
  billPage.value = 1;
  loadBills();
}

function reloadCurrent() {
  if (tab.value === 'ledger') loadLedger();
  else loadBills();
}

async function loadBills() {
  if (!assertRangeOk()) return;
  loading.value = true;
  try {
    const q = queryDates();
    q.set('page', String(Math.max(0, billPage.value - 1)));
    q.set('size', String(billSize.value));
    if (keyword.value.trim()) q.set('keyword', keyword.value.trim());
    const data = await api.request<{ items: BillRow[]; total: number }>(
      `/api/v2/ops/admin/fund/daily-bills?${q}`,
      'GET'
    );
    bills.value = (data.items || []).map((r) => ({
      ...r,
      rowKey: `${r.bizDate}|${r.merchantId}`
    }));
    billTotal.value = Number(data.total) || 0;
    clearBillSelection();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    listHydrated.value = true;
    loading.value = false;
  }
}

async function loadLedger() {
  if (!assertRangeOk()) return;
  ledgerLoading.value = true;
  try {
    const q = queryDates();
    if (financialType.value) q.set('financialType', financialType.value);
    if (direction.value) q.set('direction', direction.value);
    if (keyword.value.trim()) q.set('keyword', keyword.value.trim());
    q.set('page', String(Math.max(0, ledgerPage.value - 1)));
    q.set('size', String(ledgerSize.value));
    const data = await api.request<{ items: LedgerRow[]; total: number }>(
      `/api/v2/ops/admin/fund/ledger?${q}`,
      'GET'
    );
    ledger.value = data.items || [];
    ledgerTotal.value = data.total || 0;
    clearLedgerSelection();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '账务明细加载失败');
  } finally {
    ledgerHydrated.value = true;
    ledgerLoading.value = false;
  }
}

async function exportCsv() {
  if (tab.value === 'ledger') {
    exportLedgerCsv();
    return;
  }
  if (billSelectedKeys.value.length) {
    exportBillsCsv();
    return;
  }
  if (!assertRangeOk()) return;
  const q = queryDates();
  const from = range.value?.[0];
  const to = range.value?.[1];
  const prefix =
    from && to ? `资金日账单_${from.replaceAll('-', '')}-${to.replaceAll('-', '')}` : '资金日账单';
  try {
    await downloadAuthFile(`/api/v2/ops/admin/fund/daily-bills/export?${q}`, csvFileName(prefix));
    ElMessage.success('已导出日账单');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '导出失败');
  }
}

watch(keyword, () => {
  billPage.value = 1;
  ledgerPage.value = 1;
});

watch(tab, (v) => {
  if (v === 'ledger') loadLedger();
  else loadBills();
});

onMounted(loadBills);
</script>

<style scoped>
.t1-alert {
  margin-bottom: 12px;
}
.range-hint {
  margin-left: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.search-result-hint {
  margin-bottom: 12px;
}
</style>
