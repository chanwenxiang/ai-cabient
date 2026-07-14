<template>
  <el-card class="page-card">
    <template #header>
      <div style="display:flex;justify-content:space-between;align-items:center">
        <span>设备管理</span>
        <el-button type="primary" :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </template>
    <el-form inline class="filter-bar" @submit.prevent="search">
      <el-form-item label="关键词">
        <el-input v-model="keyword" placeholder="编号 / 名称 / 商户" clearable style="width:220px" @keyup.enter="search" />
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="onlineFilter" clearable placeholder="全部" style="width:120px">
          <el-option label="在线" value="ONLINE" />
          <el-option label="离线" value="OFFLINE" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </el-form-item>
    </el-form>
    <el-table v-loading="loading" :data="paged" stripe>
    <template #empty><el-empty description="暂无设备" /></template>
     <el-table-column label="设备编号" min-width="120">
        <template #default="{ row }"><span class="cell-id">{{ row.deviceId }}</span></template>
      </el-table-column>
      <el-table-column prop="deviceName" label="名称" min-width="120" />
      <el-table-column label="类型" width="120">
        <template #default="{ row }">{{ dictLabel('device_type', row.deviceType) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.onlineStatus === 'ONLINE' ? 'success' : 'info'">{{ dictLabel('online_status', row.onlineStatus) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="merchantId" label="商户编号" width="120" />
      <el-table-column prop="merchantName" label="商户" min-width="120" />
      <el-table-column prop="activeSessionId" label="当前会话" min-width="140" show-overflow-tooltip />
      <el-table-column label="会话状态" width="110">
        <template #default="{ row }">{{ row.activeSessionState ? dictLabel('session_state', row.activeSessionState) : '-' }}</template>
      </el-table-column>
      <el-table-column label="更新时间" width="180">
        <template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="88" fixed="right" align="center">
        <template #default="{ row }">
          <TableActions
            :actions="[{ key: 'detail', label: '详情', icon: View, type: 'primary' }]"
            @action="() => router.push(`/devices/${row.deviceId}`)"
          />
        </template>
      </el-table-column>
    </el-table>
    <div class="page-pager">
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="filtered.length"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
      />
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { Refresh, View } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { dictLabel } from '@aicabinet/shared-dict';
import { api } from '@/api/client';
import TableActions from '@/components/TableActions.vue';
import type { DeviceInfo } from '@aicabinet/shared-types';
import { formatDateTime } from '@aicabinet/shared-uni/format';

const router = useRouter();
const loading = ref(false);
const keyword = ref('');
const onlineFilter = ref('');
const devices = ref<DeviceInfo[]>([]);
const page = ref(1);
const size = ref(20);

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase();
  return devices.value.filter((d) => {
    if (onlineFilter.value && d.onlineStatus !== onlineFilter.value) return false;
    if (!kw) return true;
    return [d.deviceId, d.deviceName, d.merchantName, d.merchantId].some((v) =>
      String(v || '').toLowerCase().includes(kw)
    );
  });
});

const paged = computed(() => {
  const start = (page.value - 1) * size.value;
  return filtered.value.slice(start, start + size.value);
});

async function load() {
  loading.value = true;
  try {
    devices.value = await api.request<DeviceInfo[]>('/api/v2/ops/admin/devices', 'GET');
    ElMessage.success('已刷新');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

function search() {
  page.value = 1;
}
function reset() {
  keyword.value = '';
  onlineFilter.value = '';
  page.value = 1;
  load();
}

onMounted(load);
</script>
