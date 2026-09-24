<script setup lang="ts">
import TableActions from '@/components/TableActions.vue';
import type { TableAction } from '@/components/TableActions.vue';
import type { AdminDynamicRow } from '@/types/admin-dynamic-row';
import { dictLabel, dictTagType } from '@aicabinet/shared-dict';
import { formatDateTime } from '@aicabinet/shared-uni/format';

export type WarehouseOutboundRow = AdminDynamicRow;

defineProps<{
  loading: boolean;
  hydrated: boolean;
  rows: WarehouseOutboundRow[];
  canEdit: boolean;
  skuName: (skuId?: string) => string;
  warehouseName: (warehouseId: string) => string;
  deviceName: (deviceId?: string, snapshot?: string | null) => string;
  outboundRowClassName: (data: { row: WarehouseOutboundRow }) => string;
  outboundSecondaryActions: (row: WarehouseOutboundRow) => TableAction[];
}>();

const emit = defineEmits<{
  'selection-change': [rows: WarehouseOutboundRow[]];
  print: [row: WarehouseOutboundRow];
  pick: [row: WarehouseOutboundRow];
  ship: [row: WarehouseOutboundRow];
  'cancel-unreceived': [row: WarehouseOutboundRow];
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
        row-key="outboundId"
        :row-class-name="outboundRowClassName"
        data-testid="outbound-table"
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
            <div class="expand-panel" :data-testid="`outbound-expand-${row.outboundId}`">
              <el-table :data="row.lines || []" size="small" border class="line-table">
                <el-table-column
                  label="目标设备"
                  min-width="180"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                >
                  <template #default="scope">
                    {{ deviceName(scope.row.deviceId, scope.row.deviceName) }}
                  </template>
                </el-table-column>
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
                  label="货道"
                  min-width="88"
                  class-name="col-text"
                  label-class-name="col-text"
                >
                  <template #default="scope">{{ scope.row.slotId || '无' }}</template>
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
                  label="交接状态"
                  min-width="110"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                >
                  <template #default="scope">{{
                    dictLabel('handover_status', scope.row.handoverStatus || 'PENDING')
                  }}</template>
                </el-table-column>
              </el-table>
            </div>
          </template>
        </el-table-column>
        <el-table-column
          label="出库单"
          min-width="110"
          class-name="col-text"
          label-class-name="col-text"
        >
          <template #default="{ row }">
            <span :data-testid="`outbound-id-${row.outboundId}`" class="outbound-id-cell">{{
              row.outboundId
            }}</span>
          </template>
        </el-table-column>
        <el-table-column
          prop="routeId"
          label="路线"
          min-width="88"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        />
        <el-table-column
          label="出库仓库"
          min-width="160"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        >
          <template #default="{ row }">
            {{ warehouseName(String(row.warehouseId || '')) }}
          </template>
        </el-table-column>
        <el-table-column
          label="状态"
          min-width="110"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        >
          <template #default="{ row }">
            <el-tag :type="dictTagType(row.status)" size="small">{{
              dictLabel('warehouse_outbound_status', row.status)
            }}</el-tag>
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
        <el-table-column
          v-if="canEdit"
          label="操作"
          min-width="240"
          class-name="col-action"
          align="center"
          fixed="right"
        >
          <template #default="{ row }">
            <div :data-testid="`outbound-row-${row.outboundId}`">
              <el-button
                v-if="row.lines?.length"
                link
                type="primary"
                class="print-btn"
                @click="emit('print', row)"
                >打印拣货单</el-button
              >
              <el-button
                v-if="row.status === 'DRAFT' && row.lines?.length"
                link
                type="primary"
                class="print-btn"
                :data-testid="`outbound-${row.outboundId}-pick`"
                @click="emit('pick', row)"
                >确认拣货</el-button
              >
              <el-button
                v-if="row.status === 'PICKED' && row.lines?.length"
                link
                type="danger"
                class="print-btn"
                :data-testid="`outbound-${row.outboundId}-ship`"
                @click="emit('ship', row)"
                >确认发运</el-button
              >
              <TableActions
                v-if="outboundSecondaryActions(row).length"
                :actions="outboundSecondaryActions(row)"
                :test-id-prefix="`outbound-${row.outboundId}`"
                @action="emit('cancel-unreceived', row)"
              />
              <span v-else-if="!row.lines?.length && row.status !== 'SHIPPED'" class="muted"
                >无明细</span
              >
              <span v-else-if="row.status === 'SHIPPED'" class="muted">已发运</span>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty v-if="hydrated && !loading" description="暂无出库单" />
        </template>
      </el-table>
    </div>
  </div>
</template>
