<template>
  <div v-loading="loading" class="finance-page">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-head">
          <span class="title">财务毛利</span>
          <div class="heading-actions">
            <el-radio-group v-model="days" @change="load">
              <el-radio-button :label="7">近 7 天</el-radio-button>
              <el-radio-button :label="30">近 30 天</el-radio-button>
              <el-radio-button :label="90">近 90 天</el-radio-button>
            </el-radio-group>
            <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
          </div>
        </div>
      </template>

      <el-row :gutter="12">
        <el-col v-for="item in kpiTiles" :key="item.label" :xs="12" :sm="8" :md="4">
          <div class="kpi-tile">
            <div class="kpi-label">{{ item.label }}</div>
            <div class="kpi-value">{{ item.value }}</div>
          </div>
        </el-col>
      </el-row>
    </el-card>

    <div class="chart-grid chart-grid--split">
      <ChartPanel title="毛利趋势" hint="营收 / 成本 / 毛利">
        <ChartBox v-if="chartSvg" :svg="chartSvg" />
        <el-empty v-else description="暂无趋势数据" :image-size="64" />
        <template #footer>
          <span class="chart-legend-item"><i style="background:#2dd4bf" />营收</span>
          <span class="chart-legend-item"><i style="background:#60a5fa" />成本</span>
          <span class="chart-legend-item"><i style="background:#fbbf24" />毛利</span>
        </template>
      </ChartPanel>

      <ChartPanel title="累计快照" compact>
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="累计营收">¥{{ ((stats.revenueTotalCents || 0) / 100).toFixed(2) }}</el-descriptions-item>
          <el-descriptions-item label="累计成本">¥{{ ((stats.cogsTotalCents || 0) / 100).toFixed(2) }}</el-descriptions-item>
          <el-descriptions-item label="累计毛利">¥{{ ((stats.grossMarginTotalCents || 0) / 100).toFixed(2) }}</el-descriptions-item>
          <el-descriptions-item label="今日报废金额">¥{{ ((stats.writeOffTodayCents || 0) / 100).toFixed(2) }}</el-descriptions-item>
          <el-descriptions-item label="今日报废件数">{{ stats.writeOffTodayQty || 0 }}</el-descriptions-item>
        </el-descriptions>
      </ChartPanel>
    </div>

    <ChartPanel title="SKU 毛利 TOP" compact class="sku-panel">
      <template #actions>
        <el-button link type="primary" @click="router.push('/skus')">商品管理</el-button>
      </template>
      <div class="table-scroll">
        <div class="table-scroll-inner" style="min-width: 780px">
          <el-table :data="topSkus" stripe>
            <el-table-column prop="skuId" label="SKU" min-width="120" />
            <el-table-column prop="skuName" label="商品" min-width="140" show-overflow-tooltip />
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
            <template #empty><el-empty description="暂无 SKU 毛利数据" /></template>
          </el-table>
        </div>
      </div>
    </ChartPanel>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';
import ChartBox from '@/components/ChartBox.vue';
import ChartPanel from '@/components/ChartPanel.vue';
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

const router = useRouter();
const loading = ref(false);
const days = ref(7);
const stats = ref<FinanceStats>({});
const daily = ref<FinanceDaily[]>([]);
const topSkus = ref<FinanceSku[]>([]);

const kpiTiles = computed(() => [
  { label: '今日营收', value: `¥${((stats.value.revenueTodayCents || 0) / 100).toFixed(2)}` },
  { label: '今日成本', value: `¥${((stats.value.cogsTodayCents || 0) / 100).toFixed(2)}` },
  { label: '今日毛利', value: `¥${((stats.value.grossMarginTodayCents || 0) / 100).toFixed(2)}` },
  { label: '今日毛利率', value: `${((stats.value.grossMarginRateToday || 0) * 100).toFixed(1)}%` },
  { label: '今日订单', value: String(stats.value.orderToday || 0) },
  { label: '今日客单', value: `¥${((stats.value.averageOrderValueTodayCents || 0) / 100).toFixed(2)}` }
]);

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
.card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.title { font-weight: 600; }
.heading-actions { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; }
.kpi-tile {
  background: var(--layout-bg, #f8fafc);
  border-radius: 10px;
  padding: 12px 14px;
  margin-bottom: 8px;
}
.kpi-label { font-size: 13px; color: var(--layout-muted); }
.kpi-value { font-size: 20px; font-weight: 700; margin-top: 4px; }
.sku-panel {
  margin-top: 16px;
}
</style>
