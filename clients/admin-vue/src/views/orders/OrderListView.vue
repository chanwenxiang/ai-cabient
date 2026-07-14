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
      <el-form-item label="订单状态">
        <el-select v-model="status" clearable placeholder="全部" style="width:140px">
          <el-option v-for="o in statusOptions" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </el-form-item>
    </el-form>
    <el-table v-loading="loading" :data="items" stripe>
    <template #empty><el-empty description="暂无订单" /></template>
     <el-table-column label="订单号" min-width="150">
        <template #default="{ row }"><span class="cell-id">{{ row.orderId }}</span></template>
      </el-table-column>
      <el-table-column prop="sessionId" label="会话" min-width="140" show-overflow-tooltip />
      <el-table-column prop="userId" label="用户ID" width="110" />
      <el-table-column prop="deviceId" label="设备" width="120" />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="orderStatusType(row.status)">{{ dictLabel('order_status', row.status) || row.status || '-' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="lineCount" label="商品行" width="90" />
      <el-table-column label="金额" width="110">
        <template #default="{ row }">¥{{ ((row.totalAmountCents || 0) / 100).toFixed(2) }}</template>
      </el-table-column>
      <el-table-column label="创建时间" width="180">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
    </el-table>
    <div class="page-pager">
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        @current-change="load"
        @size-change="onSizeChange"
      />
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { dictLabel, dictOptions } from '@aicabinet/shared-dict';
import { api } from '@/api/client';
import type { OrderSummary, PageResult } from '@aicabinet/shared-types';
import { formatDateTime } from '@aicabinet/shared-uni/format';

const loading = ref(false);
const deviceId = ref('');
const status = ref('');
const items = ref<OrderSummary[]>([]);
const page = ref(1);
const size = ref(20);
const total = ref(0);
const statusOptions = dictOptions('order_status');

function orderStatusType(s?: string) {
  if (s === 'PAID' || s === 'COMPLETED') return 'success';
  if (s === 'CANCELLED' || s === 'REFUNDED') return 'info';
  if (s === 'FAILED') return 'danger';
  return 'warning';
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
  status.value = '';
  search();
}
function onSizeChange() {
  page.value = 1;
  load();
}

onMounted(load);
</script>
