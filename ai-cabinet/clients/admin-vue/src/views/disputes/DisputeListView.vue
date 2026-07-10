<template>
  <el-card class="page-card">
    <template #header>
      <div style="display:flex;justify-content:space-between;align-items:center">
        <span>争议审核</span>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </template>
    <el-form inline class="filter-bar">
      <el-form-item label="状态">
        <el-select v-model="status" style="width:120px" @change="load">
          <el-option label="待审核" value="OPEN" />
          <el-option label="已结案" value="RESOLVED" />
        </el-select>
      </el-form-item>
      <el-form-item><el-button type="primary" @click="load">查询</el-button></el-form-item>
    </el-form>
    <el-table v-loading="loading" :data="items" stripe>
      <el-table-column prop="ticketId" label="工单" />
      <el-table-column prop="deviceId" label="设备" />
      <el-table-column prop="sessionId" label="会话" />
      <el-table-column label="状态"><template #default="{ row }">{{ dictLabel('dispute_status', row.status) }}</template></el-table-column>
      <el-table-column prop="reason" label="原因" show-overflow-tooltip />
      <el-table-column prop="createdAt" label="创建时间" />
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { dictLabel } from '@aicabinet/shared-dict';
import { api } from '@/api/client';
import type { DisputeSummary, PageResult } from '@aicabinet/shared-types';

const loading = ref(false);
const status = ref('OPEN');
const items = ref<DisputeSummary[]>([]);

async function load() {
  loading.value = true;
  try {
    const q = new URLSearchParams({ page: '0', size: '20', status: status.value || 'OPEN' });
    const data = await api.request<PageResult<DisputeSummary>>(`/api/v2/ops/disputes?${q}`, 'GET');
    items.value = data.items;
    ElMessage.success('已刷新');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>
