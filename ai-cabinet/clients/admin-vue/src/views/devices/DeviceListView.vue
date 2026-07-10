<template>
  <el-card class="page-card">
    <template #header>
      <div style="display:flex;justify-content:space-between;align-items:center">
        <span>设备管理</span>
        <el-button type="primary" :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </template>
    <el-form inline class="filter-bar" @submit.prevent="load">
      <el-form-item label="关键词">
        <el-input v-model="keyword" placeholder="编号 / 名称 / 商户" clearable style="width:220px" @keyup.enter="load" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="load">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </el-form-item>
    </el-form>
    <el-table v-loading="loading" :data="filtered" stripe>
      <el-table-column label="设备编号" min-width="120">
        <template #default="{ row }"><span class="cell-id">{{ row.deviceId }}</span></template>
      </el-table-column>
      <el-table-column prop="deviceName" label="名称" />
      <el-table-column label="类型">
        <template #default="{ row }">{{ dictLabel('device_type', row.deviceType) }}</template>
      </el-table-column>
      <el-table-column label="状态">
        <template #default="{ row }">
          <el-tag :type="row.onlineStatus === 'ONLINE' ? 'success' : 'info'">{{ dictLabel('online_status', row.onlineStatus) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="merchantName" label="商户" />
      <el-table-column label="操作" width="160">
        <template #default="{ row }">
          <el-button link type="primary" @click="router.push(`/devices/${row.deviceId}`)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { dictLabel } from '@aicabinet/shared-dict';
import { api } from '@/api/client';
import type { DeviceInfo } from '@aicabinet/shared-types';

const router = useRouter();
const loading = ref(false);
const keyword = ref('');
const devices = ref<DeviceInfo[]>([]);

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase();
  if (!kw) return devices.value;
  return devices.value.filter((d) =>
    [d.deviceId, d.deviceName, d.merchantName, d.merchantId].some((v) => String(v || '').toLowerCase().includes(kw))
  );
});

async function load() {
  loading.value = true;
  try {
    devices.value = await api.request<DeviceInfo[]>('/api/v2/ops/admin/devices', 'GET');
    ElMessage.success('已刷新');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
    throw e;
  } finally {
    loading.value = false;
  }
}

function reset() {
  keyword.value = '';
  load();
}

onMounted(load);
</script>
