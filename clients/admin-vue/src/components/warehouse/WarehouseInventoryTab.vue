<script setup lang="ts">
import type { AdminDynamicRow } from '@/types/admin-dynamic-row';

export type WarehouseInventoryRow = AdminDynamicRow;

defineProps<{
  loading: boolean;
  hydrated: boolean;
  rows: WarehouseInventoryRow[];
  rowKey: (row: WarehouseInventoryRow) => string | number;
  warehouseName: (id: string) => string;
  skuName: (skuId?: string) => string;
  expiryType: (expiryDate: string) => string;
  expiryText: (expiryDate: string) => string;
}>();

const emit = defineEmits<{
  'selection-change': [rows: WarehouseInventoryRow[]];
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
        :row-key="rowKey"
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
          label="仓库"
          min-width="140"
          class-name="col-text"
          label-class-name="col-text"
        >
          <template #default="{ row }">{{ warehouseName(String(row.warehouseId || '')) }}</template>
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
          min-width="150"
          class-name="col-text"
          label-class-name="col-text"
        />
        <el-table-column
          prop="productionDate"
          label="生产日期"
          min-width="120"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        />
        <el-table-column
          prop="expiryDate"
          label="到期日期"
          min-width="120"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        />
        <el-table-column
          prop="quantity"
          label="库存"
          min-width="88"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        />
        <el-table-column
          label="效期"
          min-width="100"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        >
          <template #default="{ row }">
            <el-tag :type="expiryType(String(row.expiryDate || ''))" size="small">{{
              expiryText(String(row.expiryDate || ''))
            }}</el-tag>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty v-if="hydrated && !loading" description="暂无库存" />
        </template>
      </el-table>
    </div>
  </div>
</template>
