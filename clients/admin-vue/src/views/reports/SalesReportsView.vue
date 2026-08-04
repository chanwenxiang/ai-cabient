<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">销售报表</span>
            <span class="hint">商品 / 货柜 / 商户 / 毛利四维 · 可按柜机筛选</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button @click="exportCsv">导出</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-form inline class="filter-bar filter-bar--compact">
      <el-form-item label="维度">
        <el-radio-group v-model="dim" @change="load">
          <el-radio-button value="PRODUCT">商品</el-radio-button>
          <el-radio-button value="CABINET">货柜</el-radio-button>
          <el-radio-button value="MERCHANT">商户</el-radio-button>
          <el-radio-button value="MARGIN">毛利</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="柜机">
        <el-select
          v-model="deviceId"
          clearable
          filterable
          placeholder="全部柜机"
          style="width: 200px"
          @change="load"
        >
          <el-option
            v-for="d in deviceOptions"
            :key="d.deviceId"
            :label="`${d.deviceName || d.deviceId}（${d.deviceId}）`"
            :value="d.deviceId"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="区间">
        <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="load">查询</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="rows" stripe border class="report-table">
      <template #empty><el-empty description="暂无数据" /></template>
      <el-table-column prop="dimKey" label="编码" min-width="140" align="center" />
      <el-table-column prop="dimLabel" label="名称" min-width="180" align="center" />
      <el-table-column prop="orderCount" label="订单数" width="90" align="center" />
      <el-table-column prop="qty" label="销量" width="90" align="center" />
      <el-table-column label="营收" width="110" align="center">
        <template #default="{ row }">¥{{ ((row.revenueCents || 0) / 100).toFixed(2) }}</template>
      </el-table-column>
      <el-table-column label="成本" width="110" align="center">
        <template #default="{ row }">¥{{ ((row.cogsCents || 0) / 100).toFixed(2) }}</template>
      </el-table-column>
      <el-table-column label="毛利" width="110" align="center">
        <template #default="{ row }">¥{{ ((row.marginCents || 0) / 100).toFixed(2) }}</template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';

interface DeviceOpt {
  deviceId: string;
  deviceName?: string;
}

const loading = ref(false);
const dim = ref('PRODUCT');
const deviceId = ref('');
const range = ref<[string, string] | null>(null);
const rows = ref<any[]>([]);
const deviceOptions = ref<DeviceOpt[]>([]);

async function loadDevices() {
  try {
    const res = await api.request<{ items: DeviceOpt[] } | DeviceOpt[]>(
      '/api/v2/ops/admin/devices?page=0&size=200',
      'GET'
    );
    deviceOptions.value = Array.isArray(res) ? res : res.items || [];
  } catch {
    deviceOptions.value = [];
  }
}

async function load() {
  loading.value = true;
  try {
    const q = new URLSearchParams({ dim: dim.value });
    if (range.value?.[0]) q.set('fromDate', range.value[0]);
    if (range.value?.[1]) q.set('toDate', range.value[1]);
    if (deviceId.value) q.set('deviceId', deviceId.value);
    rows.value = await api.request(`/api/v2/ops/admin/sales-reports?${q}`, 'GET');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

function exportCsv() {
  const header = 'dimKey,dimLabel,orderCount,qty,revenueCents,cogsCents,marginCents\n';
  const body = rows.value
    .map((r) =>
      [r.dimKey, r.dimLabel, r.orderCount, r.qty, r.revenueCents, r.cogsCents, r.marginCents]
        .map((v) => `"${String(v ?? '').replace(/"/g, '""')}"`)
        .join(',')
    )
    .join('\n');
  const blob = new Blob(['\ufeff' + header + body], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `sales-report-${dim.value}.csv`;
  a.click();
  URL.revokeObjectURL(url);
}

onMounted(async () => {
  await loadDevices();
  await load();
});
</script>
