<script setup lang="ts">
import type { AdminDynamicRow } from '@/types/admin-dynamic-row';
import { formatDateTime } from '@aicabinet/shared-uni/format';

export type WarehouseStocktakeRow = AdminDynamicRow;

defineProps<{
  loading: boolean;
  hydrated: boolean;
  rows: WarehouseStocktakeRow[];
  canWarehouseEdit: boolean;
  stocktakeModeText: (mode: string) => string;
  stocktakeStatusType: (status: string) => string;
  stocktakeStatusText: (status: string) => string;
}>();

const emit = defineEmits<{
  'selection-change': [rows: WarehouseStocktakeRow[]];
  detail: [row: WarehouseStocktakeRow];
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
        row-key="stocktakeId"
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
          label="盘点单号"
          min-width="160"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        >
          <template #default="{ row }">
            <span class="cell-id">{{ row.stocktakeNo }}</span>
          </template>
        </el-table-column>
        <el-table-column
          label="仓库"
          min-width="140"
          class-name="col-text"
          label-class-name="col-text"
        >
          <template #default="{ row }">{{ row.warehouseName }}</template>
        </el-table-column>
        <el-table-column
          label="模式"
          min-width="80"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        >
          <template #default="{ row }">{{ stocktakeModeText(String(row.mode || '')) }}</template>
        </el-table-column>
        <el-table-column
          label="状态"
          min-width="100"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        >
          <template #default="{ row }">
            <el-tag :type="stocktakeStatusType(String(row.status || ''))" size="small">
              {{ stocktakeStatusText(String(row.status || '')) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          label="账面件数"
          prop="bookQty"
          min-width="90"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        />
        <el-table-column
          label="实盘件数"
          prop="countedQty"
          min-width="90"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        />
        <el-table-column
          label="差异件数"
          min-width="90"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        >
          <template #default="{ row }">{{ row.diffQty }}</template>
        </el-table-column>
        <el-table-column
          label="差异行数"
          prop="diffLineCount"
          min-width="90"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        />
        <el-table-column
          label="创建时间"
          min-width="160"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        >
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column
          v-if="canWarehouseEdit"
          label="操作"
          width="110"
          align="center"
          fixed="right"
          class-name="col-action"
          label-class-name="col-action"
        >
          <template #default="{ row }">
            <el-button link type="primary" @click="emit('detail', row)">
              {{ ['DRAFT', 'IN_PROGRESS'].includes(row.status) ? '盘点' : '查看/调整' }}
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty v-if="hydrated && !loading" description="暂无盘点单" />
        </template>
      </el-table>
    </div>
  </div>
</template>
