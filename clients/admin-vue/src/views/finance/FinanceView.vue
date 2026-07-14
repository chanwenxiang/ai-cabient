<template>
  <div v-loading="loading" class="finance-page">
    <div class="page-heading">
      <div>
        <h1>财务毛利</h1>
        <p>营收、成本、毛利与滞销报废；支持近 7 / 30 / 90 天趋势。</p>
      </div>
      <div class="heading-actions">
        <el-radio-group v-model="days" @change="load">
          <el-radio-button :label="7">近 7 天</el-radio-button>
          <el-radio-button :label="30">近 30 天</el-radio-button>
          <el-radio-button :label="90">近 90 天</el-radio-button>
        </el-radio-group>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <el-row :gutter="16">
      <el-col :xs="12" :sm="8" :md="4"><el-card shadow="never"><el-statistic title="今日营收" :value="(stats.revenueTodayCents || 0) / 100" prefix="¥" :precision="2" /></el-card></el-col>
      <el-col :xs="12" :sm="8" :md="4"><el-card shadow="never"><el-statistic title="今日成本" :value="(stats.cogsTodayCents || 0) / 100" prefix="¥" :precision="2" /></el-card></el-col>
      <el-col :xs="12" :sm="8" :md="4"><el-card shadow="never"><el-statistic title="今日毛利" :value="(stats.grossMarginTodayCents || 0) / 100" prefix="¥" :precision="2" /></el-card></el-col>
      <el-col :xs="12" :sm="8" :md="4"><el-card shadow="never"><el-statistic title="今日毛利率" :value="(stats.grossMarginRateToday || 0) * 100" suffix="%" :precision="1" /></el-card></el-col>
      <el-col :xs="12" :sm="8" :md="4"><el-card shadow="never"><el-statistic title="今日订单" :value="stats.orderToday || 0" /></el-card></el-col>
      <el-col :xs="12" :sm="8" :md="4"><el-card shadow="never"><el-statistic title="今日客单" :value="(stats.averageOrderValueTodayCents || 0) / 100" prefix="¥" :precision="2" /></el-card></el-col>
    </el-row>

    <el-row :gutter="16" style="margin-top:16px">
      <el-col :xs="24" :md="14">
        <el-card shadow="never" class="page-card">
          <template #header><strong>毛利趋势</strong></template>
          <ChartBox v-if="chartSvg" :svg="chartSvg" />
          <el-empty v-else description="暂无趋势" :image-size="64" />
          <div class="legend">
            <span><i style="background:#2dd4bf" />营收</span>
            <span><i style="background:#60a5fa" />成本</span>
            <span><i style="background:#fbbf24" />毛利</span>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="24" :md="10">
        <el-card shadow="never" class="page-card">
          <template #header><strong>累计快照</strong></template>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="累计营收">¥{{ ((stats.revenueTotalCents || 0) / 100).toFixed(2) }}</el-descriptions-item>
            <el-descriptions-item label="累计成本">¥{{ ((stats.cogsTotalCents || 0) / 100).toFixed(2) }}</el-descriptions-item>
            <el-descriptions-item label="累计毛利">¥{{ ((stats.grossMarginTotalCents || 0) / 100).toFixed(2) }}</el-descriptions-item>
            <el-descriptions-item label="今日报废金额">¥{{ ((stats.writeOffTodayCents || 0) / 100).toFixed(2) }}</el-descriptions-item>
            <el-descriptions-item label="今日报废件数">{{ stats.writeOffTodayQty || 0 }}</el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>
    </el-row>

    <el-card class="page-card" style="margin-top:16px">
      <template #header><strong>SKU 毛利 TOP</strong></template>
      <el-table :data="topSkus" stripe>
        <el-table-column prop="skuId" label="SKU" min-width="120" />
        <el-table-column prop="skuName" label="商品" min-width="140" />
        <el-table-column prop="qtySold" label="销量" width="90" />
        <el-table-column label="营收" width="110">
          <template #default="{ row }">¥{{ (row.revenueCents / 100).toFixed(2) }}</template>
        </el-table-column>
        <el-table-column label="成本" width="110">
          <template #default="{ row }">¥{{ (row.cogsCents / 100).toFixed(2) }}</template>
        </el-table-column>
        <el-table-column label="毛利" width="110">
          <template #default="{ row }">¥{{ (row.grossMarginCents / 100).toFixed(2) }}</template>
        </el-table-column>
        <el-table-column label="毛利率" width="100">
          <template #default="{ row }">
            {{ row.revenueCents ? ((row.grossMarginCents / row.revenueCents) * 100).toFixed(1) : '0.0' }}%
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';
import ChartBox from '@/components/ChartBox.vue';
import { buildSeriesChart, formatYuan, shortDate } from '@/utils/charts';

interface FinanceStats {
  revenueTodayCents?: number;
  cogsTodayCents?: number;
  grossMarginTodayCents?: number;
  writeOffTodayCents?: number;
  writeOffTodayQty?: number;
  orderToday?: number;
  averageOrderValueTodayCents?: number;
  grossMarginRateToday?: number;
  revenueTotalCents?: number;
  cogsTotalCents?: number;
  grossMarginTotalCents?: number;
}

interface FinanceDaily {
  date: string;
  revenueCents: number;
  cogsCents: number;
  grossMarginCents: number;
  writeOffCents: number;
}

interface FinanceSku {
  skuId: string;
  skuName: string;
  qtySold: number;
  revenueCents: number;
  cogsCents: number;
  grossMarginCents: number;
}

interface FinanceReport {
  summary: FinanceStats;
  daily: FinanceDaily[];
  topSkus: FinanceSku[];
}

const loading = ref(false);
const days = ref(7);
const stats = ref<FinanceStats>({});
const daily = ref<FinanceDaily[]>([]);
const topSkus = ref<FinanceSku[]>([]);

const chartSvg = computed(() => {
  if (!daily.value.length) return '';
  return buildSeriesChart({
    labels: daily.value.map((d) => shortDate(d.date)),
    series: [
      { name: '营收', values: daily.value.map((d) => d.revenueCents / 100), color: '#2dd4bf' },
      { name: '成本', values: daily.value.map((d) => d.cogsCents / 100), color: '#60a5fa' },
      { name: '毛利', values: daily.value.map((d) => d.grossMarginCents / 100), color: '#fbbf24' }
    ],
    kind: 'area',
    formatY: (v) => formatYuan(v * 100)
  });
});

async function load() {
  loading.value = true;
  try {
    const data = await api.request<FinanceReport>(`/api/v2/ops/admin/finance/report?days=${days.value}`, 'GET');
    stats.value = data.summary || {};
    daily.value = data.daily || [];
    topSkus.value = data.topSkus || [];
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<style scoped>
.page-heading { display:flex; justify-content:space-between; gap:16px; align-items:flex-start; margin-bottom:16px; flex-wrap:wrap; }
.page-heading h1 { margin:0; font-size:22px; }
.page-heading p { margin:6px 0 0; color:var(--layout-muted); }
.heading-actions { display:flex; gap:12px; align-items:center; flex-wrap:wrap; }
.legend { display:flex; gap:16px; margin-top:8px; color:var(--layout-muted); font-size:13px; }
.legend i { display:inline-block; width:10px; height:10px; border-radius:999px; margin-right:6px; }
</style>
