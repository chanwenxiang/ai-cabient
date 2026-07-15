<template>
  <el-card class="page-card">
    <template #header>
      <div style="display:flex;justify-content:space-between;align-items:center">
        <span>对账</span>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </template>
    <el-table v-loading="loading" :data="items" stripe>
      <el-table-column prop="reconId" label="对账ID" min-width="140" />
      <el-table-column prop="reconDate" label="日期" width="120" />
      <el-table-column label="渠道" width="100"><template #default="{ row }">{{ dictLabel('pay_channel', row.channel) }}</template></el-table-column>
      <el-table-column label="状态" width="120"><template #default="{ row }"><el-tag :type="dictTagType(row.status)">{{ dictLabel('reconciliation_status', row.status) }}</el-tag></template></el-table-column>
      <el-table-column label="差异笔数"><template #default="{ row }">{{ row.mismatchCount ?? 0 }}</template></el-table-column>
      <el-table-column label="创建时间" width="180"><template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template></el-table-column>
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';
import { dictLabel, dictTagType } from '@aicabinet/shared-dict';
import { formatDateTime } from '@aicabinet/shared-uni/format';

const loading = ref(false);
const items = ref<Record<string, unknown>[]>([]);

async function load() {
  loading.value = true;
  try {
    items.value = await api.request<Record<string, unknown>[]>('/api/v2/ops/admin/reconciliation?page=0&size=50', 'GET');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>
