<template>
  <el-card class="page-card">
    <template #header>
      <div class="head">
        <div>
          <strong>设备经营报表</strong>
          <span class="hint">按柜机汇总累计 / 今日订单、营收与会话</span>
        </div>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </template>
    <el-row :gutter="12" class="kpi-row">
      <el-col :xs="12" :sm="6"><el-statistic title="设备数" :value="rows.length" /></el-col>
      <el-col :xs="12" :sm="6"><el-statistic title="累计订单" :value="sum.orderTotal" /></el-col>
      <el-col :xs="12" :sm="6"><el-statistic title="累计营收" :value="sum.revenueTotal / 100" prefix="¥" :precision="2" /></el-col>
      <el-col :xs="12" :sm="6"><el-statistic title="今日营收" :value="sum.revenueToday / 100" prefix="¥" :precision="2" /></el-col>
    </el-row>
    <el-form inline class="filter-bar" @submit.prevent>
      <el-form-item label="关键词">
        <el-input v-model="keyword" clearable placeholder="设备编号 / 名称" style="width:220px" />
      </el-form-item>
    </el-form>
    <el-table v-loading="loading" :data="paged" stripe>
      <el-table-column prop="deviceId" label="设备编号" min-width="120" />
      <el-table-column prop="deviceName" label="设备名称" min-width="120" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.onlineStatus === 'ONLINE' ? 'success' : 'info'" size="small">
            {{ dictLabel('online_status', row.onlineStatus) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="orderTotal" label="累计订单" width="100" />
      <el-table-column label="累计营收" width="120">
        <template #default="{ row }">¥{{ (row.revenueTotalCents / 100).toFixed(2) }}</template>
      </el-table-column>
      <el-table-column prop="orderToday" label="今日订单" width="100" />
      <el-table-column label="今日营收" width="120">
        <template #default="{ row }">¥{{ (row.revenueTodayCents / 100).toFixed(2) }}</template>
      </el-table-column>
      <el-table-column prop="sessionTotal" label="累计会话" width="100" />
      <el-table-column prop="sessionActive" label="进行中会话" width="110" />
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
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { dictLabel } from '@aicabinet/shared-dict';
import { api } from '@/api/client';

interface DeviceReportRow {
  deviceId: string;
  deviceName?: string;
  onlineStatus?: string;
  orderTotal: number;
  revenueTotalCents: number;
  orderToday: number;
  revenueTodayCents: number;
  sessionTotal: number;
  sessionActive: number;
}

const loading = ref(false);
const rows = ref<DeviceReportRow[]>([]);
const keyword = ref('');
const page = ref(1);
const size = ref(20);

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase();
  if (!kw) return rows.value;
  return rows.value.filter((r) =>
    [r.deviceId, r.deviceName].some((v) => String(v || '').toLowerCase().includes(kw))
  );
});

const paged = computed(() => {
  const start = (page.value - 1) * size.value;
  return filtered.value.slice(start, start + size.value);
});

const sum = computed(() =>
  rows.value.reduce(
    (acc, r) => ({
      orderTotal: acc.orderTotal + (r.orderTotal || 0),
      revenueTotal: acc.revenueTotal + (r.revenueTotalCents || 0),
      revenueToday: acc.revenueToday + (r.revenueTodayCents || 0)
    }),
    { orderTotal: 0, revenueTotal: 0, revenueToday: 0 }
  )
);

async function load() {
  loading.value = true;
  try {
    rows.value = await api.request<DeviceReportRow[]>('/api/v2/ops/admin/reports/devices', 'GET');
    page.value = 1;
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<style scoped>
.head { display:flex; justify-content:space-between; align-items:center; gap:12px; flex-wrap:wrap; }
.hint { margin-left:10px; color:var(--layout-muted); font-size:12px; font-weight:400; }
.kpi-row { margin-bottom:12px; }
</style>
