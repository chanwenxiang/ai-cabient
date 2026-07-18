<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">录像上传队列</span>
            <span class="hint">设备自动上传状态；非人工上传入口</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button v-hasPermi="['ops:upload:export']" @click="onExport">{{ exportButtonLabel }}</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-alert
      class="upload-hint"
      type="info"
      :closable="false"
      show-icon
      title="本页为设备录像上传状态队列，不是人工上传入口"
      description="购物会话关门后，设备/边缘端会自动上传录像到对象存储；此处仅查询待上传、上传中、失败会话，并可预览已上传文件。"
    />

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="search">
      <el-form-item label="设备">
        <el-input
          v-model="deviceId"
          clearable
          placeholder="设备编号"
          style="width: 180px"
          @keyup.enter="search"
          @clear="search"
        />
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
          table-layout="auto"
          row-key="sessionId"
          @selection-change="onSelectionChange"
        >
          <template #empty>
            <el-empty description="暂无待上传录像（队列为空表示当前没有滞留上传任务）" :image-size="88" />
          </template>
          <el-table-column type="selection" width="48" align="center" />
          <el-table-column label="会话" min-width="168" class-name="col-text">
            <template #default="{ row }">
              <button type="button" class="link-cell" @click="goSession(row.sessionId)">
                <span class="cell-id">{{ row.sessionId }}</span>
              </button>
            </template>
          </el-table-column>
          <el-table-column label="用户" width="100" class-name="col-text">
            <template #default="{ row }">{{ row.userId || '-' }}</template>
          </el-table-column>
          <el-table-column label="设备" min-width="120" class-name="col-text">
            <template #default="{ row }">
              <button
                v-if="row.deviceId"
                type="button"
                class="link-cell"
                @click="router.push(`/devices/${encodeURIComponent(row.deviceId)}`)"
              >
                {{ row.deviceId }}
              </button>
              <span v-else class="muted">-</span>
            </template>
          </el-table-column>
          <el-table-column label="对象路径" min-width="200" class-name="col-text" show-overflow-tooltip>
            <template #default="{ row }">
              <span v-if="objectKey(row.videoUri)" class="cell-id">{{ objectKey(row.videoUri) }}</span>
              <span v-else class="muted">-</span>
            </template>
          </el-table-column>
          <el-table-column label="上传状态" width="110" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="dictTagType(String(row.uploadStatus || ''))">
                {{ dictLabel('upload_status', row.uploadStatus) || row.uploadStatus || '-' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="预览" width="80" align="center">
            <template #default="{ row }">
              <el-link v-if="row.videoUri" type="primary" @click.prevent="playVideo(row.sessionId)">播放</el-link>
              <span v-else class="muted">-</span>
            </template>
          </el-table-column>
          <el-table-column label="关门时间" width="168" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-datetime">{{ formatDateTime(row.closeTime) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="更新时间" width="168" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-datetime">{{ formatDateTime(row.updatedAt) }}</span>
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
        layout="total, sizes, prev, pager, next"
        background
        @current-change="load"
        @size-change="onSizeChange"
      />
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { onActivated, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { dictLabel, dictTagType } from '@aicabinet/shared-dict';
import { api } from '@/api/client';
import { useListCsv } from '@/composables/useListCsv';
import { useTableSelection } from '@/composables/useTableSelection';
import type { PageResult } from '@aicabinet/shared-types';
import { formatDateTime } from '@aicabinet/shared-uni/format';

interface SessionRow {
  sessionId: string;
  userId?: string;
  deviceId?: string;
  uploadStatus?: string;
  videoUri?: string;
  videoPreviewUrl?: string;
  closeTime?: string;
  updatedAt?: string;
}

const route = useRoute();
const router = useRouter();
const loading = ref(false);
const deviceId = ref('');
const page = ref(1);
const size = ref(20);
const total = ref(0);
const items = ref<SessionRow[]>([]);

const { onSelectionChange, pickSelected, exportButtonLabel, clearSelection } = useTableSelection<SessionRow>(
  (row) => row.sessionId
);

const { onExport } = useListCsv({
  filePrefix: '录像上传队列',
  headers: ['会话', '用户', '设备', '上传状态', '关门时间', '更新时间'],
  toRows: () =>
    pickSelected(items.value).map((row) => [
      row.sessionId,
      row.userId ?? '',
      row.deviceId ?? '',
      dictLabel('upload_status', row.uploadStatus) || row.uploadStatus || '',
      formatDateTime(row.closeTime),
      formatDateTime(row.updatedAt)
    ])
});

function syncRouteQuery() {
  const query: Record<string, string> = {};
  if (deviceId.value.trim()) query.deviceId = deviceId.value.trim();
  router.replace({ query });
}

function applyRouteQuery() {
  if (typeof route.query.deviceId === 'string' && route.query.deviceId !== deviceId.value) {
    deviceId.value = route.query.deviceId;
    return true;
  }
  return false;
}

async function load() {
  loading.value = true;
  try {
    const q = new URLSearchParams({
      page: String(page.value - 1),
      size: String(size.value),
      state: 'WAITING_UPLOAD'
    });
    if (deviceId.value.trim()) q.set('deviceId', deviceId.value.trim());
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
  page.value = 1;
  syncRouteQuery();
  load();
}

function onSizeChange() {
  page.value = 1;
  load();
}

function goSession(sessionId: string) {
  router.push({ path: '/sessions', query: { sessionId } });
}

function objectKey(videoUri?: string) {
  if (!videoUri) return '';
  const match = videoUri.match(/^(?:minio|oss|s3):\/\/[^/]+\/(.+)$/);
  return match?.[1] ?? '';
}

async function playVideo(sessionId: string) {
  const token = localStorage.getItem('admin_token');
  try {
    const res = await fetch(
      `${window.location.origin}/api/v2/ops/admin/sessions/${encodeURIComponent(sessionId)}/video`,
      { headers: token ? { Authorization: `Bearer ${token}` } : {} }
    );
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const blob = await res.blob();
    const url = URL.createObjectURL(blob);
    window.open(url, '_blank');
    window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '播放失败');
  }
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
.title { font-size: 15px; font-weight: 600; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.4; }
.page-card-head__actions { display: flex; gap: 8px; }
.upload-hint { margin-bottom: 12px; }
.link-cell {
  appearance: none;
  border: 0;
  padding: 0;
  margin: 0;
  background: transparent;
  color: var(--el-color-primary);
  cursor: pointer;
  font: inherit;
  text-align: left;
}
.link-cell:hover { text-decoration: underline; }
.muted { color: var(--el-text-color-placeholder); }
</style>
