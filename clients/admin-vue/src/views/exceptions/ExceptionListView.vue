<template>
  <div>
    <el-card shadow="never" class="page-card report-page">
      <template #header>
        <div class="page-card-head">
          <div class="page-card-head__meta">
            <div class="page-card-head__title">
              <span class="title">异常中心</span>
              <span class="hint">默认看待处理；超时标红；设备 / 会话 / 订单可跳转</span>
            </div>
          </div>
          <div class="page-card-head__actions">
            <el-button v-hasPermi="['ops:exception:export']" @click="onExport">{{ exportButtonLabel }}</el-button>
            <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
          </div>
        </div>
      </template>

      <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="search">
        <el-form-item label="状态">
          <el-select v-model="status" clearable placeholder="全部" style="width: 140px" @change="search">
            <el-option
              v-for="item in dictOptions('exception_status')"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="级别">
          <el-select v-model="severity" clearable placeholder="全部" style="width: 120px" @change="search">
            <el-option
              v-for="item in dictOptions('exception_severity')"
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
            :row-class-name="rowClassName"
            table-layout="auto"
            row-key="exceptionId"
            @selection-change="onSelectionChange"
          >
            <el-table-column type="selection" width="48" align="center" />
            <el-table-column label="异常" min-width="200" class-name="col-text">
              <template #default="{ row }">
                <button type="button" class="exception-cell" @click="openDetail(row)">
                  <strong>{{ row.title || dictLabel('exception_type', row.exceptionType) }}</strong>
                  <small>{{ row.exceptionId }}</small>
                </button>
              </template>
            </el-table-column>
            <el-table-column label="级别" width="80" align="center">
              <template #default="{ row }">
                <el-tag :type="dictTagType(row.severity)" size="small">
                  {{ dictLabel('exception_severity', row.severity) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="类型" min-width="120" class-name="col-text" show-overflow-tooltip>
              <template #default="{ row }">{{ dictLabel('exception_type', row.exceptionType) }}</template>
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
            <el-table-column label="订单" min-width="120" class-name="col-text" show-overflow-tooltip>
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
            <el-table-column label="用户" width="88" class-name="col-text">
              <template #default="{ row }">{{ row.userId || '-' }}</template>
            </el-table-column>
            <el-table-column label="状态" width="92" align="center">
              <template #default="{ row }">
                <el-tag :type="dictTagType(row.status)" size="small">
                  {{ dictLabel('exception_status', row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="SLA" width="150" class-name="col-text">
              <template #default="{ row }">
                <el-tag v-if="row.slaOverdue" type="danger" size="small">已超时</el-tag>
                <span v-else-if="row.slaDueAt" class="cell-datetime">{{ formatDateTime(row.slaDueAt) }}</span>
                <span v-else class="muted">-</span>
              </template>
            </el-table-column>
            <el-table-column label="负责人" width="88" class-name="col-text">
              <template #default="{ row }">{{ row.assigneeUserId || '未领取' }}</template>
            </el-table-column>
            <el-table-column label="创建时间" width="160" class-name="col-text">
              <template #default="{ row }">
                <span class="cell-datetime">{{ formatDateTime(row.createdAt) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="150" class-name="col-action" align="center">
              <template #default="{ row }">
                <TableActions :actions="exceptionActions(row)" @action="(key) => onExceptionAction(key, row)" />
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
    </el-card>

    <el-drawer
      v-if="drawer"
      v-model="drawer"
      title="异常处理详情"
      size="560px"
      append-to-body
      destroy-on-close
    >
      <div v-loading="detailLoading" v-if="detail">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="异常编号">
            <span class="cell-id">{{ detail.exception.exceptionId }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="异常类型">
            <el-tag type="info" size="small">{{ dictLabel('exception_type', detail.exception.exceptionType) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="严重级别">
            <el-tag :type="dictTagType(detail.exception.severity)" size="small">
              {{ dictLabel('exception_severity', detail.exception.severity) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="处理状态">
            <el-tag :type="dictTagType(detail.exception.status)" size="small">
              {{ dictLabel('exception_status', detail.exception.status) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="异常内容">{{ detail.exception.title }}</el-descriptions-item>
          <el-descriptions-item label="详细信息">{{ detail.exception.detail || '-' }}</el-descriptions-item>
          <el-descriptions-item label="关联设备">
            <button
              v-if="detail.exception.deviceId"
              type="button"
              class="link-cell"
              @click="goDevice(detail.exception.deviceId)"
            >{{ detail.exception.deviceId }}</button>
            <span v-else>-</span>
          </el-descriptions-item>
          <el-descriptions-item label="关联会话">
            <button
              v-if="detail.exception.sessionId"
              type="button"
              class="link-cell mono"
              @click="goSessions(detail.exception.deviceId)"
            >{{ detail.exception.sessionId }}</button>
            <span v-else>-</span>
          </el-descriptions-item>
          <el-descriptions-item label="关联订单">
            <button
              v-if="detail.exception.orderId"
              type="button"
              class="link-cell mono"
              @click="goOrders(detail.exception.deviceId)"
            >{{ detail.exception.orderId }}</button>
            <span v-else>-</span>
          </el-descriptions-item>
          <el-descriptions-item label="用户">{{ detail.exception.userId || '-' }}</el-descriptions-item>
          <el-descriptions-item label="负责人">
            {{ detail.exception.assigneeUserId ? `用户 ${detail.exception.assigneeUserId}` : '未领取' }}
          </el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ formatDateTime(detail.exception.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="SLA截止">
            <el-tag v-if="detail.exception.slaOverdue" type="danger" size="small">已超时</el-tag>
            <span v-else>{{ formatDateTime(detail.exception.slaDueAt) || '-' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="更新时间">{{ formatDateTime(detail.exception.updatedAt) }}</el-descriptions-item>
        </el-descriptions>

        <div v-if="canHandle && detail.exception.status !== 'RESOLVED'" class="drawer-actions">
          <el-button type="primary" @click="addNote">添加备注</el-button>
          <el-button @click="transfer">转派</el-button>
          <el-button v-if="canRetry(detail.exception)" type="warning" @click="retryException">重试识别/结算</el-button>
          <el-button
            v-if="canManualSettle && canManualResolve(detail.exception)"
            type="success"
            @click="openManualResolve"
          >人工确认商品</el-button>
          <el-button
            v-if="canManualSettle && canManualResolve(detail.exception)"
            type="danger"
            plain
            @click="waiveOrder"
          >免单/全额退回</el-button>
          <el-button v-if="detail.exception.sessionId && auth.hasPerm('ops:session:cancel')" type="danger" @click="cancelSession">取消会话并释放设备</el-button>
        </div>

        <h3 class="section-title">处理记录</h3>
        <el-timeline>
          <el-timeline-item
            v-for="action in detail.actions"
            :key="action.actionId"
            :timestamp="formatDateTime(action.createdAt)"
          >
            <strong>{{ dictLabel('ops_exception_action', action.action) }}</strong>
            · 操作人 {{ action.operatorId || '系统' }}
            <div class="action-detail">{{ formatOpsActionDetail(action.detail) }}</div>
          </el-timeline-item>
        </el-timeline>
      </div>
    </el-drawer>

    <el-dialog v-model="manualDialog" title="人工确认商品与结算金额" width="640px">
      <el-alert type="warning" :closable="false" title="提交后可能产生首次扣款、补扣或余额退差，并同步变更库存。" />
      <div class="manual-lines">
        <div v-for="(line, index) in manualLines" :key="index" class="manual-line">
          <el-select v-model="line.skuId" filterable placeholder="选择商品" style="flex:1">
            <el-option
              v-for="sku in skus"
              :key="sku.skuId"
              :label="`${sku.skuName}（¥${(sku.priceCents / 100).toFixed(2)}）`"
              :value="sku.skuId"
            />
          </el-select>
          <el-input-number v-model="line.quantity" :min="1" :max="99" />
          <el-button type="danger" link @click="manualLines.splice(index, 1)">删除</el-button>
        </div>
        <el-button @click="manualLines.push({ skuId: '', quantity: 1 })">添加商品</el-button>
      </div>
      <el-input
        v-model="manualReason"
        type="textarea"
        :rows="3"
        maxlength="500"
        show-word-limit
        placeholder="必须填写判断依据和处理原因"
      />
      <template #footer>
        <el-button @click="manualDialog = false">取消</el-button>
        <el-button type="primary" :loading="manualSubmitting" @click="submitManualResolve">确认商品并结算</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { CircleCheck, Refresh, UserFilled, View } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import TableActions, { type TableAction } from '@/components/TableActions.vue';
import { useListCsv } from '@/composables/useListCsv';
import { useNavAccess } from '@/composables/useNavAccess';
import { useTableSelection } from '@/composables/useTableSelection';
import { useAuthStore } from '@/stores/auth';
import { dictLabel, dictOptions, dictTagType, formatOpsActionDetail } from '@aicabinet/shared-dict';
import type { PageResult } from '@aicabinet/shared-types';
import { formatDateTime } from '@aicabinet/shared-uni/format';

const route = useRoute();
const router = useRouter();
const { canAccessPath, goPath } = useNavAccess();
const auth = useAuthStore();
const canHandle = computed(
  () => auth.hasPerm('ops:exception:handle')
);
/** 与后端 manual-resolve 一致：需同时具备争议解决权限 */
const canManualSettle = computed(
  () => canHandle.value && auth.hasPerm('ops:dispute:resolve')
);

interface OpsException {
  exceptionId: string;
  exceptionType: string;
  severity: string;
  status: string;
  title: string;
  detail?: string;
  deviceId?: string;
  sessionId?: string;
  orderId?: string;
  userId?: number;
  assigneeUserId?: number;
  createdAt?: string;
  updatedAt?: string;
  slaDueAt?: string;
  slaOverdue?: boolean;
}
interface OpsAction { actionId: number; operatorId: number; action: string; detail?: string; createdAt: string }
interface OpsDetail { exception: OpsException; actions: OpsAction[] }
interface Sku { skuId: string; skuName: string; priceCents: number }

const loading = ref(false);
const status = ref('OPEN');
const severity = ref('');
const page = ref(1);
const size = ref(20);
const total = ref(0);
const items = ref<OpsException[]>([]);
const drawer = ref(false);
const detailLoading = ref(false);
const detail = ref<OpsDetail | null>(null);
const manualDialog = ref(false);
const manualSubmitting = ref(false);
const manualReason = ref('');
const manualLines = ref<{ skuId: string; quantity: number }[]>([{ skuId: '', quantity: 1 }]);
const skus = ref<Sku[]>([]);

const { onSelectionChange, pickSelected, exportButtonLabel, clearSelection } =
  useTableSelection<OpsException>((r) => r.exceptionId);

const { onExport } = useListCsv({
  filePrefix: '异常',
  headers: ['异常编号', '级别', '类型', '异常', '设备', '会话', '订单', '用户', '状态', 'SLA截止', '超时', '负责人', '创建时间'],
  toRows: () =>
    pickSelected(items.value).map((row) => [
      row.exceptionId,
      dictLabel('exception_severity', row.severity),
      dictLabel('exception_type', row.exceptionType),
      row.title,
      row.deviceId,
      row.sessionId,
      row.orderId,
      row.userId,
      dictLabel('exception_status', row.status),
      formatDateTime(row.slaDueAt),
      row.slaOverdue ? '是' : '否',
      row.assigneeUserId || '未领取',
      formatDateTime(row.createdAt)
    ])
});

const emptyHint = computed(() =>
  status.value === 'OPEN'
    ? '当前无待处理异常，可清空状态筛选查看历史'
    : '暂无异常'
);

function rowClassName({ row }: { row: OpsException }) {
  return row.slaOverdue ? 'is-overdue' : '';
}

function exceptionActions(row: OpsException): TableAction[] {
  const acts: TableAction[] = [];
  if (canHandle.value && row.status === 'OPEN') {
    acts.push({ key: 'claim', label: '领取', icon: UserFilled, type: 'primary' });
  }
  if (canHandle.value && row.status !== 'RESOLVED') {
    acts.push({ key: 'resolve', label: '解决', icon: CircleCheck, type: 'success' });
  }
  acts.push({
    key: 'detail',
    label: '详情',
    icon: View,
    type: 'primary',
    overflow: acts.length >= 2
  });
  return acts;
}

function onExceptionAction(key: string, row: OpsException) {
  if (key === 'detail') openDetail(row);
  else if (key === 'claim') claim(row);
  else if (key === 'resolve') resolve(row);
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

function syncRouteQuery() {
  const query: Record<string, string> = {};
  if (status.value) query.status = status.value;
  if (severity.value) query.severity = severity.value;
  router.replace({ query });
}

async function load() {
  loading.value = true;
  try {
    const q = new URLSearchParams({ page: String(page.value - 1), size: String(size.value) });
    if (status.value) q.set('status', status.value);
    if (severity.value) q.set('severity', severity.value);
    const data = await api.request<PageResult<OpsException>>(`/api/v2/ops/admin/exceptions?${q}`, 'GET');
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
  status.value = 'OPEN';
  severity.value = '';
  page.value = 1;
  syncRouteQuery();
  load();
}
function onSizeChange() {
  page.value = 1;
  load();
}

async function claim(row: OpsException) {
  await api.request(`/api/v2/ops/admin/exceptions/${row.exceptionId}/claim`, 'POST');
  ElMessage.success('已领取');
  await load();
}
async function resolve(row: OpsException) {
  const { value } = await ElMessageBox.prompt('请填写处理结果，记录将进入审计日志', '解决异常', {
    inputValidator: (v) => !!String(v || '').trim() || '必须填写处理结果',
    confirmButtonText: '确认解决',
    cancelButtonText: '取消'
  });
  await api.request(`/api/v2/ops/admin/exceptions/${row.exceptionId}/resolve`, 'POST', { resolution: value });
  ElMessage.success('异常已解决');
  await load();
}
async function openDetail(row: OpsException) {
  drawer.value = true;
  detailLoading.value = true;
  try {
    detail.value = await api.request<OpsDetail>(`/api/v2/ops/admin/exceptions/${row.exceptionId}`, 'GET');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '详情加载失败');
  } finally {
    detailLoading.value = false;
  }
}
async function refreshDetail() {
  if (detail.value) await openDetail(detail.value.exception);
}
async function addNote() {
  if (!detail.value) return;
  const { value } = await ElMessageBox.prompt('请输入处理备注', '添加备注', {
    inputValidator: (v) => !!String(v || '').trim() || '备注不能为空'
  });
  await api.request(`/api/v2/ops/admin/exceptions/${detail.value.exception.exceptionId}/notes`, 'POST', { note: value });
  ElMessage.success('备注已记录');
  await refreshDetail();
}
async function transfer() {
  if (!detail.value) return;
  const { value } = await ElMessageBox.prompt('请输入接收人的用户 ID', '转派异常', {
    inputPattern: /^\d+$/,
    inputErrorMessage: '请输入有效用户 ID'
  });
  await api.request(`/api/v2/ops/admin/exceptions/${detail.value.exception.exceptionId}/transfer`, 'POST', {
    assigneeUserId: Number(value),
    reason: '运营工作台转派'
  });
  ElMessage.success('已转派');
  await Promise.all([load(), refreshDetail()]);
}
async function cancelSession() {
  if (!detail.value) return;
  const item = detail.value.exception;
  const { value } = await ElMessageBox.prompt(
    `将终止会话 ${item.sessionId} 并释放设备 ${item.deviceId || '-'}，请填写原因`,
    '危险操作确认',
    {
      type: 'warning',
      confirmButtonText: '确认终止',
      cancelButtonText: '取消',
      inputValidator: (v) => !!String(v || '').trim() || '必须填写原因'
    }
  );
  await api.request(`/api/v2/ops/admin/exceptions/${item.exceptionId}/cancel-session`, 'POST', {
    reason: value,
    idempotencyKey: `ops-cancel-${item.exceptionId}`
  });
  ElMessage.success('会话已终止，设备占用已释放');
  await Promise.all([load(), refreshDetail()]);
}
function canRetry(item: OpsException) {
  return !!item.sessionId && ['RECOGNITION_UNAVAILABLE', 'RECOGNITION_FAILED', 'SETTLEMENT_FAILED'].includes(item.exceptionType);
}
async function retryException() {
  if (!detail.value) return;
  const item = detail.value.exception;
  await ElMessageBox.confirm(`将重新处理会话 ${item.sessionId}，系统仍会执行订单、库存和余额幂等校验。`, '确认重试', {
    type: 'warning',
    confirmButtonText: '开始重试'
  });
  await api.request(`/api/v2/ops/admin/exceptions/${item.exceptionId}/retry`, 'POST', {
    reason: '运营人工触发重试',
    idempotencyKey: `ops-retry-${item.exceptionId}-${Date.now()}`
  });
  ElMessage.success('重试请求已执行');
  await Promise.all([load(), refreshDetail()]);
}
function canManualResolve(item: OpsException) {
  return !!item.sessionId && ['BALANCE_INSUFFICIENT', 'RECOGNITION_UNAVAILABLE', 'RECOGNITION_FAILED', 'SETTLEMENT_FAILED'].includes(item.exceptionType);
}
async function openManualResolve() {
  if (!skus.value.length) skus.value = await api.request<Sku[]>('/api/v2/ops/admin/skus', 'GET');
  manualLines.value = [{ skuId: '', quantity: 1 }];
  manualReason.value = '';
  manualDialog.value = true;
}
async function submitManualResolve() {
  if (!detail.value) return;
  const lines = manualLines.value.filter((line) => line.skuId && line.quantity > 0);
  if (!lines.length) {
    ElMessage.warning('请至少选择一个商品');
    return;
  }
  if (!manualReason.value.trim()) {
    ElMessage.warning('必须填写处理原因');
    return;
  }
  await ElMessageBox.confirm('确认按当前商品清单结算？系统将自动计算补扣或退差金额。', '资金操作二次确认', {
    type: 'warning',
    confirmButtonText: '确认结算'
  });
  manualSubmitting.value = true;
  try {
    await api.request(`/api/v2/ops/admin/exceptions/${detail.value.exception.exceptionId}/manual-resolve`, 'POST', {
      resolutionType: 'CONFIRM',
      items: lines,
      reason: manualReason.value.trim(),
      idempotencyKey: `ops-manual-${detail.value.exception.exceptionId}-${Date.now()}`
    });
    manualDialog.value = false;
    ElMessage.success('人工商品清单已结算');
    await Promise.all([load(), refreshDetail()]);
  } finally {
    manualSubmitting.value = false;
  }
}
async function waiveOrder() {
  if (!detail.value) return;
  const item = detail.value.exception;
  const { value } = await ElMessageBox.prompt('该操作会取消本次消费并退回已经扣除的余额，请填写免单原因。', '免单与全额退款', {
    type: 'warning',
    confirmButtonText: '确认免单',
    inputValidator: (v) => !!String(v || '').trim() || '必须填写免单原因'
  });
  await api.request(`/api/v2/ops/admin/exceptions/${item.exceptionId}/manual-resolve`, 'POST', {
    resolutionType: 'WAIVE',
    items: [],
    reason: value,
    idempotencyKey: `ops-waive-${item.exceptionId}`
  });
  ElMessage.success('免单处理完成');
  await Promise.all([load(), refreshDetail()]);
}

function applyRouteQuery() {
  let changed = false;
  const qStatus = typeof route.query.status === 'string' ? route.query.status : '';
  const qSeverity = typeof route.query.severity === 'string' ? route.query.severity : '';
  // Keep default OPEN when query omits status (matches page default).
  const nextStatus = qStatus || 'OPEN';
  if (nextStatus !== status.value) {
    status.value = nextStatus;
    changed = true;
  }
  if (qSeverity !== severity.value) {
    severity.value = qSeverity;
    changed = true;
  }
  return changed;
}

async function reloadFromRouteQuery() {
  if (!applyRouteQuery()) return;
  page.value = 1;
  await load();
}

watch(
  () => [route.query.status, route.query.severity] as const,
  () => {
    void reloadFromRouteQuery();
  }
);

onActivated(async () => {
  drawer.value = false;
  detail.value = null;
  manualDialog.value = false;
  await reloadFromRouteQuery();
});
onDeactivated(() => {
  drawer.value = false;
  detail.value = null;
  manualDialog.value = false;
});
onMounted(async () => {
  applyRouteQuery();
  syncRouteQuery();
  await load();
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
.exception-cell {
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
.exception-cell strong { color: var(--el-color-primary); font-weight: 650; }
.exception-cell small {
  color: var(--el-text-color-secondary);
  font-size: 11px;
  font-family: var(--app-font-mono);
}
.exception-cell:hover strong { text-decoration: underline; }
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
.drawer-actions { display: flex; gap: 10px; flex-wrap: wrap; margin: 16px 0; }
.section-title { margin: 16px 0 8px; font-size: 14px; color: var(--layout-text); }
.action-detail { color: var(--layout-muted); margin-top: 5px; white-space: pre-wrap; }
.manual-lines { display: flex; flex-direction: column; gap: 12px; margin: 18px 0; }
.manual-line { display: flex; align-items: center; gap: 10px; }
:deep(.el-table .is-overdue > td.el-table__cell) {
  background: color-mix(in srgb, var(--el-color-danger) 6%, transparent) !important;
}
</style>
