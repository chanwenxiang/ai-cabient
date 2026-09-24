<script setup lang="ts">
import type { AdminDynamicRow } from '@/types/admin-dynamic-row';
import { dictLabel, dictTagType } from '@aicabinet/shared-dict';
import { formatDateTime } from '@aicabinet/shared-uni/format';

export type WarehouseTransitRow = AdminDynamicRow;

defineProps<{
  loading: boolean;
  hydrated: boolean;
  rows: WarehouseTransitRow[];
  emptyHint: string;
  rowKey: (row: WarehouseTransitRow) => string | number;
  rowClassName: (data: { row: WarehouseTransitRow }) => string;
  deviceName: (deviceId?: string, snapshot?: string | null) => string;
  skuName: (skuId?: string) => string;
  isTransitOverdue: (row: WarehouseTransitRow) => boolean;
  isTransitDueSoon: (row: WarehouseTransitRow) => boolean;
  formatAge: (ms: number) => string;
  transitAgeMs: (row: WarehouseTransitRow) => number;
  transitRemainMs: (row: WarehouseTransitRow) => number;
  transitOverdueMs: (row: WarehouseTransitRow) => number;
}>();

const emit = defineEmits<{
  'selection-change': [rows: WarehouseTransitRow[]];
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
        :row-class-name="rowClassName"
        empty-text=" "
        @selection-change="emit('selection-change', $event)"
      >
        <template #empty>
          <el-empty v-if="hydrated && !loading" :description="emptyHint" />
        </template>
        <el-table-column
          type="selection"
          width="48"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        />
        <el-table-column
          prop="outboundId"
          label="出库单"
          min-width="96"
          class-name="col-text"
          label-class-name="col-text"
        />
        <el-table-column
          label="目标设备"
          min-width="180"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        >
          <template #default="{ row }">
            {{ deviceName(row.deviceId, row.deviceName) }}
          </template>
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
          prop="quantity"
          label="数量"
          min-width="88"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        />
        <el-table-column
          label="状态"
          min-width="110"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        >
          <template #default="{ row }">
            <el-tag :type="dictTagType(row.status)" size="small">{{
              dictLabel('in_transit_status', row.status)
            }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column
          align="center"
          label="在途 / 时限"
          min-width="160"
          class-name="col-status"
          label-class-name="col-status"
        >
          <template #default="{ row }">
            <div class="sla-cell">
              <template v-if="isTransitOverdue(row)">
                <el-tag type="danger" size="small">到柜超时</el-tag>
                <small class="sla-meta danger">超 {{ formatAge(transitOverdueMs(row)) }}</small>
              </template>
              <template v-else-if="isTransitDueSoon(row)">
                <el-tag type="warning" size="small">临近超时</el-tag>
                <small class="sla-meta"
                  >已运 {{ formatAge(transitAgeMs(row)) }} · 剩
                  {{ formatAge(transitRemainMs(row)) }}</small
                >
              </template>
              <template v-else>
                <span class="cell-datetime">已运 {{ formatAge(transitAgeMs(row)) }}</span>
                <small class="sla-meta">待补货员到柜完成</small>
              </template>
            </div>
          </template>
        </el-table-column>
        <el-table-column
          label="发运时间"
          min-width="170"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        >
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>
