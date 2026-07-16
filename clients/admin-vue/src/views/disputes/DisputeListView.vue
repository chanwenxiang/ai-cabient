<template>
  <el-card class="page-card">
    <template #header>
      <div style="display:flex;justify-content:space-between;align-items:center">
        <span>争议审核</span>
        <div style="display:flex;gap:8px">
          <el-button @click="onExport">导出</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load()">刷新</el-button>
        </div>
      </div>
    </template>
    <el-form inline class="filter-bar">
      <el-form-item label="状态">
        <el-select v-model="status" style="width:120px" @change="search">
          <el-option v-for="item in dictOptions('dispute_status')" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
      </el-form-item>
      <el-form-item><el-button type="primary" @click="search">查询</el-button></el-form-item>
    </el-form>
    <div class="table-scroll">
      <div class="table-scroll-inner" style="min-width: 1180px">
        <el-table v-loading="loading" :data="items" stripe border :empty-text="emptyHint">
      <el-table-column prop="ticketId" label="工单" min-width="140" show-overflow-tooltip />
      <el-table-column prop="deviceId" label="设备" width="120" />
      <el-table-column prop="sessionId" label="会话" min-width="140" show-overflow-tooltip />
      <el-table-column prop="orderId" label="关联订单" min-width="130" show-overflow-tooltip />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">{{ dictLabel('dispute_status', row.status) }}</template>
      </el-table-column>
      <el-table-column prop="category" label="分类" width="100" />
      <el-table-column prop="priority" label="优先级" width="90" />
      <el-table-column label="已扣金额" width="110">
        <template #default="{ row }">¥{{ ((row.billedAmountCents || 0) / 100).toFixed(2) }}</template>
      </el-table-column>
      <el-table-column prop="reason" label="原因" min-width="140" show-overflow-tooltip />
      <el-table-column label="创建时间" width="170"><template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template></el-table-column>
      <el-table-column label="结案时间" width="170"><template #default="{ row }">{{ formatDateTime(row.resolvedAt) }}</template></el-table-column>
      <el-table-column label="操作" width="88" class-name="col-action" align="center">
        <template #default="{ row }">
          <TableActions
            :actions="[{ key: 'detail', label: '详情', icon: View, type: 'primary' }]"
            @action="() => openDetail(row)"
          />
        </template>
      </el-table-column>
        </el-table>
      </div>
    </div>
    <div class="page-pager">
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        @current-change="() => load(false)"
        @size-change="onSizeChange"
      />
    </div>

    <el-drawer
      v-if="detailVisible"
      v-model="detailVisible"
      title="争议工单详情"
      size="520px"
      append-to-body
      destroy-on-close
      @closed="resolveFeedback = null"
    >
      <el-alert
        v-if="resolveFeedback"
        type="success"
        title="已处理"
        :description="resolveFeedback.message"
        show-icon
        :closable="false"
        class="resolve-feedback"
      />
      <el-descriptions v-if="selected" :column="1" border>
        <el-descriptions-item label="工单">{{ selected.ticketId }}</el-descriptions-item>
        <el-descriptions-item label="会话">{{ selected.sessionId }}</el-descriptions-item>
        <el-descriptions-item label="设备">{{ selected.deviceId }}</el-descriptions-item>
        <el-descriptions-item label="原因">{{ selected.reason }}</el-descriptions-item>
        <el-descriptions-item label="已扣金额">¥{{ ((selected.billedAmountCents || 0) / 100).toFixed(2) }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag v-if="resolveFeedback || selected.status !== 'OPEN'" type="success" effect="light">
            {{ resolveFeedback ? '已处理' : dictLabel('dispute_status', selected.status) }}
          </el-tag>
          <span v-else>{{ dictLabel('dispute_status', selected.status) }}</span>
        </el-descriptions-item>
        <el-descriptions-item v-if="selected.resolvedAt" label="处理时间">
          {{ formatDateTime(selected.resolvedAt) }}
        </el-descriptions-item>
        <el-descriptions-item v-if="selected.orderId" label="关联订单">{{ selected.orderId }}</el-descriptions-item>
      </el-descriptions>

      <div v-if="selected?.suggestedItems?.length" class="items-block">
        <div class="items-title">识别建议清单</div>
        <el-table :data="selected.suggestedItems" size="small" stripe>
          <el-table-column prop="skuId" label="SKU" />
          <el-table-column prop="skuName" label="商品" />
          <el-table-column prop="quantity" label="数量" width="72" />
          <el-table-column label="单价" width="88">
            <template #default="{ row }">¥{{ ((row.unitPriceCents || 0) / 100).toFixed(2) }}</template>
          </el-table-column>
        </el-table>
      </div>

      <div v-if="selected?.status === 'OPEN'" class="drawer-actions">
        <div class="ai-suggest-block">
          <div class="items-title">DeepSeek 辅助识别</div>
          <input ref="disputeImageInput" type="file" accept="image/*" class="hidden-input" @change="onDisputeImagePick" />
          <el-button size="small" :loading="suggestingDispute" @click="triggerDisputeImage">上传关键帧获取 SKU 建议</el-button>
          <el-alert v-if="disputeSuggestHint" :title="disputeSuggestHint" type="info" show-icon :closable="false" class="suggest-alert" />
        </div>
        <el-button
          v-if="hasPriorBill"
          type="primary"
          :loading="resolving"
          @click="resolveSelected('KEEP')"
        >维持原账单</el-button>
        <el-button
          type="success"
          :loading="resolving"
          :disabled="!confirmItems.length"
          @click="resolveSelected('CONFIRM')"
        >确认扣款</el-button>
        <el-button type="danger" plain :loading="resolving" @click="resolveSelected('WAIVE')">免单并退款</el-button>
      </div>
    </el-drawer>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import { Refresh, View } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { dictLabel, dictOptions } from '@aicabinet/shared-dict';
import { api } from '@/api/client';
import TableActions from '@/components/TableActions.vue';
import { useListCsv } from '@/composables/useListCsv';
import type { DevRecognitionPreviewDto, DisputeTicketDto, OrderLineDto, PageResult } from '@aicabinet/shared-types';
import { formatDateTime } from '@aicabinet/shared-uni/format';

interface ResolveDisputeResultDto {
  order?: { orderId?: string } | null;
  resolutionType?: string;
  originalAmountCents?: number;
  finalAmountCents?: number;
  adjustmentCents?: number;
  message?: string;
}

const route = useRoute();
const loading = ref(false);
const status = ref('OPEN');
const items = ref<DisputeTicketDto[]>([]);
const page = ref(1);
const size = ref(20);
const total = ref(0);
const selected = ref<DisputeTicketDto | null>(null);
const detailVisible = ref(false);
const resolving = ref(false);
const suggestingDispute = ref(false);
const disputeSuggestHint = ref('');
const disputeImageInput = ref<HTMLInputElement | null>(null);
const resolveFeedback = ref<{ message: string } | null>(null);

const { onExport } = useListCsv({
  filePrefix: '争议',
  headers: ['工单', '设备', '会话', '关联订单', '状态', '分类', '优先级', '已扣金额', '原因', '创建时间', '结案时间'],
  toRows: () =>
    items.value.map((row) => [
      row.ticketId,
      row.deviceId,
      row.sessionId,
      row.orderId,
      dictLabel('dispute_status', row.status),
      row.category,
      row.priority,
      ((row.billedAmountCents || 0) / 100).toFixed(2),
      row.reason,
      formatDateTime(row.createdAt),
      formatDateTime(row.resolvedAt)
    ])
});

const emptyHint = computed(() =>
  status.value === 'OPEN'
    ? '当前无待审核工单，可切换「已结案」查看历史'
    : '暂无数据'
);

const hasPriorBill = computed(() => (selected.value?.billedAmountCents || 0) > 0);

const confirmItems = computed(() =>
  (selected.value?.suggestedItems || [])
    .filter((line: OrderLineDto) => line.skuId && (line.quantity || 0) > 0)
    .map((line: OrderLineDto) => ({ skuId: line.skuId, quantity: line.quantity }))
);

function openDetail(row: DisputeTicketDto) {
  resolveFeedback.value = null;
  disputeSuggestHint.value = '';
  selected.value = row;
  detailVisible.value = true;
}

function triggerDisputeImage() {
  disputeImageInput.value?.click();
}

async function onDisputeImagePick(ev: Event) {
  if (!selected.value?.deviceId) {
    ElMessage.warning('工单缺少设备 ID');
    return;
  }
  const input = ev.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  suggestingDispute.value = true;
  disputeSuggestHint.value = '';
  try {
    const token = localStorage.getItem('admin_token');
    const base = (import.meta.env.VITE_API_BASE || '').replace(/\/$/, '') || window.location.origin;
    const form = new FormData();
    form.append('deviceId', selected.value.deviceId);
    form.append('image', file);
    const res = await fetch(`${base}/api/v2/ops/dispute-suggest`, {
      method: 'POST',
      headers: token ? { Authorization: `Bearer ${token}` } : {},
      body: form
    });
    const json = await res.json().catch(() => ({}));
    if (!res.ok || json.code !== 0) {
      throw new Error(json.message || `请求失败 (${res.status})`);
    }
    const preview = json.data as DevRecognitionPreviewDto;
    disputeSuggestHint.value = preview.hint || '未返回建议';
    if (preview.items?.length && selected.value) {
      selected.value = {
        ...selected.value,
        suggestedItems: preview.items.map((i) => ({
          skuId: i.skuId,
          skuName: i.skuName,
          quantity: i.quantity,
          unitPriceCents: 0,
          lineAmountCents: 0
        }))
      };
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : 'DeepSeek 建议失败');
  } finally {
    suggestingDispute.value = false;
    input.value = '';
  }
}

function applyResolvedTicket(result: ResolveDisputeResultDto) {
  if (!selected.value) return;
  const ticketId = selected.value.ticketId;
  const patched: DisputeTicketDto = {
    ...selected.value,
    status: 'RESOLVED',
    resolvedAt: new Date().toISOString(),
    orderId: result.order?.orderId ?? selected.value.orderId,
    billedAmountCents: result.finalAmountCents ?? selected.value.billedAmountCents
  };
  selected.value = patched;
  if (status.value === 'OPEN') {
    items.value = items.value.filter((t) => t.ticketId !== ticketId);
    total.value = Math.max(0, total.value - 1);
  } else {
    items.value = items.value.map((t) => (t.ticketId === ticketId ? patched : t));
  }
  resolveFeedback.value = { message: result.message || '争议已结案' };
}

async function resolveSelected(resolutionType: 'KEEP' | 'WAIVE' | 'CONFIRM') {
  if (!selected.value || resolving.value) return;
  const action = resolutionType === 'KEEP'
    ? '维持原账单'
    : resolutionType === 'WAIVE'
      ? '免单并退回全部已扣余额'
      : '按识别建议清单确认扣款';
  await ElMessageBox.confirm(`确认${action}？该操作会写入资金与审计记录。`, '确认争议处理', {
    type: resolutionType === 'WAIVE' ? 'warning' : 'info',
    confirmButtonText: '确认处理',
    cancelButtonText: '取消'
  });
  resolving.value = true;
  try {
    const result = await api.request<ResolveDisputeResultDto>(
      `/api/v2/ops/disputes/${encodeURIComponent(selected.value.ticketId)}/resolve`,
      'POST',
      {
        resolutionType,
        items: resolutionType === 'CONFIRM' ? confirmItems.value : []
      }
    );
    applyResolvedTicket(result);
    ElMessage.success(result.message || '争议已处理');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '处理失败');
  } finally {
    resolving.value = false;
  }
}

async function load(showToast = true) {
  loading.value = true;
  try {
    const q = new URLSearchParams({
      page: String(page.value - 1),
      size: String(size.value),
      status: status.value || 'OPEN'
    });
    const data = await api.request<PageResult<DisputeTicketDto>>(`/api/v2/ops/disputes?${q}`, 'GET');
    items.value = data.items || [];
    total.value = data.total || 0;
    if (showToast) ElMessage.success('已刷新');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

function onSizeChange() {
  page.value = 1;
  load(false);
}

function search() {
  page.value = 1;
  load();
}

onActivated(async () => {
  detailVisible.value = false;
  selected.value = null;
  resolveFeedback.value = null;
  if (typeof route.query.status === 'string' && route.query.status && route.query.status !== status.value) {
    status.value = route.query.status;
    page.value = 1;
    await load(false);
  }
});
onDeactivated(() => {
  detailVisible.value = false;
  selected.value = null;
});
onMounted(async () => {
  if (typeof route.query.status === 'string' && route.query.status) {
    status.value = route.query.status;
  }
  await load(false);
  const ticketId = route.query.ticketId;
  if (typeof ticketId === 'string' && ticketId) {
    const row = items.value.find((t) => t.ticketId === ticketId);
    if (row) openDetail(row);
  }
});
</script>

<style scoped>
.resolve-feedback { margin-bottom: 16px; }
.drawer-actions { display: flex; flex-wrap: wrap; gap: 12px; margin-top: 24px; }
.items-block { margin-top: 20px; }
.items-title { font-weight: 600; margin-bottom: 8px; }
.ai-suggest-block { width: 100%; margin-bottom: 12px; }
.suggest-alert { margin-top: 8px; }
.hidden-input { display: none; }
</style>
