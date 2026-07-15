<template>
  <el-card class="page-card">
    <template #header>
      <div style="display:flex;justify-content:space-between;align-items:center">
        <span>风控</span>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </template>
    <el-tabs v-model="tab">
      <el-tab-pane label="风险事件" name="events">
        <el-table :data="events" stripe>
          <el-table-column prop="eventId" label="事件ID" min-width="140" />
          <el-table-column prop="userId" label="用户" />
          <el-table-column prop="eventType" label="类型" />
          <el-table-column prop="severity" label="级别" width="100" />
          <el-table-column label="时间" width="180"><template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template></el-table-column>
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="黑名单" name="blacklist">
        <el-table :data="blacklist" stripe>
          <el-table-column prop="userId" label="用户ID" />
          <el-table-column prop="reason" label="原因" />
          <el-table-column label="加入时间" width="180"><template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template></el-table-column>
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
import { formatDateTime } from '@aicabinet/shared-uni/format';

const loading = ref(false);
const tab = ref('events');
const events = ref<Record<string, unknown>[]>([]);
const blacklist = ref<Record<string, unknown>[]>([]);

async function load() {
  loading.value = true;
  try {
    const [ev, bl] = await Promise.all([
      api.request<{ items?: Record<string, unknown>[] }>('/api/v2/ops/admin/risk/events?page=0&size=20', 'GET'),
      api.request<Record<string, unknown>[]>('/api/v2/ops/admin/risk/blacklist', 'GET')
    ]);
    events.value = ev?.items || (Array.isArray(ev) ? ev : []);
    blacklist.value = bl || [];
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>
