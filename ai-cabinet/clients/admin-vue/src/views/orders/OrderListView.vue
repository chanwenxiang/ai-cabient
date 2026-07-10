<template>
  <el-card class="page-card">
    <template #header>
      <div style="display:flex;justify-content:space-between;align-items:center">
        <span>订单管理</span>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </template>
    <el-form inline class="filter-bar" @submit.prevent="search">
      <el-form-item label="设备编号"><el-input v-model="deviceId" clearable placeholder="留空=全部" /></el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </el-form-item>
    </el-form>
    <el-table v-loading="loading" :data="items" stripe>
      <el-table-column label="订单号"><template #default="{ row }"><span class="cell-id">{{ row.orderId }}</span></template></el-table-column>
      <el-table-column prop="sessionId" label="会话" />
      <el-table-column prop="deviceId" label="设备" />
      <el-table-column label="金额"><template #default="{ row }">¥{{ (row.totalAmountCents / 100).toFixed(2) }}</template></el-table-column>
      <el-table-column prop="createdAt" label="时间" />
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';
import type { OrderSummary, PageResult } from '@aicabinet/shared-types';

const loading = ref(false);
const deviceId = ref('');
const items = ref<OrderSummary[]>([]);

async function load() {
  loading.value = true;
  try {
    const q = new URLSearchParams({ page: '0', size: '20' });
    if (deviceId.value.trim()) q.set('deviceId', deviceId.value.trim());
    const data = await api.request<PageResult<OrderSummary>>(`/api/v2/ops/admin/orders?${q}`, 'GET');
    items.value = data.items;
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

function search() { load(); }
function reset() { deviceId.value = ''; load(); }

onMounted(load);
</script>
