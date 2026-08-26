<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { CircleCheck, CircleClose, Refresh } from '@element-plus/icons-vue';
import { api } from '@/api/client';
import { useAuthStore } from '@/stores/auth';
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
const canEdit = computed(() => auth.hasPerm('ops:invoice:edit'));

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

async function onRowAction(key: string, row: InvoiceRow) {
  try {
    if (key === 'issue') {
      await ElMessageBox.confirm(`确认开具发票？订单 ${displayBizNo(row.orderId)}`, '开具发票');
      await api.request(`/api/v2/ops/admin/invoices/${row.invoiceId}/issue`, 'POST');
      ElMessage.success('已开具');
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
  } finally {
    loading.value = false;
    hydrated.value = true;
  }
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
            <span class="hint">消费者订单开票 · 运营开具或驳回 · 商户税号在商户端维护</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-form inline class="filter-bar filter-bar--compact">
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
    </el-form>

    <el-table :data="rows" v-loading="loading" stripe border empty-text=" ">
      <template #empty>
        <el-empty v-if="hydrated && !loading" :description="emptyHint()" />
      </template>
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
          <el-tag :type="statusTag(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
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
      <el-table-column label="操作" width="140" fixed="right" align="center">
        <template #default="{ row }">
          <TableActions :actions="rowActions(row)" @action="(k) => onRowAction(k, row)" />
        </template>
      </el-table-column>
    </el-table>

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
