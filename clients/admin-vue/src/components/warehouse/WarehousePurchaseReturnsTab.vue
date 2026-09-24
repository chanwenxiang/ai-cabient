<script setup lang="ts">
import type { AdminDynamicRow } from '@/types/admin-dynamic-row';
import { formatDateTime } from '@aicabinet/shared-uni/format';

export type WarehousePurchaseReturnRow = AdminDynamicRow;

defineProps<{
  loading: boolean;
  hydrated: boolean;
  rows: WarehousePurchaseReturnRow[];
  skuName: (skuId?: string) => string;
  supplierName: (id: string) => string;
  warehouseName: (id: string) => string;
  returnStatusLabel: (status?: string) => string;
}>();

const emit = defineEmits<{
  'selection-change': [rows: WarehousePurchaseReturnRow[]];
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
        row-key="returnId"
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
          type="expand"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        >
          <template #default="{ row }">
            <div class="expand-panel">
              <el-table :data="row.lines || []" size="small" border class="line-table">
                <el-table-column
                  label="商品"
                  min-width="180"
                  class-name="col-text"
                  label-class-name="col-text"
                >
                  <template #default="scope">
                    {{ skuName(scope.row.skuId) }}
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
                  prop="quantity"
                  label="退货数"
                  min-width="88"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                />
              </el-table>
            </div>
          </template>
        </el-table-column>
        <el-table-column
          prop="returnId"
          label="退货单"
          min-width="96"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        />
        <el-table-column
          prop="purchaseOrderId"
          label="采购单"
          min-width="96"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        />
        <el-table-column
          label="供应商"
          min-width="160"
          class-name="col-text"
          label-class-name="col-text"
        >
          <template #default="{ row }">
            {{ supplierName(String(row.supplierId || '')) }}
          </template>
        </el-table-column>
        <el-table-column
          label="仓库"
          min-width="160"
          class-name="col-text"
          label-class-name="col-text"
        >
          <template #default="{ row }">
            {{ warehouseName(String(row.warehouseId || '')) }}
          </template>
        </el-table-column>
        <el-table-column
          label="状态"
          min-width="100"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        >
          <template #default="{ row }">
            <el-tag type="success" size="small">{{ returnStatusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column
          label="创建时间"
          min-width="170"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        >
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <template #empty>
          <el-empty v-if="hydrated && !loading" description="暂无采购退货" />
        </template>
      </el-table>
    </div>
  </div>
</template>
