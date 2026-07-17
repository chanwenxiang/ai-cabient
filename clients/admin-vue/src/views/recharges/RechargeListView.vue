<template>
  <el-card class="page-card" shadow="never">
    <template #header>
      <div class="card-head">
        <span class="title">充值管理</span>
        <div class="actions">
          <el-button @click="onExport">导出</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>
    <div class="table-scroll">
      <div class="table-scroll-inner" style="min-width: 900px">
        <el-table v-loading="loading" :data="items" stripe border>
          <el-table-column prop="orderId" label="充值单" min-width="140" show-overflow-tooltip />
          <el-table-column prop="userId" label="用户" width="100" />
          <el-table-column label="金额" width="120">
            <template #default="{ row }">¥{{ ((row.amountCents || 0) / 100).toFixed(2) }}</template>
          </el-table-column>
          <el-table-column label="渠道" width="100">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{ dictLabel('pay_channel', row.channel) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag :type="dictTagType(row.status)" size="small">
                {{ dictLabel('recharge_status', row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="时间" width="180">
            <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
          </el-table-column>
          <template #empty><el-empty description="暂无充值记录" /></template>
        </el-table>
      </div>
    </div>
    <el-pagination
      v-model:current-page="page"
      :page-size="20"
      :total="total"
      layout="total, prev, pager, next"
      style="margin-top: 16px"
      @current-change="load"
    />
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { dictLabel, dictTagType } from '@aicabinet/shared-dict';
import { api } from '@/api/client';
import { useListCsv } from '@/composables/useListCsv';
import type { PageResult } from '@aicabinet/shared-types';
import { formatDateTime } from '@aicabinet/shared-uni/format';

const loading = ref(false);
const page = ref(1);
const total = ref(0);
const items = ref<Record<string, unknown>[]>([]);

const { onExport } = useListCsv({
  filePrefix: '充值',
  headers: ['充值单', '用户', '金额', '渠道', '状态', '时间'],
  toRows: () =>
    items.value.map((row) => [
      row.orderId,
      row.userId,
      ((Number(row.amountCents) || 0) / 100).toFixed(2),
      dictLabel('pay_channel', String(row.channel || '')),
      dictLabel('recharge_status', String(row.status || '')),
      formatDateTime(String(row.createdAt || ''))
    ])
});

async function load() {
  loading.value = true;
  try {
    const q = new URLSearchParams({ page: String(page.value - 1), size: '20' });
    const data = await api.request<PageResult<Record<string, unknown>>>(
      `/api/v2/ops/admin/recharges?${q}`,
      'GET'
    );
    items.value = data.items || [];
    total.value = data.total || 0;
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<style scoped>
.card-head { display: flex; justify-content: space-between; align-items: center; }
.title { font-weight: 600; }
.actions { display: flex; gap: 8px; }
</style>
