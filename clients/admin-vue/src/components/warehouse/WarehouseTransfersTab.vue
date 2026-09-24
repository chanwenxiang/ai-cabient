<script setup lang="ts">
import type { AdminDynamicRow } from '@/types/admin-dynamic-row';
import { formatDateTime } from '@aicabinet/shared-uni/format';

export type WarehouseTransferRow = AdminDynamicRow;

type TransferLine = {
  skuId?: string;
  quantity?: number;
  batchNo?: string;
};

defineProps<{
  loading: boolean;
  hydrated: boolean;
  rows: WarehouseTransferRow[];
  canWarehouseEdit: boolean;
  warehouseName: (id: string) => string;
  skuName: (skuId?: string) => string;
  transferStatusLabel: (status?: string) => string;
}>();

const emit = defineEmits<{
  ship: [row: WarehouseTransferRow];
  receive: [row: WarehouseTransferRow];
  cancel: [row: WarehouseTransferRow];
}>();

function linesSummary(
  lines: TransferLine[] | undefined,
  skuName: (skuId?: string) => string
) {
  return (lines || [])
    .map(
      (l) =>
        `${skuName(l.skuId) || l.skuId}×${l.quantity}${l.batchNo ? '(' + l.batchNo + ')' : ''}`
    )
    .join(' · ');
}
</script>

<template>
  <div class="table-scroll">
    <div class="table-scroll-inner">
      <el-table
        v-loading="loading"
        :data="rows"
        stripe
        border
        class="report-table"
        empty-text=" "
      >
        <template #empty>
          <el-empty v-if="hydrated && !loading" description="暂无调拨单" />
        </template>
        <el-table-column prop="transferNo" label="调拨单号" min-width="160" />
        <el-table-column label="调出仓" min-width="120">
          <template #default="{ row }">{{
            warehouseName(String(row.fromWarehouseId || '')) || row.fromWarehouseId
          }}</template>
        </el-table-column>
        <el-table-column label="调入仓" min-width="120">
          <template #default="{ row }">{{
            warehouseName(String(row.toWarehouseId || '')) || row.toWarehouseId
          }}</template>
        </el-table-column>
        <el-table-column
          label="状态"
          width="100"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        >
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ transferStatusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="明细" min-width="180" class-name="col-text">
          <template #default="{ row }">
            <span
              class="cell-ellipsis"
              :title="linesSummary(row.lines, skuName) || ''"
              >{{ linesSummary(row.lines, skuName) || '' }}</span
            >
          </template>
        </el-table-column>
        <el-table-column
          label="发运"
          width="150"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        >
          <template #default="{ row }">
            <span class="cell-datetime">{{
              row.shippedAt ? formatDateTime(row.shippedAt) : ''
            }}</span>
          </template>
        </el-table-column>
        <el-table-column
          label="收货"
          width="150"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        >
          <template #default="{ row }">
            <span class="cell-datetime">{{
              row.receivedAt ? formatDateTime(row.receivedAt) : ''
            }}</span>
          </template>
        </el-table-column>
        <el-table-column label="备注" min-width="100" class-name="col-text">
          <template #default="{ row }">
            <span class="cell-ellipsis" :title="row.notes || ''">{{ row.notes || '' }}</span>
          </template>
        </el-table-column>
        <el-table-column
          v-if="canWarehouseEdit"
          label="操作"
          width="200"
          align="center"
          fixed="right"
          class-name="col-action"
          label-class-name="col-action"
        >
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'DRAFT'"
              link
              type="primary"
              @click="emit('ship', row)"
              >发运</el-button
            >
            <el-button
              v-if="row.status === 'SHIPPED'"
              link
              type="success"
              @click="emit('receive', row)"
              >收货</el-button
            >
            <el-button
              v-if="row.status === 'DRAFT'"
              link
              type="danger"
              @click="emit('cancel', row)"
              >取消</el-button
            >
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>
