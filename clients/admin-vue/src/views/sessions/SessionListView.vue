<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">开门记录</span>
            <span class="hint">按设备 / 会话状态筛选；可看时间线、跳转设备与订单</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button v-hasPermi="['ops:session:export']" @click="onExport">{{ exportButtonLabel }}</el-button>
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
        <el-select v-model="state" clearable placeholder="全部" style="width: 140px" @change="search">
          <el-option v-for="o in stateOptions" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </el-form-item>
    </el-form>

    <div class="table-scroll">
      <div class="table-scroll-inner" style="min-width: 1080px">
        <el-table
          v-loading="loading"
          :data="items"
          stripe
          border
          class="report-table"
          row-key="sessionId"
          @selection-change="onSelectionChange"
        >
          <template #empty><el-empty description="暂无开门记录" /></template>
          <el-table-column type="selection" width="48" align="center" />
          <el-table-column label="会话" min-width="160" class-name="col-text">
            <template #default="{ row }">
              <button type="button" class="link-cell mono" @click="openTimeline(row)">
                {{ row.sessionId }}
              </button>
            </template>
          </el-table-column>
          <el-table-column label="用户" width="88" class-name="col-text">
            <template #default="{ row }">{{ row.userId ?? '-' }}</template>
          </el-table-column>
          <el-table-column label="设备" min-width="110" class-name="col-text">
            <template #default="{ row }">
              <button
                v-if="row.deviceId"
                type="button"
                class="link-cell"
                @click="router.push(`/devices/${encodeURIComponent(row.deviceId)}`)"
              >{{ row.deviceId }}</button>
              <span v-else class="muted">-</span>
            </template>
          </el-table-column>
          <el-table-column label="订单" min-width="130" class-name="col-text" show-overflow-tooltip>
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
          <el-table-column label="状态" width="110" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="sessionStateType(row.state)">
                {{ dictLabel('session_state', row.state) || row.state || '-' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="失败原因" min-width="140" class-name="col-text" show-overflow-tooltip>
            <template #default="{ row }">{{ row.failureReason || '-' }}</template>
          </el-table-column>
          <el-table-column label="创建时间" width="160" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-datetime">{{ formatDateTime(row.createdAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="更新时间" width="160" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-datetime">{{ formatDateTime(row.updatedAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="140" class-name="col-action" align="center">
            <template #default="{ row }">
              <TableActions :actions="sessionActions(row)" :max-primary="2" @action="(k) => onAction(String(k), row)" />
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
        @current-change="load"
        @size-change="onSizeChange"
      />
    </div>

    <el-drawer v-model="timelineOpen" title="会话时间线" size="420px" destroy-on-close>
      <template v-if="timelineRow">
        <el-descriptions :column="1" border size="small" class="mb12">
          <el-descriptions-item label="会话">
            <span class="cell-id">{{ timelineRow.sessionId }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="设备">
            <button
              v-if="timelineRow.deviceId"
              type="button"
              class="link-cell"
              @click="router.push(`/devices/${encodeURIComponent(timelineRow.deviceId)}`)"
            >{{ timelineRow.deviceId }}</button>
            <span v-else>-</span>
          </el-descriptions-item>
          <el-descriptions-item label="订单">
            <button
              v-if="timelineRow.orderId"
              type="button"
              class="link-cell mono"
              @click="goOrders(timelineRow.deviceId)"
            >{{ timelineRow.orderId }}</button>
            <span v-else>-</span>
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            {{ dictLabel('session_state', timelineRow.state) }}
          </el-descriptions-item>
        </el-descriptions>
        <el-timeline>
          <el-timeline-item
            v-for="step in sessionTimeline(timelineRow)"
            :key="step.label"
            :timestamp="step.time"
            :type="step.type"
          >
            {{ step.label }}
            <div v-if="step.detail" class="tl-detail">{{ step.detail }}</div>
          </el-timeline-item>
        </el-timeline>
        <div class="tl-actions">
          <el-button
            v-if="timelineRow.deviceId"
            @click="router.push({ path: '/upload-queue', query: { deviceId: timelineRow.deviceId } })"
          >录像上传队列</el-button>
          <el-button
            v-if="timelineRow.orderId"
            type="primary"
            @click="goOrders(timelineRow.deviceId)"
          >查看订单</el-button>
        </div>
      </template>
    </el-drawer>
  </el-card>
</template>

<script setup lang="ts">
import { onActivated, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { CircleClose, Clock, CopyDocument, Refresh, View, VideoCamera } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { dictLabel, dictOptions } from '@aicabinet/shared-dict';
import { api } from '@/api/client';
import TableActions, { type TableAction } from '@/components/TableActions.vue';
import { useListCsv } from '@/composables/useListCsv';
import { useTableSelection } from '@/composables/useTableSelection';
import { useAuthStore } from '@/stores/auth';
import type { PageResult } from '@aicabinet/shared-types';
import { formatDateTime } from '@aicabinet/shared-uni/format';

interface SessionRow {
  sessionId: string;
  userId?: string;
  deviceId?: string;
  state?: string;
  orderId?: string;
  failureReason?: string;
  openTime?: string;
  closeTime?: string;
  uploadStatus?: string;
  createdAt?: string;
  updatedAt?: string;
}

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const loading = ref(false);
const deviceId = ref('');
const state = ref('');
const page = ref(1);
const size = ref(20);
const total = ref(0);
const items = ref<SessionRow[]>([]);
const timelineOpen = ref(false);
const timelineRow = ref<SessionRow | null>(null);
const stateOptions = dictOptions('session_state');

const { onSelectionChange, pickSelected, exportButtonLabel, clearSelection } =
  useTableSelection<SessionRow>((r) => r.sessionId);

const { onExport } = useListCsv({
  filePrefix: '开门记录',
  headers: ['会话ID', '用户', '设备', '订单', '状态', '失败原因', '创建时间', '更新时间'],
  toRows: () =>
    pickSelected(items.value).map((row) => [
      row.sessionId,
      row.userId,
      row.deviceId,
      row.orderId,
      dictLabel('session_state', row.state),
      row.failureReason,
      formatDateTime(row.createdAt),
      formatDateTime(row.updatedAt)
    ])
});

function canCancel(s?: string) {
  return !!s && !['COMPLETED', 'CANCELLED', 'FAILED'].includes(s);
}

function sessionStateType(s?: string) {
  if (s === 'COMPLETED') return 'success';
  if (s === 'CANCELLED') return 'info';
  if (s === 'FAILED') return 'danger';
  if (s === 'SHOPPING' || s === 'OPEN' || s === 'DOOR_OPEN') return 'warning';
  return '';
}

function sessionActions(row: SessionRow): TableAction[] {
  const acts: TableAction[] = [
    { key: 'timeline', label: '时间线', icon: Clock, type: 'primary' }
  ];
  if (row.deviceId) {
    acts.push({ key: 'device', label: '看设备', icon: View, type: 'info' });
  }
  acts.push({ key: 'copy', label: '复制会话ID', icon: CopyDocument, type: 'info', overflow: true });
  if (row.deviceId) {
    acts.push({ key: 'video', label: '录像队列', icon: VideoCamera, type: 'warning', overflow: true });
  }
  if (canCancel(row.state) && auth.hasPerm('ops:session:cancel')) {
    acts.push({ key: 'cancel', label: '取消会话', icon: CircleClose, type: 'danger', overflow: true });
  }
  return acts;
}

function sessionTimeline(row: SessionRow) {
  const steps: {
    label: string;
    time: string;
    type?: 'primary' | 'success' | 'warning' | 'danger' | 'info';
    detail?: string;
  }[] = [];
  steps.push({ label: '创建会话', time: formatDateTime(row.createdAt), type: 'primary' });
  if (row.openTime) steps.push({ label: '开门', time: formatDateTime(row.openTime), type: 'success' });
  if (row.closeTime) steps.push({ label: '关门', time: formatDateTime(row.closeTime), type: 'success' });
  if (row.uploadStatus && row.uploadStatus !== 'NONE') {
    steps.push({
      label: '录像上传',
      time: formatDateTime(row.updatedAt),
      type: String(row.uploadStatus).includes('FAIL') ? 'danger' : 'warning',
      detail: dictLabel('upload_status', row.uploadStatus) || row.uploadStatus
    });
  }
  if (row.orderId) {
    steps.push({ label: '生成订单', time: formatDateTime(row.updatedAt), type: 'success', detail: row.orderId });
  }
  if (row.failureReason) {
    steps.push({ label: '失败', time: formatDateTime(row.updatedAt), type: 'danger', detail: row.failureReason });
  }
  steps.push({
    label: `当前：${dictLabel('session_state', row.state)}`,
    time: formatDateTime(row.updatedAt),
    type: 'info'
  });
  return steps;
}

function openTimeline(row: SessionRow) {
  timelineRow.value = row;
  timelineOpen.value = true;
}

function goOrders(device?: string) {
  const query: Record<string, string> = {};
  if (device) query.deviceId = device;
  router.push({ path: '/orders', query });
}

async function onAction(key: string, row: SessionRow) {
  if (key === 'timeline') {
    openTimeline(row);
    return;
  }
  if (key === 'device' && row.deviceId) {
    router.push(`/devices/${encodeURIComponent(row.deviceId)}`);
    return;
  }
  if (key === 'copy') {
    try {
      await navigator.clipboard.writeText(row.sessionId);
      ElMessage.success('已复制会话 ID');
    } catch {
      ElMessage.error('复制失败');
    }
    return;
  }
  if (key === 'video' && row.deviceId) {
    router.push({ path: '/upload-queue', query: { deviceId: row.deviceId } });
    return;
  }
  if (key === 'cancel') {
    await cancelSession(row.sessionId);
  }
}

function syncRouteQuery() {
  const query: Record<string, string> = {};
  if (deviceId.value.trim()) query.deviceId = deviceId.value.trim();
  if (state.value) query.state = state.value;
  router.replace({ query });
}

async function load() {
  loading.value = true;
  try {
    const q = new URLSearchParams({ page: String(page.value - 1), size: String(size.value) });
    if (deviceId.value.trim()) q.set('deviceId', deviceId.value.trim());
    if (state.value) q.set('state', state.value);
    const data = await api.request<PageResult<SessionRow>>(`/api/v2/ops/admin/sessions?${q}`, 'GET');
    items.value = data.items;
    total.value = data.total;
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
  state.value = '';
  page.value = 1;
  syncRouteQuery();
  load();
}

function onSizeChange() {
  page.value = 1;
  load();
}

async function cancelSession(sessionId: string) {
  await ElMessageBox.confirm('确认取消该会话？', '取消会话');
  try {
    await api.request(`/api/v2/ops/admin/sessions/${encodeURIComponent(sessionId)}/cancel`, 'POST');
    ElMessage.success('已取消');
    load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败');
  }
}

function applyRouteQuery() {
  let changed = false;
  if (typeof route.query.deviceId === 'string' && route.query.deviceId !== deviceId.value) {
    deviceId.value = route.query.deviceId;
    changed = true;
  }
  if (typeof route.query.state === 'string' && route.query.state !== state.value) {
    state.value = route.query.state;
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
.page-card-head__meta { min-width: 0; }
.page-card-head__title { display: flex; flex-direction: column; gap: 4px; }
.title { font-weight: 600; font-size: 15px; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.4; }
.page-card-head__actions { display: flex; gap: 8px; flex-wrap: wrap; }
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
.mb12 { margin-bottom: 12px; }
.tl-detail { margin-top: 4px; font-size: 12px; color: var(--el-text-color-secondary); }
.tl-actions { margin-top: 16px; display: flex; gap: 8px; flex-wrap: wrap; }
</style>
