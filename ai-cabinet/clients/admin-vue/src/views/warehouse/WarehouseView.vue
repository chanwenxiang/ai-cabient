<template>
  <el-card class="page-card">
    <template #header>
      <div style="display:flex;justify-content:space-between;align-items:center">
        <span>仓库</span>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </template>
    <el-tabs v-model="tab">
      <el-tab-pane label="仓库列表" name="list">
        <el-table :data="warehouses" stripe>
          <el-table-column prop="warehouseId" label="仓库ID" />
          <el-table-column prop="warehouseName" label="名称" />
          <el-table-column prop="address" label="地址" />
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="在途" name="transit">
        <el-table :data="inTransit" stripe>
          <el-table-column prop="outboundId" label="出库单" />
          <el-table-column prop="deviceId" label="目标设备" />
          <el-table-column prop="status" label="状态" />
          <el-table-column prop="shippedAt" label="发货时间" width="180" />
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="库存" name="inventory">
        <el-table :data="inventory" stripe>
          <el-table-column prop="skuId" label="SKU" />
          <el-table-column prop="skuName" label="名称" />
          <el-table-column prop="qtyOnHand" label="库存" width="100" />
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
const tab = ref('list');
const warehouses = ref<Record<string, unknown>[]>([]);
const inTransit = ref<Record<string, unknown>[]>([]);
const inventory = ref<Record<string, unknown>[]>([]);

async function load() {
  loading.value = true;
  try {
    const [list, transit, inv] = await Promise.all([
      api.request<Record<string, unknown>[]>('/api/v2/ops/admin/warehouse/list', 'GET'),
      api.request<Record<string, unknown>[]>('/api/v2/ops/admin/warehouse/in-transit', 'GET').catch(() => []),
      api.request<Record<string, unknown>[]>('/api/v2/ops/admin/warehouse/inventory', 'GET').catch(() => [])
    ]);
    warehouses.value = list || [];
    inTransit.value = transit || [];
    inventory.value = inv || [];
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>
