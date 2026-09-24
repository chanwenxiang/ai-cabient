<script setup lang="ts">
import type { AdminDynamicRow } from '@/types/admin-dynamic-row';

export type WarehouseSuggestionRow = AdminDynamicRow;

defineProps<{
  loading: boolean;
  hydrated: boolean;
  rows: WarehouseSuggestionRow[];
  skuName: (skuId?: string) => string;
  suggestionReasonText: (reason: string) => string;
}>();

const emit = defineEmits<{
  'selection-change': [rows: WarehouseSuggestionRow[]];
}>();
</script>

<template>
  <div class="table-scroll">
    <div class="table-scroll-inner">
      <el-table
        class="report-table"
        v-loading="loading"
        :data="rows"
        stripe
        border
        row-key="skuId"
        empty-text=" "
        @selection-change="emit('selection-change', $event)"
      >
        <el-table-column
          type="selection"
          width="48"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        />
        <el-table-column
          label="商品"
          min-width="170"
          class-name="col-text"
          label-class-name="col-text"
        >
          <template #default="{ row }">{{ skuName(row.skuId) }}</template>
        </el-table-column>
        <el-table-column
          label="近7日销量"
          prop="soldQty7d"
          min-width="96"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        />
        <el-table-column
          label="近14日销量"
          prop="soldQty14d"
          min-width="104"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        />
        <el-table-column
          label="日均销量"
          min-width="88"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        >
          <template #default="{ row }">
            {{ Number(row.avgDailySales ?? 0).toFixed(2) }}
          </template>
        </el-table-column>
        <el-table-column
          label="预测日均"
          min-width="88"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        >
          <template #default="{ row }">
            {{ Number(row.forecastDailySales ?? row.avgDailySales ?? 0).toFixed(2) }}
          </template>
        </el-table-column>
        <el-table-column
          label="日均趋势"
          min-width="88"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        >
          <template #default="{ row }">
            <span v-if="Number(row.trendPerDay ?? 0) > 0" class="trend-up"
              >+{{ Number(row.trendPerDay).toFixed(2) }}</span
            >
            <span v-else-if="Number(row.trendPerDay ?? 0) < 0" class="trend-down">{{
              Number(row.trendPerDay).toFixed(2)
            }}</span>
            <span v-else>暂无</span>
          </template>
        </el-table-column>
        <el-table-column
          label="仓库库存"
          prop="onHandQty"
          min-width="88"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        />
        <el-table-column
          label="待收采购"
          prop="pendingPoQty"
          min-width="88"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        />
        <el-table-column
          label="覆盖天数"
          prop="coverageDays"
          min-width="88"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        />
        <el-table-column
          label="建议采购量"
          min-width="104"
          class-name="col-text"
          label-class-name="col-text"
        >
          <template #default="{ row }">
            <span class="cell-id">{{ row.suggestQty }}</span>
          </template>
        </el-table-column>
        <el-table-column
          label="安全库存"
          min-width="88"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        >
          <template #default="{ row }">
            {{ row.safetyStockQty ?? 0 }}
          </template>
        </el-table-column>
        <el-table-column
          label="建议理由"
          min-width="110"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        >
          <template #default="{ row }">
            <el-tag size="small" type="warning">
              {{ suggestionReasonText(String(row.suggestReason || '')) }}
            </el-tag>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty
            v-if="hydrated && !loading"
            description="暂无采购建议（近 14 日有动销且库存不足的商品才会出现）"
          />
        </template>
      </el-table>
    </div>
  </div>
</template>

<style scoped>
.trend-up {
  color: var(--el-color-danger);
  font-weight: 600;
}

.trend-down {
  color: var(--el-color-success);
  font-weight: 600;
}
</style>
