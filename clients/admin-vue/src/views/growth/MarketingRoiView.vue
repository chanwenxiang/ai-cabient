<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">活动效果分析</span>
            <span class="hint"
              >发券 → 核销 → 带动营收；「预算已用」为活动占用预算，「订单优惠」为订单实扣优惠</span
            >
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-radio-group v-model="days" @change="load">
            <el-radio-button :value="7">近 7 天</el-radio-button>
            <el-radio-button :value="30">近 30 天</el-radio-button>
            <el-radio-button :value="90">近 90 天</el-radio-button>
          </el-radio-group>
          <el-button @click="onExport">导出 CSV</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-table
      v-loading="loading"
      :data="list"
      stripe
      border
      row-key="activityId"
      empty-text=" "
      class="report-table"
    >
      <template #empty><el-empty v-if="!loading" description="暂无活动数据" /></template>
      <el-table-column prop="activityName" label="活动" min-width="170" align="center" />
      <el-table-column label="类型" width="90" align="center">
        <template #default="{ row }">{{ typeLabel(row.activityType) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="80" align="center">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{
            displayLabel('enable_status', row.status, '未知')
          }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="预算(元)" width="100" align="center">
        <template #default="{ row }">{{ yuan(row.budgetCents) }}</template>
      </el-table-column>
      <el-table-column label="预算已用(元)" width="110" align="center">
        <template #default="{ row }">{{ yuan(row.usedCents) }}</template>
      </el-table-column>
      <el-table-column prop="claimedCount" label="发券数" width="90" align="center" />
      <el-table-column prop="usedCount" label="核销数" width="90" align="center" />
      <el-table-column label="核销率" width="90" align="center">
        <template #default="{ row }">{{ pct(row.redeemRate) }}</template>
      </el-table-column>
      <el-table-column label="订单优惠(元)" width="110" align="center">
        <template #default="{ row }">{{ yuan(row.discountCents) }}</template>
      </el-table-column>
      <el-table-column prop="orderCount" label="带动订单" width="90" align="center" />
      <el-table-column label="带动营收(元)" width="120" align="center">
        <template #default="{ row }">
          <span class="cell-revenue">{{ yuan(row.orderRevenueCents) }}</span>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import { displayLabel } from '@aicabinet/shared-dict';
import { api } from '@/api/client';
import { useListCsv } from '@/composables/useListCsv';

type RoiRow = {
  activityId: number;
  activityName: string;
  activityType: string;
  status: string;
  budgetCents: number;
  usedCents: number;
  claimedCount: number;
  usedCount: number;
  orderCount: number;
  orderRevenueCents: number;
  discountCents: number;
  redeemRate: number;
};

const loading = ref(false);
const days = ref(30);
const list = ref<RoiRow[]>([]);

const { onExport } = useListCsv({
  filePrefix: '活动效果分析',
  headers: [
    '活动',
    '类型',
    '状态',
    '预算(元)',
    '预算已用(元)',
    '发券数',
    '核销数',
    '核销率',
    '订单优惠(元)',
    '带动订单',
    '带动营收(元)'
  ],
  toRows: () =>
    list.value.map((r) => [
      r.activityName,
      typeLabel(r.activityType),
      displayLabel('enable_status', r.status, '未知'),
      yuan(r.budgetCents),
      yuan(r.usedCents),
      r.claimedCount,
      r.usedCount,
      pct(r.redeemRate),
      yuan(r.discountCents),
      r.orderCount,
      yuan(r.orderRevenueCents)
    ])
});

onMounted(load);

async function load() {
  loading.value = true;
  try {
    list.value = await api.request<RoiRow[]>(
      `/api/v2/ops/admin/growth/marketing-roi?days=${days.value}`
    );
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

function typeLabel(t: string) {
  return displayLabel('promotion_type', t, t || '活动');
}
function yuan(cents?: number) {
  return cents == null ? '暂无' : (cents / 100).toFixed(2);
}
function pct(v?: number) {
  if (v == null || !Number.isFinite(v)) return '暂无';
  return `${(v * 100).toFixed(1)}%`;
}
</script>

<style scoped>
.cell-revenue {
  font-weight: 700;
  color: #065f46;
}
</style>
