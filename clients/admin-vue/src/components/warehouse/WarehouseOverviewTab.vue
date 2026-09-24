<script setup lang="ts">
import CrudTable from '@/components/CrudTable.vue';
import type { CrudRowAction } from '@/components/CrudTable.vue';
import type { AdminDynamicRow } from '@/types/admin-dynamic-row';
import { dictLabel, dictTagType } from '@aicabinet/shared-dict';

export type WarehouseOverviewRow = AdminDynamicRow;

defineProps<{
  table: object;
  actions: (row: WarehouseOverviewRow) => CrudRowAction[];
}>();

const emit = defineEmits<{
  action: [payload: { key: string; row: WarehouseOverviewRow }];
}>();
</script>

<template>
  <div class="table-scroll">
    <div class="table-scroll-inner">
      <CrudTable
        :table="table"
        row-key="warehouseId"
        selectable
        :actions="actions"
        :action-width="88"
        sort-field-label="仓库编号"
        empty-text="暂无仓库"
        @action="emit('action', $event)"
      >
        <el-table-column prop="warehouseId" label="仓库编号" min-width="120" class-name="col-text">
          <template #default="{ row }">
            <span class="cell-id">{{ row.warehouseId }}</span>
          </template>
        </el-table-column>
        <el-table-column label="仓库" min-width="140" class-name="col-text">
          <template #default="{ row }">{{ row.warehouseName || '无' }}</template>
        </el-table-column>
        <el-table-column prop="address" label="地址" min-width="220" class-name="col-text" />
        <el-table-column
          label="状态"
          width="100"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        >
          <template #default="{ row }">
            <el-tag :type="dictTagType(row.status)" size="small">
              {{ dictLabel('warehouse_status', row.status || 'ACTIVE') }}
            </el-tag>
          </template>
        </el-table-column>
      </CrudTable>
    </div>
  </div>
</template>
