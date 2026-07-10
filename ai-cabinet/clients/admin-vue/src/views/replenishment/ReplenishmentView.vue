<template>
  <el-card class="page-card">
    <template #header>
      <div style="display:flex;justify-content:space-between;align-items:center">
        <span>补货管理</span>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </template>
    <el-tabs v-model="tab">
      <el-tab-pane label="补货路线" name="routes">
        <el-table :data="routes" stripe>
          <el-table-column prop="routeId" label="路线ID" />
          <el-table-column prop="routeName" label="名称" />
          <el-table-column prop="deviceCount" label="设备数" width="100" />
          <el-table-column prop="status" label="状态" width="100" />
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="商户要货" name="requests">
        <el-table :data="requests" stripe>
          <el-table-column prop="requestId" label="要货单" />
          <el-table-column prop="merchantName" label="商户" />
          <el-table-column prop="deviceId" label="设备" />
          <el-table-column prop="status" label="状态" />
          <el-table-column prop="createdAt" label="提交时间" width="180" />
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';

const loading = ref(false);
const tab = ref('routes');
const routes = ref<Record<string, unknown>[]>([]);
const requests = ref<Record<string, unknown>[]>([]);

async function load() {
  loading.value = true;
  try {
    const [r, req] = await Promise.all([
      api.request<Record<string, unknown>[]>('/api/v2/ops/admin/replenishment/routes', 'GET'),
      api.request<Record<string, unknown>[]>('/api/v2/ops/admin/replenishment/requests?status=SUBMITTED', 'GET').catch(() => [])
    ]);
    routes.value = r || [];
    requests.value = req || [];
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>
