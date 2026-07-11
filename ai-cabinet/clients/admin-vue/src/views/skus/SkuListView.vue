<template>
  <el-card class="page-card">
    <template #header>
      <div style="display:flex;justify-content:space-between;align-items:center">
        <span>商品管理</span>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </template>
    <el-table v-loading="loading" :data="items" stripe>
      <el-table-column prop="skuId" label="SKU" min-width="120"><template #default="{ row }"><code>{{ row.skuId }}</code></template></el-table-column>
      <el-table-column prop="skuName" label="名称" />
      <el-table-column label="基准价"><template #default="{ row }">¥{{ ((row.priceCents || 0) / 100).toFixed(2) }}</template></el-table-column>
      <el-table-column prop="category" label="分类" />
      <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="dictTagType(row.status)">{{ dictLabel('sku_status', row.status) }}</el-tag></template></el-table-column>
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';
import type { SkuCatalog } from '@aicabinet/shared-types';
import { dictLabel, dictTagType } from '@aicabinet/shared-dict';

const loading = ref(false);
const items = ref<SkuCatalog[]>([]);

async function load() {
  loading.value = true;
  try {
    items.value = await api.request<SkuCatalog[]>('/api/v2/ops/admin/skus', 'GET');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>
