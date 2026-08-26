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
        <el-radio-group v-model="dim" @change="search">
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
          @change="search"
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
        <el-button type="primary" @click="search">查询</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="rows" stripe border class="report-table" empty-text=" ">
      <template #empty
        ><el-empty v-if="listHydrated && !loading" description="暂无数据"
      /></template>
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
      <el-table-column label="毛利率" width="90" align="center">
        <template #default="{ row }">
          {{
            Number(row.revenueCents) > 0
              ? `${((Number(row.marginCents || 0) / Number(row.revenueCents)) * 100).toFixed(1)}%`
              : '暂无'
          }}
        </template>
      </el-table-column>
      <el-table-column label="客单价" width="100" align="center">
        <template #default="{ row }">
          {{
            Number(row.orderCount) > 0
              ? `¥${(Number(row.revenueCents || 0) / Number(row.orderCount) / 100).toFixed(2)}`
              : '暂无'
          }}
        </template>
      </el-table-column>
      <el-table-column label="件均价" width="100" align="center">
        <template #default="{ row }">
          {{
            Number(row.qty) > 0
              ? `¥${(Number(row.revenueCents || 0) / Number(row.qty) / 100).toFixed(2)}`
              : '暂无'
          }}
        </template>
      </el-table-column>
    </el-table>

    <PagePager
      :hydrated="listHydrated"
      v-model:current-page="page"
      v-model:page-size="size"
      :total="total"
      :page-sizes="[10, 20, 50, 100]"
      layout="total, sizes, prev, pager, next, jumper"
      background
      @current-change="load"
      @size-change="onSizeChange"
    />
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { api, downloadAuthFile } from '@/api/client';
import PagePager from '@/components/PagePager.vue';
import { useDeviceOptions } from '@/composables/useDeviceOptions';
import { csvFileName } from '@/utils/csv';

const { deviceOptions, loadDeviceOptions } = useDeviceOptions();

const loading = ref(false);
const listHydrated = ref(false);
const page = ref(1);
const size = ref(20);
const total = ref(0);
const dim = ref('PRODUCT');
const deviceId = ref('');
function todayStr() {
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

// 默认当天；只有选择日期范围时才按范围查询
const range = ref<[string, string] | null>([todayStr(), todayStr()]);
const rows = ref<any[]>([]);

function queryParams(includePage = true) {
  const q = new URLSearchParams({ dim: dim.value });
  if (range.value?.[0]) q.set('fromDate', range.value[0]);
  if (range.value?.[1]) q.set('toDate', range.value[1]);
  if (deviceId.value) q.set('deviceId', deviceId.value);
  if (includePage) {
    q.set('page', String(page.value - 1));
    q.set('size', String(size.value));
  }
  return q;
}

async function load() {
  loading.value = true;
  try {
    const data = await api.request<{ items: any[]; total: number }>(
      `/api/v2/ops/admin/sales-reports?${queryParams()}`,
      'GET'
    );
    rows.value = data.items || [];
    total.value = Number(data.total) || 0;
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
    if (!listHydrated.value) rows.value = [];
  } finally {
    listHydrated.value = true;
    loading.value = false;
  }
}

function onSizeChange() {
  page.value = 1;
  load();
}

function search() {
  page.value = 1;
  load();
}

async function exportCsv() {
  try {
    await downloadAuthFile(
      `/api/v2/ops/admin/sales-reports/export?${queryParams(false)}`,
      csvFileName(`销售报表-${dim.value}`)
    );
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '导出失败');
  }
}

onMounted(async () => {
  await loadDeviceOptions();
  await load();
});
</script>
