<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">设备运维</span>
            <span class="hint">与交易异常分流：离线 / 禁售 / 锁机等设备侧事件</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-form inline class="filter-bar filter-bar--compact">
      <el-form-item label="类型">
        <el-select v-model="eventType" clearable placeholder="全部" style="width: 140px" @change="load">
          <el-option label="离线" value="OFFLINE" />
          <el-option label="无销售" value="NO_SALES" />
          <el-option label="开锁" value="UNLOCK" />
          <el-option label="故障/锁机" value="FAULT" />
          <el-option label="货道巡检" value="AISLE_AUDIT" />
          <el-option label="主板" value="MAINBOARD" />
        </el-select>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="items" stripe border class="report-table">
      <template #empty><el-empty description="暂无运维事件" /></template>
      <el-table-column prop="eventType" label="类型" width="120" />
      <el-table-column label="级别" width="90" align="center">
        <template #default="{ row }">
          <el-tag :type="row.severity === 'CRITICAL' ? 'danger' : row.severity === 'WARN' ? 'warning' : 'info'" size="small">
            {{ row.severity }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="设备" min-width="180">
        <template #default="{ row }">
          <div>{{ row.deviceName || row.deviceId }}</div>
          <small>{{ row.deviceId }}</small>
        </template>
      </el-table-column>
      <el-table-column prop="title" label="标题" min-width="140" />
      <el-table-column prop="detail" label="详情" min-width="200" show-overflow-tooltip />
      <el-table-column label="时间" width="170">
        <template #default="{ row }">{{ String(row.createdAt || '').replace('T', ' ').slice(0, 19) }}</template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';

const loading = ref(false);
const eventType = ref('');
const items = ref<any[]>([]);

async function load() {
  loading.value = true;
  try {
    const q = new URLSearchParams({ page: '0', size: '50' });
    if (eventType.value) q.set('eventType', eventType.value);
    const data = await api.request<{ items: any[] }>(`/api/v2/ops/admin/device-ops/events?${q}`, 'GET');
    items.value = data.items || [];
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<style scoped>
small { color: var(--el-text-color-secondary); }
</style>
