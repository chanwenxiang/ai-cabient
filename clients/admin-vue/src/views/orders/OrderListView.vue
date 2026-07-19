<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">订单管理</span>
            <span class="hint">按设备 / 状态筛选；详情可退款，设备与会话可跳转</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button v-hasPermi="['ops:order:export']" @click="onExport">{{ exportButtonLabel }}</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="search">
      <el-form-item label="设备">
        <el-input
          v-model="deviceId"
          clearable
          placeholder="设备编号"
          style="width: 160px"
          @keyup.enter="search"
        />
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="status" clearable placeholder="全部" style="width: 140px" @change="search">
          <el-option v-for="o in statusOptions" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </el-form-item>
    </el-form>

    <div class="table-scroll">
      <div class="table-scroll-inner" style="min-width: 1100px">
        <el-table
          v-loading="loading"
          :data="items"
          stripe
          border
          class="report-table"
          row-key="orderId"
          @selection-change="onSelectionChange"
        >
          <template #empty><el-empty description="暂无订单" /></template>
          <el-table-column type="selection" width="48" align="center" />
          <el-table-column label="订单号" min-width="168" class-name="col-text">
            <template #default="{ row }">
              <button type="button" class="link-cell" @click="openDetail(row)">
                <span class="cell-id">{{ row.orderId }}</span>
              </button>
            </template>
          </el-table-column>
          <el-table-column label="会话" min-width="150" class-name="col-text" show-overflow-tooltip>
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
          <el-table-column label="用户" width="96" class-name="col-text">
            <template #default="{ row }">{{ row.userId ?? '-' }}</template>
          </el-table-column>
          <el-table-column label="设备" min-width="120" class-name="col-text">
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
          <el-table-column label="状态" width="100" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="orderStatusType(row.status)">
                {{ dictLabel('order_status', row.status) || row.status || '-' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="支付渠道" width="100" align="center">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">
                {{ dictLabel('pay_channel', row.payChannel) || row.payChannel || '-' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="商品" min-width="140" class-name="col-text" show-overflow-tooltip>
            <template #default="{ row }">
              <div class="line-cell">
                <strong>{{ row.lineCount ?? 0 }} 件</strong>
                <small v-if="row.lineSummary">{{ row.lineSummary }}</small>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="金额" width="110" align="right" class-name="col-money">
            <template #default="{ row }">¥{{ money(row.totalAmountCents) }}</template>
          </el-table-column>
          <el-table-column label="创建时间" width="172" class-name="col-text" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="cell-datetime">{{ formatDateTime(row.createdAt) }}</span>
            </template>
          </el-table-column>
          <!-- 与 .table-scroll 约定一致：不用 fixed，避免时间列与操作列叠层透视 -->
          <el-table-column label="操作" width="100" align="center" class-name="col-action">
            <template #default="{ row }">
              <TableActions :actions="rowActions(row)" :max-primary="2" @action="(key) => onRowAction(key, row)" />
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
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        background
        @current-change="load"
        @size-change="onSizeChange"
      />
    </div>

    <el-drawer v-model="detailOpen" title="订单详情" size="480px" destroy-on-close>
      <div v-loading="detailLoading">
        <template v-if="detail">
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="订单号">
              <span class="cell-id">{{ detail.orderId }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="会话">
              <button
                v-if="detail.sessionId"
                type="button"
                class="link-cell mono"
                @click="goSessions(detail.deviceId)"
              >{{ detail.sessionId }}</button>
              <span v-else>-</span>
            </el-descriptions-item>
            <el-descriptions-item label="设备">
              <button
                v-if="detail.deviceId"
                type="button"
                class="link-cell"
                @click="goDevice(detail.deviceId)"
              >{{ detail.deviceId }}</button>
              <span v-else>-</span>
            </el-descriptions-item>
            <el-descriptions-item label="状态">
              {{ dictLabel('order_status', detail.status) }}
            </el-descriptions-item>
            <el-descriptions-item label="金额">¥{{ money(detail.totalAmountCents) }}</el-descriptions-item>
            <el-descriptions-item label="支付渠道">
              {{ dictLabel('pay_channel', detail.payChannel) || detail.payChannel || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="创建时间">{{ formatDateTime(detail.createdAt) }}</el-descriptions-item>
          </el-descriptions>

          <div class="drawer-actions">
            <el-button
              v-if="canRefund(detail.status) && auth.hasPerm('ops:order:refund')"
              type="danger"
              :loading="refundingId === detail.orderId"
              @click="refundOrder(detail)"
            >原路退款</el-button>
          </div>

          <h4 class="section-title">商品行</h4>
          <el-table :data="detail.lines || detail.items || []" size="small" border empty-text="无商品行">
            <el-table-column prop="skuName" label="商品" min-width="120" class-name="col-text" />
            <el-table-column prop="quantity" label="数量" width="70" align="center" />
            <el-table-column label="小计" width="90" align="right">
              <template #default="{ row }">
                ¥{{ money(row.lineAmountCents || row.amountCents || 0) }}
              </template>
            </el-table-column>
          </el-table>

          <h4 class="section-title">时间线</h4>
          <el-timeline class="order-timeline">
            <el-timeline-item :timestamp="formatDateTime(detail.createdAt)" type="primary">下单</el-timeline-item>
            <el-timeline-item
              v-if="detail.paidAt || detail.status === 'PAID' || detail.status === 'COMPLETED'"
              :timestamp="formatDateTime(detail.paidAt || detail.updatedAt || detail.createdAt)"
              type="success"
            >扣款完成</el-timeline-item>
            <el-timeline-item
              v-if="detail.status === 'REFUNDED'"
              :timestamp="formatDateTime(detail.updatedAt)"
              type="warning"
            >已退款</el-timeline-item>
            <el-timeline-item
              v-if="detail.sessionId"
              :timestamp="formatDateTime(detail.createdAt)"
              type="info"
            >关联会话 {{ detail.sessionId }}</el-timeline-item>
          </el-timeline>
        </template>
      </div>
    </el-drawer>
  </el-card>
</template>

<script setup lang="ts">
import { onActivated, onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import { Refresh, View, Wallet } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { dictLabel, dictOptions } from '@aicabinet/shared-dict';
import { api, downloadAuthFile } from '@/api/client';
import TableActions, { type TableAction } from '@/components/TableActions.vue';
import { useListCsv } from '@/composables/useListCsv';
import { useNavAccess } from '@/composables/useNavAccess';
import { useTableSelection } from '@/composables/useTableSelection';
import { useAuthStore } from '@/stores/auth';
import type { OrderSummary, PageResult } from '@aicabinet/shared-types';
import { formatDateTime } from '@aicabinet/shared-uni/format';
import { csvFileName } from '@/utils/csv';

const route = useRoute();
const { router, goPath } = useNavAccess();
const auth = useAuthStore();
const loading = ref(false);
const refundingId = ref('');
const deviceId = ref('');
const status = ref('');
const items = ref<OrderSummary[]>([]);
const page = ref(1);
const size = ref(20);
const total = ref(0);
const detailOpen = ref(false);
const detailLoading = ref(false);
const detail = ref<any>(null);
const statusOptions = dictOptions('order_status');

const { onSelectionChange, pickSelected, exportButtonLabel, clearSelection } =
  useTableSelection<OrderSummary>((r) => r.orderId);

const { onExport: exportSelectedCsv } = useListCsv({
  filePrefix: '订单',
  headers: ['订单号', '会话', '用户ID', '设备', '状态', '支付渠道', '商品行', '金额', '创建时间'],
  toRows: () =>
    pickSelected(items.value).map((row) => [
      row.orderId,
      row.sessionId,
      row.userId,
      row.deviceId,
      dictLabel('order_status', row.status) || row.status,
      dictLabel('pay_channel', row.payChannel) || row.payChannel || '-',
      row.lineCount,
      money(row.totalAmountCents),
      formatDateTime(row.createdAt)
    ])
});

async function onExport() {
  const selected = pickSelected(items.value);
  if (selected.length && selected.length < items.value.length) {
    exportSelectedCsv();
    return;
  }
  try {
    const q = new URLSearchParams();
    if (deviceId.value.trim()) q.set('deviceId', deviceId.value.trim());
    const qs = q.toString();
    await downloadAuthFile(
      `/api/v2/ops/admin/orders/export${qs ? `?${qs}` : ''}`,
      csvFileName('订单')
    );
    ElMessage.success('已导出');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '导出失败');
  }
}

function money(cents?: number) {
  return ((cents || 0) / 100).toFixed(2);
}

function orderStatusType(s?: string) {
  if (s === 'PAID' || s === 'COMPLETED') return 'success';
  if (s === 'CANCELLED' || s === 'REFUNDED') return 'info';
  if (s === 'FAILED') return 'danger';
  return 'warning';
}

function canRefund(s?: string) {
  return s === 'PAID' || s === 'COMPLETED' || s === 'DISPUTED';
}

function rowActions(row: OrderSummary): TableAction[] {
  const actions: TableAction[] = [{ key: 'detail', label: '详情', icon: View, type: 'primary' }];
  if (canRefund(row.status) && auth.hasPerm('ops:order:refund')) {
    actions.push({
      key: 'refund',
      label: '退款',
      icon: Wallet,
      type: 'danger',
      disabled: refundingId.value === row.orderId
    });
  }
  return actions;
}

function onRowAction(key: string, row: OrderSummary) {
  if (key === 'detail') openDetail(row);
  if (key === 'refund') refundOrder(row);
}

function goDevice(id: string) {
  goPath(`/devices/${encodeURIComponent(id)}`);
}

function goSessions(device?: string) {
  const query: Record<string, string> = {};
  if (device) query.deviceId = device;
  goPath('/sessions', query);
}

async function openDetail(row: OrderSummary) {
  detailOpen.value = true;
  detailLoading.value = true;
  detail.value = null;
  try {
    detail.value = await api.request(`/api/v2/ops/admin/orders/${encodeURIComponent(row.orderId)}`, 'GET');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '详情加载失败');
    detailOpen.value = false;
  } finally {
    detailLoading.value = false;
  }
}

async function refundOrder(row: { orderId: string; status?: string }) {
  try {
    const { value } = await ElMessageBox.prompt(
      `确认原路退回订单 ${row.orderId} 的全部金额？`,
      '订单退款',
      {
        inputPlaceholder: '请填写退款原因（至少4字）',
        inputValidator: (v) => !!String(v || '').trim() && String(v).trim().length >= 4 || '请填写至少4字原因',
        confirmButtonText: '确认退款',
        type: 'warning'
      }
    );
    refundingId.value = row.orderId;
    const result = await api.request<{ message?: string; refundedCents?: number }>(
      `/api/v2/ops/admin/orders/${encodeURIComponent(row.orderId)}/refund`,
      'POST',
      { reason: String(value).trim() }
    );
    ElMessage.success(result.message || '退款成功');
    if (detailOpen.value && detail.value?.orderId === row.orderId) {
      await openDetail(row as OrderSummary);
    }
    await load();
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : '退款失败');
    }
  } finally {
    refundingId.value = '';
  }
}

function syncRouteQuery() {
  const query: Record<string, string> = {};
  if (deviceId.value.trim()) query.deviceId = deviceId.value.trim();
  if (status.value) query.status = status.value;
  router.replace({ query });
}

async function load() {
  loading.value = true;
  try {
    const q = new URLSearchParams({ page: String(page.value - 1), size: String(size.value) });
    if (deviceId.value.trim()) q.set('deviceId', deviceId.value.trim());
    if (status.value) q.set('status', status.value);
    const data = await api.request<PageResult<OrderSummary>>(`/api/v2/ops/admin/orders?${q}`, 'GET');
    items.value = data.items || [];
    total.value = data.total || 0;
    clearSelection();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

function search() {
  page.value = 1;
  syncRouteQuery();
  load();
}
function reset() {
  deviceId.value = '';
  status.value = '';
  page.value = 1;
  syncRouteQuery();
  load();
}
function onSizeChange() {
  page.value = 1;
  load();
}

function applyRouteQuery() {
  let changed = false;
  if (typeof route.query.deviceId === 'string' && route.query.deviceId !== deviceId.value) {
    deviceId.value = route.query.deviceId;
    changed = true;
  }
  if (typeof route.query.status === 'string' && route.query.status !== status.value) {
    status.value = route.query.status;
    changed = true;
  }
  return changed;
}

onMounted(() => {
  applyRouteQuery();
  load();
});
onActivated(() => {
  if (applyRouteQuery()) {
    page.value = 1;
    load();
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
.page-card-head__meta {
  min-width: 0;
}
.page-card-head__title {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.title {
  font-weight: 600;
  font-size: 15px;
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.4;
}
.page-card-head__actions {
  display: flex;
  gap: 8px;
}
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
.link-cell:hover {
  text-decoration: underline;
}
.link-cell.mono {
  font-family: var(--app-font-mono);
  font-size: 12px;
}
.line-cell {
  display: grid;
  gap: 2px;
  line-height: 1.35;
}
.line-cell strong {
  font-weight: 650;
}
.line-cell small {
  color: var(--el-text-color-secondary);
  font-size: 11px;
}
.muted {
  color: var(--el-text-color-secondary);
}
.section-title {
  margin: 16px 0 8px;
  font-size: 14px;
}
.drawer-actions {
  margin-top: 12px;
}
.order-timeline {
  margin-top: 8px;
}
</style>
