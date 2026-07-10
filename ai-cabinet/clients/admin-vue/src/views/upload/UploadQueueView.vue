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
    <el-table v-loading="loading" :data="items" stripe>
      <el-table-column prop="sessionId" label="会话" min-width="160"><template #default="{ row }"><code>{{ row.sessionId }}</code></template></el-table-column>
      <el-table-column prop="userId" label="用户" />
      <el-table-column prop="deviceId" label="设备" />
      <el-table-column label="上传状态"><template #default="{ row }">{{ dictLabel('upload_status', row.uploadStatus) }}</template></el-table-column>
      <el-table-column prop="closeTime" label="关门时间" width="180" />
      <el-table-column prop="updatedAt" label="更新时间" width="180" />
    </el-table>
    <el-pagination v-model:current-page="page" :page-size="size" :total="total" layout="prev,pager,next" style="margin-top:16px" @current-change="load" />
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { dictLabel } from '@aicabinet/shared-dict';
import { api } from '@/api/client';
import type { PageResult } from '@aicabinet/shared-types';

interface SessionRow {
  sessionId: string;
  userId?: string;
  deviceId?: string;
  uploadStatus?: string;
  closeTime?: string;
  updatedAt?: string;
}

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

onMounted(load);
</script>
