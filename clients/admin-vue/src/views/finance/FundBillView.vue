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
          <el-button v-hasPermi="['ops:fund:export']" @click="exportCsv">{{ exportLabel }}</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
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

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="load">
      <el-form-item label="账期">
        <el-date-picker
          v-model="range"
          type="daterange"
          value-format="YYYY-MM-DD"
          start-placeholder="开始"
          end-placeholder="结束"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="load">查询</el-button>
      </el-form-item>
    </el-form>

    <el-tabs v-model="tab">
      <el-tab-pane label="日资金账单" name="bills">
        <el-table
          v-loading="loading"
          :data="bills"
          stripe
          border
          class="report-table"
          row-key="rowKey"
          @selection-change="onBillSelectionChange"
        >
          <template #empty><el-empty description="暂无账单" /></template>
          <el-table-column type="selection" width="48" align="center" />
          <el-table-column prop="bizDate" label="账期" width="120" />
          <el-table-column label="商户" min-width="180">
            <template #default="{ row }">
              <div>{{ row.merchantName || row.merchantId }}</div>
              <small>{{ row.merchantId }}</small>
            </template>
          </el-table-column>
          <el-table-column label="订单实付" width="110" align="right">
            <template #default="{ row }">¥{{ yuan(row.orderPaidCents) }}</template>
          </el-table-column>
          <el-table-column label="平台抽成" width="100" align="right">
            <template #default="{ row }">¥{{ yuan(row.platformFeeCents) }}</template>
          </el-table-column>
          <el-table-column label="通道费(估)" width="100" align="right">
            <template #default="{ row }">¥{{ yuan(row.channelFeeCents) }}</template>
          </el-table-column>
          <el-table-column label="已入账" width="100" align="right">
            <template #default="{ row }">¥{{ yuan(row.creditedCents) }}</template>
          </el-table-column>
          <el-table-column label="待入账" width="100" align="right">
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
      </el-tab-pane>

      <el-tab-pane label="账务明细" name="ledger">
        <el-form inline class="filter-bar filter-bar--compact">
          <el-form-item label="财务类型">
            <el-select v-model="financialType" clearable placeholder="全部" style="width: 160px" @change="loadLedger">
              <el-option
                v-for="item in dictOptions('fund_ledger_type')"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="收支">
            <el-select v-model="direction" clearable placeholder="全部" style="width: 120px" @change="loadLedger">
              <el-option
                v-for="item in dictOptions('fund_direction')"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </el-form-item>
        </el-form>
        <el-table
          v-loading="ledgerLoading"
          :data="ledger"
          stripe
          border
          class="report-table"
          row-key="entryId"
          @selection-change="onLedgerSelectionChange"
        >
          <template #empty><el-empty description="暂无流水" /></template>
          <el-table-column type="selection" width="48" align="center" />
          <el-table-column label="财务类型" width="140">
            <template #default="{ row }">{{ dictLabel('fund_ledger_type', row.financialType) }}</template>
          </el-table-column>
          <el-table-column label="收支" width="80" align="center">
            <template #default="{ row }">
              <el-tag :type="row.direction === 'IN' ? 'success' : 'danger'" size="small">
                {{ dictLabel('fund_direction', row.direction) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="金额" width="110" align="right">
            <template #default="{ row }">¥{{ yuan(row.amountCents) }}</template>
          </el-table-column>
          <el-table-column prop="orderId" label="订单" min-width="160" show-overflow-tooltip />
          <el-table-column prop="deviceId" label="货柜" width="120" />
          <el-table-column prop="merchantName" label="商户" min-width="140" show-overflow-tooltip />
          <el-table-column label="时间" width="170">
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
        </el-table>
        <div class="pager">
          <el-pagination
            v-model:current-page="ledgerPage"
            :page-size="20"
            layout="total, prev, pager, next"
            :total="ledgerTotal"
            @current-change="loadLedger"
          />
        </div>
      </el-tab-pane>
    </el-tabs>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { dictLabel, dictOptions } from '@aicabinet/shared-dict';
import { api, downloadAuthFile } from '@/api/client';
import { useListCsv } from '@/composables/useListCsv';
import { useTableSelection } from '@/composables/useTableSelection';

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

const tab = ref('bills');
const loading = ref(false);
const ledgerLoading = ref(false);
const bills = ref<BillRow[]>([]);
const ledger = ref<LedgerRow[]>([]);
const ledgerTotal = ref(0);
const ledgerPage = ref(1);
const financialType = ref('');
const direction = ref('');
const range = ref<[string, string] | null>(null);

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
  selectedKeys: ledgerSelectedKeys,
  clearSelection: clearLedgerSelection
} = useTableSelection<LedgerRow>((r) => r.entryId);

const exportLabel = computed(() =>
  tab.value === 'ledger' ? ledgerExportLabel.value.replace('导出', '导出明细') : billsExportLabel.value.replace('导出', '导出日账单')
);

const { onExport: exportBillsCsv } = useListCsv({
  filePrefix: '资金日账单',
  headers: ['账期', '商户编号', '商户名称', '订单实付', '平台抽成', '通道费', '已入账', '待入账', '笔数', '固化'],
  toRows: () =>
    pickBills(bills.value).map((row) => [
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
    pickLedger(ledger.value).map((row) => [
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
  if (!v) return '-';
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

async function load() {
  loading.value = true;
  try {
    const q = queryDates();
    const rows = await api.request<BillRow[]>(`/api/v2/ops/admin/fund/daily-bills?${q}`, 'GET');
    bills.value = (rows || []).map((r) => ({
      ...r,
      rowKey: `${r.bizDate}|${r.merchantId}`
    }));
    clearBillSelection();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
  if (tab.value === 'ledger') await loadLedger();
}

async function loadLedger() {
  ledgerLoading.value = true;
  try {
    const q = queryDates();
    if (financialType.value) q.set('financialType', financialType.value);
    if (direction.value) q.set('direction', direction.value);
    q.set('page', String(Math.max(0, ledgerPage.value - 1)));
    q.set('size', '20');
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
  const q = queryDates();
  try {
    await downloadAuthFile(`/api/v2/ops/admin/fund/daily-bills/export?${q}`, 'fund-daily-bills.csv');
    ElMessage.success('已导出日账单');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '导出失败');
  }
}

watch(tab, (v) => {
  if (v === 'ledger') loadLedger();
});

onMounted(load);
</script>

<style scoped>
.t1-alert { margin-bottom: 12px; }
.pager { margin-top: 12px; display: flex; justify-content: flex-end; }
</style>
