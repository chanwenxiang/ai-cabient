<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { CircleCheck, CircleClose, Refresh } from '@element-plus/icons-vue';
import { api } from '@/api/client';
import { useAuthStore } from '@/stores/auth';
import { useNavAccess } from '@/composables/useNavAccess';
import { useAdminListTable } from '@/composables/useAdminListTable';
import { useListCsv } from '@/composables/useListCsv';
import PagePager from '@/components/PagePager.vue';
import TableActions, { type TableAction } from '@/components/TableActions.vue';
import type { BalanceRefundRequestDto, PageResult } from '@aicabinet/shared-types';
import { displayBizNo, formatDateTime } from '@aicabinet/shared-uni/format';

const auth = useAuthStore();
const { goPath } = useNavAccess();
const loading = ref(false);
const listHydrated = ref(false);
const rows = ref<BalanceRefundRequestDto[]>([]);
const statusTab = ref(localStorage.getItem('ops_balance_refund_status_tab') || 'PENDING_REVIEW');
const page = ref(1);
const size = ref(20);
const total = ref(0);
const batchLoading = ref<'approve' | 'reject' | ''>('');

const {
  tableRef,
  keyword,
  hasSelection,
  onSelectionChange,
  pickSelected,
  exportButtonLabel,
  clearSelection,
  filterByKeyword,
  resetKeyword
} = useAdminListTable<BalanceRefundRequestDto>((r) => r.requestId);

const canReview = computed(() => auth.hasPerm('ops:balance-refund:review'));

const displayRows = computed(() => {
  const list = filterByKeyword([...rows.value], (row, kw) => {
    return (
      String(row.requestId).includes(kw) ||
      String(row.requestNo || '').toLowerCase().includes(kw) ||
      displayBizNo(row.requestNo).toLowerCase().includes(kw) ||
      String(row.userId || '').includes(kw)
    );
  });
  list.sort((a, b) => createdAtMs(b.createdAt) - createdAtMs(a.createdAt));
  return list;
});

const { onExport } = useListCsv({
  filePrefix: '余额退款申请',
  headers: [
    '申请号',
    '用户ID',
    '金额(元)',
    '状态',
    '申请原因',
    '审核备注',
    '失败原因',
    '申请时间'
  ],
  toRows: () =>
    pickSelected(displayRows.value).map((row) => [
      displayBizNo(row.requestNo),
      row.userId ?? '',
      yuan(row.amountCents),
      statusLabel(row.status),
      row.reason || '',
      row.reviewRemark || '',
      row.failReason || '',
      formatDateTime(row.createdAt)
    ])
});

function createdAtMs(createdAt?: string) {
  if (!createdAt) return 0;
  const t = new Date(createdAt).getTime();
  return Number.isFinite(t) ? t : 0;
}

function yuan(cents?: number) {
  return ((cents || 0) / 100).toFixed(2);
}

function statusLabel(s?: string) {
  switch (String(s || '').toUpperCase()) {
    case 'PENDING_REVIEW':
      return '待审核';
    case 'REFUNDED':
      return '已退款';
    case 'REJECTED':
      return '已驳回';
    case 'FAILED':
      return '失败';
    default:
      return '未知状态';
  }
}

function statusTagType(s?: string): 'success' | 'warning' | 'danger' | 'info' {
  switch (String(s || '').toUpperCase()) {
    case 'PENDING_REVIEW':
      return 'warning';
    case 'REFUNDED':
      return 'success';
    case 'REJECTED':
      return 'info';
    case 'FAILED':
      return 'danger';
    default:
      return 'info';
  }
}

function rowActions(row: BalanceRefundRequestDto): TableAction[] {
  if (row.status !== 'PENDING_REVIEW' || !canReview.value) return [];
  return [
    { key: 'approve', label: '通过', icon: CircleCheck, type: 'success' },
    { key: 'reject', label: '驳回', icon: CircleClose, type: 'danger' }
  ];
}

function onRowAction(key: string, row: BalanceRefundRequestDto) {
  if (key === 'approve') review(row, true);
  if (key === 'reject') review(row, false);
}

function onStatusTab(name: string | number) {
  statusTab.value = String(name);
  localStorage.setItem('ops_balance_refund_status_tab', statusTab.value);
  page.value = 1;
  load();
}

function search() {
  page.value = 1;
  load();
}

function reset() {
  resetKeyword();
  page.value = 1;
  load();
}

async function load() {
  loading.value = true;
  try {
    const q = new URLSearchParams({
      page: String(page.value - 1),
      size: String(size.value)
    });
    if (statusTab.value && statusTab.value !== 'ALL') q.set('status', statusTab.value);
    const res = await api.request<PageResult<BalanceRefundRequestDto>>(
      `/api/v2/ops/admin/balance-refunds?${q}`
    );
    rows.value = res?.items || [];
    total.value = Number(res?.total || 0);
    clearSelection();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
    rows.value = [];
    total.value = 0;
  } finally {
    loading.value = false;
    listHydrated.value = true;
  }
}

async function review(row: BalanceRefundRequestDto, approve: boolean) {
  const action = approve ? '通过并原路退款' : '驳回';
  try {
    const { value } = await ElMessageBox.prompt(
      approve
        ? `确认通过申请 ${row.requestNo}？将按充值单 FIFO 原路退回微信/支付宝 ¥${yuan(row.amountCents)}，并扣减用户余额。`
        : `确认驳回申请 ${row.requestNo}？将释放冻结金额。`,
      action,
      {
        confirmButtonText: action,
        cancelButtonText: '取消',
        inputPlaceholder: '审核备注（可选）',
        inputValue: ''
      }
    );
    await api.request(`/api/v2/ops/admin/balance-refunds/${row.requestId}/review`, 'POST', {
      approve,
      remark: value || undefined
    });
    ElMessage.success(approve ? '已退款' : '已驳回');
    await load();
  } catch (e) {
    if (e === 'cancel' || e === 'close') return;
    ElMessage.error(e instanceof Error ? e.message : '操作失败');
  }
}

async function batchReviewAll(approve: boolean) {
  const targets = pickSelected(displayRows.value).filter((r) => r.status === 'PENDING_REVIEW');
  if (!targets.length) {
    ElMessage.warning('请先勾选待审核申请');
    return;
  }
  const action = approve ? '通过并原路退款' : '驳回';
  try {
    const { value } = await ElMessageBox.prompt(
      approve
        ? `确认批量通过 ${targets.length} 条申请？将按充值单 FIFO 原路退款并扣减用户余额。`
        : `确认批量驳回 ${targets.length} 条申请？将释放冻结金额。`,
      `批量${action}`,
      {
        confirmButtonText: action,
        cancelButtonText: '取消',
        inputPlaceholder: '审核备注（可选）',
        inputValue: ''
      }
    );
    batchLoading.value = approve ? 'approve' : 'reject';
    const results = await Promise.allSettled(
      targets.map((row) =>
        api.request(`/api/v2/ops/admin/balance-refunds/${row.requestId}/review`, 'POST', {
          approve,
          remark: value || undefined
        })
      )
    );
    const ok = results.filter((r) => r.status === 'fulfilled').length;
    const fail = results.length - ok;
    if (fail === 0) ElMessage.success(`已${approve ? '通过' : '驳回'} ${ok} 条`);
    else ElMessage.warning(`批量${action}完成：成功 ${ok}，失败 ${fail}`);
    clearSelection();
    await load();
  } catch (e) {
    if (e === 'cancel' || e === 'close') return;
    ElMessage.error(e instanceof Error ? e.message : '操作失败');
  } finally {
    batchLoading.value = '';
  }
}

function onSizeChange() {
  page.value = 1;
  load();
}

onMounted(load);
</script>

<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">余额退款申请</span>
            <span class="hint">用户在充值页提交 · 审核通过后按充值单原路退微信/支付宝</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <template v-if="canReview">
            <el-button
              type="success"
              plain
              :disabled="!hasSelection"
              :loading="batchLoading === 'approve'"
              @click="batchReviewAll(true)"
              >批量通过</el-button
            >
            <el-button
              type="danger"
              plain
              :disabled="!hasSelection"
              :loading="batchLoading === 'reject'"
              @click="batchReviewAll(false)"
              >批量驳回</el-button
            >
          </template>
          <el-button @click="onExport">{{ exportButtonLabel }}</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-tabs v-model="statusTab" class="status-tabs" @tab-change="onStatusTab">
      <el-tab-pane label="待审核" name="PENDING_REVIEW" />
      <el-tab-pane label="已退款" name="REFUNDED" />
      <el-tab-pane label="已驳回" name="REJECTED" />
      <el-tab-pane label="失败" name="FAILED" />
      <el-tab-pane label="全部" name="ALL" />
    </el-tabs>

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="search">
      <el-form-item label="关键词">
        <el-input
          v-model="keyword"
          clearable
          placeholder="申请号 / 单号 / 用户ID"
          style="width: 200px"
          @keyup.enter="search"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </el-form-item>
    </el-form>

    <div class="table-scroll">
      <div class="table-scroll-inner">
        <el-table
          ref="tableRef"
          v-loading="loading"
          :data="displayRows"
          stripe
          border
          class="report-table"
          row-key="requestId"
          empty-text=" "
          @selection-change="onSelectionChange"
        >
          <template #empty>
            <el-empty v-if="listHydrated && !loading" description="暂无申请" />
          </template>
          <el-table-column type="selection" width="48" align="center" />
          <el-table-column
            prop="requestNo"
            label="申请号"
            min-width="160"
            align="center"
            class-name="col-text"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              <span class="cell-id">{{ displayBizNo(row.requestNo) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="用户" width="100" align="center" class-name="col-text">
            <template #default="{ row }">
              <button
                v-if="row.userId"
                type="button"
                class="link-cell"
                @click="goPath('/users', { keyword: String(row.userId) })"
              >
                {{ row.userId }}
              </button>
              <span v-else class="muted">无</span>
            </template>
          </el-table-column>
          <el-table-column label="金额" width="110" align="center" class-name="col-money">
            <template #default="{ row }">¥{{ yuan(row.amountCents) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100" align="center">
            <template #default="{ row }">
              <el-tag :type="statusTagType(row.status)" size="small">
                {{ statusLabel(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column
            label="申请原因"
            min-width="140"
            align="center"
            class-name="col-text"
            show-overflow-tooltip
          >
            <template #default="{ row }">{{ row.reason || '暂无' }}</template>
          </el-table-column>
          <el-table-column
            label="审核备注"
            min-width="120"
            align="center"
            class-name="col-text"
            show-overflow-tooltip
          >
            <template #default="{ row }">{{ row.reviewRemark || '暂无' }}</template>
          </el-table-column>
          <el-table-column
            label="失败原因"
            min-width="140"
            align="center"
            class-name="col-text"
            show-overflow-tooltip
          >
            <template #default="{ row }">{{ row.failReason || '暂无' }}</template>
          </el-table-column>
          <el-table-column label="申请时间" width="168" align="center" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-datetime">{{ formatDateTime(row.createdAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column
            v-if="canReview"
            label="操作"
            width="120"
            align="center"
            class-name="col-action"
            fixed="right"
          >
            <template #default="{ row }">
              <TableActions
                v-if="rowActions(row).length"
                :actions="rowActions(row)"
                @action="(key) => onRowAction(key, row)"
              />
              <span v-else class="muted">暂无</span>
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
      layout="total, sizes, prev, pager, next"
      background
      @current-change="load"
      @size-change="onSizeChange"
    />
  </el-card>
</template>

<style scoped>
.muted {
  color: var(--el-text-color-placeholder);
}
</style>
