<script setup lang="ts">
import type { AdminDynamicRow } from '@/types/admin-dynamic-row';

export type WarehouseMonthlyCloseRow = AdminDynamicRow;

defineProps<{
  loading: boolean;
  hydrated: boolean;
  hint: string;
  rows: WarehouseMonthlyCloseRow[];
}>();
</script>

<template>
  <p class="muted tip">{{ hint || '件数月结，与结算金额无关。' }}</p>
  <div class="table-scroll">
    <div class="table-scroll-inner">
      <el-table
        class="report-table"
        v-loading="loading"
        :data="rows"
        stripe
        border
        row-key="skuId"
        empty-text="本月无进出流水"
      >
        <el-table-column
          label="商品"
          min-width="160"
          class-name="col-text"
          label-class-name="col-text"
        >
          <template #default="{ row }">
            <div>{{ row.skuName || row.skuId }}</div>
            <small class="muted">{{ row.skuId }}</small>
          </template>
        </el-table-column>
        <el-table-column
          prop="openingQty"
          label="上期"
          min-width="72"
          class-name="col-num"
          label-class-name="col-num"
        />
        <el-table-column
          prop="purchaseInQty"
          label="采购入库"
          min-width="88"
          class-name="col-num"
          label-class-name="col-num"
        />
        <el-table-column
          prop="transferInQty"
          label="调入"
          min-width="72"
          class-name="col-num"
          label-class-name="col-num"
        />
        <el-table-column
          prop="returnQty"
          label="退货"
          min-width="72"
          class-name="col-num"
          label-class-name="col-num"
        />
        <el-table-column
          prop="transferOutQty"
          label="调出"
          min-width="72"
          class-name="col-num"
          label-class-name="col-num"
        />
        <el-table-column
          prop="restockQty"
          label="上柜"
          min-width="72"
          class-name="col-num"
          label-class-name="col-num"
        />
        <el-table-column
          prop="rockLossQty"
          label="摇机损耗"
          min-width="80"
          class-name="col-num"
          label-class-name="col-num"
        />
        <el-table-column
          prop="expiredLossQty"
          label="过期损耗"
          min-width="80"
          class-name="col-num"
          label-class-name="col-num"
        />
        <el-table-column
          prop="guestWaterQty"
          label="客情用水"
          min-width="80"
          class-name="col-num"
          label-class-name="col-num"
        />
        <el-table-column
          prop="feeDeductQty"
          label="费用抵扣"
          min-width="80"
          class-name="col-num"
          label-class-name="col-num"
        />
        <el-table-column
          prop="lossQty"
          label="其他损耗"
          min-width="80"
          class-name="col-num"
          label-class-name="col-num"
        />
        <el-table-column
          prop="expectedQty"
          label="应有"
          min-width="72"
          class-name="col-num"
          label-class-name="col-num"
        />
        <el-table-column
          label="实盘"
          min-width="72"
          class-name="col-num"
          label-class-name="col-num"
        >
          <template #default="{ row }">{{
            row.countedQty == null ? '未盘' : row.countedQty
          }}</template>
        </el-table-column>
        <el-table-column
          label="差异"
          min-width="72"
          class-name="col-num"
          label-class-name="col-num"
        >
          <template #default="{ row }">{{ row.gapQty == null ? '—' : row.gapQty }}</template>
        </el-table-column>
        <el-table-column
          label="单价"
          min-width="76"
          class-name="col-num"
          label-class-name="col-num"
        >
          <template #default="{ row }">{{
            row.unitPriceCents == null ? '—' : (row.unitPriceCents / 100).toFixed(2)
          }}</template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<style scoped>
.tip {
  margin: 0 0 8px;
}
.muted {
  color: var(--layout-muted);
}
</style>
