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
          <el-radio-group v-model="days" @change="search">
            <el-radio-button :value="7">近 7 天</el-radio-button>
            <el-radio-button :value="30">近 30 天</el-radio-button>
            <el-radio-button :value="90">近 90 天</el-radio-button>
          </el-radio-group>
        </div>
      </div>
    </template>

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="search">
      <el-form-item label="关键词">
        <el-input
          v-model="keyword"
          clearable
          placeholder="活动名称"
          style="width: 180px"
          @keyup.enter="search"
          @clear="search"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </el-form-item>
    </el-form>

    <CrudTable
      :table="crud"
      row-key="activityId"
      selectable
      :csv="csvOptions"
      empty-text="暂无活动数据"
    >
      <el-table-column
        prop="activityName"
        label="活动"
        min-width="170"
        align="center"
        class-name="col-status"
        label-class-name="col-status"
      />
      <el-table-column
        label="类型"
        width="90"
        align="center"
        class-name="col-status"
        label-class-name="col-status"
      >
        <template #default="{ row }">{{ typeLabel(row.activityType) }}</template>
      </el-table-column>
      <el-table-column
        label="状态"
        width="80"
        align="center"
        class-name="col-status"
        label-class-name="col-status"
      >
        <template #default="{ row }">
          <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{
            displayLabel('enable_status', row.status, '未知')
          }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column
        label="预算(元)"
        width="100"
        align="center"
        class-name="col-money"
        label-class-name="col-money"
      >
        <template #default="{ row }">{{ yuan(row.budgetCents) }}</template>
      </el-table-column>
      <el-table-column
        label="预算已用(元)"
        width="110"
        align="center"
        class-name="col-money"
        label-class-name="col-money"
      >
        <template #default="{ row }">{{ yuan(row.usedCents) }}</template>
      </el-table-column>
      <el-table-column
        prop="claimedCount"
        label="发券数"
        width="90"
        align="center"
        class-name="col-status"
        label-class-name="col-status"
      />
      <el-table-column
        prop="usedCount"
        label="核销数"
        width="90"
        align="center"
        class-name="col-status"
        label-class-name="col-status"
      />
      <el-table-column
        label="核销率"
        width="90"
        align="center"
        class-name="col-status"
        label-class-name="col-status"
      >
        <template #default="{ row }">{{ pct(row.redeemRate) }}</template>
      </el-table-column>
      <el-table-column
        label="订单优惠(元)"
        width="110"
        align="center"
        class-name="col-money"
        label-class-name="col-money"
      >
        <template #default="{ row }">{{ yuan(row.discountCents) }}</template>
      </el-table-column>
      <el-table-column
        prop="orderCount"
        label="带动订单"
        width="90"
        align="center"
        class-name="col-status"
        label-class-name="col-status"
      />
      <el-table-column
        label="带动营收(元)"
        width="120"
        align="center"
        class-name="col-money"
        label-class-name="col-money"
      >
        <template #default="{ row }">
          <span class="cell-revenue">{{ yuan(row.orderRevenueCents) }}</span>
        </template>
      </el-table-column>
    </CrudTable>
  </el-card>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { displayLabel } from '@aicabinet/shared-dict';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import CrudTable, { type CrudCsvOptions } from '@/components/CrudTable.vue';
import { useCrudTable } from '@/composables/useCrudTable';

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

const days = ref(30);
const keyword = ref('');

/** 关键词为纯前端过滤（接口无该参数）：原 displayList 计算属性前移到取数处，切片前先过滤保证分页计数一致 */
function filterByKeyword(rows: RoiRow[]): RoiRow[] {
  const kw = keyword.value.trim().toLowerCase();
  if (!kw) return rows;
  return rows.filter((row) => (row.activityName || '').toLowerCase().includes(kw));
}

// 列表状态机统一交给 CrudTable：分页（接口无分页，前端切片）/ 多选 / 竞态 / 空态 全部内建
const crud = useCrudTable<RoiRow>({
  rowKey: (r) => r.activityId,
  fetchPage: async (params) => {
    const list = filterByKeyword(
      await api.request<RoiRow[]>(AdminEndpoints.growthMarketingRoi(days.value))
    );
    const start = params.page * params.size;
    return { items: list.slice(start, start + params.size), total: list.length };
  }
});

const csvOptions: CrudCsvOptions = {
  filePrefix: '活动效果分析',
  exportPerm: 'ops:report:export',
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
  toRows: (rows) =>
    rows.map((r) => [
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
};

function search() {
  void crud.search();
}
function reset() {
  keyword.value = '';
  void crud.search();
}

function typeLabel(t: string) {
  return displayLabel('promotion_type', t, '活动');
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
