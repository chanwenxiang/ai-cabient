<script setup lang="ts">
import type { AdminDynamicRow } from '@/types/admin-dynamic-row';
import { dictLabel } from '@aicabinet/shared-dict';
import { displayBizNo, formatDateTime } from '@aicabinet/shared-uni/format';

export type WarehouseMovementRow = AdminDynamicRow;

defineProps<{
  loading: boolean;
  hydrated: boolean;
  rows: WarehouseMovementRow[];
  skuName: (skuId?: string) => string;
}>();

const emit = defineEmits<{
  'selection-change': [rows: WarehouseMovementRow[]];
}>();
</script>

<template>
  <p class="muted tip">仅显示最近 100 条</p>
  <div class="table-scroll">
    <div class="table-scroll-inner">
      <el-table
        class="report-table"
        v-loading="loading"
        :data="rows"
        stripe
        border
        row-key="movementId"
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
          prop="movementId"
          label="流水"
          min-width="90"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        />
        <el-table-column
          label="类型"
          min-width="130"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        >
          <template #default="{ row }">{{
            dictLabel('warehouse_movement_type', row.movementType)
          }}</template>
        </el-table-column>
        <el-table-column
          label="商品"
          min-width="180"
          class-name="col-text"
          label-class-name="col-text"
        >
          <template #default="{ row }">
            {{ skuName(row.skuId) }}
          </template>
        </el-table-column>
        <el-table-column
          prop="batchNo"
          label="批次"
          min-width="140"
          class-name="col-text"
          label-class-name="col-text"
        />
        <el-table-column
          prop="deltaQty"
          label="变动"
          min-width="88"
          align="center"
          class-name="col-money"
          label-class-name="col-money"
        >
          <template #default="{ row }">
            <span :class="row.deltaQty >= 0 ? 'positive' : 'negative'"
              >{{ row.deltaQty > 0 ? '+' : '' }}{{ row.deltaQty }}</span
            >
          </template>
        </el-table-column>
        <el-table-column
          label="关联业务"
          min-width="140"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        >
          <template #default="{ row }">{{
            dictLabel('business_reference_type', row.refType)
          }}</template>
        </el-table-column>
        <el-table-column
          label="关联单号"
          min-width="120"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        >
          <template #default="{ row }">{{ displayBizNo(row.refId, '无') }}</template>
        </el-table-column>
        <el-table-column
          label="时间"
          min-width="170"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        >
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <template #empty>
          <el-empty v-if="hydrated && !loading" description="暂无流水" />
        </template>
      </el-table>
    </div>
  </div>
</template>
