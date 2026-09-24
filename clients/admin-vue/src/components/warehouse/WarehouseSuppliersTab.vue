<script setup lang="ts">
import { EditPen } from '@element-plus/icons-vue';
import type { Sort } from 'element-plus';
import TableActions from '@/components/TableActions.vue';
import type { AdminDynamicRow } from '@/types/admin-dynamic-row';
import { dictLabel, dictTagType } from '@aicabinet/shared-dict';

export type WarehouseSupplierRow = AdminDynamicRow;

defineProps<{
  loading: boolean;
  hydrated: boolean;
  rows: WarehouseSupplierRow[];
  canEdit: boolean;
  defaultSort: object;
}>();

const emit = defineEmits<{
  'selection-change': [rows: WarehouseSupplierRow[]];
  'sort-change': [payload: Sort];
  edit: [row: WarehouseSupplierRow];
}>();
</script>

<template>
  <div class="table-scroll">
    <div class="table-scroll-inner">
      <el-table
        class="report-table"
        v-loading="loading"
        :data="rows"
        :default-sort="defaultSort"
        stripe
        border
        row-key="supplierId"
        empty-text=" "
        @sort-change="emit('sort-change', $event)"
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
          prop="supplierId"
          label="供应商编号"
          min-width="120"
          class-name="col-text"
          sortable="custom"
        >
          <template #default="{ row }">
            <span class="cell-id">{{ row.supplierId }}</span>
          </template>
        </el-table-column>
        <el-table-column label="供应商" min-width="140" class-name="col-text">
          <template #default="{ row }">{{ row.supplierName || '无' }}</template>
        </el-table-column>
        <el-table-column
          prop="contactName"
          label="联系人"
          min-width="120"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        />
        <el-table-column
          prop="contactPhone"
          label="联系电话"
          min-width="150"
          class-name="col-text"
          label-class-name="col-text"
        />
        <el-table-column
          prop="paymentTermsDays"
          label="账期(天)"
          min-width="96"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        />
        <el-table-column
          label="状态"
          min-width="100"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        >
          <template #default="{ row }">
            <el-tag :type="dictTagType(row.status)" size="small">{{
              dictLabel('supplier_status', row.status)
            }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column
          v-if="canEdit"
          label="操作"
          width="88"
          class-name="col-action"
          align="center"
          fixed="right"
        >
          <template #default="{ row }">
            <TableActions
              :actions="[{ key: 'edit', label: '编辑', icon: EditPen, type: 'primary' }]"
              @action="emit('edit', row)"
            />
          </template>
        </el-table-column>
        <template #empty>
          <el-empty v-if="hydrated && !loading" description="暂无供应商" />
        </template>
      </el-table>
    </div>
  </div>
</template>
