<template>
  <el-card class="page-card" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">消息记录</span>
            <span class="hint">站内信发送记录（订单支付 / 充值到账 / 补货任务 / 售后）</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-table
      v-loading="loading"
      :data="list"
      stripe
      border
      row-key="id"
      empty-text=" "
      class="report-table"
    >
      <template #empty><el-empty v-if="!loading" description="暂无消息记录" /></template>
      <el-table-column prop="id" label="ID" width="80" align="center" class-name="col-text" />
      <el-table-column label="时间" width="150" align="center">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="受众" width="90" align="center">
        <template #default="{ row }">
          <el-tag :type="row.audience === 'CONSUMER' ? 'primary' : 'warning'">{{
            row.audience === 'CONSUMER' ? '消费者' : '商户'
          }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="title" label="标题" min-width="150" align="center" />
      <el-table-column prop="body" label="内容" min-width="240" show-overflow-tooltip />
      <el-table-column prop="bizType" label="业务" width="110" align="center" />
      <el-table-column
        prop="bizId"
        label="关联单号"
        width="150"
        align="center"
        class-name="col-text"
      />
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import { api } from '@/api/client';

type NotificationRow = {
  id: number;
  title: string;
  body: string;
  audience?: string;
  bizType?: string;
  bizId?: string;
  createdAt: string;
};

const loading = ref(false);
const list = ref<NotificationRow[]>([]);

onMounted(load);

async function load() {
  loading.value = true;
  try {
    list.value = await api.request<NotificationRow[]>(
      '/api/v2/ops/admin/growth/notifications?limit=200'
    );
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

function formatTime(t?: string) {
  if (!t) return '—';
  const d = new Date(t);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(
    d.getMinutes()
  )}`;
}
</script>
