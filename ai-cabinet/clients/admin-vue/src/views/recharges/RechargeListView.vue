<template>
  <el-card class="page-card">
    <template #header>
      <div style="display:flex;justify-content:space-between;align-items:center">
        <span>充值管理</span>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </template>
    <el-table v-loading="loading" :data="items" stripe>
      <el-table-column prop="rechargeId" label="充值单" min-width="140" />
      <el-table-column prop="userId" label="用户" />
      <el-table-column label="金额"><template #default="{ row }">¥{{ ((row.amountCents || 0) / 100).toFixed(2) }}</template></el-table-column>
      <el-table-column label="渠道"><template #default="{ row }">{{ dictLabel('pay_channel', row.channel) }}</template></el-table-column>
      <el-table-column prop="status" label="状态" width="100" />
      <el-table-column prop="createdAt" label="时间" width="180" />
    </el-table>
    <el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="prev,pager,next" style="margin-top:16px" @current-change="load" />
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { dictLabel } from '@aicabinet/shared-dict';
import { api } from '@/api/client';
import type { PageResult } from '@aicabinet/shared-types';

const loading = ref(false);
const page = ref(1);
const total = ref(0);
const items = ref<Record<string, unknown>[]>([]);

async function load() {
  loading.value = true;
  try {
    const q = new URLSearchParams({ page: String(page.value - 1), size: '20' });
    const data = await api.request<PageResult<Record<string, unknown>>>(`/api/v2/ops/admin/recharges?${q}`, 'GET');
    items.value = data.items;
    total.value = data.total;
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>
