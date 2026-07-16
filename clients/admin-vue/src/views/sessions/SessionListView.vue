<template>
  <el-card class="page-card">
    <template #header>
      <div class="page-card-head">
        <span class="title">开门记录</span>
        <div class="header-actions">
          <el-button @click="onExport">导出</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>
    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="search">
      <el-form-item label="设备">
        <el-input v-model="deviceId" clearable placeholder="留空=全部" style="width: 160px" />
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="state" clearable placeholder="全部" style="width: 140px">
          <el-option v-for="o in stateOptions" :key="o.value" :label="o.label" :value="o.value" />
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
          row-key="sessionId"
          @selection-change="onSelectionChange"
        >
          <el-table-column type="selection" width="48" align="center" />
          <el-table-column prop="sessionId" label="会话ID" width="148">
            <template #default="{ row }"><span class="cell-id">{{ row.sessionId }}</span></template>
          </el-table-column>
          <el-table-column prop="userId" label="用户" width="88" />
          <el-table-column prop="deviceId" label="设备" width="100" />
          <el-table-column prop="orderId" label="订单" width="120" show-overflow-tooltip />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">{{ dictLabel('session_state', row.state) }}</template>
          </el-table-column>
          <el-table-column prop="failureReason" label="失败原因" width="120" show-overflow-tooltip />
          <el-table-column label="创建时间" width="160">
            <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="更新时间" width="160">
            <template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="132" class-name="col-action" align="center">
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
        @current-change="load"
        @size-change="onSizeChange"
      />
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { onActivated, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { CircleClose, CopyDocument, Refresh, View, VideoCamera } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { dictLabel, dictOptions } from '@aicabinet/shared-dict';
import { api } from '@/api/client';
import TableActions, { type TableAction } from '@/components/TableActions.vue';
import { useListCsv } from '@/composables/useListCsv';
import type { PageResult } from '@aicabinet/shared-types';
import { formatDateTime } from '@aicabinet/shared-uni/format';

interface SessionRow {
  sessionId: string;
  userId?: string;
  deviceId?: string;
  state?: string;
  orderId?: string;
  failureReason?: string;
  createdAt?: string;
  updatedAt?: string;
}

const route = useRoute();
const router = useRouter();
const loading = ref(false);
const deviceId = ref('');
const state = ref('');
const page = ref(1);
const size = ref(20);
const total = ref(0);
const items = ref<SessionRow[]>([]);
const selected = ref<SessionRow[]>([]);
const stateOptions = dictOptions('session_state');

const { onExport } = useListCsv({
  filePrefix: '开门记录',
  headers: ['会话ID', '用户', '设备', '订单', '状态', '失败原因', '创建时间', '更新时间'],
  toRows: () =>
    items.value.map((row) => [
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

function sessionActions(row: SessionRow): TableAction[] {
  const acts: TableAction[] = [
    { key: 'device', label: '看设备', icon: View, type: 'primary' },
    { key: 'copy', label: '复制会话ID', icon: CopyDocument, type: 'info' }
  ];
  if (row.deviceId) {
    acts.push({ key: 'video', label: '录像队列', icon: VideoCamera, type: 'warning', overflow: true });
  }
  if (canCancel(row.state)) {
    acts.push({ key: 'cancel', label: '取消会话', icon: CircleClose, type: 'danger', overflow: true });
  }
  return acts;
}

function onSelectionChange(rows: SessionRow[]) {
  selected.value = rows;
}

async function onAction(key: string, row: SessionRow) {
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

async function load() {
  loading.value = true;
  try {
    const q = new URLSearchParams({ page: String(page.value - 1), size: String(size.value) });
    if (deviceId.value.trim()) q.set('deviceId', deviceId.value.trim());
    if (state.value) q.set('state', state.value);
    const data = await api.request<PageResult<SessionRow>>(`/api/v2/ops/admin/sessions?${q}`, 'GET');
    items.value = data.items;
    total.value = data.total;
    selected.value = [];
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

function search() {
  page.value = 1;
  load();
}

function reset() {
  deviceId.value = '';
  state.value = '';
  page.value = 1;
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

onMounted(() => {
  if (typeof route.query.deviceId === 'string') deviceId.value = route.query.deviceId;
  if (typeof route.query.state === 'string') state.value = route.query.state;
  load();
});
onActivated(() => {
  let changed = false;
  if (typeof route.query.deviceId === 'string' && route.query.deviceId !== deviceId.value) {
    deviceId.value = route.query.deviceId;
    changed = true;
  }
  if (typeof route.query.state === 'string' && route.query.state !== state.value) {
    state.value = route.query.state;
    changed = true;
  }
  if (changed) {
    page.value = 1;
    load();
  }
});
</script>

<style scoped>
.page-card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.title { font-weight: 600; font-size: 15px; }
.header-actions { display: flex; gap: 8px; flex-wrap: wrap; }
</style>
