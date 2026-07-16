<template>
  <el-card class="page-card">
    <template #header>
      <div style="display:flex;justify-content:space-between;align-items:center">
        <span>录像上传队列</span>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </template>
    <el-form inline @submit.prevent="search">
      <el-form-item label="设备"><el-input v-model="deviceId" clearable /></el-form-item>
      <el-form-item><el-button type="primary" @click="search">查询</el-button></el-form-item>
    </el-form>
    <div class="table-scroll">
      <div class="table-scroll-inner" style="min-width: 1100px">
        <el-table v-loading="loading" :data="items" stripe border>
          <el-table-column prop="sessionId" label="会话" min-width="160">
            <template #default="{ row }"><span class="cell-id">{{ row.sessionId }}</span></template>
          </el-table-column>
          <el-table-column prop="userId" label="用户" width="100" />
          <el-table-column prop="deviceId" label="设备" width="120" />
          <el-table-column label="对象路径" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">
              <span v-if="objectKey(row.videoUri)" class="cell-id">{{ objectKey(row.videoUri) }}</span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="上传状态" width="110">
            <template #default="{ row }">{{ dictLabel('upload_status', row.uploadStatus) }}</template>
          </el-table-column>
          <el-table-column label="预览" width="80" align="center">
            <template #default="{ row }">
              <el-link v-if="row.videoUri" type="primary" @click.prevent="playVideo(row.sessionId)">播放</el-link>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="关门时间" width="170">
            <template #default="{ row }">{{ formatDateTime(row.closeTime) }}</template>
          </el-table-column>
          <el-table-column label="更新时间" width="170">
            <template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template>
          </el-table-column>
        </el-table>
      </div>
    </div>
    <el-pagination v-model:current-page="page" :page-size="size" :total="total" layout="prev,pager,next" style="margin-top:16px" @current-change="load" />
  </el-card>
</template>

<script setup lang="ts">
import { onActivated, onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { dictLabel } from '@aicabinet/shared-dict';
import { api } from '@/api/client';
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
const loading = ref(false);
const deviceId = ref('');
const page = ref(1);
const size = 20;
const total = ref(0);
const items = ref<SessionRow[]>([]);

async function load() {
  loading.value = true;
  try {
    const q = new URLSearchParams({ page: String(page.value - 1), size: String(size), state: 'WAITING_UPLOAD' });
    if (deviceId.value.trim()) q.set('deviceId', deviceId.value.trim());
    const data = await api.request<PageResult<SessionRow>>(`/api/v2/ops/admin/sessions?${q}`, 'GET');
    items.value = data.items;
    total.value = data.total;
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

function objectKey(videoUri?: string) {
  if (!videoUri) return '';
  const match = videoUri.match(/^(?:minio|oss|s3):\/\/[^/]+\/(.+)$/);
  return match?.[1] ?? '';
}

async function playVideo(sessionId: string) {
  const token = localStorage.getItem('admin_token');
  try {
    const res = await fetch(`${window.location.origin}/api/v2/ops/admin/sessions/${encodeURIComponent(sessionId)}/video`, {
      headers: token ? { Authorization: `Bearer ${token}` } : {}
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const blob = await res.blob();
    window.open(URL.createObjectURL(blob), '_blank');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '播放失败');
  }
}

onMounted(() => {
  if (typeof route.query.deviceId === 'string') deviceId.value = route.query.deviceId;
  load();
});
onActivated(() => {
  if (typeof route.query.deviceId === 'string' && route.query.deviceId !== deviceId.value) {
    deviceId.value = route.query.deviceId;
    load();
  }
});
</script>

<style scoped>
.object-key {
  font-size: 12px;
  word-break: break-all;
}
.muted {
  color: var(--el-text-color-placeholder);
}
</style>
