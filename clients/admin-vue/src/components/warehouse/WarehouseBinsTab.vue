<script setup lang="ts">
import type { AdminDynamicRow } from '@/types/admin-dynamic-row';
import { displayLabel } from '@aicabinet/shared-dict';

export type WarehouseBinRow = AdminDynamicRow;

defineProps<{
  loading: boolean;
  hydrated: boolean;
  bins: WarehouseBinRow[];
  binStock: WarehouseBinRow[];
  canWarehouseEdit: boolean;
}>();

const emit = defineEmits<{
  edit: [row: WarehouseBinRow];
}>();
</script>

<template>
  <div class="section-title">货位档案</div>
  <div class="table-scroll compact">
    <el-table v-loading="loading" :data="bins" stripe border size="small" empty-text=" ">
      <el-table-column
        label="货位编码"
        min-width="110"
        align="center"
        class-name="col-status"
        label-class-name="col-status"
      >
        <template #default="{ row }">
          <span class="cell-id">{{ row.binCode }}</span>
        </template>
      </el-table-column>
      <el-table-column
        prop="binName"
        label="货位名称"
        min-width="140"
        class-name="col-text"
        label-class-name="col-text"
      >
        <template #default="{ row }">{{ row.binName || '暂无' }}</template>
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
        label="状态"
        min-width="90"
        align="center"
        class-name="col-status"
        label-class-name="col-status"
      >
        <template #default="{ row }">
          <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">
            {{ displayLabel('enable_status', row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column
        v-if="canWarehouseEdit"
        label="操作"
        width="80"
        align="center"
        fixed="right"
        class-name="col-action"
        label-class-name="col-action"
      >
        <template #default="{ row }">
          <el-button link type="primary" @click="emit('edit', row)">编辑</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty
          v-if="hydrated && !loading"
          description="暂无货位，请先新增货位"
          :image-size="60"
        />
      </template>
    </el-table>
  </div>
  <div class="section-title">货位库存</div>
  <div class="table-scroll">
    <el-table v-loading="loading" :data="binStock" stripe border empty-text=" ">
      <el-table-column
        label="货位"
        min-width="100"
        align="center"
        class-name="col-status"
        label-class-name="col-status"
      >
        <template #default="{ row }">
          <span class="cell-id">{{ row.binCode }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="商品"
        min-width="170"
        class-name="col-text"
        label-class-name="col-text"
      >
        <template #default="{ row }">{{ row.skuName }}</template>
      </el-table-column>
      <el-table-column
        prop="batchNo"
        label="批次"
        min-width="130"
        class-name="col-text"
        label-class-name="col-text"
      />
      <el-table-column
        prop="productionDate"
        label="生产日期"
        min-width="110"
        align="center"
        class-name="col-status"
        label-class-name="col-status"
      />
      <el-table-column
        prop="expiryDate"
        label="到期日"
        min-width="110"
        align="center"
        class-name="col-status"
        label-class-name="col-status"
      />
      <el-table-column
        prop="quantity"
        label="数量"
        min-width="80"
        align="center"
        class-name="col-status"
        label-class-name="col-status"
      />
      <template #empty>
        <el-empty v-if="hydrated && !loading" description="暂无货位库存" :image-size="60" />
      </template>
    </el-table>
  </div>
</template>
