<script setup lang="ts">
import { computed, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { CircleCheck, CircleClose } from '@element-plus/icons-vue';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import { useAuthStore } from '@/stores/auth';
import { useNavAccess } from '@/composables/useNavAccess';
import CrudTable, { type CrudCsvOptions, type CrudRowAction } from '@/components/CrudTable.vue';
import { useCrudTable } from '@/composables/useCrudTable';
import type { BalanceRefundRequestDto, PageResult } from '@aicabinet/shared-types';
import { displayLabel } from '@aicabinet/shared-dict';
import { displayBizNo, formatDateTime } from '@aicabinet/shared-uni/format';

const auth = useAuthStore();
const { goPath } = useNavAccess();
const statusTab = ref(localStorage.getItem('ops_balance_refund_status_tab') || 'PENDING_REVIEW');
const keyword = ref('');
const batchLoading = ref<'approve' | 'reject' | ''>('');

const canReview = computed(() => auth.hasPerm('ops:balance-refund:review'));

/** 关键词为纯前端过滤（后端无该参数）：原 displayRows 计算属性前移到取数处，total 仍取服务端值 */
function filterByKeyword(rows: BalanceRefundRequestDto[]): BalanceRefundRequestDto[] {
  const kw = keyword.value.trim().toLowerCase();
  if (!kw) return rows;
  return rows.filter(
    (row) =>
      String(row.requestId).includes(kw) ||
      String(row.requestNo || '')
        .toLowerCase()
        .includes(kw) ||
      displayBizNo(row.requestNo).toLowerCase().includes(kw) ||
      String(row.userId || '').includes(kw)
  );
}

function createdAtMs(createdAt?: string) {
  if (!createdAt) return 0;
  const t = new Date(createdAt).getTime();
  return Number.isFinite(t) ? t : 0;
}

// 列表状态机统一交给 CrudTable：分页 / 多选 / 竞态 / 空态 全部内建；
// 固定「申请时间倒序」（原 displayRows 排序前移到取数处，本表无表头排序配置）
const crud = useCrudTable<BalanceRefundRequestDto>({
  rowKey: (r) => r.requestId,
  fetchPage: async (params) => {
    const q = new URLSearchParams({
      page: String(params.page), // 0 起（useCrudTable 已换算）
      size: String(params.size)
    });
    if (statusTab.value && statusTab.value !== 'ALL') q.set('status', statusTab.value);
    const res = await api.request<PageResult<BalanceRefundRequestDto>>(
      AdminEndpoints.balanceRefundsList(q)
    );
    const list = filterByKeyword(res?.items || []);
    list.sort((a, b) => createdAtMs(b.createdAt) - createdAtMs(a.createdAt));
    return { items: list, total: Number(res?.total || 0) };
  }
});

const csvOptions: CrudCsvOptions = {
  filePrefix: '余额退款申请',
  exportPerm: 'ops:balance-refund:export',
  headers: ['申请号', '用户ID', '金额(元)', '状态', '申请原因', '审核备注', '失败原因', '申请时间'],
  toRows: (rows) =>
    rows.map((row) => [
      displayBizNo(row.requestNo),
      row.userId ?? '',
      yuan(row.amountCents),
      statusLabel(row.status),
      row.reason || '',
      row.reviewRemark || '',
      row.failReason || '',
      formatDateTime(row.createdAt)
    ])
};

function yuan(cents?: number) {
  return ((cents || 0) / 100).toFixed(2);
}

function statusLabel(s?: string) {
  return displayLabel('balance_refund_status', s, '未知状态');
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

function rowActions(row: BalanceRefundRequestDto): CrudRowAction[] {
  if (row.status !== 'PENDING_REVIEW') return [];
  return [
    {
      key: 'approve',
      label: '通过',
      icon: CircleCheck,
      type: 'success',
      perm: 'ops:balance-refund:review'
    },
    {
      key: 'reject',
      label: '驳回',
      icon: CircleClose,
      type: 'danger',
      perm: 'ops:balance-refund:review'
    }
  ];
}

/** 过滤后无可审核项（含无 ops:balance-refund:review 权限）时隐藏操作列（避免终态页整列「暂无」） */
const showActionColumn = computed(
  () => canReview.value && crud.displayItems.some((row) => rowActions(row).length > 0)
);

function onAction({ key, row }: { key: string; row: BalanceRefundRequestDto }) {
  if (key === 'approve') void review(row, true);
  if (key === 'reject') void review(row, false);
}

function onStatusTab(name: string | number) {
  statusTab.value = String(name);
  localStorage.setItem('ops_balance_refund_status_tab', statusTab.value);
  void crud.search();
}

function search() {
  void crud.search();
}

function reset() {
  keyword.value = '';
  void crud.search();
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
    await api.request(AdminEndpoints.balanceRefundReview(row.requestId), 'POST', {
      approve,
      remark: value || undefined
    });
    ElMessage.success(displayLabel('balance_refund_status', approve ? 'REFUNDED' : 'REJECTED'));
    await crud.load();
  } catch (e) {
    if (e === 'cancel' || e === 'close') return;
    ElMessage.error(e instanceof Error ? e.message : '操作失败');
  }
}

async function batchReviewAll(approve: boolean) {
  const targets = crud.pickSelected(crud.displayItems).filter((r) => r.status === 'PENDING_REVIEW');
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
        api.request(AdminEndpoints.balanceRefundReview(row.requestId), 'POST', {
          approve,
          remark: value || undefined
        })
      )
    );
    const ok = results.filter((r) => r.status === 'fulfilled').length;
    const fail = results.length - ok;
    if (fail === 0) ElMessage.success(`已${approve ? '通过' : '驳回'} ${ok} 条`);
    else ElMessage.warning(`批量${action}完成：成功 ${ok}，失败 ${fail}`);
    crud.clearSelection();
    await crud.load();
  } catch (e) {
    if (e === 'cancel' || e === 'close') return;
    ElMessage.error(e instanceof Error ? e.message : '操作失败');
  } finally {
    batchLoading.value = '';
  }
}
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
              :disabled="!crud.hasSelection"
              :loading="batchLoading === 'approve'"
              @click="batchReviewAll(true)"
              >批量通过</el-button
            >
            <el-button
              type="danger"
              plain
              :disabled="!crud.hasSelection"
              :loading="batchLoading === 'reject'"
              @click="batchReviewAll(false)"
              >批量驳回</el-button
            >
          </template>
        </div>
      </div>
    </template>

    <el-tabs v-model="statusTab" class="status-tabs" @tab-change="onStatusTab">
      <el-tab-pane
        :label="displayLabel('balance_refund_status', 'PENDING_REVIEW')"
        name="PENDING_REVIEW"
      />
      <el-tab-pane :label="displayLabel('balance_refund_status', 'REFUNDED')" name="REFUNDED" />
      <el-tab-pane :label="displayLabel('balance_refund_status', 'REJECTED')" name="REJECTED" />
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
        <CrudTable
          :table="crud"
          row-key="requestId"
          selectable
          :actions="showActionColumn ? rowActions : undefined"
          :csv="csvOptions"
          empty-text="暂无申请"
          @action="onAction"
        >
          <el-table-column prop="requestNo" label="申请号" min-width="160" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-id">{{ displayBizNo(row.requestNo) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="用户" width="100" class-name="col-text">
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
          <el-table-column
            label="状态"
            width="100"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <el-tag :type="statusTagType(row.status)" size="small">
                {{ statusLabel(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="申请原因" min-width="140" class-name="col-text">
            <template #default="{ row }">{{ row.reason || '暂无' }}</template>
          </el-table-column>
          <el-table-column label="审核备注" min-width="120" class-name="col-text">
            <template #default="{ row }">{{ row.reviewRemark || '暂无' }}</template>
          </el-table-column>
          <el-table-column label="失败原因" min-width="140" class-name="col-text">
            <template #default="{ row }">{{ row.failReason || '暂无' }}</template>
          </el-table-column>
          <el-table-column
            align="center"
            label="申请时间"
            width="168"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <span class="cell-datetime">{{ formatDateTime(row.createdAt) }}</span>
            </template>
          </el-table-column>
        </CrudTable>
      </div>
    </div>
  </el-card>
</template>

<style scoped>
.muted {
  color: var(--el-text-color-placeholder);
}
</style>
