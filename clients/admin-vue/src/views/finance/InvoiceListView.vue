<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { CircleCheck, CircleClose, Refresh } from '@element-plus/icons-vue';
import { api } from '@/api/client';
import { useAuthStore } from '@/stores/auth';
import { useAdminListTable } from '@/composables/useAdminListTable';
import PagePager from '@/components/PagePager.vue';
import TableActions, { type TableAction } from '@/components/TableActions.vue';
import { displayBizNo, formatDateTime } from '@aicabinet/shared-uni/format';

interface InvoiceRow {
  invoiceId: number;
  orderId: string;
  userId: number;
  title: string;
  taxNo?: string;
  email?: string;
  amountCents: number;
  status: string;
  rejectReason?: string;
  createdAt?: string;
  issuedAt?: string;
}

const auth = useAuthStore();
const loading = ref(false);
const hydrated = ref(false);
const rows = ref<InvoiceRow[]>([]);
const page = ref(1);
const size = ref(20);
const total = ref(0);
const statusTab = ref(localStorage.getItem('ops_invoice_status_tab') ?? '');
const batchLoading = ref<'issue' | 'reject' | ''>('');

const {
  tableRef,
  keyword,
  hasSelection,
  onSelectionChange,
  pickSelected,
  clearSelection,
  filterByKeyword,
  resetKeyword
} = useAdminListTable<InvoiceRow>((r) => r.invoiceId);

const canEdit = computed(() => auth.hasPerm('ops:invoice:edit'));

const displayRows = computed(() =>
  filterByKeyword(rows.value, (row, kw) => {
    return (
      String(row.invoiceId).includes(kw) ||
      String(row.orderId || '')
        .toLowerCase()
        .includes(kw) ||
      displayBizNo(row.orderId).toLowerCase().includes(kw) ||
      String(row.userId ?? '').includes(kw) ||
      String(row.title || '')
        .toLowerCase()
        .includes(kw) ||
      String(row.taxNo || '')
        .toLowerCase()
        .includes(kw) ||
      String(row.email || '')
        .toLowerCase()
        .includes(kw)
    );
  })
);

const statusOptions = [
  { value: '', label: '全部' },
  { value: 'PENDING', label: '待开具' },
  { value: 'ISSUED', label: '已开具' },
  { value: 'REJECTED', label: '已驳回' }
];

function yuan(cents?: number) {
  return ((cents || 0) / 100).toFixed(2);
}

function statusLabel(s?: string) {
  switch (String(s || '').toUpperCase()) {
    case 'PENDING':
      return '待开具';
    case 'ISSUED':
      return '已开具';
    case 'REJECTED':
      return '已驳回';
    default:
      return s ? '未知状态' : '暂无';
  }
}

function statusTag(s?: string): 'success' | 'warning' | 'info' {
  switch (String(s || '').toUpperCase()) {
    case 'PENDING':
      return 'warning';
    case 'ISSUED':
      return 'success';
    default:
      return 'info';
  }
}

function emptyHint() {
  if (!statusTab.value) return '暂无开票申请';
  const label = statusOptions.find((o) => o.value === statusTab.value)?.label || statusTab.value;
  return `当前「${label}」无数据，可切换状态查看`;
}

function rowActions(row: InvoiceRow): TableAction[] {
  if (row.status !== 'PENDING' || !canEdit.value) return [];
  return [
    { key: 'issue', label: '开具', icon: CircleCheck, type: 'success' },
    { key: 'reject', label: '驳回', icon: CircleClose, type: 'danger' }
  ];
}

/** 当前页无可审核项时隐藏操作列 */
const showActionColumn = computed(() => rows.value.some((row) => rowActions(row).length > 0));

async function onRowAction(key: string, row: InvoiceRow) {
  try {
    if (key === 'issue') {
      await ElMessageBox.confirm(
        `确认将订单 ${displayBizNo(row.orderId)} 标记为已开具？\n（仅改状态，不生成税控 PDF / 不发邮件）`,
        '开具发票（仅状态）'
      );
      await api.request(`/api/v2/ops/admin/invoices/${row.invoiceId}/issue`, 'POST');
      ElMessage.success('已标记为已开具（未对接税控）');
      await load();
      return;
    }
    if (key === 'reject') {
      const { value } = await ElMessageBox.prompt('驳回原因', '驳回开票', {
        inputPlaceholder: '不符合开票条件',
        confirmButtonText: '驳回'
      });
      await api.request(`/api/v2/ops/admin/invoices/${row.invoiceId}/reject`, 'POST', {
        reason: value || '不符合开票条件'
      });
      ElMessage.success('已驳回');
      await load();
    }
  } catch {
    /* 用户取消对话框 */
  }
}

async function batchIssue() {
  const targets = pickSelected(displayRows.value).filter((r) => r.status === 'PENDING');
  if (!targets.length) {
    ElMessage.warning('请先勾选待开具申请');
    return;
  }
  try {
    await ElMessageBox.confirm(
      `确认批量标记开具 ${targets.length} 张？\n（仅改状态，不生成税控 PDF / 不发邮件）`,
      '批量开具（仅状态）',
      {
        type: 'warning'
      }
    );
  } catch {
    return;
  }
  batchLoading.value = 'issue';
  try {
    const results = await Promise.allSettled(
      targets.map((row) => api.request(`/api/v2/ops/admin/invoices/${row.invoiceId}/issue`, 'POST'))
    );
    const ok = results.filter((r) => r.status === 'fulfilled').length;
    const fail = results.length - ok;
    if (fail === 0) ElMessage.success(`已开具 ${ok} 张`);
    else ElMessage.warning(`批量开具完成：成功 ${ok}，失败 ${fail}`);
    clearSelection();
    await load();
  } finally {
    batchLoading.value = '';
  }
}

async function batchReject() {
  const targets = pickSelected(displayRows.value).filter((r) => r.status === 'PENDING');
  if (!targets.length) {
    ElMessage.warning('请先勾选待开具申请');
    return;
  }
  let reason = '不符合开票条件';
  try {
    const { value } = await ElMessageBox.prompt(
      `确认批量驳回 ${targets.length} 张开票申请？`,
      '批量驳回',
      {
        inputPlaceholder: '驳回原因',
        confirmButtonText: '驳回',
        inputValue: reason
      }
    );
    reason = value || reason;
  } catch {
    return;
  }
  batchLoading.value = 'reject';
  try {
    const results = await Promise.allSettled(
      targets.map((row) =>
        api.request(`/api/v2/ops/admin/invoices/${row.invoiceId}/reject`, 'POST', { reason })
      )
    );
    const ok = results.filter((r) => r.status === 'fulfilled').length;
    const fail = results.length - ok;
    if (fail === 0) ElMessage.success(`已驳回 ${ok} 张`);
    else ElMessage.warning(`批量驳回完成：成功 ${ok}，失败 ${fail}`);
    clearSelection();
    await load();
  } finally {
    batchLoading.value = '';
  }
}

async function load() {
  loading.value = true;
  try {
    const q = new URLSearchParams({
      page: String(page.value - 1),
      size: String(size.value)
    });
    if (statusTab.value) q.set('status', statusTab.value);
    const data = await api.request<{ items: InvoiceRow[]; total: number }>(
      `/api/v2/ops/admin/invoices?${q}`
    );
    rows.value = data.items || [];
    total.value = Number(data.total) || 0;
    clearSelection();
  } finally {
    loading.value = false;
    hydrated.value = true;
  }
}

function search() {
  page.value = 1;
  load();
}

function resetFilters() {
  resetKeyword();
  page.value = 1;
  load();
}

function onSizeChange() {
  page.value = 1;
  load();
}

function onStatusChange() {
  localStorage.setItem('ops_invoice_status_tab', statusTab.value);
  page.value = 1;
  load();
}

onMounted(load);
</script>

<template>
  <el-card class="page-card" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">开票申请</span>
            <span class="hint"
              >仅状态流转：开具=PENDING→ISSUED，不生成税控 PDF、不发邮件；商户税号在商户端维护</span
            >
          </div>
        </div>
        <div class="page-card-head__actions">
          <template v-if="canEdit">
            <el-button
              type="success"
              plain
              :disabled="!hasSelection"
              :loading="batchLoading === 'issue'"
              @click="batchIssue"
              >批量开具</el-button
            >
            <el-button
              type="danger"
              plain
              :disabled="!hasSelection"
              :loading="batchLoading === 'reject'"
              @click="batchReject"
              >批量驳回</el-button
            >
          </template>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-alert
      type="warning"
      :closable="false"
      show-icon
      class="mb"
      title="仅状态：点「开具」只把申请标为已开具，不会生成税控发票、PDF 或发送邮件"
    />

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="search">
      <el-form-item label="状态">
        <el-select v-model="statusTab" style="width: 140px" @change="onStatusChange">
          <el-option
            v-for="o in statusOptions"
            :key="o.value || 'all'"
            :label="o.label"
            :value="o.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="关键词">
        <el-input
          v-model="keyword"
          clearable
          placeholder="申请号 / 订单 / 抬头"
          style="width: 200px"
          @keyup.enter="search"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="resetFilters">重置</el-button>
      </el-form-item>
    </el-form>

    <div class="table-scroll">
      <div class="table-scroll-inner">
        <el-table
          ref="tableRef"
          :data="displayRows"
          v-loading="loading"
          stripe
          border
          row-key="invoiceId"
          empty-text=" "
          class="report-table"
          @selection-change="onSelectionChange"
        >
          <template #empty>
            <el-empty v-if="hydrated && !loading" :description="emptyHint()" />
          </template>
          <el-table-column type="selection" width="48" align="center" />
          <el-table-column label="申请号" width="100" align="center">
            <template #default="{ row }">{{ row.invoiceId }}</template>
          </el-table-column>
          <el-table-column label="订单" min-width="140">
            <template #default="{ row }">{{ displayBizNo(row.orderId) }}</template>
          </el-table-column>
          <el-table-column label="用户" width="90" align="center">
            <template #default="{ row }">{{ row.userId ?? '' }}</template>
          </el-table-column>
          <el-table-column prop="title" label="抬头" min-width="140" show-overflow-tooltip />
          <el-table-column label="税号" width="140" show-overflow-tooltip>
            <template #default="{ row }">{{ row.taxNo || '' }}</template>
          </el-table-column>
          <el-table-column label="邮箱" min-width="140" show-overflow-tooltip>
            <template #default="{ row }">{{ row.email || '' }}</template>
          </el-table-column>
          <el-table-column label="金额" width="100" align="right">
            <template #default="{ row }">¥{{ yuan(row.amountCents) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100" align="center">
            <template #default="{ row }">
              <el-tag :type="statusTag(row.status)" size="small">{{
                statusLabel(row.status)
              }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="驳回原因" min-width="120" show-overflow-tooltip>
            <template #default="{ row }">{{ row.rejectReason || '' }}</template>
          </el-table-column>
          <el-table-column label="申请时间" width="150">
            <template #default="{ row }">{{ formatDateTime(row.createdAt) || '' }}</template>
          </el-table-column>
          <el-table-column label="开票时间" width="150">
            <template #default="{ row }">{{
              row.issuedAt ? formatDateTime(row.issuedAt) : ''
            }}</template>
          </el-table-column>
          <el-table-column
            v-if="showActionColumn"
            label="操作"
            width="140"
            fixed="right"
            align="center"
            class-name="col-action"
          >
            <template #default="{ row }">
              <TableActions :actions="rowActions(row)" @action="(k) => onRowAction(k, row)" />
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <PagePager
      :hydrated="hydrated"
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

<style scoped>
.mb {
  margin-bottom: 12px;
}
</style>
