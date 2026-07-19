<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">争议审核</span>
            <span class="hint">默认待审核；可维持账单 / 确认扣款 / 免单退款</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button v-hasPermi="['ops:dispute:export']" @click="onExport">{{ exportButtonLabel }}</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load(false)">刷新</el-button>
        </div>
      </div>
    </template>

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="search">
      <el-form-item label="状态">
        <el-select v-model="status" clearable placeholder="全部" style="width: 140px" @change="search">
          <el-option
            v-for="item in dictOptions('dispute_status')"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </el-form-item>
    </el-form>

    <div class="table-scroll">
      <div class="table-scroll-inner" style="min-width: 1180px">
        <el-table
          v-loading="loading"
          :data="items"
          stripe
          border
          class="report-table"
          :empty-text="emptyHint"
          row-key="ticketId"
          @selection-change="onSelectionChange"
        >
          <el-table-column type="selection" width="48" align="center" />
          <el-table-column label="工单" min-width="180" class-name="col-text">
            <template #default="{ row }">
              <button type="button" class="ticket-cell" @click="openDetail(row)">
                <strong>{{ row.reason || row.ticketId }}</strong>
                <small>{{ row.ticketId }}</small>
              </button>
            </template>
          </el-table-column>
          <el-table-column label="设备" min-width="110" class-name="col-text" show-overflow-tooltip>
            <template #default="{ row }">
              <button
                v-if="row.deviceId"
                type="button"
                class="link-cell"
                @click="goDevice(row.deviceId)"
              >{{ row.deviceId }}</button>
              <span v-else class="muted">-</span>
            </template>
          </el-table-column>
          <el-table-column label="会话" min-width="130" class-name="col-text" show-overflow-tooltip>
            <template #default="{ row }">
              <button
                v-if="row.sessionId"
                type="button"
                class="link-cell mono"
                @click="goSessions(row.deviceId)"
              >{{ row.sessionId }}</button>
              <span v-else class="muted">-</span>
            </template>
          </el-table-column>
          <el-table-column label="关联订单" min-width="130" class-name="col-text" show-overflow-tooltip>
            <template #default="{ row }">
              <button
                v-if="row.orderId"
                type="button"
                class="link-cell mono"
                @click="goOrders(row.deviceId)"
              >{{ row.orderId }}</button>
              <span v-else class="muted">-</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="96" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="disputeStatusType(row.status)">
                {{ dictLabel('dispute_status', row.status) || row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="分类" width="100" class-name="col-text" show-overflow-tooltip>
            <template #default="{ row }">
              {{ dictLabel('dispute_category', row.category) || row.category || '-' }}
            </template>
          </el-table-column>
          <el-table-column label="优先级" width="88" align="center">
            <template #default="{ row }">
              <el-tag
                v-if="row.priority"
                size="small"
                :type="row.priority === 'HIGH' || row.priority === 'URGENT' ? 'danger' : 'info'"
              >
                {{ dictLabel('dispute_priority', row.priority) || row.priority }}
              </el-tag>
              <span v-else class="muted">-</span>
            </template>
          </el-table-column>
          <el-table-column label="已扣金额" width="110" align="right" class-name="col-money">
            <template #default="{ row }">¥{{ money(row.billedAmountCents) }}</template>
          </el-table-column>
          <el-table-column label="创建时间" width="168" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-datetime">{{ formatDateTime(row.createdAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="结案时间" width="168" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-datetime">{{ formatDateTime(row.resolvedAt) || '-' }}</span>
            </template>
          </el-table-column>
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
        background
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
      <el-descriptions v-if="selected" :column="1" border size="small">
        <el-descriptions-item label="工单">
          <span class="cell-id">{{ selected.ticketId }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="会话">
          <button
            v-if="selected.sessionId"
            type="button"
            class="link-cell mono"
            @click="goSessions(selected.deviceId)"
          >{{ selected.sessionId }}</button>
          <span v-else>-</span>
        </el-descriptions-item>
        <el-descriptions-item label="设备">
          <button
            v-if="selected.deviceId"
            type="button"
            class="link-cell"
            @click="goDevice(selected.deviceId)"
          >{{ selected.deviceId }}</button>
          <span v-else>-</span>
        </el-descriptions-item>
        <el-descriptions-item label="原因">{{ selected.reason || '-' }}</el-descriptions-item>
        <el-descriptions-item label="已扣金额">¥{{ money(selected.billedAmountCents) }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag v-if="resolveFeedback || selected.status !== 'OPEN'" type="success" effect="light" size="small">
            {{ resolveFeedback ? '已处理' : dictLabel('dispute_status', selected.status) }}
          </el-tag>
          <el-tag v-else size="small" type="warning">
            {{ dictLabel('dispute_status', selected.status) }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item v-if="selected.resolvedAt" label="处理时间">
          {{ formatDateTime(selected.resolvedAt) }}
        </el-descriptions-item>
        <el-descriptions-item v-if="selected.orderId" label="关联订单">
          <button type="button" class="link-cell mono" @click="goOrders(selected.deviceId)">
            {{ selected.orderId }}
          </button>
        </el-descriptions-item>
      </el-descriptions>

      <div v-if="selected?.suggestedItems?.length" class="items-block">
        <div class="items-title">识别建议清单</div>
        <el-table :data="selected.suggestedItems" size="small" stripe border>
          <el-table-column prop="skuName" label="商品" min-width="120" class-name="col-text" />
          <el-table-column prop="skuId" label="SKU" min-width="100" class-name="col-text" show-overflow-tooltip />
          <el-table-column prop="quantity" label="数量" width="72" align="center" />
          <el-table-column label="单价" width="88" align="right">
            <template #default="{ row }">¥{{ money(row.unitPriceCents) }}</template>
          </el-table-column>
        </el-table>
      </div>

      <div v-if="selected?.status === 'OPEN'" class="drawer-actions">
        <div class="ai-suggest-block">
          <div class="items-title">DeepSeek 辅助识别</div>
          <input ref="disputeImageInput" type="file" accept="image/*" class="hidden-input" @change="onDisputeImagePick" />
          <el-button size="small" :loading="suggestingDispute" @click="triggerDisputeImage">
            上传关键帧获取 SKU 建议
          </el-button>
          <el-alert
            v-if="disputeSuggestHint"
            :title="disputeSuggestHint"
            type="info"
            show-icon
            :closable="false"
            class="suggest-alert"
          />
        </div>
        <el-button
          v-if="hasPriorBill"
          v-hasPermi="['ops:dispute:resolve']"
          type="primary"
          :loading="resolving"
          @click="resolveSelected('KEEP')"
        >维持原账单</el-button>
        <el-button
          v-hasPermi="['ops:dispute:resolve']"
          type="success"
          :loading="resolving"
          :disabled="!confirmItems.length"
          @click="resolveSelected('CONFIRM')"
        >确认扣款</el-button>
        <el-button
          v-hasPermi="['ops:dispute:resolve']"
          type="danger"
          plain
          :loading="resolving"
          @click="resolveSelected('WAIVE')"
        >免单并退款</el-button>
      </div>
    </el-drawer>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Refresh, View } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { dictLabel, dictOptions } from '@aicabinet/shared-dict';
import { api } from '@/api/client';
import TableActions from '@/components/TableActions.vue';
import { useListCsv } from '@/composables/useListCsv';
import { useNavAccess } from '@/composables/useNavAccess';
import { useTableSelection } from '@/composables/useTableSelection';
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
const router = useRouter();
const { canAccessPath, goPath } = useNavAccess();
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

const { onSelectionChange, pickSelected, exportButtonLabel, clearSelection } =
  useTableSelection<DisputeTicketDto>((r) => r.ticketId);

const { onExport } = useListCsv({
  filePrefix: '争议',
  headers: ['工单', '设备', '会话', '关联订单', '状态', '分类', '优先级', '已扣金额', '原因', '创建时间', '结案时间'],
  toRows: () =>
    pickSelected(items.value).map((row) => [
      row.ticketId,
      row.deviceId,
      row.sessionId,
      row.orderId,
      dictLabel('dispute_status', row.status),
      dictLabel('dispute_category', row.category) || row.category,
      dictLabel('dispute_priority', row.priority) || row.priority,
      money(row.billedAmountCents),
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

function money(cents?: number) {
  return ((cents || 0) / 100).toFixed(2);
}

function disputeStatusType(s?: string) {
  if (s === 'OPEN') return 'warning';
  if (s === 'RESOLVED') return 'success';
  if (s === 'CLOSED') return 'info';
  return '';
}

function goDevice(id: string) {
  if (!canAccessPath('/devices')) {
    ElMessage.warning('无访问权限');
    return;
  }
  router.push(`/devices/${encodeURIComponent(id)}`);
}
function goSessions(device?: string) {
  const query: Record<string, string> = {};
  if (device) query.deviceId = device;
  goPath('/sessions', query);
}
function goOrders(device?: string) {
  const query: Record<string, string> = {};
  if (device) query.deviceId = device;
  goPath('/orders', query);
}

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

function syncRouteQuery() {
  const query: Record<string, string> = {};
  if (status.value) query.status = status.value;
  router.replace({ query });
}

async function load(showToast = false) {
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
    clearSelection();
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
  syncRouteQuery();
  load(false);
}

function reset() {
  status.value = 'OPEN';
  page.value = 1;
  syncRouteQuery();
  load(false);
}

function applyRouteQuery() {
  let changed = false;
  if (typeof route.query.status === 'string' && route.query.status !== status.value) {
    status.value = route.query.status;
    changed = true;
  }
  return changed;
}

onActivated(async () => {
  detailVisible.value = false;
  selected.value = null;
  resolveFeedback.value = null;
  if (applyRouteQuery()) {
    page.value = 1;
    await load(false);
  }
});
onDeactivated(() => {
  detailVisible.value = false;
  selected.value = null;
});
onMounted(async () => {
  applyRouteQuery();
  syncRouteQuery();
  await load(false);
  const ticketId = route.query.ticketId;
  if (typeof ticketId === 'string' && ticketId) {
    const row = items.value.find((t) => t.ticketId === ticketId);
    if (row) openDetail(row);
  }
});
</script>

<style scoped>
.page-card-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  flex-wrap: wrap;
}
.page-card-head__meta { min-width: 0; }
.page-card-head__title { display: flex; flex-direction: column; gap: 4px; }
.title { font-weight: 600; font-size: 15px; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.4; }
.page-card-head__actions { display: flex; gap: 8px; }
.ticket-cell {
  appearance: none;
  border: 0;
  padding: 0;
  margin: 0;
  background: transparent;
  display: grid;
  gap: 2px;
  text-align: left;
  cursor: pointer;
  color: inherit;
  font: inherit;
  line-height: 1.35;
}
.ticket-cell strong { color: var(--el-color-primary); font-weight: 650; }
.ticket-cell small {
  color: var(--el-text-color-secondary);
  font-size: 11px;
  font-family: var(--app-font-mono);
}
.ticket-cell:hover strong { text-decoration: underline; }
.link-cell {
  appearance: none;
  border: 0;
  padding: 0;
  margin: 0;
  background: transparent;
  color: var(--el-color-primary);
  cursor: pointer;
  text-align: left;
  font: inherit;
}
.link-cell:hover { text-decoration: underline; }
.link-cell.mono { font-family: var(--app-font-mono); font-size: 12px; }
.muted { color: var(--el-text-color-secondary); }
.resolve-feedback { margin-bottom: 16px; }
.drawer-actions { display: flex; flex-wrap: wrap; gap: 12px; margin-top: 24px; }
.items-block { margin-top: 20px; }
.items-title { font-weight: 600; margin-bottom: 8px; }
.ai-suggest-block { width: 100%; margin-bottom: 12px; }
.suggest-alert { margin-top: 8px; }
.hidden-input { display: none; }
</style>
