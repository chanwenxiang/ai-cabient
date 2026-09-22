<script setup lang="ts">
import { computed, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { CircleCheck, CircleClose } from '@element-plus/icons-vue';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import { useAuthStore } from '@/stores/auth';
import CrudTable, { type CrudRowAction } from '@/components/CrudTable.vue';
import { useCrudTable } from '@/composables/useCrudTable';
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
const keyword = ref('');
const statusTab = ref(localStorage.getItem('ops_invoice_status_tab') ?? '');
const batchLoading = ref<'issue' | 'reject' | ''>('');

const canEdit = computed(() => auth.hasPerm('ops:invoice:edit'));

/** 关键词为纯前端过滤（后端无该参数）：原 displayRows 计算属性前移到取数处，total 仍取服务端值 */
function filterByKeyword(rows: InvoiceRow[]): InvoiceRow[] {
  const kw = keyword.value.trim().toLowerCase();
  if (!kw) return rows;
  return rows.filter(
    (row) =>
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
}

// 列表状态机统一交给 CrudTable：分页 / 多选 / 竞态 / 空态 全部内建（本表无排序、无 CSV 导出）
const crud = useCrudTable<InvoiceRow>({
  rowKey: (r) => r.invoiceId,
  fetchPage: async (params) => {
    const q = new URLSearchParams({
      page: String(params.page), // 0 起（useCrudTable 已换算）
      size: String(params.size)
    });
    if (statusTab.value) q.set('status', statusTab.value);
    const data = await api.request<{ items: InvoiceRow[]; total: number }>(
      AdminEndpoints.invoicesList(q)
    );
    return { items: filterByKeyword(data.items || []), total: Number(data.total) || 0 };
  }
});

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

function rowActions(row: InvoiceRow): CrudRowAction[] {
  if (row.status !== 'PENDING') return [];
  return [
    { key: 'issue', label: '开具', icon: CircleCheck, type: 'success', perm: 'ops:invoice:edit' },
    { key: 'reject', label: '驳回', icon: CircleClose, type: 'danger', perm: 'ops:invoice:edit' }
  ];
}

/** 过滤后无可审核项（含无 ops:invoice:edit 权限）时隐藏操作列 */
const showActionColumn = computed(
  () => canEdit.value && crud.displayItems.some((row) => rowActions(row).length > 0)
);

async function onRowAction(key: string, row: InvoiceRow) {
  try {
    if (key === 'issue') {
      await ElMessageBox.confirm(
        `确认将订单 ${displayBizNo(row.orderId)} 标记为已开具？\n（仅改状态，不生成税控 PDF / 不发邮件）`,
        '开具发票（仅状态）'
      );
      await api.request(AdminEndpoints.invoiceIssue(row.invoiceId), 'POST');
      ElMessage.success('已标记为已开具（未对接税控）');
      await crud.load();
      return;
    }
    if (key === 'reject') {
      const { value } = await ElMessageBox.prompt('驳回原因', '驳回开票', {
        inputPlaceholder: '不符合开票条件',
        confirmButtonText: '驳回'
      });
      await api.request(AdminEndpoints.invoiceReject(row.invoiceId), 'POST', {
        reason: value || '不符合开票条件'
      });
      ElMessage.success('已驳回');
      await crud.load();
    }
  } catch {
    /* 用户取消对话框 */
  }
}

function onAction({ key, row }: { key: string; row: InvoiceRow }) {
  void onRowAction(key, row);
}

async function batchIssue() {
  const targets = crud.pickSelected(crud.displayItems).filter((r) => r.status === 'PENDING');
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
      targets.map((row) => api.request(AdminEndpoints.invoiceIssue(row.invoiceId), 'POST'))
    );
    const ok = results.filter((r) => r.status === 'fulfilled').length;
    const fail = results.length - ok;
    if (fail === 0) ElMessage.success(`已开具 ${ok} 张`);
    else ElMessage.warning(`批量开具完成：成功 ${ok}，失败 ${fail}`);
    crud.clearSelection();
    await crud.load();
  } finally {
    batchLoading.value = '';
  }
}

async function batchReject() {
  const targets = crud.pickSelected(crud.displayItems).filter((r) => r.status === 'PENDING');
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
        api.request(AdminEndpoints.invoiceReject(row.invoiceId), 'POST', { reason })
      )
    );
    const ok = results.filter((r) => r.status === 'fulfilled').length;
    const fail = results.length - ok;
    if (fail === 0) ElMessage.success(`已驳回 ${ok} 张`);
    else ElMessage.warning(`批量驳回完成：成功 ${ok}，失败 ${fail}`);
    crud.clearSelection();
    await crud.load();
  } finally {
    batchLoading.value = '';
  }
}

function search() {
  void crud.search();
}

function resetFilters() {
  keyword.value = '';
  void crud.search();
}

function onStatusChange() {
  localStorage.setItem('ops_invoice_status_tab', statusTab.value);
  void crud.search();
}
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
              :disabled="!crud.hasSelection"
              :loading="batchLoading === 'issue'"
              @click="batchIssue"
              >批量开具</el-button
            >
            <el-button
              type="danger"
              plain
              :disabled="!crud.hasSelection"
              :loading="batchLoading === 'reject'"
              @click="batchReject"
              >批量驳回</el-button
            >
          </template>
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
        <CrudTable
          :table="crud"
          row-key="invoiceId"
          selectable
          :actions="showActionColumn ? rowActions : undefined"
          :action-width="140"
          :empty-text="emptyHint()"
          @action="onAction"
        >
          <el-table-column
            label="申请号"
            width="100"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">{{ row.invoiceId }}</template>
          </el-table-column>
          <el-table-column label="订单" min-width="140">
            <template #default="{ row }">{{ displayBizNo(row.orderId) }}</template>
          </el-table-column>
          <el-table-column
            label="用户"
            width="90"
            class-name="col-text"
            label-class-name="col-text"
          >
            <template #default="{ row }">{{ row.userId ?? '' }}</template>
          </el-table-column>
          <el-table-column label="抬头" min-width="140" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-ellipsis" :title="row.title || ''">{{ row.title }}</span>
            </template>
          </el-table-column>
          <el-table-column label="税号" width="140">
            <template #default="{ row }">{{ row.taxNo || '' }}</template>
          </el-table-column>
          <el-table-column label="邮箱" min-width="140" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-ellipsis" :title="row.email || ''">{{ row.email || '' }}</span>
            </template>
          </el-table-column>
          <el-table-column
            label="金额"
            width="100"
            align="right"
            class-name="col-money"
            label-class-name="col-money"
          >
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
              <el-tag :type="statusTag(row.status)" size="small">{{
                statusLabel(row.status)
              }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="驳回原因" min-width="120" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-ellipsis" :title="row.rejectReason || ''">{{
                row.rejectReason || ''
              }}</span>
            </template>
          </el-table-column>
          <el-table-column label="申请时间" width="150">
            <template #default="{ row }">{{ formatDateTime(row.createdAt) || '' }}</template>
          </el-table-column>
          <el-table-column label="开票时间" width="150">
            <template #default="{ row }">{{
              row.issuedAt ? formatDateTime(row.issuedAt) : ''
            }}</template>
          </el-table-column>
        </CrudTable>
      </div>
    </div>
  </el-card>
</template>

<style scoped>
.mb {
  margin-bottom: 12px;
}
</style>
